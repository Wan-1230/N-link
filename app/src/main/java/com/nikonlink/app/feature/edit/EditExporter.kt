package com.nikonlink.app.feature.edit

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 修图导出器（PRD 4.7 保存与导出）
 *
 * - 另存为新图至 DCIM/NikonLink/Edited/（命中相册页 RELATIVE_PATH LIKE '%NikonLink%' 查询）
 * - 命名：{原名}_EDITED_{yyyyMMdd_HHmmss}.{jpg|png}
 * - EXIF：复制原图拍摄参数，追加 Software=NikonLink Edit 标记；不新增 GPS 采集
 * - IS_PENDING 两段式写入（Scoped Storage 规范）
 *
 * 编辑产物不注册 transferRepository.recordTransfer（PRD 6.2：不干扰相机文件去重）。
 * 日志来源: EditExporter 标签输出保存结果与 EXIF 复制状态。
 */
object EditExporter {

    private const val TAG = "EditExporter"
    private val RELATIVE_PATH = Environment.DIRECTORY_DCIM + "/NikonLink/Edited"
    private val BACKUP_PATH = Environment.DIRECTORY_DCIM + "/NikonLink/.backup"
    private const val SOFTWARE_TAG = "NikonLink Edit v0.1"

    /** 覆盖他应用文件需系统 SAF 临时授权（PRD 4.7，API 30+） */
    class SafAuthorizationRequiredException(
        val intentSender: IntentSender
    ) : IllegalStateException("需要系统授权")

    /** 需要从原图复制的 EXIF 标签（拍摄参数 + 时间 + GPS 原样保留） */
    private val EXIF_TAGS_TO_COPY = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP
    )

    /**
     * 另存为新图。
     * @return 新文件的 MediaStore Uri
     * @throws IllegalStateException 插入或写盘失败时抛出（含中文化原因）
     */
    fun saveAsNewImage(
        context: Context,
        bitmap: Bitmap,
        sourceUri: Uri,
        sourceName: String,
        format: ExportFormat
    ): Uri {
        val displayName = buildDisplayName(sourceName, format)
        val mimeType = if (format == ExportFormat.PNG_LOSSLESS) "image/png" else "image/jpeg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建目标文件（存储空间不足？）")

        try {
            val written = resolver.openOutputStream(targetUri)?.use { out ->
                val compressFormat =
                    if (format == ExportFormat.PNG_LOSSLESS) Bitmap.CompressFormat.PNG
                    else Bitmap.CompressFormat.JPEG
                bitmap.compress(compressFormat, format.quality, out)
            } ?: throw IllegalStateException("无法写入目标文件")
            if (!written) throw IllegalStateException("图片编码失败")

            copyExif(resolver, sourceUri, targetUri, bitmap)

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(targetUri, values, null, null)

            Timber.tag(TAG).i("Saved: $displayName (${bitmap.width}x${bitmap.height}, $format)")
            return targetUri
        } catch (e: Exception) {
            // 失败时清理半成品记录，避免相册出现损坏文件
            runCatching { resolver.delete(targetUri, null, null) }
            when (e) {
                is IllegalStateException -> throw e
                else -> throw IllegalStateException("保存失败：${e.message ?: "未知错误"}", e)
            }
        }
    }

    /** {原名去扩展名}_EDITED_{时间戳}.{扩展名} */
    internal fun buildDisplayName(sourceName: String, format: ExportFormat): String {
        val base = sourceName.substringBeforeLast('.', sourceName).ifBlank { "IMG" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = if (format == ExportFormat.PNG_LOSSLESS) "png" else "jpg"
        return "${base}_EDITED_$stamp.$ext"
    }

    /**
     * 覆盖保存（PRD 4.7 P1）：
     * - 仅本 App 创建的文件可直接覆盖；其他来源文件招出 SAF 授权（API 30+）
     * - 覆盖前先备份原图至隐藏目录 .backup（防止用户数据静默丢失）
     * - EXIF 先读出后回写，保留拍摄参数
     *
     * @throws SafAuthorizationRequiredException 需 SAF 授权时抛出，由 UI 层发起系统授权
     * @throws IllegalStateException 其他失败（含中文化原因）
     */
    fun overwriteOriginal(
        context: Context,
        sourceUri: Uri,
        bitmap: Bitmap,
        format: ExportFormat,
        skipBackup: Boolean = false
    ) {
        val resolver = context.contentResolver

        if (!skipBackup) {
            backupOriginal(context, sourceUri)
        }

        if (!isOwnedByApp(context, sourceUri)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createWriteRequest(resolver, listOf(sourceUri))
                throw SafAuthorizationRequiredException(request.intentSender)
            }
            throw IllegalStateException("该照片非本应用管理，无法覆盖，请使用另存")
        }

        writeOverwrite(resolver, sourceUri, bitmap, format)
        Timber.tag(TAG).i("Overwritten original: $sourceUri")
    }

    /** 覆盖前备份原图原始字节至 DCIM/NikonLink/.backup（PRD 4.7，保留 30 天策略见运维清理） */
    fun backupOriginal(context: Context, sourceUri: Uri) {
        try {
            val resolver = context.contentResolver
            val name = queryDisplayName(resolver, sourceUri) ?: "backup"
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val ext = name.substringAfterLast('.', "jpg")
            val base = name.substringBeforeLast('.', name)
            val values = ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, "${base}_BAK_$stamp.$ext")
                put(MediaStore.Files.FileColumns.MIME_TYPE, queryMime(resolver, sourceUri) ?: "image/jpeg")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, BACKUP_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val target = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: return
            resolver.openOutputStream(target)?.use { out ->
                resolver.openInputStream(sourceUri)?.use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            Timber.tag(TAG).i("Original backed up before overwrite")
        } catch (e: Exception) {
            // 备份失败不阻断覆盖（二次确认弹窗已告知用户风险），仅记录日志
            Timber.tag(TAG).w(e, "Backup before overwrite failed")
        }
    }

    /** 本 App 是否为文件所有者（Scoped Storage 所有权免授权写入） */
    private fun isOwnedByApp(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.Media.OWNER_PACKAGE_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val owner = c.getString(0)
                    owner == context.packageName ||
                        owner == context.packageName.removeSuffix(".debug")
                } else false
            } ?: false
        }.getOrDefault(false)
    }

    /** 覆盖写入：先读 EXIF → 截断写新像素 → 回写 EXIF */
    private fun writeOverwrite(
        resolver: android.content.ContentResolver,
        uri: Uri,
        bitmap: Bitmap,
        format: ExportFormat
    ) {
        val attrs = readExifAttributes(resolver, uri)

        val written = resolver.openOutputStream(uri, "wt")?.use { out ->
            val compressFormat =
                if (format == ExportFormat.PNG_LOSSLESS) Bitmap.CompressFormat.PNG
                else Bitmap.CompressFormat.JPEG
            bitmap.compress(compressFormat, format.quality, out)
        } ?: throw IllegalStateException("无法写入目标文件")
        if (!written) throw IllegalStateException("图片编码失败")

        // 覆盖后回写 EXIF（含 Software 标记）
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                attrs.forEach { (tag, value) -> exif.setAttribute(tag, value) }
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE_TAG)
                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EXIF reapply failed (non-blocking)")
        }
    }

    private fun readExifAttributes(
        resolver: android.content.ContentResolver,
        uri: Uri
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                EXIF_TAGS_TO_COPY.forEach { tag ->
                    exif.getAttribute(tag)?.let { result[tag] = it }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Read EXIF before overwrite failed (non-blocking)")
        }
        return result
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()

    private fun queryMime(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching { resolver.getType(uri) }.getOrNull()

    /**
     * 复制原图 EXIF 至导出文件；失败仅记录日志，不阻断保存（EXIF 属增强项）。
     */
    private fun copyExif(
        resolver: android.content.ContentResolver,
        sourceUri: Uri,
        targetUri: Uri,
        bitmap: Bitmap
    ) {
        try {
            val sourceExif = resolver.openInputStream(sourceUri)?.use { stream: InputStream ->
                ExifInterface(stream)
            } ?: return

            resolver.openFileDescriptor(targetUri, "rw")?.use { pfd ->
                val targetExif = ExifInterface(pfd.fileDescriptor)
                var copied = 0
                EXIF_TAGS_TO_COPY.forEach { tag ->
                    sourceExif.getAttribute(tag)?.let { value ->
                        targetExif.setAttribute(tag, value)
                        copied++
                    }
                }
                targetExif.setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE_TAG)
                targetExif.setAttribute(
                    ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString()
                )
                targetExif.setAttribute(
                    ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString()
                )
                targetExif.saveAttributes()
                Timber.tag(TAG).d("EXIF copied: $copied tags")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EXIF copy failed (non-blocking)")
        }
    }
}

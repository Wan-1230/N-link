package com.nikonlink.app.camera.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.nikonlink.app.shared.common.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分享导出器（PRD v0.2.0 主题 F / F4「分享预览副本」）
 *
 * 职责：把相机卡内 / 已归档的照片整理成可分享的 URI：
 * - 预览副本：下载原图到 cache → 重编码为长边 ≤ 2048 的 JPEG → 复制拍摄参数 EXIF
 *   （默认剥离 GPS，见 [AppSettings.shareKeepGps]）→ FileProvider URI。
 *   副本只落 cache 目录，不写 MediaStore、不覆盖原图、不进传输历史。
 * - 原图：已归档的直接用 MediaStore URI；未归档的下载到 cache 后走 FileProvider。
 */
@Singleton
class PreviewShareExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferManager: TransferManager,
    private val settings: AppSettings
) {

    companion object {
        private const val TAG = "ShareExporter"

        /** 预览副本长边上限（微信发图不被二压的常用尺寸） */
        const val PREVIEW_LONG_EDGE = 2048
        private const val PREVIEW_JPEG_QUALITY = 90
        private const val SHARE_SUB_DIR = "share"

        /**
         * 复制到副本的拍摄参数 EXIF 白名单（重新编码后位图不携带 EXIF，需显式回写）。
         * GPS 系列标签一律不在名单内：默认剥离位置信息的实现就是「不复制它们」；
         * 用户开启「分享保留 GPS」时补写坐标两项。
         */
        private val EXIF_COPY_TAGS = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_ORIENTATION
        )
    }

    /** 批量导出结果：uris 可直接进分享面板；skipped/failed 供 UI 汇总提示 */
    data class ExportResult(
        val uris: List<Uri>,
        val skipped: List<String>,
        val failed: List<String>
    ) {
        val isSuccess: Boolean get() = uris.isNotEmpty()
    }

    /** 该文件能否生成预览副本：JPEG 可重编码；RAW 依赖预览通道（本版未实现）→ 置灰说明 */
    fun supportsPreviewCopy(file: CameraFile): Boolean = file.format == CameraFileFormat.JPEG

    /**
     * 批量导出预览副本。逐张串行（内存可控），进度回调 (已完成, 总数)。
     * RAW/视频调用方应先行过滤（[supportsPreviewCopy]），漏进来的记入 skipped。
     */
    suspend fun export(files: List<CameraFile>, onProgress: (Int, Int) -> Unit): ExportResult {
        val uris = mutableListOf<Uri>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val shareDir = File(context.cacheDir, SHARE_SUB_DIR).apply { mkdirs() }

        withContext(Dispatchers.IO) {
            files.forEachIndexed { index, file ->
                runCatching {
                    if (!supportsPreviewCopy(file)) {
                        skipped.add(file.fileName)
                        return@runCatching
                    }
                    val source = downloadToCache(file, shareDir)
                        ?: throw IllegalStateException("下载原图到缓存失败")
                    val copy = buildPreviewCopy(source, file, shareDir)
                        ?: throw IllegalStateException("副本生成失败")
                    uris.add(fileProviderUri(copy))
                    source.takeIf { it !== copy }?.delete()
                }.onFailure { e ->
                    Timber.tag(TAG).w(e, "Preview copy failed: ${file.fileName}")
                    failed.add(file.fileName)
                }
                onProgress(index + 1, files.size)
            }
        }
        return ExportResult(uris, skipped, failed)
    }

    /**
     * 导出原图分享 URI：已归档的用 MediaStore content URI；未归档的下载到 cache 走 FileProvider。
     * 返回 null 表示不可分享（相机未连接 / 下载失败）。
     */
    suspend fun exportOriginalUri(file: CameraFile, archivedPath: String?): Uri? {
        archivedPath?.let { return Uri.parse(it) }
        val shareDir = File(context.cacheDir, SHARE_SUB_DIR).apply { mkdirs() }
        return withContext(Dispatchers.IO) {
            val source = downloadToCache(file, shareDir) ?: return@withContext null
            fileProviderUri(source)
        }
    }

    /** 下载原图到 cache（不写 MediaStore、不进传输历史） */
    private suspend fun downloadToCache(file: CameraFile, shareDir: File): File? {
        val target = File(shareDir, "raw_${file.handle}_${file.fileName}")
        if (target.isFile && target.length() >= file.size && file.size > 0) return target
        val ok = transferManager.downloadPhotoToCache(file, target)
        return if (ok) target else {
            target.delete()
            null
        }
    }

    /**
     * 重编码预览副本：解码 → 采样缩放 → 长边 ≤ 2048 → JPEG 90 → 回写参数 EXIF（默认无 GPS）。
     */
    private fun buildPreviewCopy(source: File, file: CameraFile, shareDir: File): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_LONG_EDGE) {
            sample *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null

        val scaled = downscale(bitmap)
        if (scaled !== bitmap) bitmap.recycle()

        val output = File(
            shareDir,
            "${file.fileName.substringBeforeLast('.')}_preview.jpg"
        )
        val keptGps = settings.shareKeepGps
        val ok = output.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, out)
        } && output.length() > 0
        scaled.recycle()
        if (ok) copyExif(source, output, keptGps) else output.delete()
        return if (ok) output else null
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= PREVIEW_LONG_EDGE) return bitmap
        val scale = PREVIEW_LONG_EDGE.toFloat() / longEdge
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * 拍摄参数 EXIF 回写到副本文件（文件路径构造才支持 saveAttributes）；
     * GPS 仅在用户显式开启「分享保留 GPS」时保留，默认剥离。
     */
    private fun copyExif(source: File, target: File, keepGps: Boolean) {
        runCatching {
            val src = source.inputStream().use { ExifInterface(it) }
            val dst = ExifInterface(target.absolutePath)
            EXIF_COPY_TAGS.forEach { tag ->
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
            if (keepGps) {
                src.latLong?.let { (lat, lon) -> dst.setLatLong(lat, lon) }
            }
            dst.saveAttributes()
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "EXIF copy failed for ${source.name}")
        }
    }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

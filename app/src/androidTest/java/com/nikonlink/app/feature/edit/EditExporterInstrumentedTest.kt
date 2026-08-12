package com.nikonlink.app.feature.edit

import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * EditExporter 插桩验收测试（PRD-AI修图 §10.1 功能验收自动化）
 *
 * 覆盖：
 * - 另存为新图写入 DCIM/NikonLink/Edited/（命中本地相册查询）
 * - 导出命名规范 {原名}_EDITED_{时间戳}.jpg
 * - EXIF 复制（拍摄参数保留）+ Software 标记
 * - 覆盖前备份至 DCIM/NikonLink/.backup/
 *
 * 运行：connectedDebugAndroidTest（需真机/模拟器）
 */
@RunWith(AndroidJUnit4::class)
class EditExporterInstrumentedTest {

    private lateinit var context: android.content.Context
    private lateinit var sourceFile: File
    private lateinit var sourceUri: Uri
    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        // 构造带 EXIF 的源图（模拟相机下载的 JPEG）
        sourceFile = File(context.cacheDir, "exporter_test_source.jpg")
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        sourceFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        // 写入拍摄参数，验证导出时复制（PRD 4.7 EXIF 保留）
        ExifInterface(sourceFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MAKE, "NIKONLINK TEST")
            setAttribute(ExifInterface.TAG_MODEL, "Z 50II")
            setAttribute(ExifInterface.TAG_F_NUMBER, "4.0")
            saveAttributes()
        }
        sourceUri = Uri.fromFile(sourceFile)
    }

    @After
    fun teardown() {
        // 清理测试产生的 MediaStore 记录，避免污染用户相册
        val resolver = context.contentResolver
        createdUris.forEach { runCatching { resolver.delete(it, null, null) } }
        sourceFile.delete()
    }

    @Test
    fun saveAsNewImage_写入Edited目录且命名符合规范() {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val uri = EditExporter.saveAsNewImage(
            context, bmp, sourceUri, "DSC_9999.JPG", ExportFormat.JPEG_HIGH
        )
        createdUris.add(uri)
        bmp.recycle()

        val resolver = context.contentResolver
        resolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            ),
            null, null, null
        )?.use { c ->
            assertTrue("应能查询到导出文件", c.moveToFirst())
            val name = c.getString(0)
            val path = c.getString(1)
            assertTrue("命名含 EDITED 标记: $name", name.startsWith("DSC_9999_EDITED_"))
            assertTrue("jpg 扩展名: $name", name.endsWith(".jpg"))
            assertEquals(
                Environment.DIRECTORY_DCIM + "/NikonLink/Edited/",
                path
            )
        } ?: throw AssertionError("导出文件查询失败")
    }

    @Test
    fun saveAsNewImage_复制原图EXIF并追加Software标记() {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val uri = EditExporter.saveAsNewImage(
            context, bmp, sourceUri, "DSC_8888.JPG", ExportFormat.JPEG_HIGH
        )
        createdUris.add(uri)
        bmp.recycle()

        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        assertNotNull("导出文件应可读取 EXIF", exif)
        assertEquals("NIKONLINK TEST", exif!!.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals("Z 50II", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("4.0", exif.getAttribute(ExifInterface.TAG_F_NUMBER))
        assertEquals("NikonLink Edit v0.1", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
    }

    @Test
    fun backupOriginal_备份至隐藏目录() {
        EditExporter.backupOriginal(context, sourceUri)

        // 查询 .backup 目录下的备份文件（命名 {原名}_BAK_{时间戳}.jpg）
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            ),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ?",
            arrayOf(Environment.DIRECTORY_DCIM + "/NikonLink/.backup/"),
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { c ->
            assertTrue("备份目录应有文件", c.moveToFirst())
            val name = c.getString(1)
            assertTrue("备份命名含 BAK 标记: $name", name.contains("_BAK_"))
            val id = c.getLong(0)
            createdUris.add(
                Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
            )
        } ?: throw AssertionError("备份目录查询失败")
    }
}

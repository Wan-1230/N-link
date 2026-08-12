package com.nikonlink.app.feature.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditExporter 单元测试（PRD 4.7 导出命名规范：{原名}_EDITED_{时间戳}.{扩展名}）
 */
class EditExporterTest {

    @Test
    fun `JPG 导出命名含 EDITED 标记与 jpg 扩展名`() {
        val name = EditExporter.buildDisplayName("DSC_1234.JPG", ExportFormat.JPEG_HIGH)
        assertTrue("应以原名开头: $name", name.startsWith("DSC_1234_EDITED_"))
        assertTrue("应 jpg 结尾: $name", name.endsWith(".jpg"))
    }

    @Test
    fun `PNG 画质导出使用 png 扩展名`() {
        val name = EditExporter.buildDisplayName("DSC_1234.JPG", ExportFormat.PNG_LOSSLESS)
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun `无扩展名文件名正确处理`() {
        val name = EditExporter.buildDisplayName("IMG_0001", ExportFormat.JPEG_HIGH)
        assertTrue(name.startsWith("IMG_0001_EDITED_"))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun `空文件名兜底为 IMG`() {
        val name = EditExporter.buildDisplayName("", ExportFormat.JPEG_HIGH)
        assertTrue(name.startsWith("IMG_EDITED_"))
    }

    @Test
    fun `多段扩展名只去掉最后一段`() {
        val name = EditExporter.buildDisplayName("photo.backup.jpg", ExportFormat.JPEG_HIGH)
        assertTrue(name.startsWith("photo.backup_EDITED_"))
    }

    @Test
    fun `时间戳为 15 位 yyyyMMdd_HHmmss`() {
        val name = EditExporter.buildDisplayName("X.jpg", ExportFormat.JPEG_HIGH)
        // X_EDITED_yyyyMMdd_HHmmss.jpg → 时间戳段长度 15
        val stamp = name.removePrefix("X_EDITED_").removeSuffix(".jpg")
        assertEquals(15, stamp.length)
        assertTrue("含下划线分隔: $stamp", stamp[8] == '_')
    }
}

package com.nikonlink.app.camera.gallery

import com.nikonlink.app.shared.common.AppSettings
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F4 分享副本支持判定：JPEG 可生成预览副本；
 * RAW 依赖预览通道（本版未实现，入口置灰说明）、视频不重编码。
 */
class PreviewShareExporterTest {

    private lateinit var exporter: PreviewShareExporter

    @Before
    fun setUp() {
        // supportsPreviewCopy 只读 file.format，依赖全部可用 relaxed mock
        exporter = PreviewShareExporter(
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk<AppSettings>(relaxed = true)
        )
    }

    private fun file(format: CameraFileFormat) = CameraFile(
        handle = 1,
        fileName = "DSC_0001.${if (format == CameraFileFormat.RAW) "NEF" else if (format == CameraFileFormat.VIDEO) "MOV" else "JPG"}",
        size = 10_000L,
        formatCode = 0,
        storageId = 1,
        format = format
    )

    @Test
    fun `jpeg supports preview copy`() {
        assertTrue(exporter.supportsPreviewCopy(file(CameraFileFormat.JPEG)))
    }

    @Test
    fun `raw does not support preview copy yet`() {
        assertFalse(exporter.supportsPreviewCopy(file(CameraFileFormat.RAW)))
    }

    @Test
    fun `video does not support preview copy`() {
        assertFalse(exporter.supportsPreviewCopy(file(CameraFileFormat.VIDEO)))
    }

    @Test
    fun `other format does not support preview copy`() {
        assertFalse(exporter.supportsPreviewCopy(file(CameraFileFormat.OTHER)))
    }
}

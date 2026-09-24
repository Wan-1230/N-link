package com.nikonlink.app.camera.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-17 配对与展开。
 *
 * 这一块的失败方式很坏：**把某个文件在界面上变没了**。所以断言的重点不是"合得对"，
 * 而是"有一点不确定就不合、合了也一张都不能丢"。
 */
class RawJpegPairingTest {

    private fun file(handle: Int, name: String, format: CameraFileFormat) = CameraFile(
        handle = handle,
        fileName = name,
        size = 1000L + handle,
        formatCode = 0,
        storageId = 1,
        format = format
    )

    private fun nef(handle: Int, name: String = "DSC_0001.NEF") = file(handle, name, CameraFileFormat.RAW)
    private fun jpg(handle: Int, name: String = "DSC_0001.JPG") = file(handle, name, CameraFileFormat.JPEG)
    private fun mov(handle: Int, name: String = "DSC_0002.MOV") = file(handle, name, CameraFileFormat.VIDEO)

    private fun byHandle(files: List<CameraFile>): (Int) -> CameraFile? =
        { handle -> files.firstOrNull { it.handle == handle } }

    @Test
    fun `同名 NEF 与 JPG 合成一条，藏起的只有那张 JPG`() {
        val files = listOf(nef(1), jpg(2))
        val plan = RawJpegPairing.plan(files)

        assertEquals(setOf(2), plan.hiddenJpegHandles)
        assertEquals(mapOf(1 to 2), plan.jpegOfRaw)
        assertEquals(mapOf(2 to 1), plan.rawOfJpeg)

        val merged = RawJpegPairing.applyMerge(files, plan)
        assertEquals(listOf(1), merged.map { it.handle })
        assertEquals(2, merged.first().pairedJpegHandle)
    }

    @Test
    fun `单格式机身逐位不变——没有任何东西被藏`() {
        val jpegOnly = listOf(jpg(1), jpg(2, "DSC_0002.JPG"))
        val planJpeg = RawJpegPairing.plan(jpegOnly)
        assertTrue(planJpeg.isEmpty)
        assertEquals(jpegOnly, RawJpegPairing.applyMerge(jpegOnly, planJpeg))

        val rawOnly = listOf(nef(3), nef(4, "DSC_0002.NEF"))
        val planRaw = RawJpegPairing.plan(rawOnly)
        assertTrue(planRaw.isEmpty)
        assertEquals(2, RawJpegPairing.applyMerge(rawOnly, planRaw).size)
    }

    @Test
    fun `两个 RAW 配一张 JPG 有歧义时全部平铺，一张都不许消失`() {
        val files = listOf(nef(1), nef(2, "DSC_0001.NEF"), jpg(3))
        val plan = RawJpegPairing.plan(files)
        assertTrue("同名下多出一张就不能猜", plan.hiddenJpegHandles.isEmpty())
        assertEquals(3, RawJpegPairing.applyMerge(files, plan).size)
    }

    @Test
    fun `三张以上同名的组一律不合`() {
        val files = listOf(nef(1), jpg(2), jpg(3, "DSC_0001.JPG"))
        assertTrue(RawJpegPairing.plan(files).hiddenJpegHandles.isEmpty())
    }

    @Test
    fun `大小写与前后空格不影响配对`() {
        val files = listOf(nef(1, "  dsc_0001.nef "), jpg(2, "DSC_0001.JPG"))
        val plan = RawJpegPairing.plan(files)
        assertEquals(setOf(2), plan.hiddenJpegHandles)
    }

    @Test
    fun `视频永不参与合并`() {
        val files = listOf(mov(1, "DSC_0001.MOV"), jpg(2))
        assertTrue(RawJpegPairing.plan(files).hiddenJpegHandles.isEmpty())
    }

    @Test
    fun `脏文件名不参与配对而不是抛异常`() {
        assertNull(RawJpegPairing.baseNameOf("NO_EXTENSION"))
        assertNull(RawJpegPairing.baseNameOf("TRAILING_DOT."))
        assertNull(RawJpegPairing.baseNameOf(""))
        assertNull(RawJpegPairing.baseNameOf("   "))
        assertEquals("DSC_0001", RawJpegPairing.baseNameOf("DSC_0001.NEF"))
        // 文件名里还有点（如 100NXORG 之外的自定义前缀）时取最后一个点
        assertEquals("DSC.0001", RawJpegPairing.baseNameOf("DSC.0001.JPG"))
    }

    @Test
    fun `成对模式选中 RAW 时两张都排队，未配对的照常`() {
        val files = listOf(nef(1), jpg(2), mov(3, "DSC_0009.MOV"))
        val plan = RawJpegPairing.plan(files)
        val merged = RawJpegPairing.applyMerge(files, plan)

        val queued = RawJpegPairing.expand(
            listOf(merged.first(), merged[1]), plan, RawJpegPairing.DownloadMode.BOTH, byHandle(files)
        )
        assertEquals(listOf(1, 2, 3), queued.map { it.handle })
    }

    @Test
    fun `只下 RAW 时合并格只排一张，纯 JPG 的那格不能被吞`() {
        val files = listOf(nef(1), jpg(2), jpg(7, "DSC_0007.JPG"))
        val plan = RawJpegPairing.plan(files)
        val merged = RawJpegPairing.applyMerge(files, plan)

        val queued = RawJpegPairing.expand(
            merged, plan, RawJpegPairing.DownloadMode.RAW_ONLY, byHandle(files)
        )
        assertEquals("合并格下只排 RAW；没有 RAW 可配的那张 JPG 照常下", listOf(1, 7), queued.map { it.handle })
    }

    @Test
    fun `只下 JPG 时合并格换成那张 JPG，视频保持原样`() {
        val files = listOf(nef(1), jpg(2), mov(3))
        val plan = RawJpegPairing.plan(files)
        val merged = RawJpegPairing.applyMerge(files, plan)

        val queued = RawJpegPairing.expand(
            merged, plan, RawJpegPairing.DownloadMode.JPEG_ONLY, byHandle(files)
        )
        assertEquals(listOf(2, 3), queued.map { it.handle })
    }

    @Test
    fun `同时选中两张时不重复入队`() {
        val files = listOf(nef(1), jpg(2))
        val plan = RawJpegPairing.plan(files)
        val queued = RawJpegPairing.expand(files, plan, RawJpegPairing.DownloadMode.BOTH, byHandle(files))
        assertEquals("句柄必须唯一，否则同一张传两遍", listOf(1, 2), queued.map { it.handle })
    }

    @Test
    fun `查不到搭档时退回手里这张，绝不静默少传`() {
        val files = listOf(nef(1), jpg(2))
        val plan = RawJpegPairing.plan(files)
        // lookup 故意只认识 RAW：JPG 查不到
        val queued = RawJpegPairing.expand(
            listOf(files.first()), plan, RawJpegPairing.DownloadMode.BOTH,
            { handle -> files.firstOrNull { it.handle == handle && handle == 1 } }
        )
        assertEquals(listOf(1), queued.map { it.handle })
        assertFalse(queued.isEmpty())
    }

    @Test
    fun `闸门关掉（plan 为空）时展开就是原样透传`() {
        val files = listOf(nef(1), jpg(2))
        val empty = RawJpegPairing.plan(emptyList())
        assertEquals(files, RawJpegPairing.expand(files, empty, RawJpegPairing.DownloadMode.BOTH) { null })
    }
}

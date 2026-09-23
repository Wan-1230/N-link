package com.nikonlink.app.camera.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-16：机内保护标记的只读判定。
 *
 * 核心不是"能不能筛"，而是**没报**这一列不能被当成"没保护"——
 * 尼康机型走 `0x9805` 时返回哪些属性列随机型而定，这条路径随时可能不带保护位。
 */
class ProtectionFilterTest {

    private fun file(handle: Int, protection: Int?) = CameraFile(
        handle = handle,
        fileName = "DSC_%04d.NEF".format(handle),
        size = 1024L,
        formatCode = 0xB103,
        storageId = 1,
        format = CameraFileFormat.RAW,
        protectionStatus = protection
    )

    @Test
    fun `未报告不等于未保护——protectionKnown 能分开这两者`() {
        assertFalse(file(1, null).protectionKnown)
        assertTrue(file(2, 0).protectionKnown)
    }

    @Test
    fun `非零即已保护，未报告按未保护`() {
        assertFalse(file(1, null).isProtected)
        assertFalse(file(2, 0x0000).isProtected)
        assertTrue(file(3, 0x0001).isProtected)
        // 规范里非零取值不止 0x0001（如带校验的 0x8001），逐值列举会漏掉机型私有值
        assertTrue(file(4, 0x8001).isProtected)
    }

    @Test
    fun `空列表是还没拉到，不是不支持`() {
        assertEquals(ProtectionReport.NO_DATA, protectionReportOf(emptyList()))
    }

    @Test
    fun `一条都没报保护列时判为未上报`() {
        val files = listOf(file(1, null), file(2, null))
        assertEquals(ProtectionReport.UNREPORTED, protectionReportOf(files))
    }

    @Test
    fun `报了但一张都没保护，仍算可用——空结果是筛出来的，不是功能不支持`() {
        val files = listOf(file(1, 0), file(2, 0))
        assertEquals(ProtectionReport.REPORTED, protectionReportOf(files))
    }

    @Test
    fun `标签呈现：未报不出现，报了带张数`() {
        assertEquals(false, protectChipOf(listOf(file(1, null), file(2, null))).shown)
        assertEquals(false, protectChipOf(emptyList()).shown)

        val noneProtected = protectChipOf(listOf(file(1, 0), file(2, 0)))
        assertTrue(noneProtected.shown)
        assertEquals("已保护", noneProtected.label)

        val some = protectChipOf(listOf(file(1, 0x0001), file(2, 0), file(3, 0x8001), file(4, null)))
        assertTrue(some.shown)
        assertEquals("已保护 2", some.label)
    }

    @Test
    fun `筛选只放行已保护的，未报告的留在全部里`() {
        val protected = file(1, 0x0001)
        val unprotected = file(2, 0x0000)
        val unknown = file(3, null)

        val kept = listOf(protected, unprotected, unknown).filter { PhotoFilter.PROTECTED.matches(it) }
        assertEquals(listOf(protected.handle), kept.map { it.handle })
    }

    @Test
    fun `既有筛选取值不受新增项影响`() {
        val raw = file(1, 0x0001)
        assertTrue(PhotoFilter.ALL.matches(raw))
        assertTrue(PhotoFilter.PHOTOS.matches(raw))
        assertTrue(PhotoFilter.RAW.matches(raw))
        assertFalse(PhotoFilter.JPEG.matches(raw))
        assertFalse(PhotoFilter.VIDEO.matches(raw))
    }
}

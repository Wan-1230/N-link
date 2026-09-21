package com.nikonlink.app.camera.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-03：分页期间的累积快照必须与最终列表同构，不能把视口翻来覆去。 */
class AlbumPageAccumulatorTest {

    private fun file(handle: Int, capturedAt: Long?, name: String? = null) = CameraFile(
        handle = handle,
        fileName = name ?: "DSC_$handle.NEF",
        size = 1000L,
        formatCode = 0x3801,
        storageId = 1,
        format = CameraFileFormat.RAW,
        captureTimeMillis = capturedAt
    )

    private val timeDesc = AlbumSort(AlbumSortDimension.CAPTURE_TIME, AlbumSortDirection.DESC)

    @Test
    fun `第一页就能出快照，不必等全量`() {
        val acc = AlbumPageAccumulator()
        acc.addAll(listOf(file(3, 300L), file(4, 400L)))
        assertEquals(2, acc.size)
        assertEquals(listOf(4, 3), acc.snapshot(timeDesc).map { it.handle })
    }

    @Test
    fun `后到的旧页只往尾部补，不翻转已有顺序`() {
        val acc = AlbumPageAccumulator()
        acc.addAll(listOf(file(40, 4000L), file(39, 3900L)))
        val first = acc.snapshot(timeDesc).map { it.handle }
        acc.addAll(listOf(file(1, 100L), file(2, 200L)))
        val second = acc.snapshot(timeDesc).map { it.handle }
        assertEquals(listOf(40, 39), first)
        // 新快照的前缀必须与上一帧完全一致 —— 这正是 holdPages 当初要解决的跳动
        assertEquals(first, second.take(first.size))
        assertEquals(listOf(40, 39, 2, 1), second)
    }

    @Test
    fun `同一 handle 重复上报不产生两条`() {
        val acc = AlbumPageAccumulator()
        acc.addAll(listOf(file(7, 700L)))
        acc.addAll(listOf(file(7, 700L), file(8, 800L)))
        assertEquals(2, acc.size)
        assertEquals(listOf(8, 7), acc.snapshot(timeDesc).map { it.handle })
    }

    @Test
    fun `拿不到拍摄时间的文件沉底，不霸占首屏`() {
        val acc = AlbumPageAccumulator()
        acc.addAll(listOf(file(1, null), file(2, 50L)))
        assertEquals(listOf(2, 1), acc.snapshot(timeDesc).map { it.handle })
    }

    @Test
    fun `按类型排序时同样只认当前规则`() {
        val acc = AlbumPageAccumulator()
        acc.addAll(
            listOf(
                file(1, 100L, "IMG_0001.MP4"),
                file(2, 90L, "IMG_0002.NEF"),
                file(3, 80L, "IMG_0003.JPG")
            )
        )
        val byType = AlbumSort(AlbumSortDimension.FILE_TYPE, AlbumSortDirection.ASC)
        assertEquals(listOf(3, 2, 1), acc.snapshot(byType).map { it.handle })
    }

    @Test
    fun `空页不改变任何东西`() {
        val acc = AlbumPageAccumulator()
        acc.addAll(emptyList())
        assertTrue(acc.snapshot(timeDesc).isEmpty())
    }
}

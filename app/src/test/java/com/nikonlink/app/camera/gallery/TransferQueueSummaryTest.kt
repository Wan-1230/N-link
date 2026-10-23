package com.nikonlink.app.camera.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferQueueSummaryTest {

    private fun task(handle: Int, status: TransferTaskStatus) = TransferTask(
        CameraFile(handle, "DSC_%04d.JPG".format(handle), 1L, 0x3801, 0),
        status
    )

    @Test
    fun `下载中显示第几张`() {
        val q = listOf(
            task(1, TransferTaskStatus.COMPLETED),
            task(2, TransferTaskStatus.DOWNLOADING),
            task(3, TransferTaskStatus.PENDING),
            task(4, TransferTaskStatus.PENDING)
        )
        val s = TransferQueueSummary.of(q)
        assertEquals("下载中 2/4", s.pillText(paused = false))
        assertEquals("已暂停 2/4", s.pillText(paused = true))
    }

    @Test
    fun `失败不阻断计数且跑完后提示失败数`() {
        val running = TransferQueueSummary.of(
            listOf(task(1, TransferTaskStatus.FAILED), task(2, TransferTaskStatus.PENDING))
        )
        assertEquals("下载中 1/2", running.pillText(paused = false))
        val done = TransferQueueSummary.of(
            listOf(task(1, TransferTaskStatus.FAILED), task(2, TransferTaskStatus.COMPLETED))
        )
        assertEquals("1 项下载失败", done.pillText(paused = false))
    }

    @Test
    fun `全部成功显示已完成，空队列不显示入口`() {
        assertNull(TransferQueueSummary.of(emptyList()).pillText(paused = false))
        val done = TransferQueueSummary.of(
            listOf(task(1, TransferTaskStatus.COMPLETED), task(2, TransferTaskStatus.COMPLETED))
        )
        assertEquals("已完成 2/2", done.pillText(paused = false))
        assertTrue(done.allDone)
    }
}

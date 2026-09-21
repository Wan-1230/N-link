package com.nikonlink.app.device.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-02 验收②：三条 STA 超时路径的分支（含闸门关闭时的旧行为）。 */
class StaTimeoutPolicyTest {

    @Test
    fun `非配对且闸门开 —— event 通道不再无限阻塞`() {
        assertEquals(15000, StaTimeoutPolicy.eventSoTimeout(false, true, 15000))
    }

    @Test
    fun `闸门关时回到 v2_3_1 的 0，保证可回退`() {
        assertEquals(0, StaTimeoutPolicy.eventSoTimeout(false, false, 15000))
    }

    @Test
    fun `配对模式无论闸门开关都有超时（等用户按 OK 也需要边界）`() {
        assertEquals(60000, StaTimeoutPolicy.eventSoTimeout(true, true, 60000))
        assertEquals(60000, StaTimeoutPolicy.eventSoTimeout(true, false, 60000))
    }

    @Test
    fun `requestNetwork 的截止点取本次 acquire 的超时`() {
        assertEquals(6000, StaTimeoutPolicy.systemRequestDeadline(true, 6000L))
        assertNull(StaTimeoutPolicy.systemRequestDeadline(false, 6000L))
    }

    @Test
    fun `零与负超时被夹到 1ms，不递给系统一个 0`() {
        // requestNetwork(..., 0) 不是「马上超时」，别指望它
        assertEquals(1, StaTimeoutPolicy.systemRequestDeadline(true, 0L))
        assertEquals(1, StaTimeoutPolicy.systemRequestDeadline(true, -5_000L))
    }

    @Test
    fun `ARP 预算耗尽后返回非正数`() {
        assertTrue(StaTimeoutPolicy.arpRemainingMs(10_000L, 9_999L) > 0L)
        assertEquals(0L, StaTimeoutPolicy.arpRemainingMs(10_000L, 10_000L))
        assertTrue(StaTimeoutPolicy.arpRemainingMs(10_000L, 12_000L) < 0L)
    }
}

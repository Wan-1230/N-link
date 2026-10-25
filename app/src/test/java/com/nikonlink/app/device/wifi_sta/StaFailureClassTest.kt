package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.ptp.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-07 验收①：争抢型失败要有自己的桶，且不能被别的分支抢先吃掉。 */
class StaFailureClassTest {

    @Test
    fun `机身说连接被占用 - 归到争抢`() {
        assertEquals(
            StaFailureClass.BUSY_OTHER_CLIENT,
            StaFailureClass.classify(PtpConstants.INIT_FAIL_CONNECTION_IN_USE, false, true)
        )
        assertEquals(
            StaFailureClass.BUSY_OTHER_CLIENT,
            StaFailureClass.classify(PtpConstants.INIT_FAIL_HOST_ALREADY_CONNECTED, false, true)
        )
    }

    @Test
    fun `机身说被占用时，可达性探测与静默都不能翻案`() {
        // 机身自己已经答过话（InitFail），任何推断都必须排在它后面
        assertEquals(
            StaFailureClass.BUSY_OTHER_CLIENT,
            StaFailureClass.classify(PtpConstants.INIT_FAIL_CONNECTION_IN_USE, true, false)
        )
    }

    @Test
    fun `拒绝但不属于占用 - 归到 denied 而不是笼统握手失败`() {
        assertEquals(
            StaFailureClass.DENIED,
            StaFailureClass.classify(PtpConstants.INIT_FAIL_CONNECTION_DENIED, false, true)
        )
    }

    @Test
    fun `没有机身证据时才用推断，静默优先于不可达`() {
        assertEquals(StaFailureClass.ACK_SILENT, StaFailureClass.classify(null, true, false))
        assertEquals(StaFailureClass.UNREACHABLE, StaFailureClass.classify(null, false, false))
        assertEquals(StaFailureClass.HANDSHAKE_FAILED, StaFailureClass.classify(null, false, true))
    }

    @Test
    fun `原因码与既有文案分支保持兼容`() {
        // 老文案分支按字面量匹配，改分类不能把它换掉
        assertEquals("ptp_handshake_failed", StaFailureClass.HANDSHAKE_FAILED)
        assertEquals("camera_unreachable", StaFailureClass.UNREACHABLE)
        assertEquals("event_ack_timeout", StaFailureClass.ACK_SILENT)
    }

    // ── FR-39①：reason=1 持续被拒的分诊（classify 本身不动，只加分诊判据） ──

    @Test
    fun `首次与短窗内的 reason=1 不升级 - 维持忙等待口径`() {
        // streak=0：还没被拒过（首次失败前的初始态）
        assertFalse(StaFailureClass.initFail1NeedsHostReg(0, 0L))
        // 次数够但时间没跨过 35s 槽位回收窗 —— 可能只是相机在收上一代半开会话
        assertFalse(StaFailureClass.initFail1NeedsHostReg(4, 10_000L))
        assertFalse(StaFailureClass.initFail1NeedsHostReg(4, 44_999L))
        // 时间够但只拒了 1~3 次 —— 半开会话场景也会短连击，不升级
        assertFalse(StaFailureClass.initFail1NeedsHostReg(1, 60_000L))
        assertFalse(StaFailureClass.initFail1NeedsHostReg(3, 120_000L))
    }

    @Test
    fun `跨过槽位回收窗仍持续被拒 - 升级为主机注册引导`() {
        // 机身收回半开会话按 FR-21 实测 ~35s；连续 4 次 + 45s 仍在拒，
        // 剩下的解释只有"不认本机 GUID"（同一路由器新配置文件未注册）。
        assertTrue(StaFailureClass.initFail1NeedsHostReg(4, 45_000L))
        assertTrue(StaFailureClass.initFail1NeedsHostReg(9, 90_000L))
    }

    @Test
    fun `分诊门槛常量钉死 - 防止后续调参无声漂移`() {
        assertEquals(4, StaFailureClass.INITFAIL1_STREAK_THRESHOLD)
        assertEquals(45_000L, StaFailureClass.INITFAIL1_ELAPSED_FLOOR_MS)
    }
}

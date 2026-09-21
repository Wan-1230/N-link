package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.ptp.PtpConstants
import org.junit.Assert.assertEquals
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
}

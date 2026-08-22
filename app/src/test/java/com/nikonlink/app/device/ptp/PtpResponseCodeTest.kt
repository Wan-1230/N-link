package com.nikonlink.app.device.ptp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PTP 响应码中文化映射测试（全链路错误提示统一）。
 */
class PtpResponseCodeTest {

    @Test
    fun `known response codes map to Chinese description`() {
        assertEquals("成功", PtpConstants.describeResponseCode(PtpConstants.RESPONSE_OK))
        assertEquals(
            "相机忙碌，请稍后重试",
            PtpConstants.describeResponseCode(PtpConstants.RESPONSE_DEVICE_BUSY)
        )
        assertEquals(
            "访问被拒绝（相机端未确认连接）",
            PtpConstants.describeResponseCode(PtpConstants.RESPONSE_ACCESS_DENIED)
        )
        assertEquals(
            "相机不支持该操作",
            PtpConstants.describeResponseCode(PtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED)
        )
        assertEquals(
            "存储卡已满",
            PtpConstants.describeResponseCode(PtpConstants.RESPONSE_STORE_FULL)
        )
    }

    @Test
    fun `unknown response codes fall back to hex`() {
        assertEquals("0xABCD", PtpConstants.describeResponseCode(0xABCD))
    }
}
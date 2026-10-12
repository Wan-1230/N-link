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

    /**
     * 0x2013 必须有自己的中文释义，不能落进 hex 兜底。
     *
     * 2026-10-12 群友日志里 `0x1005` / `0x1007` 报的就是这个码，而常量表当时没有它，
     * 于是导出日志里只留下 `code=0x2013` 这串数字 —— 判读所需的语义在打日志那一步就丢了。
     * 外部两处一致定为 **Store Not Available**（微软 MTP 错误代码表、Wireshark
     * `packet-ptpip.h`，2026-10-12 取用），且微软明确写着这个码**不用于表示存储繁忙**，
     * 所以相册侧对它的第一反应不该是重试。
     */
    @Test
    fun `0x2013 存储不可用有独立释义而不是裸 hex`() {
        assertEquals(
            "存储不可用（卡未在位或已弹出）",
            PtpConstants.describeResponseCode(PtpConstants.RESPONSE_STORE_NOT_AVAILABLE)
        )
        assertEquals(0x2013, PtpConstants.RESPONSE_STORE_NOT_AVAILABLE)
    }

    @Test
    fun `unknown response codes fall back to hex`() {
        assertEquals("0xABCD", PtpConstants.describeResponseCode(0xABCD))
    }
}
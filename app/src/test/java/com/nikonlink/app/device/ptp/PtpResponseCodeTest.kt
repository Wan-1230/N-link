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
            "访问被拒绝（相机端未放行该操作）",
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
    fun `unknown non-vendor response codes fall back to hex`() {
        // 0x1234 不在厂商私有段，保持裸十六进制（这条兜底原本就是给扩展排查用的）
        assertEquals("0x1234", PtpConstants.describeResponseCode(0x1234))
    }

    /**
     * A4/E2：厂商私有段（0xAxxx）此前也只给裸 hex，日志与用户提示里就是一串没有语义的数。
     * 现在单列一句中文，至少让人知道「这是 Nikon 私有码、本项目未登记」。
     */
    @Test
    fun `unknown vendor response codes get a Chinese explanation instead of bare hex`() {
        val text = PtpConstants.describeResponseCode(0xABCD)
        assert(text.startsWith("相机返回厂商私有错误 0xABCD")) { "实际: $text" }
        assert(text.contains("未登记释义")) { "实际: $text" }
    }

    /**
     * A4/E2：0xA002 —— 释义状态是「未释义」，不是编出来的中文名。
     *
     * 唯一可核证据是本项目日志 A `ptp_tx op=0x90c8 code=0xa002`（DeviceReady 保活被拒，
     * 出现在监看中断前一刻）。它不在标准 PTP 响应码段内，也没有可核的公开厂商定义；
     * 按 v3.0 §3.7「不采信未经核实的第三方常量表」的纪律，只登记复现证据与处置口径。
     */
    @Test
    fun `0xA002 is described but explicitly marked as undefined`() {
        assertEquals(0xA002, PtpConstants.RESPONSE_NIKON_UNDEFINED_A002)
        val text = PtpConstants.describeResponseCode(PtpConstants.RESPONSE_NIKON_UNDEFINED_A002)
        assert(text.contains("0xA002")) { "实际: $text" }
        assert(text.contains("无权威释义")) { "必须显式标注未释义，不能假装知道它是什么。实际: $text" }
        assert(text.contains("DeviceReady")) { "应带上唯一的复现证据。实际: $text" }
    }
}
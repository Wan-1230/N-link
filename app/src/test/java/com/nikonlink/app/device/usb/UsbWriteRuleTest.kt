package com.nikonlink.app.device.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-28②：命令出阶段的成败判据。
 *
 * 这里只测纯规则本身（`bulkTransfer` 的返回值怎么翻译成成败）—— USB 栈的真实交互
 * JVM 测不到，测了也是自证。规则钉住的目的是让"闸门关掉 = 回到旧行为"这件事可验证。
 */
class UsbWriteRuleTest {

    /** 严格判据（闸门开）：只有写满长度才算成功 */
    @Test
    fun `strict mode requires the full length to be written`() {
        assertFalse(usbWriteFailed(returned = 12, expected = 12, strict = true))
        assertTrue("返回 0（超时未写入）必须算失败", usbWriteFailed(returned = 0, expected = 12, strict = true))
        assertTrue("短写必须算失败", usbWriteFailed(returned = 8, expected = 12, strict = true))
        assertTrue(usbWriteFailed(returned = -1, expected = 12, strict = true))
    }

    /**
     * 旧判据（闸门关）：< 0 才算失败。
     *
     * 这两条断言不是在为旧行为背书，而是把"0 和短写当时被放过"这个事实钉成回归护栏 ——
     * 谁将来改判据把 0 重新当成成功，这里会红。
     */
    @Test
    fun `legacy mode treats only negative as failure`() {
        assertFalse(usbWriteFailed(returned = 12, expected = 12, strict = false))
        assertTrue("旧判据下返回 0 会被当成发送成功，这正是 5s 空等的来源",
            usbWriteFailed(returned = -1, expected = 12, strict = false))
        assertFalse(usbWriteFailed(returned = 0, expected = 12, strict = false))
        assertFalse(usbWriteFailed(returned = 8, expected = 12, strict = false))
    }
}

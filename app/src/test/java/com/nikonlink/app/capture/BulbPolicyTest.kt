package com.nikonlink.app.capture

import com.nikonlink.app.device.ptp.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B 门链路策略测试（长曝光全链路修复的决策核心）：
 * - 开启策略序列：0x9207 → 0x100E → 0x100F，且仅 0x100F 需要收门补发 0x1010；
 * - busy 判定覆盖标准 0x2019 与尼康 0xA200（BulbReleaseBusy）；
 * - 失败文案可操作（通道不支持 / 档位不对 / 卡满各给对应指引）。
 */
class BulbPolicyTest {

    @Test
    fun `start plan tries vendor capture first then standard then open capture`() {
        val plan = BulbPolicy.startPlan(BulbPolicy.Channel.WIFI)
        assertEquals(3, plan.size)
        assertEquals(PtpConstants.OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA, plan[0].opcode)
        assertEquals(listOf(-1, 0), plan[0].params)
        assertEquals(PtpConstants.OP_INITIATE_CAPTURE, plan[1].opcode)
        assertEquals(PtpConstants.OP_INITIATE_OPEN_CAPTURE, plan[2].opcode)
        assertTrue(plan.take(2).none { it.usesOpenCapture })
        assertTrue(plan[2].usesOpenCapture)
    }

    @Test
    fun `standard capture params differ by channel`() {
        val wifi = BulbPolicy.startPlan(BulbPolicy.Channel.WIFI)[1].params
        val usb = BulbPolicy.startPlan(BulbPolicy.Channel.USB)[1].params
        assertEquals(listOf(0, 0), wifi)
        assertEquals(listOf(0), usb)
    }

    @Test
    fun `busy covers standard and nikon bulb busy`() {
        assertTrue(BulbPolicy.isBusy(PtpConstants.RESPONSE_DEVICE_BUSY))
        assertTrue(BulbPolicy.isBusy(PtpConstants.RESPONSE_NIKON_BULB_BUSY))
        assertFalse(BulbPolicy.isBusy(PtpConstants.RESPONSE_OK))
        assertFalse(BulbPolicy.isBusy(PtpConstants.RESPONSE_ACCESS_DENIED))
    }

    @Test
    fun `rejection messages are actionable`() {
        assertTrue(
            BulbPolicy.describeRejection(PtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED, false)
                .contains("USB")
        )
        assertTrue(
            BulbPolicy.describeRejection(PtpConstants.RESPONSE_ACCESS_DENIED, false)
                .contains("M/S 档")
        )
        assertTrue(
            BulbPolicy.describeRejection(PtpConstants.RESPONSE_STORE_FULL, false)
                .contains("存储卡")
        )
        // 其它失败码落在通用文案，包含响应码描述
        val generic = BulbPolicy.describeRejection(PtpConstants.RESPONSE_GENERAL_ERROR, false)
        assertTrue(generic.contains("开启曝光失败"))
        assertTrue(generic.contains("一般错误"))
    }

    @Test
    fun `bulb raw value is the 0xFFFFFFFF sentinel`() {
        assertEquals(-1, BulbPolicy.SHUTTER_BULB_RAW)
    }
}

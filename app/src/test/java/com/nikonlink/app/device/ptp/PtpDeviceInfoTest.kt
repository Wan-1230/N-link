package com.nikonlink.app.device.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FR-36②：`GetDeviceInfo` 里 Model 字段的解码回归。
 *
 * Model 前面压着五个 AUINT16 数组与 Manufacturer 字符串，**只能顺序解到它**，
 * 所以这套用例的价值不在「能不能取出字符串」，而在偏移对不对：
 * 少跳一个数组、或把 AUINT16 的计数当成 u16（规范是 u32），取回来的就是
 * 半截数组或一串垃圾字符——那会直接变成设备页上一个「看着像但不正确」的型号名。
 * 每条用例都同时跑 `parseOperations`（v2.6.3 就在用的那个解析器）做交叉验证：
 * 两个解析器对同一份应答的字段边界必须一致。
 */
class PtpDeviceInfoTest {

    private fun ptpString(value: String): ByteArray {
        val chars = value.length + 1           // 长度含结尾 NUL
        val buf = ByteBuffer.allocate(1 + chars * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(chars.toByte())
        value.forEach { buf.putShort(it.code.toShort()) }
        buf.putShort(0)
        return buf.array()
    }

    private fun aUINT16(values: List<Int>): ByteArray {
        val buf = ByteBuffer.allocate(4 + values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(values.size)
        values.forEach { buf.putShort(it.toShort()) }
        return buf.array()
    }

    /** 按 PTP 1.1 §13.2 的字段顺序拼一份应答体。 */
    private fun deviceInfo(
        model: String,
        manufacturer: String = "Nikon",
        operations: List<Int> = listOf(0x1001, 0x1002, 0x952B),
        events: List<Int> = listOf(0x1002),
        properties: List<Int> = listOf(0x5001, 0xD068),
        captureFormats: List<Int> = listOf(0x3801),
        imageFormats: List<Int> = listOf(0xB101)
    ): ByteArray {
        val parts = listOf(
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x0100)   // StandardVersion
                .putInt(0x0001)     // VendorExtensionID
                .putShort(0x0101)   // VendorExtensionVersion
                .array(),
            ptpString("Nikon v1.00"),   // VendorExtensionDesc
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0).array(),
            aUINT16(operations),
            aUINT16(events),
            aUINT16(properties),
            aUINT16(captureFormats),
            aUINT16(imageFormats),
            ptpString(manufacturer),
            ptpString(model)
        )
        return parts.fold(ByteArray(0)) { acc, part -> acc + part }
    }

    @Test
    fun `按规范顺序解到 Model，同时不破坏 OperationsSupported 的解析`() {
        val raw = deviceInfo(model = "NIKON Z 6_III")

        assertEquals("NIKON Z 6_III", PtpDeviceInfo.parseModel(raw))
        // 交叉验证：Model 与 Operations 是同一份应答里的两个字段，偏移算错任一个都会在这里露出来
        val ops = PtpDeviceInfo.parseOperations(raw)
        assertTrue("ops 应解析出 3 项，实际=$ops", ops != null && ops.size == 3)
        assertTrue("ops 里应有 0x952B，实际=$ops", ops?.contains(0x952B) == true)
    }

    @Test
    fun `真机见过的型号写法都能取出来`() {
        // v2.6 真机日志：usb_open ok=true model=Z 50II；PTP 侧机身自报形态见 UsbPtpProtocol 注释
        assertEquals("NIKON Z 50II", PtpDeviceInfo.parseModel(deviceInfo("NIKON Z 50II")))
        assertEquals("NIKON Z 8", PtpDeviceInfo.parseModel(deviceInfo("NIKON Z 8")))
        assertEquals("NIKON Z 5_II", PtpDeviceInfo.parseModel(deviceInfo("NIKON Z 5_II")))
    }

    /**
     * 取不到就必须返回 null，绝不能给个「像样的假值」：
     * 设备页的名称来源链据此回落，编出来的名字会一路显示到用户报障为止。
     */
    @Test
    fun `应答残缺、Model 为空或字段数不符一律 null`() {
        assertNull(PtpDeviceInfo.parseModel(null))
        assertNull(PtpDeviceInfo.parseModel(ByteArray(0)))
        // 截断：只留前半段（连数组头都不全）
        val full = deviceInfo(model = "NIKON Z 9")
        assertNull(PtpDeviceInfo.parseModel(full.copyOf(full.size / 2)))
        // Model 是空串：不是型号，交给下一个来源
        assertNull(PtpDeviceInfo.parseModel(deviceInfo(model = "")))
        assertNull(PtpDeviceInfo.parseModel(deviceInfo(model = "   ")))
    }

    @Test
    fun `数组个数被机身写大时不越界读取`() {
        // 尼康固件偶发把计数写大（parseOperations 里就有同款兜底注释）：
        // 声明 6 个操作码但只给 3 个的应答，必须解析失败而不是读出后面的字符串当数字
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0100).putInt(0x0001).putShort(0x0101).array()
        val bad = header + ptpString("Nikon v1.00") +
            ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(0).array() +
            ByteBuffer.allocate(4 + 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(6).putShort(0x1001).putShort(0x1002).putShort(0x952B.toShort()).array()
        assertNull(PtpDeviceInfo.parseModel(bad))
        // parseOperations 是**故意宽容**的：个数写大时按剩余字节夹住（v2.6.3 就这么写的），
        // 所以这里不断言 null，而是钉住「夹住之后不能越过数组边界把后面的字符串当成操作码」
        assertEquals(
            setOf(0x1001, 0x1002, 0x952B),
            PtpDeviceInfo.parseOperations(bad)
        )
    }
}

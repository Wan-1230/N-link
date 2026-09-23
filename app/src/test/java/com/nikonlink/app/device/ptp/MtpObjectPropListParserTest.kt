package com.nikonlink.app.device.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FR-16 验收②：`0x9805` 批量属性里带保护位时，必须从**批量**这一趟读出来，
 * 不允许为此退回逐张 `0x1008`（那会把一次往返变成 N 次）。
 * 所以这里锁两件事：报了要能取到，没报要留 null 而不是 0。
 */
class MtpObjectPropListParserTest {

    /** 载荷是连续条目流：u32 handle + u16 propCode + u16 dataType + 变长 value */
    private class Payload {
        private val buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

        fun u16(handle: Int, prop: Int, value: Int) = apply {
            buf.putInt(handle); buf.putShort(prop.toShort()); buf.putShort(MtpDataType.UINT16.toShort())
            buf.putShort(value.toShort())
        }

        fun u32(handle: Int, prop: Int, value: Int) = apply {
            buf.putInt(handle); buf.putShort(prop.toShort()); buf.putShort(MtpDataType.UINT32.toShort())
            buf.putInt(value)
        }

        fun str(handle: Int, prop: Int, value: String) = apply {
            buf.putInt(handle); buf.putShort(prop.toShort()); buf.putShort(MtpDataType.STRING.toShort())
            buf.put((value.length + 1).toByte())
            value.forEach { buf.putShort(it.code.toShort()) }
            buf.putShort(0)
        }

        fun bytes(): ByteArray = ByteArray(buf.position()).also { buf.rewind(); buf.get(it) }
    }

    @Test
    fun `批量属性带保护位时直接读出，不需要逐张 GetObjectInfo`() {
        val payload = Payload()
            .u32(0x1001, MtpObjectProp.STORAGE_ID, 0x00010001)
            .u16(0x1001, MtpObjectProp.OBJECT_FORMAT, 0xB103)
            .u16(0x1001, MtpObjectProp.PROTECTION_STATUS, 0x0001)
            .bytes()

        val props = MtpObjectPropListParser.parse(payload)!!.single()
        assertEquals(0x1001, props.handle)
        assertEquals(0x0001, props.protectionStatus)
    }

    @Test
    fun `机身没给保护列时是 null，不能当成未保护`() {
        val payload = Payload()
            .u32(0x1002, MtpObjectProp.STORAGE_ID, 0x00010001)
            .u16(0x1002, MtpObjectProp.OBJECT_FORMAT, 0x3801)
            .str(0x1002, MtpObjectProp.OBJECT_FILE_NAME, "DSC_0002.JPG")
            .bytes()

        val props = MtpObjectPropListParser.parse(payload)!!.single()
        assertNull(props.protectionStatus)
        assertEquals("DSC_0002.JPG", props.fileName)
    }

    @Test
    fun `保护位按无符号解释`() {
        val payload = Payload()
            .u16(0x1003, MtpObjectProp.PROTECTION_STATUS, 0x8001)
            .bytes()

        assertEquals(0x8001, MtpObjectPropListParser.parse(payload)!!.single().protectionStatus)
    }

    @Test
    fun `同一对象的多个条目归并到一条`() {
        val payload = Payload()
            .u32(0x1004, MtpObjectProp.OBJECT_SIZE, 2048)
            .u16(0x1004, MtpObjectProp.PROTECTION_STATUS, 0x0001)
            .u32(0x1005, MtpObjectProp.OBJECT_SIZE, 4096)
            .u16(0x1005, MtpObjectProp.PROTECTION_STATUS, 0x0000)
            .bytes()

        val all = MtpObjectPropListParser.parse(payload)!!
        assertEquals(2, all.size)
        assertEquals(0x0001, all[0].protectionStatus)
        assertEquals(2048L, all[0].objectSize)
        assertEquals(0x0000, all[1].protectionStatus)
        assertEquals(4096L, all[1].objectSize)
    }
}

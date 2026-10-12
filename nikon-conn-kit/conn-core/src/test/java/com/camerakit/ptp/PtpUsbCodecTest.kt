package com.camerakit.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PtpUsbCodecTest {

    @Test
    fun pack_parse_roundTrip() {
        val bytes = PtpUsbCodec.pack(PtpConsts.USB_CONTAINER_COMMAND, 0x1001, 7, PtpUsbCodec.paramsPayload(listOf(1L, 2L)))
        assertEquals(12 + 8, bytes.size)

        val c = PtpUsbCodec.parse(bytes)
        assertEquals(PtpConsts.USB_CONTAINER_COMMAND, c.type)
        assertEquals(0x1001, c.code)
        assertEquals(7, c.transId)
        assertEquals(listOf(1L, 2L), PtpUsbCodec.paramsOf(c.payload))
    }

    @Test
    fun header_parse() {
        val bytes = PtpUsbCodec.pack(PtpConsts.USB_CONTAINER_RESPONSE, 0x2001, 3)
        val h = PtpUsbCodec.parseHeader(bytes)
        assertEquals(12, h.length)
        assertEquals(PtpConsts.USB_CONTAINER_RESPONSE, h.type)
        assertEquals(0x2001, h.code)
        assertEquals(3, h.transId)
    }

    @Test
    fun container_layoutIsLittleEndian() {
        val bytes = PtpUsbCodec.pack(PtpConsts.USB_CONTAINER_DATA, 0x1009, 0x01020304, byteArrayOf(1))
        val length = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(13, length)
        assertEquals(2.toByte(), bytes[4]) // type u16 LE 低字节
        assertEquals(0x09.toByte(), bytes[6]) // code 0x1009 低字节
        assertEquals(0x10.toByte(), bytes[7])
    }

    @Test
    fun parseHeader_rejectsBadLength() {
        val bad = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(4).putShort(1).putShort(1).putInt(1).array()
        assertThrows(PtpProtocolException::class.java) {
            PtpUsbCodec.parseHeader(bad)
        }
    }

    @Test
    fun params_roundTrip() {
        val params = listOf(0xFFFFFFFFL, 42L)
        assertArrayEquals(
            PtpUsbCodec.paramsPayload(params),
            PtpUsbCodec.paramsPayload(PtpUsbCodec.paramsOf(PtpUsbCodec.paramsPayload(params))),
        )
    }
}

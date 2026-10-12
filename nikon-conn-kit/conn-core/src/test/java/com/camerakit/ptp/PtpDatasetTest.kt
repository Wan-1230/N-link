package com.camerakit.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PtpDatasetTest {

    @Test
    fun string_roundTrip() {
        val bytes = PtpDatasetWriter().string("NIKON Z 9").toByteArray()
        // u8 count(含 \0) + 10×2 字节
        assertEquals(1 + 10 * 2, bytes.size)
        assertEquals("NIKON Z 9", PtpDatasetReader(bytes).string())
    }

    @Test
    fun emptyString_roundTrip() {
        // PTP 字符串计数含结尾 \0：空串 = u8(1) + u16(0) 共 3 字节
        val bytes = PtpDatasetWriter().string("").toByteArray()
        assertEquals(3, bytes.size)
        assertEquals("", PtpDatasetReader(bytes).string())
    }

    @Test
    fun arrays_roundTrip() {
        val w = PtpDatasetWriter().u16Array(listOf(0x1001, 0x9431)).u32Array(listOf(1L, 0xFFFFFFFFL))
        val r = PtpDatasetReader(w.toByteArray())
        assertEquals(listOf(0x1001, 0x9431), r.u16Array())
        assertEquals(listOf(1L, 0xFFFFFFFFL), r.u32Array())
    }

    @Test
    fun scalars_roundTrip() {
        val w = PtpDatasetWriter().u8(0xAB).u16(0xBEEF).u32(0xDEADBEEFL).u64(0x1122334455667788L)
        val r = PtpDatasetReader(w.toByteArray())
        assertEquals(0xAB, r.u8())
        assertEquals(0xBEEF, r.u16())
        assertEquals(0xDEADBEEFL, r.u32())
        assertEquals(0x1122334455667788L, r.u64())
    }

    @Test
    fun reader_underflow_throws() {
        val r = PtpDatasetReader(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { r.u32() }
    }
}

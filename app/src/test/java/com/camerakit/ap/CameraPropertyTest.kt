package com.camerakit.ap

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CameraPropertyTest {
    @Test fun autoSyncReadsSignedAndUnsignedCurrentValues() {
        assertEquals(-333L, CameraProperty.scalar(byteArrayOf(0xb3.toByte(), 0xfe.toByte()), 3))
        assertEquals(0xffffffffL, CameraProperty.scalar(byteArrayOf(-1, -1, -1, -1), 6))
    }
    private fun u16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun u32(v: Int) = ByteArray(4).also { ApPtpWire.put32(it, 0, v) }

    @Test fun signedExposureCompensationEnumerationAndWrite() {
        val data = u16(0x5010) + u16(3) + byteArrayOf(1) + u16(0) + u16(-333) +
            byteArrayOf(2) + u16(3) + u16(-333) + u16(0) + u16(333)
        val property = CameraProperty.decode(data)
        assertEquals(-333L, property.current)
        assertEquals(listOf(-333L, 0L, 333L), property.choices())
        assertEquals("-0.3 EV", property.label())
        assertArrayEquals(u16(-333), property.encode(-333))
    }

    @Test fun shutterUsesUnsigned32BitAndFractionalSeconds() {
        val data = u16(0x500D) + u16(6) + byteArrayOf(1) + u32(100) + u32(-1) +
            byteArrayOf(2) + u16(3) + u32(80) + u32(10000) + u32(-1)
        val property = CameraProperty.decode(data)
        assertEquals(0xffffffffL, property.current)
        assertEquals("Bulb", property.label())
        assertEquals(80L, property.parseInput("1/125"))
        assertArrayEquals(u32(80), property.encode(80))
    }

    @Test(expected = IOException::class) fun rejectsValueOutsideCameraEnumeration() {
        CameraProperty(0x500F, 4, true, 100, listOf(100, 200, 400)).encode(300)
    }
    @Test(expected = IOException::class) fun rejectsReadOnlyWrite() {
        CameraProperty(0x5007, 4, false, 280, emptyList()).encode(400)
    }
    @Test(expected = IOException::class) fun rejectsTruncatedDescriptor() {
        CameraProperty.decode(u16(0x500F) + u16(4) + byteArrayOf(1) + u16(100))
    }
    @Test fun rangeChecksStepAndHumanUnits() {
        val property = CameraProperty(0x5007, 4, true, 280, emptyList(), 200, 800, 10)
        assertEquals(280L, property.parseInput("2.8"))
        assertEquals("f/2.8", property.label())
        assertThrows(IOException::class.java) { property.encode(285) }
    }
}

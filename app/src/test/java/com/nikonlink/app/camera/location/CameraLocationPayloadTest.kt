package com.nikonlink.app.camera.location

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * 0x9523 数据阶段是固定 41 字节，这里锁死偏移与字节序：
 * 任何一位错位都会让相机把经度当纬度、或把时间写成 1970 年，真机上表现为
 * "照片有 GPS 但位置在天上/海里"，靠肉眼很难发现。
 */
class CameraLocationPayloadTest {

    private fun ByteArray.shortAt(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

    @Test
    fun `payload is 41 bytes with expected header and datum`() {
        val out = CameraLocationPayload.build(
            latitude = 39.5,
            longitude = 116.5,
            altitude = 43.5,
            timeMillis = Instant.parse("2026-10-25T08:30:15.123Z").toEpochMilli(),
            satellites = 12
        )

        assertEquals(41, out.size)
        assertEquals(0x3F, out[0].toInt() and 0xFF)
        assertEquals(0, out[1].toInt() and 0xFF)
        assertEquals('N'.code, out[2].toInt() and 0xFF)
        assertEquals(39, out[3].toInt() and 0xFF)
        assertEquals(30, out[4].toInt() and 0xFF)
        assertEquals(0, out[5].toInt() and 0xFF)
        assertEquals(0, out[6].toInt() and 0xFF)
        assertEquals('E'.code, out[7].toInt() and 0xFF)
        assertEquals(116, out[8].toInt() and 0xFF)
        assertEquals(30, out[9].toInt() and 0xFF)
        assertEquals(0, out[10].toInt() and 0xFF)
        assertEquals(0, out[11].toInt() and 0xFF)
        assertEquals(12, out[12].toInt() and 0xFF)
        assertEquals('P'.code, out[13].toInt() and 0xFF)
        assertEquals(43, out.shortAt(14))
    }

    @Test
    fun `time is written as UTC to the byte`() {
        val out = CameraLocationPayload.build(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            timeMillis = Instant.parse("2026-10-25T08:30:15.123Z").toEpochMilli(),
            satellites = 0
        )

        assertEquals(2026, out.shortAt(16))
        assertEquals(10, out[18].toInt() and 0xFF)
        assertEquals(25, out[19].toInt() and 0xFF)
        assertEquals(8, out[20].toInt() and 0xFF)
        assertEquals(30, out[21].toInt() and 0xFF)
        assertEquals(15, out[22].toInt() and 0xFF)
        assertEquals(12, out[23].toInt() and 0xFF)
        assertEquals(1, out[24].toInt() and 0xFF)
        assertArrayEquals("WGS-84".toByteArray(Charsets.US_ASCII), out.copyOfRange(25, 31))
        assertArrayEquals(ByteArray(10), out.copyOfRange(31, 41))
    }

    @Test
    fun `southern and western hemispheres keep magnitude positive`() {
        val out = CameraLocationPayload.build(
            latitude = -33.8688,
            longitude = -70.6693,
            altitude = -12.0,
            timeMillis = Instant.parse("2026-01-02T03:04:05.678Z").toEpochMilli(),
            satellites = 6
        )

        assertEquals('S'.code, out[2].toInt() and 0xFF)
        assertEquals(33, out[3].toInt() and 0xFF)
        assertEquals('W'.code, out[7].toInt() and 0xFF)
        assertEquals(70, out[8].toInt() and 0xFF)
        assertEquals('M'.code, out[13].toInt() and 0xFF)
        assertEquals(12, out.shortAt(14))
        assertEquals(6, out[12].toInt() and 0xFF)
        assertEquals(0x3F, out[0].toInt() and 0xFF)
    }

    @Test
    fun `missing satellites clears that flag bit but keeps the rest`() {
        val out = CameraLocationPayload.build(
            latitude = 1.0,
            longitude = 2.0,
            altitude = 3.0,
            timeMillis = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
            satellites = 0
        )

        // 0x3B = 纬度/经度/海拔/时间/GPS，不含卫星位
        assertEquals(0x3B, out[0].toInt() and 0xFF)
        assertEquals(0, out[12].toInt() and 0xFF)
    }

    @Test
    fun `disabled payload is all zero`() {
        assertArrayEquals(ByteArray(41), CameraLocationPayload.disabled())
    }
}

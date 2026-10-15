package com.camerakit.ptp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PtpIpCodecTest {

    @Test
    fun initCommandPayload_layout() {
        val guid = ByteArray(16) { it.toByte() }
        val payload = PtpIpCodec.initCommandPayload(guid, "Host")

        // GUID(16) + "Host\0" UTF-16LE (10B) + version 0x00010000
        assertEquals(16 + 10 + 4, payload.size)
        assertArrayEquals(guid, payload.copyOfRange(0, 16))
        // UTF-16LE 'H','o','s','t',0
        assertEquals('H'.code, (payload[16].toInt() and 0xFF) or ((payload[17].toInt() and 0xFF) shl 8))
        val version = ByteBuffer.wrap(payload, payload.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x00010000, version)
    }

    @Test
    fun packet_roundTrip() {
        val out = ByteArrayOutputStream()
        PtpIpCodec.writePacket(out, PtpConsts.IP_INIT_COMMAND, byteArrayOf(1, 2, 3))
        val pkt = PtpIpCodec.readPacket(ByteArrayInputStream(out.toByteArray()))
        assertEquals(PtpConsts.IP_INIT_COMMAND, pkt.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), pkt.payload)
    }

    @Test
    fun readPacket_rejectsBadLength() {
        val bad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(4).putInt(6).array()
        assertThrows(PtpProtocolException::class.java) {
            PtpIpCodec.readPacket(ByteArrayInputStream(bad))
        }
    }

    @Test
    fun cmdRequestPayload_layout() {
        // dataPhaseInfo：无数据相位=1、有数据相位=2；opcode u16；transId u32；params n×u32
        val payload = PtpIpCodec.cmdRequestPayload(1, 0x1001, 7, listOf(1L, 2L))
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buf.int)
        assertEquals(0x1001, buf.short.toInt() and 0xFFFF)
        assertEquals(7, buf.int)
        assertEquals(1L, buf.int.toLong())
        assertEquals(2L, buf.int.toLong())
    }

    @Test
    fun cmdResponse_parse() {
        val payload = PtpIpCodec.cmdResponsePayload(0x2001, 42, listOf(9L))
        val resp = PtpIpCodec.parseCmdResponse(payload)
        assertEquals(0x2001, resp.code)
        assertEquals(42, resp.transId)
        assertEquals(listOf(9L), resp.params)
    }

    @Test
    fun initCommandAck_parse() {
        val guid = ByteArray(16) { (it + 3).toByte() }
        val name = "Z 8"
        val nameBytes = ByteBuffer.allocate((name.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (c in name) nameBytes.putChar(c)
        nameBytes.putChar(0.toChar())
        val payload = ByteBuffer.allocate(20 + nameBytes.capacity()).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3).put(guid).put(nameBytes.array()).array()

        val ack = PtpIpCodec.parseInitCommandAck(payload)
        assertEquals(3, ack.connectionId)
        assertArrayEquals(guid, ack.cameraGuid)
        assertEquals("Z 8", ack.cameraName)
    }

    @Test
    fun dataPayload_transIdAndData() {
        val payload = PtpIpCodec.dataPayload(5, byteArrayOf(9, 9))
        assertEquals(5, PtpIpCodec.payloadTransId(payload))
        assertArrayEquals(byteArrayOf(9, 9), PtpIpCodec.payloadData(payload))
    }

    @Test
    fun startData_isNotDataSection() {
        // StartData(type9) 载荷 = transId u32 + 总长 u64（12 字节），绝不能当数据段收集
        val payload = PtpIpCodec.startDataPayload(7, 383)
        assertEquals(12, payload.size)
        assertEquals(7, PtpIpCodec.payloadTransId(payload))
        assertEquals(383L, PtpIpCodec.payloadStartDataLen(payload))
    }

    @Test
    fun event_parse() {
        val payload = PtpIpCodec.eventPayload(0x4002, listOf(77L))
        val (code, params) = PtpIpCodec.parseEvent(payload)
        assertEquals(0x4002, code)
        assertEquals(listOf(77L), params)
    }
}

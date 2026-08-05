package com.nikonlink.app.core.ptp

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level conformance tests against the PTP/IP layout used by libgphoto2
 * (camlibs/ptp2/ptpip.c), which is the reference implementation for Nikon
 * PTP/IP cameras.
 */
class PtpProtocolTest {

    @Test
    fun `init command request matches libgphoto2 layout`() {
        val guid = ByteArray(16) { it.toByte() }
        val packet = InitCommandPacket(clientGuid = guid, clientName = "NikonLink")

        val bytes = packet.toBytes()
        assertEquals(48, bytes.size)
        assertEquals(48, intLE(bytes, 0))
        assertEquals(PtpConstants.PACKET_TYPE_INIT_COMMAND, intLE(bytes, 4))
        assertArrayEquals(guid, bytes.copyOfRange(8, 24))

        val name = "NikonLink"
        name.forEachIndexed { index, char ->
            assertEquals(char.code, shortLE(bytes, 24 + index * 2))
        }
        val nameEnd = 24 + name.length * 2
        assertEquals(0, shortLE(bytes, nameEnd))
        assertEquals(0, shortLE(bytes, nameEnd + 2)) // version minor
        assertEquals(1, shortLE(bytes, nameEnd + 4)) // version major
    }

    @Test
    fun `command request packet encodes data phase opcode transaction and params`() {
        val bytes = CommandRequestPacket(
            transactionId = 0x01020304,
            operationCode = PtpConstants.OP_OPEN_SESSION,
            parameters = listOf(0x11223344)
        ).toBytes()

        assertEquals(22, bytes.size)
        assertEquals(22, intLE(bytes, 0))
        assertEquals(PtpConstants.PACKET_TYPE_COMMAND_REQUEST, intLE(bytes, 4))
        assertEquals(1, intLE(bytes, 8))
        assertEquals(PtpConstants.OP_OPEN_SESSION, shortLE(bytes, 12))
        assertEquals(0x01020304, intLE(bytes, 14))
        assertEquals(0x11223344, intLE(bytes, 18))

        val dataPhase = CommandRequestPacket(
            transactionId = 7,
            operationCode = PtpConstants.OP_SET_DEVICE_PROP_VALUE,
            parameters = listOf(0x5007),
            dataPhase = true
        ).toBytes()
        assertEquals(2, intLE(dataPhase, 8))
    }

    @Test
    fun `command response parses response code transaction and params`() {
        val payload = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(PtpConstants.RESPONSE_OK.toShort())
            .putInt(9)
            .putInt(11)
            .putInt(22)
            .array()

        val packet = CommandResponsePacket.parse(payload)
        assertEquals(PtpConstants.RESPONSE_OK, packet.responseCode)
        assertEquals(9, packet.transactionId)
        assertEquals(listOf(11, 22), packet.parameters)
        assertTrue(packet.isOk)
    }

    @Test
    fun `start data packet carries transaction total size and reserved word`() {
        val bytes = StartDataPacket(transactionId = 5, totalSize = 1234).toBytes()

        assertEquals(20, bytes.size)
        assertEquals(20, intLE(bytes, 0))
        assertEquals(PtpConstants.PACKET_TYPE_START_DATA, intLE(bytes, 4))
        assertEquals(5, intLE(bytes, 8))
        assertEquals(1234, intLE(bytes, 12))
        assertEquals(0, intLE(bytes, 16))
    }

    @Test
    fun `data and end data packets round trip through stream parser`() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5)
        val dataBytes = DataPacket(transactionId = 6, data = data).toBytes()
        val parsedData = PtpPacket.fromStream(ByteArrayInputStream(dataBytes)) as DataPacket
        assertEquals(6, parsedData.transactionId)
        assertArrayEquals(data, parsedData.data)

        val endBytes = EndDataPacket(transactionId = 7, data = data).toBytes()
        val parsedEnd = PtpPacket.fromStream(ByteArrayInputStream(endBytes)) as EndDataPacket
        assertEquals(7, parsedEnd.transactionId)
        assertArrayEquals(data, parsedEnd.data)
    }

    @Test
    fun `event packet parses code transaction and params`() {
        val payload = ByteBuffer.allocate(14)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(PtpConstants.EVENT_DEVICE_PROP_CHANGED.toShort())
            .putInt(8)
            .putInt(1)
            .putInt(2)
            .array()

        val packet = EventResponsePacket.parse(payload)
        assertEquals(PtpConstants.EVENT_DEVICE_PROP_CHANGED, packet.eventCode)
        assertEquals(8, packet.transactionId)
        assertEquals(listOf(1, 2), packet.parameters)
    }

    @Test
    fun `init response parses session id guid and camera name`() {
        val cameraGuid = ByteArray(16) { (it + 0x10).toByte() }
        val cameraName = "NIKON Z 50II"
        val nameBytes = cameraName.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val payload = ByteBuffer.allocate(4 + 16 + nameBytes.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x55667788)
            .put(cameraGuid)
            .put(nameBytes)
            .putInt(1) // trailing version word, ignored by the parser
            .array()

        val packet = InitResponsePacket.parse(payload)
        assertEquals(0x55667788, packet.sessionId)
        assertArrayEquals(cameraGuid, packet.serverGuid)
        assertEquals(cameraName, packet.serverName)
    }

    @Test
    fun `ping packet is header only`() {
        val bytes = PingPacket.toBytes()
        assertEquals(8, bytes.size)
        assertEquals(8, intLE(bytes, 0))
        assertEquals(PtpConstants.PACKET_TYPE_PING, intLE(bytes, 4))
    }

    @Test
    fun `pong packet is header only and round trips through stream parser`() {
        val bytes = PongPacket.toBytes()
        assertEquals(8, bytes.size)
        assertEquals(8, intLE(bytes, 0))
        assertEquals(PtpConstants.PACKET_TYPE_PONG, intLE(bytes, 4))
        val parsed = PtpPacket.fromStream(ByteArrayInputStream(bytes))
        assertEquals(PtpConstants.PACKET_TYPE_PONG, parsed!!.type)
    }

    @Test
    fun `unknown init fail packet is parsed without crashing`() {
        val bytes = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12)
            .putInt(PtpConstants.PACKET_TYPE_INIT_FAIL)
            .putInt(0x00000005)
            .array()

        val packet = PtpPacket.fromStream(ByteArrayInputStream(bytes))
        assertNotNull(packet)
        assertEquals(PtpConstants.PACKET_TYPE_INIT_FAIL, packet!!.type)
    }

    private fun intLE(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun shortLE(bytes: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }
}

package com.nikonlink.app.core.ptp

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full PTP/IP handshake integration test against an in-process mock camera.
 *
 * The mock follows the same packet sequence as libgphoto2's ptpip client:
 * init command -> init ack -> event init -> event ack -> OpenSession ->
 * DeviceReady, then regular command/data transactions.
 */
class PtpSessionManagerTest {

    @Test
    fun `connects to mock camera and runs data commands`() = runBlocking {
        val camera = MockPtpCamera()
        camera.start()
        try {
            val session = PtpSessionManager(TestIdentity())
            withTimeout(10_000) {
                val connected = session.connect(
                    host = "127.0.0.1",
                    port = camera.port,
                    pairingMode = true
                )
                assertTrue("PTP/IP connect should succeed against mock camera", connected)
            }

            assertEquals(PtpSessionState.CONNECTED, session.sessionState.value)
            assertTrue(session.isConnected())

            val deviceInfo = session.getDeviceInfo()
            assertArrayEquals(MockPtpCamera.DEVICE_INFO, deviceInfo)

            assertEquals(listOf(MockPtpCamera.STORAGE_ID), session.getStorageIds())

            val response = session.sendCommand(PtpConstants.OP_INITIATE_CAPTURE, listOf(0, 0))
            assertTrue(response.isOk)

            val setProp = session.setDevicePropValue(
                propCode = PtpConstants.PROP_F_NUMBER,
                value = byteArrayOf(0x28, 0x00, 0x00, 0x00)
            )
            assertTrue("SetDevicePropValue data phase should complete", setProp)

            session.closeSession()
            assertEquals(PtpSessionState.DISCONNECTED, session.sessionState.value)
            assertFalse(session.isConnected())
        } finally {
            camera.stop()
        }
    }

    @Test
    fun `handshake failure surfaces error state`() = runBlocking {
        val camera = RejectingPtpCamera()
        camera.start()
        try {
            val session = PtpSessionManager(TestIdentity())
            val connected = withTimeout(10_000) {
                session.connect("127.0.0.1", camera.port, pairingMode = true)
            }
            assertFalse(connected)
            assertEquals(PtpSessionState.ERROR, session.sessionState.value)
        } finally {
            camera.stop()
        }
    }

    private class TestIdentity : PtpClientIdentity {
        override val clientGuid: ByteArray = ByteArray(16) { 0x42 }
        override val clientName: String = "NikonLink-Test"
    }
}

private class MockPtpCamera {
    companion object {
        val DEVICE_INFO: ByteArray = byteArrayOf(
            0x64, 0x00,             // StandardVersion = 100
            0x06, 0x00, 0x00, 0x00, // VendorExtensionID
            0x64, 0x00,             // VendorExtensionVersion = 100
            0x00, 0x00,             // FunctionalMode
            0x00, 0x00,             // OperationsSupported count = 0
            0x00, 0x00,             // EventsSupported count = 0
            0x00, 0x00,             // DevicePropertiesSupported count = 0
            0x00, 0x00,             // CaptureFormats count = 0
            0x00, 0x00,             // ImageFormats count = 0
            0x05, 0x4E, 0x00, 0x69, 0x00, 0x6B, 0x00, 0x6F, 0x00, 0x6E, 0x00, 0x00, 0x00
        )
        const val STORAGE_ID = 0x00010001
        private const val SESSION_ID = 0x55667788
        private val CAMERA_GUID = ByteArray(16) { (it + 0x10).toByte() }
    }

    private val serverSocket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort
    private var thread: Thread? = null

    fun start() {
        thread = Thread {
            runCatching { serve() }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { serverSocket.close() }
        thread?.join(2000)
    }

    private fun serve() {
        val command = serverSocket.accept()
        val commandIn = command.getInputStream()
        val commandOut = command.getOutputStream()

        val init = PtpPacket.fromStream(commandIn) as? InitCommandPacket ?: return
        commandOut.write(initResponse(init))
        commandOut.flush()

        val event = serverSocket.accept()
        val eventIn = event.getInputStream()
        val eventOut = event.getOutputStream()
        val eventInit = PtpPacket.fromStream(eventIn)
        if (eventInit !is InitEventRequestPacket) return
        eventOut.write(InitEventAckPacket.toBytes())
        eventOut.flush()

        var pendingSetResponseTx: Int? = null
        while (true) {
            val packet = PtpPacket.fromStream(commandIn) ?: break
            when (packet) {
                is CommandRequestPacket -> {
                    if (packet.operationCode == PtpConstants.OP_SET_DEVICE_PROP_VALUE) {
                        pendingSetResponseTx = packet.transactionId
                    } else {
                        handleCommand(packet, commandOut)
                    }
                }
                is EndDataPacket -> {
                    val tx = pendingSetResponseTx
                    if (tx != null) {
                        commandOut.write(commandResponse(tx, PtpConstants.RESPONSE_OK))
                        commandOut.flush()
                        pendingSetResponseTx = null
                    }
                }
                else -> Unit
            }
        }
    }

    private fun handleCommand(packet: CommandRequestPacket, out: OutputStream) {
        val tx = packet.transactionId
        when (packet.operationCode) {
            PtpConstants.OP_OPEN_SESSION,
            PtpConstants.OP_NIKON_DEVICE_READY,
            PtpConstants.OP_INITIATE_CAPTURE,
            PtpConstants.OP_NIKON_START_LIVE_VIEW,
            PtpConstants.OP_NIKON_END_LIVE_VIEW,
            0x920A -> {
                out.write(commandResponse(tx, PtpConstants.RESPONSE_OK))
                out.flush()
            }
            PtpConstants.OP_GET_DEVICE_INFO -> {
                writeData(tx, DEVICE_INFO, out)
            }
            PtpConstants.OP_GET_STORAGE_IDS -> {
                val data = ByteBuffer.allocate(8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(1)
                    .putInt(STORAGE_ID)
                    .array()
                writeData(tx, data, out)
            }
            PtpConstants.OP_GET_OBJECT_HANDLES -> {
                writeData(tx, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array(), out)
            }
            PtpConstants.OP_GET_DEVICE_PROP_VALUE -> {
                writeData(tx, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x28).array(), out)
            }
            else -> {
                out.write(commandResponse(tx, PtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED))
                out.flush()
            }
        }
    }

    private fun writeData(tx: Int, data: ByteArray, out: OutputStream) {
        out.write(StartDataPacket(tx, data.size).toBytes())
        out.write(DataPacket(tx, data).toBytes())
        out.write(EndDataPacket(tx, ByteArray(0)).toBytes())
        out.write(commandResponse(tx, PtpConstants.RESPONSE_OK))
        out.flush()
    }

    private fun initResponse(init: InitCommandPacket): ByteArray {
        val cameraName = "NIKON-LINK-MOCK"
        val nameBytes = cameraName.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val payload = ByteBuffer.allocate(4 + 16 + nameBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SESSION_ID)
            .put(CAMERA_GUID)
            .put(nameBytes)
            .array()
        return packet(PtpConstants.PACKET_TYPE_INIT_RESPONSE, payload)
    }

    private fun commandResponse(tx: Int, code: Int): ByteArray {
        val payload = ByteBuffer.allocate(6)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(code.toShort())
            .putInt(tx)
            .array()
        return packet(PtpConstants.PACKET_TYPE_COMMAND_RESPONSE, payload)
    }

    private fun packet(type: Int, payload: ByteArray): ByteArray {
        return ByteBuffer.allocate(8 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8 + payload.size)
            .putInt(type)
            .put(payload)
            .array()
    }
}

/**
 * Camera that accepts the TCP connection but immediately sends InitFail.
 */
private class RejectingPtpCamera {
    private val serverSocket = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val port: Int = serverSocket.localPort
    private var thread: Thread? = null

    fun start() {
        thread = Thread {
            runCatching {
                val socket = serverSocket.accept()
                socket.getInputStream().use { input ->
                    input.readNBytes(48) // drain the init command
                }
                socket.getOutputStream().use { out ->
                    out.write(
                        ByteBuffer.allocate(12)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(12)
                            .putInt(PtpConstants.PACKET_TYPE_INIT_FAIL)
                            .putInt(0x00000005)
                            .array()
                    )
                    out.flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { serverSocket.close() }
        thread?.join(2000)
    }
}

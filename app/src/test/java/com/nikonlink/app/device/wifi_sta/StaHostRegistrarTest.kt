package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.ptp.*
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.shared.common.AppEventLogger
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StaHostRegistrarTest {
    @Test
    fun `注册先开会话且私有码未声明时仍按真实响应确认`() = runBlocking {
        RegistrationCamera().use { camera ->
            val registrar = registrar()
            val result = withTimeout(15_000) { registrar.register(camera.endpoint) }
            assertTrue(result is HostRegistrationResult.Success)
            camera.finished.get(3, TimeUnit.SECONDS)
            assertEquals(listOf(0x1002, 0x1001, 0x952b, 0x935a), camera.commands.map { it.operationCode })
            assertEquals(0, camera.commands.first().transactionId)
            assertEquals(listOf(1), camera.commands.first().parameters)
            assertTrue(camera.eventPong.get(1, TimeUnit.SECONDS))
            assertTrue(camera.commandClosed.get(1, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `预备注册被拒时不重发且关闭双通道`() = runBlocking {
        RegistrationCamera(prepareCode = PtpConstants.RESPONSE_ACCESS_DENIED).use { camera ->
            val result = withTimeout(5_000) { registrar().register(camera.endpoint) }
            assertEquals("prepare", (result as HostRegistrationResult.Failure).phase)
            assertEquals(1, camera.commands.count { it.operationCode == 0x952b })
            assertTrue(camera.commandClosed.get(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `取消等待时关闭通道并传播取消`() = runBlocking {
        RegistrationCamera().use { camera ->
            val registrar = registrar()
            val job = launch { registrar.register(camera.endpoint) }
            withContext(Dispatchers.IO) { camera.prepared.get(4, TimeUnit.SECONDS) }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(camera.commandClosed.get(2, TimeUnit.SECONDS))
            assertFalse(camera.commands.any { it.operationCode == 0x935a })
        }
    }

    @Test
    fun `取消阻塞的注册响应读取时及时关闭通道`() = runBlocking {
        RegistrationCamera(holdPrepareResponse = true).use { camera ->
            val job = launch { registrar().register(camera.endpoint) }
            withContext(Dispatchers.IO) { camera.prepared.get(4, TimeUnit.SECONDS) }
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue(camera.commandClosed.get(2, TimeUnit.SECONDS))
            assertFalse(camera.commands.any { it.operationCode == 0x935a })
        }
    }

    private fun registrar(): StaHostRegistrar {
        val identity = mockk<PtpIdentityStore>()
        every { identity.clientGuid } returns ByteArray(16) { 0x42 }
        every { identity.clientName } returns "测试主机"
        val routes = mockk<StaSocketRouteResolver>()
        every { routes.resolve(any(), any()) } returns StaSocketRouteResolver.Route(null, null)
        return StaHostRegistrar(identity, mockk<AppEventLogger>(relaxed = true), routes)
    }
}

private class RegistrationCamera(
    private val prepareCode: Int = PtpConstants.RESPONSE_OK,
    private val holdPrepareResponse: Boolean = false
) : AutoCloseable {
    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val endpoint = WifiEndpoint("127.0.0.1", server.localPort)
    val commands = CopyOnWriteArrayList<CommandRequestPacket>()
    val prepared = CompletableFuture<Boolean>()
    val eventPong = CompletableFuture<Boolean>()
    val commandClosed = CompletableFuture<Boolean>()
    val finished = CompletableFuture<Boolean>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val thread = Thread {
        try {
            val command = server.accept().also { sockets.add(it); it.soTimeout = 12_000 }
            val input = command.getInputStream()
            val output = command.getOutputStream()
            check(PtpPacket.fromStream(input) is InitCommandPacket)
            output.write(packet(2, ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(7).put(ByteArray(16)).putShort(65).putShort(0).putInt(0x10000).array()))
            output.flush()
            val event = server.accept().also { sockets.add(it); it.soTimeout = 12_000 }
            check((PtpPacket.fromStream(event.getInputStream()) as InitEventRequestPacket).sessionId == 7)
            event.getOutputStream().write(InitEventAckPacket.toBytes())
            event.getOutputStream().flush()
            check(PtpPacket.fromStream(event.getInputStream()) is PingPacket)
            event.getOutputStream().write(PongPacket.toBytes())
            event.getOutputStream().flush()
            while (true) {
                val request = PtpPacket.fromStream(input) as? CommandRequestPacket ?: break
                commands.add(request)
                if (request.operationCode == 0x952b && holdPrepareResponse) {
                    prepared.complete(true)
                    continue
                }
                if (request.operationCode == 0x1001) {
                    // 合法的空能力表，故意不声明厂商私有码。
                    val data = ByteArray(15)
                    output.write(StartDataPacket(request.transactionId, data.size).toBytes())
                    output.write(EndDataPacket(request.transactionId, data).toBytes())
                }
                val code = if (request.operationCode == 0x952b) prepareCode else PtpConstants.RESPONSE_OK
                output.write(packet(7, ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(code.toShort()).putInt(request.transactionId).array()))
                output.flush()
                if (request.operationCode == 0x952b && code == PtpConstants.RESPONSE_OK) {
                    event.getOutputStream().write(PingPacket.toBytes())
                    event.getOutputStream().flush()
                    eventPong.complete(PtpPacket.fromStream(event.getInputStream()) is PongPacket)
                    prepared.complete(true)
                }
            }
            commandClosed.complete(true)
            finished.complete(true)
        } catch (e: Throwable) {
            finished.completeExceptionally(e)
            commandClosed.completeExceptionally(e)
            prepared.completeExceptionally(e)
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        server.close()
        thread.join(2000)
    }

    private fun packet(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8 + payload.size).putInt(type).put(payload).array()
}

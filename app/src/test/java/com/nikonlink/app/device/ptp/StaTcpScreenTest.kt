package com.nikonlink.app.device.ptp

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.nikonlink.app.device.wifi.WifiEndpoint
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject

class StaTcpScreenTest {
    @Test
    fun `发现端口探测不发送InitCommand且使用指定本地地址`() = runBlocking {
        mockkObject(WifiEndpoint.Companion)
        every { WifiEndpoint.isProbeCandidate("127.0.0.1") } returns true
        try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val noHandshake = CompletableFuture<Boolean>()
            val thread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        noHandshake.complete(socket.getInputStream().read() == -1 &&
                            socket.inetAddress.hostAddress == "127.0.0.1")
                    }
                } catch (e: Exception) {
                    noHandshake.completeExceptionally(e)
                }
            }.apply { isDaemon = true; start() }
            assertTrue(PtpIpProbe.tcpConnectOnly(
                "127.0.0.1", server.localPort, 1000L, localAddress = "127.0.0.1"
            ))
            assertTrue(noHandshake.get(3, TimeUnit.SECONDS))
            thread.join(1000)
        }
        } finally {
            unmockkObject(WifiEndpoint.Companion)
        }
    }
}

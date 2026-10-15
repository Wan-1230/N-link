package com.nikonlink.app.device.wifi_sta

import android.content.Context
import com.nikonlink.app.device.ap.ApConnectionService
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.connect.ConnectionStateMachine
import com.nikonlink.app.device.ptp.HostRegistrationResult
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StaApIsolationTest {
    @Test
    fun `AP使用时STA扫描注册连接均不进入传输层`() = runBlocking {
        val ap = mockk<ApConnectionService>()
        every { ap.engaged } returns true
        val scanner = mockk<WifiScanner>()
        val registrar = mockk<StaHostRegistrar>()
        val connector = mockk<WifiDirectConnector>()
        val context = mockk<Context>(relaxed = true)
        val manager = ConnectionManager(
            context, mockk(relaxed = true), mockk(relaxed = true), scanner,
            mockk(relaxed = true), mockk(relaxed = true), ConnectionStateMachine(),
            mockk(relaxed = true), mockk(relaxed = true), connector,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), registrar,
            mockk(relaxed = true), ap, mockk(relaxed = true)
        )
        assertTrue(manager.scanWifiCameras().isEmpty())
        assertEquals("ap_active", (manager.registerStaHost() as HostRegistrationResult.Failure).phase)
        manager.connectToWifiCamera("192.168.43.2")
        confirmVerified(scanner, registrar, connector)
        verify(exactly = 0) { ap.disconnect() }
    }
}

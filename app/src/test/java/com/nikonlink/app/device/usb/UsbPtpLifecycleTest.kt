package com.nikonlink.app.device.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import com.nikonlink.app.device.ap.ApConnectionService
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.shared.common.AppEventLogger
import io.mockk.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UsbPtpLifecycleTest {
    @Test
    fun `重复连接通知不会重复打开或拆除正在建立的连接`() = runBlocking {
        Fixture().use { f ->
            f.blockOpen()
            f.start()
            assertTrue(f.openEntered.await(3, TimeUnit.SECONDS))
            repeat(4) { f.manager.checkExistingDevice() }
            verify(exactly = 1) { f.usb.openDevice(f.device) }
            f.openRelease.countDown()
            f.awaitConnected()
            repeat(3) { f.manager.checkExistingDevice() }
            verify(exactly = 1) { f.usb.openDevice(f.device) }
            verify(exactly = 0) { f.connection.close() }
        }
    }

    @Test
    fun `断开后迟到的打开结果不能恢复已取消连接`() = runBlocking {
        Fixture().use { f ->
            f.blockOpen()
            f.start()
            assertTrue(f.openEntered.await(3, TimeUnit.SECONDS))
            f.manager.disconnect()
            f.openRelease.countDown()
            withTimeout(3000) {
                while (f.connectJob()?.isActive == true) delay(10)
                while (!f.closed) delay(10)
            }
            assertEquals(UsbConnectionState.DISCONNECTED, f.manager.usbState.value)
            verify(exactly = 0) { f.connection.claimInterface(any(), any()) }
        }
    }

    @Test
    fun `其它USB设备拔出不能中断相机`() = runBlocking {
        Fixture().use { f ->
            f.start()
            f.awaitConnected()
            val other = mockk<UsbDevice> { every { deviceName } returns "/dev/bus/usb/other" }
            f.detach(other)
            assertTrue(f.manager.isConnected())
            verify(exactly = 0) { f.connection.close() }
        }
    }

    @Test
    fun `手动断开取消宽限任务而不会自动重连`() = runBlocking {
        Fixture(grace = true).use { f ->
            f.start()
            f.awaitConnected()
            f.detach(f.device)
            val grace = f.job("detachGraceJob")
            assertNotNull(grace)
            f.manager.disconnect()
            grace?.join()
            assertTrue(grace!!.isCancelled)
            assertEquals(UsbConnectionState.DISCONNECTED, f.manager.usbState.value)
            verify(exactly = 1) { f.usb.openDevice(f.device) }
        }
    }

    @Test
    fun `AP使用时USB自动检测不请求权限也不打开设备`() = runBlocking {
        Fixture(apActive = true).use { f ->
            f.start()
            f.manager.checkExistingDevice()
            verify(exactly = 0) { f.usb.openDevice(any()) }
            verify(exactly = 0) { f.usb.requestPermission(any<UsbDevice>(), any<android.app.PendingIntent>()) }
        }
    }

    @Test
    fun `失败后的重连任务正常交接给新连接`() = runBlocking {
        Fixture().use { f ->
            every { f.usb.openDevice(f.device) } returnsMany listOf(null, f.connection)
            f.start()
            f.awaitConnected()
            verify(exactly = 2) { f.usb.openDevice(f.device) }
        }
    }

    @Test
    fun `手动断开取消失败后的退避重连`() = runBlocking {
        Fixture().use { f ->
            every { f.usb.openDevice(f.device) } returns null
            f.start()
            withTimeout(3000) {
                while (f.job("reconnectJob") == null) delay(10)
            }
            val retry = f.job("reconnectJob")!!
            f.manager.disconnect()
            retry.join()
            assertTrue(retry.isCancelled)
            assertEquals(UsbConnectionState.DISCONNECTED, f.manager.usbState.value)
            verify(exactly = 1) { f.usb.openDevice(f.device) }
        }
    }

    private class Fixture(grace: Boolean = false, apActive: Boolean = false) : AutoCloseable {
        val context = mockk<Context>(relaxed = true)
        val usb = mockk<UsbManager>()
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val openEntered = CountDownLatch(1)
        val openRelease = CountDownLatch(1)
        @Volatile var closed = false
        private var tx = 0
        val manager: UsbPtpManager

        init {
            every { context.getSystemService(Context.USB_SERVICE) } returns usb
            every { device.deviceName } returns "/dev/bus/usb/camera"
            every { device.vendorId } returns 0x04b0
            every { device.productId } returns 0x0455
            every { device.serialNumber } returns null
            every { device.configurationCount } returns 1
            every { device.interfaceCount } returns 1
            val intf = mockk<UsbInterface>()
            val input = mockk<UsbEndpoint>()
            val output = mockk<UsbEndpoint>()
            every { device.getInterface(0) } returns intf
            every { intf.interfaceClass } returns 6
            every { intf.interfaceSubclass } returns 1
            every { intf.interfaceProtocol } returns 1
            every { intf.endpointCount } returns 2
            every { intf.getEndpoint(0) } returns input
            every { intf.getEndpoint(1) } returns output
            every { input.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
            every { input.direction } returns UsbConstants.USB_DIR_IN
            every { output.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
            every { output.direction } returns UsbConstants.USB_DIR_OUT
            every { usb.deviceList } returns hashMapOf(device.deviceName to device)
            every { usb.hasPermission(device) } returns true
            every { usb.openDevice(device) } returns connection
            every { connection.claimInterface(intf, true) } returns true
            every { connection.close() } answers { closed = true }
            every { connection.bulkTransfer(output, any(), any(), any()) } answers {
                tx = UsbPtpProtocol.parseContainerHeader(secondArg())!!.transactionId
                thirdArg()
            }
            every { connection.bulkTransfer(input, any(), any(), any()) } answers {
                val response = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(12).putShort(3).putShort(0x2001).putInt(tx).array()
                response.copyInto(secondArg())
                response.size
            }
            val flags = mockk<ConnFlags>()
            every { flags.isEnabled(any()) } returns false
            every { flags.isEnabled(ConnFlags.USB_GRACE) } returns grace
            val ap = mockk<ApConnectionService>()
            every { ap.engaged } returns apActive
            manager = UsbPtpManager(context, mockk<AppEventLogger>(relaxed = true), flags, ap)
        }

        fun start() = manager.start(scope)
        fun blockOpen() {
            every { usb.openDevice(device) } answers {
                openEntered.countDown()
                check(openRelease.await(4, TimeUnit.SECONDS))
                connection
            }
        }
        suspend fun awaitConnected() {
            withTimeout(4000) {
                while (!manager.isConnected()) delay(10)
            }
        }
        fun connectJob(): Job? = job("connectJob")
        fun job(name: String): Job? = field(name) as? Job
        private fun field(name: String): Any? =
            UsbPtpManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)
        fun detach(detached: UsbDevice) {
            val intent = mockk<Intent>()
            every { intent.action } returns UsbManager.ACTION_USB_DEVICE_DETACHED
            every { intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) } returns detached
            (field("usbReceiver") as BroadcastReceiver).onReceive(context, intent)
        }
        override fun close() {
            openRelease.countDown()
            manager.stop()
            scope.cancel()
        }
    }
}

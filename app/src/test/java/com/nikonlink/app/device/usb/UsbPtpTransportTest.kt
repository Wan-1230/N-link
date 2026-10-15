package com.nikonlink.app.device.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class UsbPtpTransportTest {
    @Test
    fun `标准 PTP 接口被 claim 后由 transport 统一释放`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        val device = mockk<UsbDevice>()
        val intf = interfaceMock(6, 1, 1)
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns intf
        every { connection.claimInterface(intf, true) } returns true

        val transport = UsbPtpTransport.open(connection, device)
        assertSame(connection, transport.connection)
        assertSame(intf, transport.usbInterface)
        transport.close()

        verify(exactly = 1) { connection.releaseInterface(intf) }
        verify(exactly = 1) { connection.close() }
        transport.close()
        verify(exactly = 1) { connection.releaseInterface(intf) }
    }

    @Test
    fun `class 描述不完整时回退到含双向 bulk 的接口`() {
        val connection = mockk<UsbDeviceConnection>(relaxed = true)
        val device = mockk<UsbDevice>()
        val intf = interfaceMock(0xFF, 0, 0)
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns intf
        every { connection.claimInterface(intf, true) } returns true

        val transport = UsbPtpTransport.open(connection, device)
        assertSame(intf, transport.usbInterface)
        transport.close()
    }

    private fun interfaceMock(clazz: Int, subclass: Int, protocol: Int): UsbInterface {
        val intf = mockk<UsbInterface>()
        val input = mockk<UsbEndpoint>()
        val output = mockk<UsbEndpoint>()
        every { intf.interfaceClass } returns clazz
        every { intf.interfaceSubclass } returns subclass
        every { intf.interfaceProtocol } returns protocol
        every { intf.endpointCount } returns 2
        every { intf.getEndpoint(0) } returns input
        every { intf.getEndpoint(1) } returns output
        every { input.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
        every { input.direction } returns UsbConstants.USB_DIR_IN
        every { output.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
        every { output.direction } returns UsbConstants.USB_DIR_OUT
        return intf
    }
}

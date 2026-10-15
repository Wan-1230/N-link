package com.nikonlink.app.device.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class UsbPtpProtocolTest {
    @Test
    fun `增量读取时仅校验头部长度范围`() {
        val header = ByteBuffer.allocate(UsbPtpProtocol.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(100)
            .putShort(UsbPtpProtocol.TYPE_DATA.toShort())
            .putShort(0x1001)
            .putInt(7)
            .array()

        assertEquals(100, UsbPtpProtocol.parseContainerHeader(header)?.length)
        assertNull(UsbPtpProtocol.parseContainerHeader(header.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 7)
        }))
    }

    @Test
    fun `完整响应和数据容器必须满足声明长度`() {
        val truncated = ByteBuffer.allocate(UsbPtpProtocol.HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(20)
            .putShort(UsbPtpProtocol.TYPE_RESPONSE.toShort())
            .putShort(0x2001)
            .putInt(1)
            .array()

        assertNull(UsbPtpProtocol.parseResponseContainer(truncated))
        assertNull(UsbPtpProtocol.parseDataContainer(truncated))
    }
}

package com.nikonlink.app.device.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.shared.common.AppEventLogger
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UsbPtpTransactionTest {
    private val connection = mockk<UsbDeviceConnection>(relaxed = true)
    private val input = mockk<UsbEndpoint>()
    private val output = mockk<UsbEndpoint>()
    private val writes = mutableListOf<ByteArray>()
    private val reads = ArrayDeque<ByteArray>()
    private var readCount = 0
    private val manager: UsbPtpManager

    init {
        val context = mockk<Context>()
        every { context.getSystemService(Context.USB_SERVICE) } returns mockk<UsbManager>()
        val flags = mockk<ConnFlags>()
        every { flags.isEnabled(any()) } returns true
        manager = UsbPtpManager(context, mockk<AppEventLogger>(relaxed = true), flags, mockk(relaxed = true))
        setField("usbConnection", connection)
        setField("bulkIn", input)
        setField("bulkOut", output)
        every { connection.bulkTransfer(output, any(), any(), any()) } answers {
            val bytes = secondArg<ByteArray>().copyOf(thirdArg())
            writes.add(bytes)
            bytes.size
        }
        every { connection.bulkTransfer(output, any(), any(), any(), any()) } answers {
            val buffer = secondArg<ByteArray>()
            val offset = thirdArg<Int>()
            val length = arg<Int>(3)
            writes.add(buffer.copyOfRange(offset, offset + length))
            length
        }
        every { connection.bulkTransfer(input, any(), any(), any()) } answers {
            readCount++
            if (reads.isEmpty()) -1 else {
                val bytes = reads.removeFirst()
                bytes.copyInto(secondArg())
                bytes.size
            }
        }
    }

    @Test
    fun `发送数据容器保留操作码和事务号`() = runBlocking {
        reads.add(container(UsbPtpProtocol.TYPE_RESPONSE, PtpConstants.RESPONSE_OK, 1))
        assertTrue(manager.setDevicePropValue(0x5007, byteArrayOf(8, 0)))
        val command = UsbPtpProtocol.parseContainerHeader(writes[0])!!
        val data = UsbPtpProtocol.parseContainerHeader(writes[1])!!
        assertEquals(command.code, data.code)
        assertEquals(command.transactionId, data.transactionId)
    }

    @Test
    fun `大数据出阶段按十六KiB分块写出`() = runBlocking {
        reads.add(container(3, PtpConstants.RESPONSE_OK, 1))
        assertTrue(manager.setDevicePropValue(0x5007, ByteArray(40 * 1024) { 3 }))
        assertEquals(4, writes.size)
        assertEquals(12 + 40 * 1024, writes.drop(1).sumOf { it.size })
        assertTrue(writes.drop(1).all { it.size <= 16 * 1024 })
    }

    @Test
    fun `打开会话事务号为零随后从一递增`() = runBlocking {
        reads.add(container(3, PtpConstants.RESPONSE_OK, 0))
        reads.add(container(3, PtpConstants.RESPONSE_OK, 1))
        assertTrue(manager.sendCommand(PtpConstants.OP_OPEN_SESSION, listOf(1))!!.isOk)
        assertTrue(manager.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)!!.isOk)
        assertEquals(listOf(0, 1), writes.map { UsbPtpProtocol.parseContainerHeader(it)!!.transactionId })
    }

    @Test
    fun `拒绝响应不能作为空数据成功返回`() = runBlocking {
        reads.add(container(3, PtpConstants.RESPONSE_ACCESS_DENIED, 1))
        assertNull(manager.sendCommandWithData(PtpConstants.OP_GET_DEVICE_INFO, allowRetry = false))
    }

    @Test
    fun `分段数据事务号错误时不能写入目标`() = runBlocking {
        val data = container(2, PtpConstants.OP_GET_OBJECT, 99, ByteArray(40) { 7 })
        reads.add(data.copyOfRange(0, 20))
        reads.add(data.copyOfRange(20, data.size))
        reads.add(container(3, PtpConstants.RESPONSE_OK, 1))
        val sink = ByteArrayOutputStream()
        assertNull(manager.sendCommandWithData(PtpConstants.OP_GET_OBJECT, sink = sink))
        assertEquals(0, sink.size())
        assertEquals(1, writes.size)
    }

    @Test
    fun `流式输出部分成功后失败不能自动重放`() = runBlocking {
        val data = container(2, PtpConstants.OP_GET_OBJECT, 1, ByteArray(40) { 7 })
        reads.add(data.copyOfRange(0, 20))
        val sink = ByteArrayOutputStream()
        assertNull(manager.sendCommandWithData(PtpConstants.OP_GET_OBJECT, sink = sink))
        assertEquals(8, sink.size())
        assertEquals(1, writes.size)
    }

    @Test
    fun `流式传输取消后不再读取或写入剩余数据`() = runBlocking {
        val data = container(2, PtpConstants.OP_GET_OBJECT, 1, ByteArray(100) { 7 })
        reads.add(data.copyOfRange(0, 20))
        reads.add(data.copyOfRange(20, data.size) + container(3, PtpConstants.RESPONSE_OK, 1))
        val sink = ByteArrayOutputStream()
        val job = launch {
            val transferContext = currentCoroutineContext()
            manager.sendCommandWithData(
                PtpConstants.OP_GET_OBJECT,
                sink = sink,
                onProgress = { _, _ -> transferContext.cancel() }
            )
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(8, sink.size())
        assertEquals(1, readCount)
        assertEquals(1, writes.size)
    }

    @Test
    fun `连续零长度读取必须有界退出`() = runBlocking {
        repeat(100) { reads.add(ByteArray(0)) }
        assertNull(manager.sendCommandWithData(PtpConstants.OP_GET_DEVICE_INFO, allowRetry = false))
        assertTrue(readCount <= 65)
    }

    @Test
    fun `分段数据及粘连响应可完整重组`() = runBlocking {
        val payload = ByteArray(100) { it.toByte() }
        val data = container(2, PtpConstants.OP_GET_OBJECT, 1, payload)
        reads.add(data.copyOfRange(0, 5))
        reads.add(data.copyOfRange(5, 30))
        reads.add(data.copyOfRange(30, data.size) + container(3, PtpConstants.RESPONSE_OK, 1))
        assertArrayEquals(payload, manager.sendCommandWithData(PtpConstants.OP_GET_OBJECT))
    }

    private fun setField(name: String, value: Any) {
        UsbPtpManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    private fun container(type: Int, code: Int, tx: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + payload.size).putShort(type.toShort()).putShort(code.toShort())
            .putInt(tx).put(payload).array()
}

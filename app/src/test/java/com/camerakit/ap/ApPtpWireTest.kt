package com.camerakit.ap

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ApPtpWireTest {
    private fun array16(vararg values: Int) = le(values.size) + values.flatMap { listOf(it.toByte(), (it ushr 8).toByte()) }.toByteArray()
    private fun le(value: Int) = ByteArray(4).also { ApPtpWire.put32(it, 0, value) }
    private fun packet(type: Int, p: ByteArray) = le(p.size + 8) + le(type) + p
    private fun response(tid: Int) = packet(7, byteArrayOf(1, 0x20) + le(tid))
    private fun start(tid: Int, size: Int) = packet(9, le(tid) + le(size) + le(0))
    private fun receive(wire: ByteArray, tid: Int = 7): ByteArray {
        val sink = ByteArrayOutputStream()
        val result = ApPtpWire.receive(DataInputStream(ByteArrayInputStream(wire)), tid, sink)
        assertEquals(0x2001, result.code)
        return sink.toByteArray()
    }

    @Test fun singleEndDataContainsWholeHandleList() {
        val handles = le(2) + le(0x10) + le(0x80000001.toInt())
        val data = receive(start(7, handles.size) + packet(12, le(7) + handles) + response(7))
        assertEquals(listOf(16L, 0x80000001L), ApPtpWire.array32(data))
    }
    @Test fun multiPacketDownloadRemovesTransactionIdsAndKeepsFinalBytes() {
        val data = ByteArray(190000) { (it % 251).toByte() }
        val wire = start(7, data.size) + packet(10, le(7) + data.copyOfRange(0, 131000)) +
            packet(12, le(7) + data.copyOfRange(131000, data.size)) + response(7)
        assertArrayEquals(data, receive(wire))
    }
    @Test(expected = IOException::class) fun truncatedTransferCannotReportSuccess() {
        receive(start(7, 20) + packet(12, le(7) + byteArrayOf(1, 2)) + response(7))
    }
    @Test(expected = IOException::class) fun mismatchedTransactionRejected() {
        receive(start(7, 2) + packet(12, le(8) + byteArrayOf(1, 2)) + response(7))
    }
    @Test(expected = IOException::class) fun malformedArrayRejected() {
        ApPtpWire.array32(le(999) + le(1))
    }
    @Test fun zeroLengthDataAndResponseOnlyOperations() {
        assertArrayEquals(byteArrayOf(), receive(start(7, 0) + packet(12, le(7)) + response(7)))
        assertArrayEquals(byteArrayOf(), receive(response(7)))
    }
    @Test fun extractsJpegPastNikonMetadataHeader() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        assertArrayEquals(jpeg, ApPtpWire.jpeg(ByteArray(384) + jpeg + ByteArray(12)))
        assertNull(ApPtpWire.jpeg(ByteArray(512)))
    }
    @Test fun readsImageFormatsAfterOtherDeviceInfoArrays() {
        val info = ByteArray(8) + byteArrayOf(0) + byteArrayOf(0, 0) +
            array16(0x1001, 0x1007) + array16(0x4006) + array16(0x500D, 0x500F) +
            array16(0x3801, 0x3802) + array16(0x3001, 0x300D, 0x3801)
        assertEquals(setOf(0x1001, 0x1007), ApPtpWire.operations(info))
        assertEquals(setOf(0x3801, 0x3802, 0x3001, 0x300D), ApPtpWire.imageFormats(info))
    }

    // ── 取帧专用接收器（2026-10-19 AP 监看零拷贝）────────────────────────

    private fun receiveFrame(wire: ByteArray, tid: Int = 7): ApPtpWire.FrameReply =
        ApPtpWire.receiveFrame(DataInputStream(ByteArrayInputStream(wire)), tid)

    @Test fun frameReceiverReturnsExactSizeWithoutExtraCopy() {
        val data = ByteArray(190000) { (it % 251).toByte() }
        val wire = start(7, data.size) + packet(10, le(7) + data.copyOfRange(0, 131000)) +
            packet(12, le(7) + data.copyOfRange(131000, data.size)) + response(7)
        val reply = receiveFrame(wire)
        assertEquals(0x2001, reply.code)
        assertArrayEquals(data, reply.data)
        // 零拷贝的前提：交出的是「正好声明长度」的那块数组，没有按 64KB 翻倍留下的尾巴
        assertEquals(data.size, reply.data!!.size)
    }

    @Test fun frameReceiverHandlesSinglePacketAndUnknownLength() {
        val data = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        assertArrayEquals(data, receiveFrame(start(7, data.size) + packet(12, le(7) + data) + response(7)).data)
        // 声明长度 -1（未知）：不预分配，退回按需扩容，仍要拿到完整数据
        val unknownLength = packet(9, le(7) + ByteArray(8) { 0xff.toByte() })
        assertArrayEquals(data, receiveFrame(unknownLength + packet(12, le(7) + data) + response(7)).data)
    }

    @Test fun frameReceiverReportsNoDataForBusyResponse() {
        val reply = receiveFrame(response(7))
        assertEquals(0x2001, reply.code)
        assertNull(reply.data)
    }

    @Test(expected = IOException::class) fun frameReceiverRejectsTruncatedData() {
        receiveFrame(start(7, 20) + packet(12, le(7) + byteArrayOf(1, 2)) + response(7))
    }

    @Test(expected = IOException::class) fun frameReceiverRejectsForeignTransaction() {
        receiveFrame(start(7, 2) + packet(12, le(8) + byteArrayOf(1, 2)) + response(7))
    }
}

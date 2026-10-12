package com.camerakit.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB PTP 容器编解码（12 字节头，小端）：
 * `length u32`（含头全长）+ `type u16`（1 Command / 2 Data / 3 Response / 4 Event）
 * + `code u16`（opcode 或响应/事件码）+ `transactionId u32`。
 *
 * 参考：开源 PTP 协议实现 ptp-pack.c、android-file-transfer-linux、AOSP MtpPacket。
 */
object PtpUsbCodec {

    const val HEADER_SIZE = 12

    data class Container(val type: Int, val code: Int, val transId: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Container && other.type == type && other.code == code &&
                other.transId == transId && other.payload.contentEquals(payload)

        override fun hashCode(): Int = (type * 31 + code) * 31 + transId
    }

    fun pack(type: Int, code: Int, transId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(HEADER_SIZE + payload.size)
        buf.putShort(type.toShort())
        buf.putShort(code.toShort())
        buf.putInt(transId)
        buf.put(payload)
        return buf.array()
    }

    data class Header(val length: Int, val type: Int, val code: Int, val transId: Int)

    fun parseHeader(header: ByteArray): Header {
        require(header.size >= HEADER_SIZE) { "container header too short" }
        val length = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val type = ByteBuffer.wrap(header, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val code = ByteBuffer.wrap(header, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val transId = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < HEADER_SIZE || length > PtpConsts.MAX_PACKET_BYTES) {
            throw PtpProtocolException("USB 容器长度异常：$length")
        }
        return Header(length, type, code, transId)
    }

    /** 解析完整容器字节流（含 12 字节头）。 */
    fun parse(bytes: ByteArray): Container {
        require(bytes.size >= HEADER_SIZE) { "container too short" }
        val length = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(bytes.size >= length) { "container truncated: ${bytes.size} < $length" }
        val type = ByteBuffer.wrap(bytes, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val code = ByteBuffer.wrap(bytes, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val transId = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return Container(type, code, transId, bytes.copyOfRange(HEADER_SIZE, length))
    }

    /** 命令容器参数（Command 载荷 = n×u32 参数）。 */
    fun paramsOf(payload: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        var off = 0
        while (off + 4 <= payload.size) {
            out.add(ByteBuffer.wrap(payload, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
            off += 4
        }
        return out
    }

    fun paramsPayload(params: List<Long>): ByteArray {
        val buf = ByteBuffer.allocate(params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        params.forEach { buf.putInt(it.toInt()) }
        return buf.array()
    }
}

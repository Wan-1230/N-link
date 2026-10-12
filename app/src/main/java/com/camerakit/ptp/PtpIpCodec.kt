package com.camerakit.ptp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP/IP 包编解码（CIPA DC-X005 / gphoto ptpip 文档 / mmattes/ptpip / 基准实现 静态分析互证）。
 *
 * 包头 8 字节小端：`length u32`（含头全长，8..32MB）+ `type u32`。
 *
 * 关键载荷（载荷内偏移，已去包头）：
 * - InitCommand(type1)：GUID(16) + UTF-16LE 主机名(含 \0) + 协议版本 u32
 * - InitCommandAck(type2)：ConnectionID u32@0 + 相机 GUID 16B@4 + UTF-16LE 相机名@20
 * - InitFail(type5)：错误码 u32@0
 * - InitEvent(type3)：ConnectionID u32@0（第二条 TCP 连接，基准实现 实证）
 * - CmdRequest(type6)：dataOut u32@0 + opcode u16@4 + transId u32@6 + params n×u32@10
 * - CmdResponse(type7)：code u16@0 + transId u32@2 + params@6
 * - Event(type8)：code u16@0 + params@2
 * - StartData(type9)：transId u32@0 + 总长 u32@4
 * - Data(type10) / EndData(type12)：transId u32@0 + 数据@4
 * - Cancel(type11)：transId u32@0
 * - Ping(type13) / Pong(type14)
 */
object PtpIpCodec {

    const val HEADER_SIZE = 8

    data class Packet(val type: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Packet && other.type == type && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    data class InitCommandAck(val connectionId: Int, val cameraGuid: ByteArray, val cameraName: String) {
        override fun equals(other: Any?): Boolean =
            other is InitCommandAck && other.connectionId == connectionId &&
                other.cameraGuid.contentEquals(cameraGuid) && other.cameraName == cameraName

        override fun hashCode(): Int = connectionId
    }

    data class CmdResponse(val code: Int, val transId: Int, val params: List<Long>)

    // ---------------- 打包 ----------------

    fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size + HEADER_SIZE)
            .putInt(type)
            .array()
        out.write(header)
        out.write(payload)
        out.flush()
    }

    fun initCommandPayload(guid: ByteArray, hostName: String): ByteArray {
        require(guid.size == 16) { "GUID must be 16 bytes" }
        val nameBytes = utf16LeZ(hostName)
        val buf = ByteBuffer.allocate(16 + nameBytes.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(guid)
        buf.put(nameBytes)
        buf.putInt(PtpConsts.PTP_IP_PROTOCOL_VERSION)
        return buf.array()
    }

    fun initEventPayload(connectionId: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(connectionId).array()

    fun cmdRequestPayload(dataOut: Int, opcode: Int, transId: Int, params: List<Long>): ByteArray {
        val buf = ByteBuffer.allocate(10 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(dataOut)
        buf.putShort(opcode.toShort())
        buf.putInt(transId)
        params.forEach { buf.putInt(it.toInt()) }
        return buf.array()
    }

    fun cmdResponsePayload(code: Int, transId: Int, params: List<Long> = emptyList()): ByteArray {
        val buf = ByteBuffer.allocate(6 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(code.toShort())
        buf.putInt(transId)
        params.forEach { buf.putInt(it.toInt()) }
        return buf.array()
    }

    fun eventPayload(code: Int, params: List<Long> = emptyList()): ByteArray {
        val buf = ByteBuffer.allocate(2 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(code.toShort())
        params.forEach { buf.putInt(it.toInt()) }
        return buf.array()
    }

    fun dataPayload(transId: Int, data: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(transId)
        buf.put(data)
        return buf.array()
    }

    fun startDataPayload(transId: Int, totalLength: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(transId).putInt(totalLength.toInt()).array()

    fun cancelPayload(transId: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(transId).array()

    // ---------------- 解包 ----------------

    /** 读取一个完整包；长度越界（<8 或 >32MB）抛 [PtpProtocolException]。 */
    fun readPacket(input: InputStream): Packet {
        val header = readExact(input, HEADER_SIZE)
        val length = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val type = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (length < HEADER_SIZE || length > PtpConsts.MAX_PACKET_BYTES) {
            throw PtpProtocolException("PTP/IP 包长度异常：$length")
        }
        return Packet(type, readExact(input, length - HEADER_SIZE))
    }

    fun parseInitCommandAck(payload: ByteArray): InitCommandAck {
        require(payload.size >= 20) { "InitCommandAck payload too short: ${payload.size}" }
        val connId = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val guid = payload.copyOfRange(4, 20)
        val name = decodeUtf16Le(payload, 20)
        return InitCommandAck(connId, guid, name)
    }

    fun parseInitFail(payload: ByteArray): Int {
        require(payload.size >= 4) { "InitFail payload too short" }
        return ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun parseConnectionId(payload: ByteArray): Int {
        require(payload.size >= 4) { "connection id payload too short" }
        return ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun parseCmdResponse(payload: ByteArray): CmdResponse {
        require(payload.size >= 6) { "CmdResponse payload too short: ${payload.size}" }
        val code = ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val transId = ByteBuffer.wrap(payload, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val params = ArrayList<Long>()
        var off = 6
        while (off + 4 <= payload.size) {
            params.add(ByteBuffer.wrap(payload, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
            off += 4
        }
        return CmdResponse(code, transId, params)
    }

    fun parseEvent(payload: ByteArray): Pair<Int, List<Long>> {
        require(payload.size >= 2) { "Event payload too short" }
        val code = ByteBuffer.wrap(payload, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val params = ArrayList<Long>()
        var off = 2
        while (off + 4 <= payload.size) {
            params.add(ByteBuffer.wrap(payload, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
            off += 4
        }
        return code to params
    }

    /** Data/EndData/StartData/Cancel 的事务号：载荷 u32@0 */
    fun payloadTransId(payload: ByteArray): Int {
        require(payload.size >= 4) { "data payload missing transId" }
        return ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * StartData(type9) 载荷 = transId u32@0 + 数据总长 u32@4。
     * 注意：这不是数据段——误收集会使 data-in 多出 4 字节垃圾。
     */
    fun payloadStartDataLen(payload: ByteArray): Long {
        require(payload.size >= 8) { "StartData payload too short" }
        return ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    /** Data/EndData 载荷去掉事务号后的数据段 */
    fun payloadData(payload: ByteArray): ByteArray = payload.copyOfRange(4, payload.size)

    // ---------------- 工具 ----------------

    private fun utf16LeZ(s: String): ByteArray {
        val buf = ByteBuffer.allocate((s.length + 1) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (c in s) buf.putChar(c)
        buf.putChar(0.toChar())
        return buf.array()
    }

    private fun decodeUtf16Le(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        var off = offset
        while (off + 1 < bytes.size) {
            val ch = ((bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)).toChar()
            if (ch == 0.toChar()) break
            sb.append(ch)
            off += 2
        }
        return sb.toString()
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) throw EOFException("相机连接已断开")
            read += r
        }
        return buf
    }
}

/** 协议层异常（包格式/校验错误），区别于传输层 [com.camerakit.api.CameraError.TransportLost]。 */
class PtpProtocolException(message: String) : Exception(message)

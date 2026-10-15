package com.camerakit.ap

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream

/** PTP/IP framing. Data and EndData both carry a four-byte transaction ID. */
internal object ApPtpWire {
    fun u16(d: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > d.size) throw IOException("PTP 数据过短 (u16 @$o)")
        return (d[o].toInt() and 255) or ((d[o + 1].toInt() and 255) shl 8)
    }
    fun i32(d: ByteArray, o: Int): Int {
        if (o < 0 || o + 4 > d.size) throw IOException("PTP 数据过短 (u32 @$o)")
        return u16(d, o) or (u16(d, o + 2) shl 16)
    }
    fun u32(d: ByteArray, o: Int): Long = i32(d, o).toLong() and 0xffffffffL
    fun i64(d: ByteArray, o: Int): Long = u32(d, o) or (u32(d, o + 4) shl 32)
    fun put32(d: ByteArray, o: Int, v: Int) { repeat(4) { d[o + it] = (v ushr (it * 8)).toByte() } }
    fun array32(d: ByteArray): List<Long> {
        val count = u32(d, 0)
        if (count > (d.size - 4) / 4) throw IOException("PTP 数组不完整：$count 项 / ${d.size} 字节")
        return List(count.toInt()) { u32(d, 4 + it * 4) }
    }
    fun string(d: ByteArray, offset: Int): Pair<String, Int> {
        if (offset !in d.indices) throw IOException("PTP 字符串缺失")
        val count = d[offset].toInt() and 255
        val end = offset + 1 + count * 2
        if (end > d.size) throw IOException("PTP 字符串不完整")
        val value = if (count == 0) "" else String(d, offset + 1, count * 2, Charsets.UTF_16LE).trimEnd('\u0000')
        return value to end
    }
    fun operations(d: ByteArray): Set<Int> {
        // StandardVersion, VendorExtensionID, VendorExtensionVersion, description, FunctionalMode.
        val offset = string(d, 8).second + 2
        val count = u32(d, offset)
        if (count > (d.size - offset - 4) / 2) throw IOException("相机能力列表不完整")
        return (0 until count.toInt()).map { u16(d, offset + 4 + it * 2) }.toSet()
    }
    fun imageFormats(d: ByteArray): Set<Int> {
        var offset = string(d, 8).second + 2
        fun array(): Set<Int> {
            val count = u32(d, offset)
            if (count > (d.size - offset - 4) / 2) throw IOException("相机能力数组不完整")
            val values = (0 until count.toInt()).map { u16(d, offset + 4 + it * 2) }.toSet()
            offset += 4 + count.toInt() * 2
            return values
        }
        repeat(3) { array() } // operations, events, properties
        return array() + array() // capture and image formats
    }
    fun jpeg(d: ByteArray): ByteArray? {
        var start = -1
        for (i in 0 until d.size - 2) {
            if (d[i] == 0xff.toByte() && d[i + 1] == 0xd8.toByte() && d[i + 2] == 0xff.toByte()) { start = i; break }
        }
        if (start < 0) return null
        for (i in d.size - 2 downTo start + 2) {
            if (d[i] == 0xff.toByte() && d[i + 1] == 0xd9.toByte()) return d.copyOfRange(start, i + 2)
        }
        return null
    }
    data class Reply(val code: Int, val bytes: Long)

    /** Stream file bodies in bounded chunks; metadata uses the same receiver. */
    fun receive(input: DataInputStream, tid: Int, sink: OutputStream, maxBytes: Long = Long.MAX_VALUE,
                onProgress: (Long, Long) -> Unit = { _, _ -> }, pong: () -> Unit = {}): Reply {
        var expected: Long? = null
        var bytes = 0L
        var ended = false
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val h = ByteArray(8).also { input.readFully(it) }
            val len = u32(h, 0)
            val type = i32(h, 4)
            if (len < 8 || len > Int.MAX_VALUE) throw IOException("PTP 包长度错误：$len")
            val payloadSize = (len - 8).toInt()
            if (type == 10 || type == 12) {
                if (payloadSize < 4 || expected == null || ended) throw IOException("PTP 数据阶段顺序错误")
                val transaction = ByteArray(4).also { input.readFully(it) }
                if (i32(transaction, 0) != tid) throw IOException("PTP 事务不匹配")
                var left = payloadSize - 4
                if (bytes + left > maxBytes || (expected!! >= 0 && bytes + left > expected!!)) throw IOException("PTP 数据超过声明长度")
                while (left > 0) {
                    val n = minOf(left, buffer.size)
                    input.readFully(buffer, 0, n)
                    sink.write(buffer, 0, n)
                    left -= n
                    bytes += n
                    onProgress(bytes, expected!!)
                }
                if (type == 12) ended = true
                continue
            }
            if (payloadSize > 1024 * 1024) throw IOException("PTP 控制包过大")
            val p = ByteArray(payloadSize).also { input.readFully(it) }
            when (type) {
                9 -> {
                    if (p.size != 12 || expected != null || i32(p, 0) != tid) throw IOException("PTP StartData 错误")
                    expected = i64(p, 4)
                    if (expected!! < -1 || expected!! > maxBytes) throw IOException("PTP 数据长度超限：$expected")
                }
                7 -> {
                    if (p.size < 6 || i32(p, 2) != tid) throw IOException("PTP 响应事务不匹配")
                    val code = u16(p, 0)
                    if (code == 0x2001 && expected != null && (!ended || (expected!! >= 0 && bytes != expected))) {
                        throw IOException("PTP 数据不完整：$bytes / $expected 字节")
                    }
                    return Reply(code, bytes)
                }
                8 -> Unit
                13 -> pong()
                14 -> Unit
                else -> throw IOException("意外的 PTP 包类型：$type")
            }
        }
    }
}

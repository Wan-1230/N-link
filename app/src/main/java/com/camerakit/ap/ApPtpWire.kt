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

    /** 取帧专用回执：数据阶段已经是一块**正好大小**的数组（不裁剪、不二次拷贝）。 */
    data class FrameReply(val code: Int, val data: ByteArray?)

    /**
     * 取帧专用接收器（0x9203）：数据阶段**一次分配、直接读入最终数组**。
     *
     * 与 [receive] 的区别只在内存路径：
     * - [receive] 写进调用方的 `ByteArrayOutputStream`（默认 32 字节起，边写边翻倍扩容，
     *   一帧数百 KB 累计要多拷约 2 倍帧长的数据），调用方拿到后通常还要再扫一遍找
     *   JPEG 头尾并 `copyOfRange` 拷一份；
     * - 这里用 StartData 声明的长度一次 `ByteArray(total)` 到位，`readFully` 直接落进最终数组；
     *   返回时若 `size == 容量`（正常路径）直接把这块数组交出去，**零附加拷贝**。
     *
     * 协议语义与 [receive] 完全一致（包序、事务号、长度校验、Ping/Pong 全同），
     * 只是少了中间缓冲。声明长度未知（-1/0）或数据超长时退回按需扩容。
     */
    fun receiveFrame(
        input: DataInputStream,
        tid: Int,
        maxBytes: Long = Long.MAX_VALUE,
        pong: () -> Unit = {},
    ): FrameReply {
        var expected: Long? = null
        var buffer: ByteArray? = null
        var size = 0
        var bytes = 0L
        var ended = false

        fun ensure(needed: Int): ByteArray {
            val cur = buffer
            if (cur != null && cur.size >= needed) return cur
            var cap = cur?.size ?: 64 * 1024
            while (cap < needed) cap = if (cap > Int.MAX_VALUE / 2) needed else cap * 2
            val next = ByteArray(cap)
            if (cur != null && size > 0) cur.copyInto(next, 0, 0, size)
            buffer = next
            return next
        }

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
                val declared = expected!!
                if (bytes + left > maxBytes ||
                    (declared >= 0 && bytes + left > declared)
                ) throw IOException("PTP 数据超过声明长度")
                if (left > 0) {
                    val target = ensure(size + left)
                    while (left > 0) {
                        val n = minOf(left, target.size - size)
                        input.readFully(target, size, n)
                        size += n
                        left -= n
                        bytes += n
                    }
                }
                if (type == 12) ended = true
                continue
            }

            if (payloadSize > 1024 * 1024) throw IOException("PTP 控制包过大")
            val p = ByteArray(payloadSize).also { input.readFully(it) }
            when (type) {
                9 -> {
                    if (p.size != 12 || expected != null || i32(p, 0) != tid) {
                        throw IOException("PTP StartData 错误")
                    }
                    val total = i64(p, 4)
                    if (total < -1 || total > maxBytes) throw IOException("PTP 数据长度超限：$total")
                    expected = total
                    // 声明长度可信 → 一次分配到位，正常路径下整个数据阶段只写这一块内存。
                    // 超过 Int 上限的声明（含 -1 之外的异常值）不预分配，退回按需扩容。
                    if (total in 1..maxBytes && total <= Int.MAX_VALUE) {
                        buffer = ByteArray(total.toInt())
                    }
                }
                7 -> {
                    if (p.size < 6 || i32(p, 2) != tid) throw IOException("PTP 响应事务不匹配")
                    val code = u16(p, 0)
                    if (code == 0x2001 && expected != null &&
                        (!ended || (expected!! >= 0 && bytes != expected))
                    ) {
                        throw IOException("PTP 数据不完整：$bytes / $expected 字节")
                    }
                    val b = buffer
                    val data = when {
                        b == null || size == 0 -> null
                        size == b.size -> b          // 正常路径：直接交出这块数组，零附加拷贝
                        else -> b.copyOf(size)
                    }
                    return FrameReply(code, data)
                }
                8 -> Unit
                13 -> pong()
                14 -> Unit
                else -> throw IOException("意外的 PTP 包类型：$type")
            }
        }
    }

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

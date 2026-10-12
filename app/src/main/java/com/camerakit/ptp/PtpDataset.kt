package com.camerakit.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP 数据集（Dataset）编解码：全部小端。
 *
 * PTP 字符串编码：`u8 字符数（含结尾 \0）+ UTF-16LE 字符`。
 * 数组编码：`u32 元素个数 + 元素`。
 */
class PtpDatasetReader(private val buf: ByteArray) {
    private var pos = 0

    val remaining: Int get() = buf.size - pos

    fun u8(): Int {
        require(remaining >= 1) { "dataset underflow u8" }
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        require(remaining >= 2) { "dataset underflow u16" }
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        require(remaining >= 4) { "dataset underflow u32" }
        val v = ByteBuffer.wrap(buf, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        pos += 4
        return v
    }

    fun u64(): Long {
        require(remaining >= 8) { "dataset underflow u64" }
        val v = ByteBuffer.wrap(buf, pos, 8).order(ByteOrder.LITTLE_ENDIAN).long
        pos += 8
        return v
    }

    /** PTP string：u8 字符数（含 \0）+ UTF-16LE */
    fun string(): String {
        val count = u8()
        if (count == 0) return ""
        require(remaining >= count * 2) { "dataset underflow string" }
        val sb = StringBuilder(count)
        repeat(count) {
            val ch = u16()
            if (ch != 0) sb.append(ch.toChar())
        }
        return sb.toString()
    }

    fun u16Array(): List<Int> {
        val n = u32().toInt()
        require(n >= 0 && remaining >= n * 2) { "dataset underflow u16 array" }
        return List(n) { u16() }
    }

    fun u32Array(): List<Long> {
        val n = u32().toInt()
        require(n >= 0 && remaining >= n * 4) { "dataset underflow u32 array" }
        return List(n) { u32() }
    }
}

class PtpDatasetWriter {
    private val out = ArrayList<Byte>()

    fun u8(v: Int): PtpDatasetWriter { out.add(v.toByte()); return this }

    fun u16(v: Int): PtpDatasetWriter {
        out.add((v and 0xFF).toByte())
        out.add(((v ushr 8) and 0xFF).toByte())
        return this
    }

    fun u32(v: Long): PtpDatasetWriter {
        repeat(4) { out.add(((v ushr (8 * it)) and 0xFF).toByte()) }
        return this
    }

    fun u64(v: Long): PtpDatasetWriter {
        repeat(8) { out.add(((v ushr (8 * it)) and 0xFF).toByte()) }
        return this
    }

    /** PTP string：u8 字符数（含 \0）+ UTF-16LE */
    fun string(s: String): PtpDatasetWriter {
        u8(s.length + 1)
        for (c in s) u16(c.code)
        u16(0)
        return this
    }

    fun u16Array(items: List<Int>): PtpDatasetWriter {
        u32(items.size.toLong())
        items.forEach { u16(it) }
        return this
    }

    fun u32Array(items: List<Long>): PtpDatasetWriter {
        u32(items.size.toLong())
        items.forEach { u32(it) }
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

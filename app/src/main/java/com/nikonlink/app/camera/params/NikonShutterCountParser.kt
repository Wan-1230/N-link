package com.nikonlink.app.camera.params

import java.io.File
import java.nio.ByteBuffer

/**
 * 尼康快门次数本地解析器（PRD 2.4 / 设备页「快门次数」可行性验证的产物）。
 *
 * 可行性结论（调研 libgphoto2 ptp.h / digiCamControl）：
 * - 尼康**不提供**快门计数的 PTP 属性（0xD100 是厂商快门速度，0xD1AC 是佳能的计数）；
 * - 业界标准做法是从照片 EXIF 的 **MakerNotes 0x00A7 (ShutterCount)** 解析；
 * - 本地解析失败（旧机型加密 MakerNote / 结构变体）时回退 Digeeker 云端解析。
 *
 * 解析路径（JPEG 为主，采样文件由查询链路挑选最小的 JPEG）：
 * 1. 定位 APP1 "Exif\0\0" 段 → TIFF 头（II/MM）；
 * 2. IFD0 → tag 0x927C MakerNote；
 * 3. MakerNote 头部两种形态：
 *    a. JPEG 常见 "Nikon\0" + 版本字节 + IFD（无内嵌 TIFF 头，字节序沿用主 TIFF 头）；
 *    b. NEF / 部分变体含内嵌 TIFF 头（"II*\0"/"MM\0*"），偏移以该头为基准；
 *    现实中两种形态的基准偏移存在流派差异，这里对 a 形态做双基准（0 / IFD 起点）
 *    启发式并校验取值合理性，两路都失败即返回 null（交由云端兜底）。
 */
object NikonShutterCountParser {

    private const val TAG_MAKER_NOTE = 0x927C
    private const val TAG_SHUTTER_COUNT = 0x00A7

    /** 快门次数合理上限：1 ~ 5000 万（防误读其它 LONG 字段） */
    private const val MAX_PLAUSIBLE_COUNT = 50_000_000

    /** 从 JPEG/NEF 文件解析快门次数；失败返回 null（不抛异常） */
    fun parseFile(file: File): Int? = runCatching {
        val bytes = file.readBytes()
        parse(bytes)
    }.getOrNull()

    fun parse(bytes: ByteArray): Int? {
        if (bytes.size < 16) return null
        // NEF/TIFF：文件头即 II/MM；JPEG：先找 APP1 Exif 段
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() ||
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte()
        ) {
            return parseTiffContainer(bytes, tiffStart = 0)
        }
        val exifStart = locateExifApp1(bytes) ?: return null
        return parseTiffContainer(bytes, tiffStart = exifStart)
    }

    /** 定位 APP1 Exif 段中 TIFF 头的起始位置（"Exif\0\0" 之后） */
    private fun locateExifApp1(bytes: ByteArray): Int? {
        var i = 2
        while (i + 4 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }           // 填充字节
            if (marker == 0xD8) { i += 2; continue }        // SOI 已跳过，容错
            if (marker == 0xDA) return null                 // SOS 之前没有 Exif
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (marker == 0xE1) {
                val head = i + 4
                if (head + 6 <= bytes.size &&
                    bytes[head] == 'E'.code.toByte() && bytes[head + 1] == 'x'.code.toByte() &&
                    bytes[head + 2] == 'i'.code.toByte() && bytes[head + 3] == 'f'.code.toByte() &&
                    bytes[head + 4].toInt() == 0 && bytes[head + 5].toInt() == 0
                ) {
                    return head + 6
                }
            }
            i += 2 + segLen
        }
        return null
    }

    /** 解析 TIFF 容器：IFD0 → MakerNote → 尼康 0x00A7 */
    private fun parseTiffContainer(bytes: ByteArray, tiffStart: Int): Int? {
        if (tiffStart + 8 > bytes.size) return null
        val bigEndian = when {
            bytes[tiffStart] == 'I'.code.toByte() && bytes[tiffStart + 1] == 'I'.code.toByte() -> false
            bytes[tiffStart] == 'M'.code.toByte() && bytes[tiffStart + 1] == 'M'.code.toByte() -> true
            else -> return null
        }
        val buffer = ByteBuffer.wrap(bytes).order(
            if (bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN
        )
        val ifd0Offset = buffer.getInt(tiffStart + 4)
        val makerNote = readTagValueBytes(buffer, tiffStart, ifd0Offset, TAG_MAKER_NOTE)
            ?: return null
        return parseNikonMakerNote(makerNote, bigEndian)
    }

    /**
     * 在指定 IFD 中查找 tag 并返回其值字节（UNDEFINED/ASCII/LONG 等一律按原始字节返回）。
     * [ifdOffset] 相对 [tiffStart]。
     */
    private fun readTagValueBytes(
        buffer: ByteBuffer,
        tiffStart: Int,
        ifdOffset: Int,
        tag: Int
    ): ByteArray? {
        val ifdStart = tiffStart + ifdOffset
        if (ifdStart + 2 > buffer.limit()) return null
        val entryCount = buffer.getShort(ifdStart).toInt() and 0xFFFF
        if (entryCount <= 0 || entryCount > 512) return null
        for (i in 0 until entryCount) {
            val entry = ifdStart + 2 + i * 12
            if (entry + 12 > buffer.limit()) return null
            if ((buffer.getShort(entry).toInt() and 0xFFFF) != tag) continue
            val type = buffer.getShort(entry + 2).toInt() and 0xFFFF
            val count = buffer.getInt(entry + 4)
            if (count <= 0 || count > 0x100000) return null
            val typeSize = when (type) {
                1, 2, 6, 7 -> 1   // BYTE / ASCII / SBYTE / UNDEFINED
                3, 8 -> 2          // SHORT
                4, 9, 11 -> 4      // LONG / SLONG / FLOAT
                5, 10, 12 -> 8     // RATIONAL / SRATIONAL / DOUBLE
                else -> return null
            }
            val size = count * typeSize
            return if (size <= 4) {
                ByteArray(size).also { buffer.position(entry + 8); buffer.get(it, 0, size) }
            } else {
                // 偏移相对 TIFF 头起点
                val valueOffset = tiffStart + buffer.getInt(entry + 8)
                if (valueOffset < 0 || valueOffset + size > buffer.limit()) return null
                ByteArray(size).also { buffer.position(valueOffset); buffer.get(it, 0, size) }
            }
        }
        return null
    }

    /** 解析尼康 MakerNote：先试内嵌 TIFF 头形态，再试 v0100 无头形态（双基准） */
    private fun parseNikonMakerNote(maker: ByteArray, mainBigEndian: Boolean): Int? {
        if (maker.size < 12) return null
        val hasNikonHeader = maker[0] == 'N'.code.toByte() && maker[1] == 'i'.code.toByte() &&
            maker[2] == 'k'.code.toByte() && maker[3] == 'o'.code.toByte() &&
            maker[4] == 'n'.code.toByte() && maker[5].toInt() == 0
        if (!hasNikonHeader) return null

        // 形态 b：内嵌 TIFF 头（II*\0 / MM\0*），偏移以该头为基准
        for (p in 6 until minOf(16, maker.size - 8)) {
            val isII = maker[p] == 'I'.code.toByte() && maker[p + 1] == 'I'.code.toByte()
            val isMM = maker[p] == 'M'.code.toByte() && maker[p + 1] == 'M'.code.toByte()
            if (!isII && !isMM) continue
            val inner = ByteBuffer.wrap(maker).order(
                if (isII) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN
            )
            if (inner.getShort(p + 2).toInt() != 0x2A) continue
            val ifdOffset = inner.getInt(p + 4)
            // isII = 小端标识；readNikonTag 的形参是大端布尔，需取反
            return readNikonTag(inner, ifdOffset, p, bigEndian = !isII)
                ?.takeIf { isPlausible(it) }
        }

        // 形态 a（JPEG v0100）：无内嵌 TIFF 头，字节序沿用主 TIFF 头；双基准启发式
        val outer = ByteBuffer.wrap(maker).order(
            if (mainBigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN
        )
        // 基准一：IFD 紧随 8 字节头（"Nikon\0"+版本），偏移相对 IFD 起点
        readNikonTag(outer, ifdOffset = 0, base = 8, bigEndian = mainBigEndian)
            ?.let { if (isPlausible(it)) return it }
        // 基准二：偏移相对 MakerNote 起点（IFD 仍在 offset 8）
        readNikonTag(outer, ifdOffset = 8, base = 0, bigEndian = mainBigEndian)
            ?.let { if (isPlausible(it)) return it }
        return null
    }

    /** 在 MakerNote 的 IFD 中查 0x00A7；[base] = 该 IFD 内偏移量的基准位置 */
    private fun readNikonTag(
        buffer: ByteBuffer,
        ifdOffset: Int,
        base: Int,
        bigEndian: Boolean
    ): Int? {
        val order0 = if (bigEndian) java.nio.ByteOrder.BIG_ENDIAN else java.nio.ByteOrder.LITTLE_ENDIAN
        val buf = buffer.duplicate().order(order0)
        val ifdStart = base + ifdOffset
        if (ifdStart + 2 > buf.limit()) return null
        val entryCount = buf.getShort(ifdStart).toInt() and 0xFFFF
        if (entryCount <= 0 || entryCount > 512) return null
        for (i in 0 until entryCount) {
            val entry = ifdStart + 2 + i * 12
            if (entry + 12 > buf.limit()) return null
            if ((buf.getShort(entry).toInt() and 0xFFFF) != TAG_SHUTTER_COUNT) continue
            val type = buf.getShort(entry + 2).toInt() and 0xFFFF
            val count = buf.getInt(entry + 4)
            if (type !in intArrayOf(4, 3, 9) || count < 1) return null
            return when {
                // 单值且 ≤4 字节 → 内联
                count == 1 && type != 3 -> buf.getInt(entry + 8)
                count == 1 && type == 3 -> (buf.getShort(entry + 8).toInt() and 0xFFFF)
                else -> {
                    val valueOffset = base + buf.getInt(entry + 8)
                    if (valueOffset + 4 > buf.limit()) return null
                    buf.getInt(valueOffset)
                }
            }
        }
        return null
    }

    private fun isPlausible(count: Int?): Boolean =
        count != null && count in 1..MAX_PLAUSIBLE_COUNT
}

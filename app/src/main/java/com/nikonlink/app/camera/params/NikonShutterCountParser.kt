package com.nikonlink.app.camera.params

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 尼康快门次数本地解析器。
 *
 * 数据源唯一：照片 EXIF 的 MakerNote(`0x927C`) → 尼康 IFD → tag `0x00A7`。
 * 尼康机身**不提供**任何快门计数的 PTP 设备属性（对照 libgphoto2 `camlibs/ptp2/ptp.h`：
 * 佳能 `0xD1AC` / 奥林巴斯 `0xD059` / 富士 `0xD154` 都有，尼康没有），所以别去读属性。
 * `0x00A7` 本身也不加密 —— 它反倒是尼康用来解密 ShotInfo/ColorBalance/LensData 的密钥。
 *
 * MakerNote 的 IFD 位置在三种形态间不同，且**不能**由 `MakerNoteVersion`(`0x0001`) 推断
 * （版本号字符串只用于选 ColorBalance/ShotInfo 子表，与 `0x00A7` 的位置无关）。这里按形态提示
 * 排好候选 IFD 位置 × 字节序后逐个枚举，再用固件恒等式
 * `0x00A5 ImageCount + 0x00A6 DeletedImageCount == 0x00A7 ShutterCount` 反选真正对齐的那一个 ——
 * 比「数值落在合理区间」可靠得多。旧实现只试过 IFD@8/0、从未试现代 D/Z 的 **IFD@18**，
 * 于是本地路径恒失败、把云端兜底顶成了主路径。
 *
 * 真机样张（Exiv2 test/data 下的尼康 NEF/JPEG）实测推翻的两条假设，别再走回头路：
 * - `0x927C` 在 **Exif 子 IFD（`0x8769`）**，不在 IFD0 —— 只在 IFD0 找的话，所有真实照片一律失败；
 * - MakerNote 的字节序与主 TIFF 头**可以相反**：D70 / D2Hs 的笔记是大端而机身主 TIFF 是小端，
 *   Z 系列 NEF 则同为小端。所以两种字节序都得试、由恒等式裁决，不能假设「尼康一律小端」。
 *
 * 这几个 tag 都是 `count=1` 的 LONG/SHORT，值内联在 12 字节条目的 value 字段里，
 * 所以偏移基准（相对 MakerNote 还是相对主 TIFF 头）对读取结果没有影响，只有 IFD 位置和字节序要紧。
 */
object NikonShutterCountParser {

    private const val TAG_MAKER_NOTE = 0x927C
    private const val TAG_EXIF_IFD = 0x8769

    private const val TAG_MECHANICAL_SHUTTER_COUNT = 0x0037
    private const val TAG_IMAGE_COUNT = 0x00A5
    private const val TAG_DELETED_IMAGE_COUNT = 0x00A6
    private const val TAG_SHUTTER_COUNT = 0x00A7

    /** 上限 5000 万足以挡掉误读到的其它 LONG 字段，以及 `0xFFFFF7FF` 这类「n/a」哨兵（有符号视角为负） */
    private const val MAX_PLAUSIBLE_COUNT = 50_000_000

    /** MakerNote 紧贴文件开头，1MB 覆盖所有尼康机身；超出即视为样本截断，交由调用方做二次窗口读取 */
    private const val MAX_SCAN_BYTES = 1_000_000

    /**
     * 一次成功解析的结果。
     *
     * @param shutterCount 主读数，含机械与电子快门的触发次数
     * @param mechanicalCount 仅机械快门（`0x0037`），-1 = 机身未写
     * @param layout 命中的形态标签，用于诊断
     * @param verified 恒等式成立、读数可信；false = 仅数值合理，UI 需标注未校验
     */
    data class Reading(
        val shutterCount: Int,
        val mechanicalCount: Int = -1,
        val imageCount: Int = -1,
        val deletedImageCount: Int = -1,
        val layout: String,
        val verified: Boolean
    )

    /** 从文件解析。只读前 [MAX_SCAN_BYTES] 字节，避免把 45MB 的 NEF 整个塞进内存。 */
    fun parseFile(file: File): Reading? = runCatching {
        if (!file.isFile) return@runCatching null
        val span = minOf(file.length(), MAX_SCAN_BYTES.toLong()).toInt()
        if (span < 16) return@runCatching null
        val bytes = ByteArray(span)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        parse(bytes)
    }.getOrNull()

    /** 解析 JPEG / NEF(TIFF) 字节；失败返回 null，不抛异常 */
    fun parse(bytes: ByteArray): Reading? {
        if (bytes.size < 16) return null
        // NEF/TIFF 的文件头即 II/MM；JPEG 要先找 APP1 "Exif\0\0"
        if (isTiffHeader(bytes, 0)) return parseTiffContainer(bytes, tiffStart = 0)
        val exifStart = locateExifApp1(bytes) ?: return null
        return parseTiffContainer(bytes, tiffStart = exifStart)
    }

    // ---------- 容器与 IFD0 ----------

    private fun isTiffHeader(bytes: ByteArray, at: Int): Boolean =
        at + 2 <= bytes.size &&
            ((bytes[at] == 'I'.code.toByte() && bytes[at + 1] == 'I'.code.toByte()) ||
                (bytes[at] == 'M'.code.toByte() && bytes[at + 1] == 'M'.code.toByte()))

    /** 定位 APP1 Exif 段中 TIFF 头的起始位置（"Exif\0\0" 之后） */
    private fun locateExifApp1(bytes: ByteArray): Int? {
        var i = if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) 2 else 0
        while (i + 4 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF || marker == 0x01) { i++; continue }   // 填充字节
            if (marker == 0xD8) { i += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null          // 走到 EOI/SOS 也没碰上 Exif
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
            if (segLen < 2) return null
            i += 2 + segLen
        }
        return null
    }

    private fun parseTiffContainer(bytes: ByteArray, tiffStart: Int): Reading? {
        if (tiffStart + 8 > bytes.size) return null
        val bigEndian = bytes[tiffStart] == 'M'.code.toByte()
        val buffer = wrap(bytes, bigEndian)
        val ifd0Offset = buffer.getInt(tiffStart + 4)
        if (ifd0Offset < 0) return null
        // MakerNote 的规范位置是 **Exif 子 IFD（0x8769）**，不是 IFD0 —— 真机样张已证实。
        // 旧实现只在 IFD0 找 0x927C，光这一条就足以在所有真实照片上失败。IFD0 仅作宽松兜底。
        val maker = readTagLong(buffer, tiffStart, ifd0Offset, TAG_EXIF_IFD)?.let { sub ->
            readTagBytes(buffer, tiffStart, sub, TAG_MAKER_NOTE)
        } ?: readTagBytes(buffer, tiffStart, ifd0Offset, TAG_MAKER_NOTE)
        return maker?.let { parseNikonMakerNote(it) }
    }

    private fun readTagLong(
        buffer: ByteBuffer,
        tiffStart: Int,
        ifdOffset: Int,
        tag: Int
    ): Int? = readTagBytes(buffer, tiffStart, ifdOffset, tag)
        ?.takeIf { it.size == 4 }
        ?.let { wrap(it, buffer.order() == ByteOrder.BIG_ENDIAN).getInt(0) }

    /** 在指定 IFD 中查 tag 并返回其原始值字节（≤4 字节取内联，否则按主 TIFF 头基准定位） */
    private fun readTagBytes(
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
            val unit = typeSize(buffer.getShort(entry + 2).toInt() and 0xFFFF)
            val count = buffer.getInt(entry + 4)
            if (unit <= 0 || count <= 0 || count > MAX_IFD_ELEMENTS) return null
            val size = count * unit
            return if (size <= 4) {
                ByteArray(size).also { copyAt(buffer, entry + 8, it) }
            } else {
                val valueOffset = tiffStart + buffer.getInt(entry + 8)
                if (valueOffset < 0 || valueOffset + size > buffer.limit()) return null
                ByteArray(size).also { copyAt(buffer, valueOffset, it) }
            }
        }
        return null
    }

    // ---------- MakerNote：候选枚举 + 自校验 ----------

    /** 逐个候选 IFD 读 tag 组，恒等式成立即采纳；否则保留第一个「数值合理」的候选作为未校验结果 */
    private fun parseNikonMakerNote(maker: ByteArray): Reading? {
        var best: Reading? = null
        for (candidate in candidateIfds(maker)) {
            val tags = readNikonIfd(maker, candidate.ifdStart, candidate.bigEndian) ?: continue
            val shot = tags[TAG_SHUTTER_COUNT] ?: continue
            if (!isPlausible(shot)) continue
            val image = tags[TAG_IMAGE_COUNT]
            val deleted = tags[TAG_DELETED_IMAGE_COUNT]
            val reading = Reading(
                shutterCount = shot,
                mechanicalCount = tags[TAG_MECHANICAL_SHUTTER_COUNT]?.takeIf { isPlausible(it) } ?: -1,
                imageCount = image ?: -1,
                deletedImageCount = deleted ?: -1,
                layout = candidate.layout,
                verified = image != null && deleted != null &&
                    image.toLong() + deleted.toLong() == shot.toLong()
            )
            if (reading.verified) return reading
            if (best == null) best = reading
        }
        return best
    }

    /**
     * 候选 IFD 起点，按命中概率降序。形态判据只决定顺序、不决定成员集合 ——
     * 真正的裁决权在 [parseNikonMakerNote] 的恒等式校验上。
     */
    private fun candidateIfds(maker: ByteArray): List<CandidateIfd> {
        val out = LinkedHashSet<CandidateIfd>()

        // 内嵌 TIFF 头（II*\0 / MM\0*）：IFD = 头起点 + 头内记录的偏移，字节序由该头决定
        for (p in 6..minOf(14, maker.size - 10)) {
            if (!isTiffHeader(maker, p)) continue
            val inner = wrap(maker, maker[p] == 'M'.code.toByte())
            if (inner.getShort(p + 2).toInt() and 0xFFFF != 0x2A) continue
            val offset = inner.getInt(p + 4)
            if (offset in 0 until maker.size) {
                out += CandidateIfd("inner@$p", p + offset, maker[p] == 'M'.code.toByte())
            }
        }
        if (hasNikonSignature(maker)) {
            // 形态 A：现代 D/Z（"Nikon\0" + 0x02 + 版本串），IFD 固定在 +18
            if (maker.size > 6 && maker[6].toInt() == 0x02) addOrders(out, "v02xx", 18)
            // 形态 B：E 系列（0x01），IFD 在 +8
            if (maker.size > 6 && maker[6].toInt() == 0x01) addOrders(out, "v01xx", 8)
            addOrders(out, "sig", 0)
            addOrders(out, "sig", 8)
        }
        // 形态 C（D1、E99x、部分 Coolpix 无 "Nikon\0" 签名，D1 为大端）与机身变体兜底
        addOrders(out, "flat", 0)
        addOrders(out, "any", 18)
        addOrders(out, "any", 8)
        return out.toList()
    }

    private fun hasNikonSignature(maker: ByteArray): Boolean =
        maker.size >= 6 && maker[0] == 'N'.code.toByte() && maker[1] == 'i'.code.toByte() &&
            maker[2] == 'k'.code.toByte() && maker[3] == 'o'.code.toByte() &&
            maker[4] == 'n'.code.toByte() && maker[5].toInt() == 0

    private data class CandidateIfd(val layout: String, val ifdStart: Int, val bigEndian: Boolean)

    /** 同一位置两种字节序都试（笔记的字节序与主 TIFF 头无关，见类注释），LE 先试 */
    private fun addOrders(into: LinkedHashSet<CandidateIfd>, label: String, at: Int) {
        into += CandidateIfd("$label/le", at, false)
        into += CandidateIfd("$label/be", at, true)
    }

    /** 读 MakerNote 内某个 IFD 关注的 tag；结构不合法返回 null，以便继续试下一个候选 */
    private fun readNikonIfd(maker: ByteArray, ifdStart: Int, bigEndian: Boolean): Map<Int, Int>? {
        if (ifdStart < 0 || ifdStart + 2 > maker.size) return null
        val buffer = wrap(maker, bigEndian)
        val entryCount = buffer.getShort(ifdStart).toInt() and 0xFFFF
        if (entryCount <= 0 || entryCount > 512) return null
        val out = HashMap<Int, Int>(4)
        for (i in 0 until entryCount) {
            val entry = ifdStart + 2 + i * 12
            if (entry + 12 > maker.size) return null
            val tag = buffer.getShort(entry).toInt() and 0xFFFF
            if (tag != TAG_SHUTTER_COUNT && tag != TAG_MECHANICAL_SHUTTER_COUNT &&
                tag != TAG_IMAGE_COUNT && tag != TAG_DELETED_IMAGE_COUNT
            ) continue
            val unit = typeSize(buffer.getShort(entry + 2).toInt() and 0xFFFF)
            // 内联即够用；需要二次寻址的变体一律跳过，宁缺毋滥
            if (unit <= 1 || unit > 4 || buffer.getInt(entry + 4) != 1) continue
            out[tag] = if (unit == 2) (buffer.getShort(entry + 8).toInt() and 0xFFFF) else buffer.getInt(entry + 8)
        }
        return out.ifEmpty { null }
    }

    private fun isPlausible(count: Int): Boolean = count in 1..MAX_PLAUSIBLE_COUNT

    private const val MAX_IFD_ELEMENTS = 0x100000

    // ---------- 字节序小工具（一律绝对寻址，不移动 position） ----------

    private fun wrap(bytes: ByteArray, bigEndian: Boolean): ByteBuffer =
        ByteBuffer.wrap(bytes).order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1   // BYTE / ASCII / SBYTE / UNDEFINED
        3, 8 -> 2         // SHORT / SSHORT
        4, 9, 11 -> 4     // LONG / SLONG / FLOAT
        5, 10, 12 -> 8    // RATIONAL / SRATIONAL / DOUBLE
        else -> -1
    }

    private fun copyAt(buffer: ByteBuffer, at: Int, into: ByteArray) {
        for (i in into.indices) into[i] = buffer.get(at + i)
    }
}

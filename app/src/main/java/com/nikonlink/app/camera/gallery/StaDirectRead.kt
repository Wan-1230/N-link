package com.nikonlink.app.camera.gallery

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * STA「直读」相册的纯解析层（无 Android 依赖，可单测）。
 *
 * ## 为什么要它
 *
 * 尼康机身处于「连接到电脑」（STA：相机连路由器 / 手机热点）时，固件对标准对象命令
 * 加了访问锁：`0x1008 GetObjectInfo`、`0x9805 GetObjectPropList`、`0x100A GetThumb`、
 * `0x101B GetPartialObject` 一律回 `0x200F AccessDenied`；AP（智能设备）与 USB 不锁。
 * 2026-10-18 Z30 真机日志：`0x1007` 拿到 31 个句柄、保活 0x90C8 全绿，元数据却被连拒。
 *
 * 同一现场下尼康私有命令不受锁（libgphoto2 #976 实测，Z30 真机复现）：
 * - `0x9434 GetObjectsMetaData(storageId,0,0)`：一次取回全卡 handle/size/拍摄时间；
 * - `0x9421 GetObjectSize(h)`：64 位文件大小；
 * - `0x9431 GetPartialObjectEx(h, offLo, offHi, lenLo, lenHi)`：任意区间读；
 * - `0x9530 GetPartialImage(h, type, offset, maxBytes)`：机身现生成的预览 JPEG。
 *
 * 于是相册改走：句柄高 8 位判类型 → 0x9434/0x9421 拿大小与时间 →
 * 0x9431 读文件头切出内嵌 JPEG 当缩略图 / 0x9530 取高清预览 → 0x9431 分段下载。
 */
object StaDirectRead {

    /**
     * 尼康 PTP/IP 在 STA 下的直读能力档位（写进 `album_access` 事件）。
     * [NO_MEDIA] = 句柄里一个照片/视频都没有（只有目录）：不是结论，调用方应稍后重列。
     */
    enum class Access { OBJECTINFO, DIRECT, UNAVAILABLE, NO_MEDIA }

    /** 句柄是否是可展示的媒体（照片/视频），目录句柄（如 0x11/0x12 段）返回 false。 */
    fun isMediaHandle(handle: Int): Boolean = formatFromHandle(handle) != null

    /** 句柄按高 8 位分组计数，诊断用：`11:2,29:4`（升序）。 */
    fun prefixHistogram(handles: List<Int>): String =
        handles.groupingBy { (it ushr 24) and 0xFF }.eachCount().toSortedMap()
            .entries.joinToString(",") { "%02x:%d".format(it.key, it.value) }

    /**
     * 网格缩略图挑哪一段内嵌 JPEG：优先 [gridMin]..[gridMax] 区间里**最大**的一张
     * （尼康 JPEG 的 MPF 640×424 预览约 180KB，比 EXIF IFD1 的 160×120 清楚得多）；
     * 区间里没有就退到不超过 [gridMax] 的最大一张（通常就是那张小图）；仍没有返回 null。
     * [ranges] 为闭区间，长度 = last - first + 1。
     */
    fun pickGridJpeg(ranges: List<LongRange>, gridMin: Long, gridMax: Long): LongRange? {
        fun len(r: LongRange) = r.last - r.first + 1
        return ranges.filter { len(it) in gridMin..gridMax }.maxByOrNull(::len)
            ?: ranges.filter { len(it) in 1..gridMax }.maxByOrNull(::len)
    }

    /**
     * 句柄高 8 位 = 对象类别（0x09/0x0A=RAW，0x29/0x2A=JPEG，
     * 0x59/0x5A/0x61/0x62/0x65/0x75=视频）。不认识的返回 null，由调用方丢弃。
     */
    fun formatFromHandle(handle: Int): CameraFileFormat? = when ((handle ushr 24) and 0xFF) {
        0x09, 0x0A -> CameraFileFormat.RAW
        0x29, 0x2A -> CameraFileFormat.JPEG
        0x59, 0x5A, 0x61, 0x62, 0x65, 0x75 -> CameraFileFormat.VIDEO
        else -> null
    }

    fun extensionFor(format: CameraFileFormat, handle: Int): String = when (format) {
        CameraFileFormat.RAW -> "NEF"
        CameraFileFormat.JPEG -> "JPG"
        CameraFileFormat.VIDEO -> if (((handle ushr 24) and 0xFF) in setOf(0x61, 0x62)) "MP4" else "MOV"
        else -> "BIN"
    }

    /** 机身默认文件名前缀；读不到「文件命名」设置时用它。 */
    const val DEFAULT_PREFIX = "DSC"

    /**
     * 尼康卡内文件名：sRGB 为 `XXX_nnnn`，AdobeRGB 为 `_XXXnnnn`（XXX = 「文件命名」前缀）。
     * 编号统一 4 位，与机身一致（0001–9999）。
     */
    fun cardName(prefix: String, number: Int, adobeRgb: Boolean, ext: String): String =
        if (adobeRgb) "_%s%04d.%s".format(prefix, number, ext) else "%s_%04d.%s".format(prefix, number, ext)

    /**
     * 拿不到真实文件名时的占位名：编号取句柄低 16 位（Z30 实测与卡内文件号一致）。
     * 读到文件头里的编号 / 色彩空间后由 [nameFromNumber] 校正。
     */
    fun placeholderName(handle: Int, format: CameraFileFormat, prefix: String = DEFAULT_PREFIX): String =
        cardName(prefix, handle and 0xFFFF, false, extensionFor(format, handle))

    /**
     * 「文件命名」前缀的合法形态：机身只允许 3 位大写字母 / 数字（含下划线）。
     * 用来从字符串型设备属性里挑出前缀候选。
     */
    fun isPlausiblePrefix(value: String?): Boolean =
        value != null && Regex("[A-Z0-9_]{3}").matches(value)

    /**
     * 解 `0x1014 GetDevicePropDesc` 里的字符串当前值：
     * `u16 code, u16 dataType, u8 getSet, STRING factoryDefault, STRING current, …`。
     * 不是字符串型（dataType ≠ 0xFFFF）或长度不够返回 null。
     */
    fun parseStringPropDesc(data: ByteArray): Pair<Int, String>? {
        if (data.size < 7) return null
        val code = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        val type = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        if (type != 0xFFFF) return null
        var p = 5
        fun str(): String? {
            if (p >= data.size) return null
            val n = data[p].toInt() and 0xFF
            p += 1
            if (n == 0) return ""
            if (p + n * 2 > data.size) return null
            val v = String(data, p, n * 2, Charsets.UTF_16LE).trimEnd('\u0000')
            p += n * 2
            return v
        }
        str() ?: return null          // FactoryDefault
        val current = str() ?: return null
        return code to current
    }

    /** `0x9434` 单条：handle / 大小 / 拍摄时间（机身本地时间，按手机时区换算）。 */
    data class MetaEntry(val handle: Int, val size: Long, val captureTimeMillis: Long?)

    /**
     * 解析 `0x9434 GetObjectsMetaData` 载荷：
     * ```
     * u32 version, u32 count, count × {
     *   u32 handle, u32 size, u8 保留, u8 秒, u8 分, u8 时, u8 日, u8 月, u16 年 }
     * ```
     * 长度不足或计数异常返回 null（= 不可用，回退逐个 0x9421）。
     */
    fun parseObjectsMetaData(payload: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<MetaEntry>? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        buf.int // version
        val count = buf.int
        if (count < 0 || count > 1_000_000 || buf.remaining() < count.toLong() * 16) return null
        val out = ArrayList<MetaEntry>(count)
        repeat(count) {
            val handle = buf.int
            val size = buf.int.toLong() and 0xFFFFFFFFL
            buf.get()
            val sec = buf.get().toInt() and 0xFF
            val min = buf.get().toInt() and 0xFF
            val hour = buf.get().toInt() and 0xFF
            val day = buf.get().toInt() and 0xFF
            val month = buf.get().toInt() and 0xFF
            val year = buf.short.toInt() and 0xFFFF
            out += MetaEntry(handle, size, localTime(year, month, day, hour, min, sec, zone))
        }
        return out
    }

    private fun localTime(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, zone: ZoneId): Long? {
        if (y !in 1970..2200 || mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || s > 59) return null
        return runCatching {
            LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /**
     * 文件头解析结果：拍摄时间、卡内文件编号、内嵌 JPEG 区间（按长度降序）。
     * [adobeRgb]：EXIF ColorSpace = 0xFFFF（Uncalibrated，尼康 AdobeRGB 的写法），决定文件名形态。
     */
    data class HeadInfo(
        val captureTimeMillis: Long?,
        val fileNumber: Int?,
        val embeddedJpegs: List<LongRange>,
        val adobeRgb: Boolean = false
    )

    /**
     * 解析 JPEG / NEF(TIFF) 文件头。只读 [head] 里已有的字节，
     * 内嵌 JPEG 的区间可能超出 [head]（由调用方按区间再用 0x9431 精确读取）。
     */
    fun parseHead(head: ByteArray, zone: ZoneId = ZoneId.systemDefault()): HeadInfo {
        val tiffStart = when {
            isTiff(head, 0) -> 0
            isJpeg(head) -> findExifTiff(head) ?: -1
            else -> -1
        }
        val jpegs = mutableListOf<LongRange>()
        var date: String? = null
        var fileNumber: Int? = null
        var colorSpace: Long? = null
        if (tiffStart >= 0) {
            val tiff = TiffReader(head, tiffStart)
            var makerNote: TiffValue? = null
            tiff.walk { tag, value ->
                when (tag) {
                    TAG_DATETIME_ORIGINAL -> date = value.asString() ?: date
                    TAG_DATETIME -> if (date == null) date = value.asString()
                    TAG_IMAGE_NUMBER -> fileNumber = value.asLong()?.toInt()
                    TAG_COLOR_SPACE -> colorSpace = value.asLong()
                    TAG_MAKER_NOTE -> makerNote = value
                }
            }
            jpegs += tiff.jpegRanges().map { (it.first + tiffStart)..(it.last + tiffStart) }
            // 尼康 MakerNote：`Nikon\0` + 4 字节版本 + 自带 TIFF 头；其 0x0011 PreviewIFD
            // 指向 ~640px 的小预览 JPEG（偏移相对 MakerNote 内 TIFF 头）—— 网格缩略图首选。
            makerNote?.let { mn ->
                val mnStart = tiffStart + mn.rawOffset
                if (mnStart >= 0 && mnStart + 18 <= head.size &&
                    String(head, mnStart, 5, Charsets.US_ASCII) == "Nikon" && isTiff(head, mnStart + 10)
                ) {
                    val mnTiff = TiffReader(head, mnStart + 10)
                    var previewIfd: Long? = null
                    var fileInfo: TiffValue? = null
                    mnTiff.walk { tag, v ->
                        when (tag) {
                            TAG_NIKON_PREVIEW_IFD -> previewIfd = v.asLong()
                            TAG_NIKON_FILE_INFO -> fileInfo = v
                        }
                    }
                    previewIfd?.let { mnTiff.readExtraIfd(it) }
                    // FileInfo(0x00B8)：4 字节版本串后是 u16 卡号 / 目录号 / 文件号（ExifTool Nikon::FileInfo）
                    fileInfo?.takeIf { it.count >= 10 }?.let { fi ->
                        if (fileNumber == null) fileNumber = mnTiff.readScalar(3, fi.rawOffset + 8, 0)?.toInt()
                    }
                    jpegs += mnTiff.jpegRanges().map { (it.first + mnStart + 10)..(it.last + mnStart + 10) }
                }
            }
        }
        if (isJpeg(head)) jpegs += findMpfImages(head)
        if (jpegs.isEmpty() && tiffStart < 0) {
            // 无 EXIF/MPF 的 JPEG、或视频（MOV/MP4 头部的 NCTH/NCVW 等原子里挂着 JPEG）：
            // 退而扫 SOI…EOI 闭合段。JPEG 本身从 0 开始的主图要跳过。
            jpegs += scanJpegSegments(head, from = if (isJpeg(head)) 2 else 0)
        }
        return HeadInfo(
            captureTimeMillis = date?.let { parseExifDate(it, zone) },
            fileNumber = fileNumber?.takeIf { it in 1..9999 },
            embeddedJpegs = jpegs.distinct().filter { it.last > it.first }
                .sortedByDescending { it.last - it.first },
            adobeRgb = colorSpace == 0xFFFFL
        )
    }

    /** 卡内编号可读时给出卡内文件名（见 [cardName]），否则 null。 */
    fun nameFromNumber(
        number: Int?,
        format: CameraFileFormat,
        handle: Int,
        prefix: String = DEFAULT_PREFIX,
        adobeRgb: Boolean = false
    ): String? = number?.let { cardName(prefix, it, adobeRgb, extensionFor(format, handle)) }

    fun parseExifDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = raw.trim()
        if (s.length < 19) return null
        return runCatching {
            localTime(
                s.substring(0, 4).toInt(), s.substring(5, 7).toInt(), s.substring(8, 10).toInt(),
                s.substring(11, 13).toInt(), s.substring(14, 16).toInt(), s.substring(17, 19).toInt(), zone
            )
        }.getOrNull()
    }

    fun isJpeg(b: ByteArray): Boolean =
        b.size >= 3 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xD8

    /** 整段 JPEG：SOI 开头、EOI 结尾。 */
    fun isCompleteJpeg(b: ByteArray): Boolean =
        isJpeg(b) && b.size >= 4 &&
            (b[b.size - 2].toInt() and 0xFF) == 0xFF && (b[b.size - 1].toInt() and 0xFF) == 0xD9

    private fun isTiff(b: ByteArray, at: Int): Boolean {
        if (b.size < at + 8) return false
        val ii = b[at] == 'I'.code.toByte() && b[at + 1] == 'I'.code.toByte()
        val mm = b[at] == 'M'.code.toByte() && b[at + 1] == 'M'.code.toByte()
        if (!ii && !mm) return false
        val magic = if (ii) (b[at + 2].toInt() and 0xFF) or ((b[at + 3].toInt() and 0xFF) shl 8)
        else ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return magic == 42
    }

    /** 在 JPEG 段里找 APP1 "Exif\0\0"，返回其后 TIFF 头的偏移。 */
    private fun findExifTiff(b: ByteArray): Int? {
        var i = 2
        while (i + 4 <= b.size && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return null
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2) return null
            if (marker == 0xE1 && i + 10 <= b.size &&
                b[i + 4] == 'E'.code.toByte() && b[i + 5] == 'x'.code.toByte() &&
                b[i + 6] == 'i'.code.toByte() && b[i + 7] == 'f'.code.toByte() &&
                b[i + 8].toInt() == 0 && b[i + 9].toInt() == 0
            ) {
                return (i + 10).takeIf { isTiff(b, it) }
            }
            i += 2 + len
        }
        return null
    }

    /**
     * JPEG 的 APP2 "MPF\0"（CIPA DC-007）：MPEntry(0xB002) 每条 16 字节
     * `attr u32, size u32, offset u32, dep u16, dep u16`，offset 相对 MPF 内 TIFF 头，
     * 第一条（offset=0）是主图本身。尼康 JPEG 用它挂一张 ~1620px 的大预览。
     */
    private fun findMpfImages(b: ByteArray): List<LongRange> {
        var i = 2
        while (i + 4 <= b.size && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD9 || marker == 0xDA) return emptyList()
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2) return emptyList()
            if (marker == 0xE2 && i + 8 <= b.size &&
                b[i + 4] == 'M'.code.toByte() && b[i + 5] == 'P'.code.toByte() &&
                b[i + 6] == 'F'.code.toByte() && b[i + 7].toInt() == 0 && isTiff(b, i + 8)
            ) {
                val base = i + 8
                val r = TiffReader(b, base)
                var entries: TiffValue? = null
                r.walk { tag, v -> if (tag == TAG_MP_ENTRY) entries = v }
                val e = entries ?: return emptyList()
                val out = mutableListOf<LongRange>()
                val n = (e.count / 16).coerceAtMost(16).toInt()
                for (k in 0 until n) {
                    val size = r.readScalar(4, e.rawOffset + k * 16 + 4, 0) ?: continue
                    val off = r.readScalar(4, e.rawOffset + k * 16 + 8, 0) ?: continue
                    if (off > 0 && size > 0) out += (base + off) until (base + off + size)
                }
                return out
            }
            i += 2 + len
        }
        return emptyList()
    }

    /** 无 EXIF 时的兜底：从 [from] 起扫 FFD8…FFD9 闭合段。 */
    private fun scanJpegSegments(b: ByteArray, from: Int): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var start = -1
        var i = from
        while (i + 1 < b.size) {
            val a = b[i].toInt() and 0xFF
            val c = b[i + 1].toInt() and 0xFF
            if (a == 0xFF && c == 0xD8) {
                start = i; i += 2; continue
            }
            if (start >= 0 && a == 0xFF && c == 0xD9) {
                out += start.toLong()..(i + 1).toLong()
                start = -1; i += 2; continue
            }
            i++
        }
        return out
    }

    private const val TAG_DATETIME = 0x0132
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_IMAGE_NUMBER = 0x9211
    private const val TAG_COLOR_SPACE = 0xA001
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_SUB_IFDS = 0x014A
    private const val TAG_JPEG_OFFSET = 0x0201
    private const val TAG_JPEG_LENGTH = 0x0202
    private const val TAG_STRIP_OFFSETS = 0x0111
    private const val TAG_STRIP_BYTES = 0x0117
    private const val TAG_COMPRESSION = 0x0103
    private const val TAG_MAKER_NOTE = 0x927C
    private const val TAG_MP_ENTRY = 0xB002
    private const val TAG_NIKON_PREVIEW_IFD = 0x0011
    private const val TAG_NIKON_FILE_INFO = 0x00B8

    /** 一个 IFD 条目的值视图（只支持文件头解析用得到的类型）。 */
    class TiffValue internal constructor(
        private val r: TiffReader,
        val type: Int,
        val count: Long,
        private val valueOffset: Int
    ) {
        /** 值所在偏移（相对本 TIFF 头）；>4 字节的值即其指针。 */
        val rawOffset: Int get() = valueOffset
        fun asLong(index: Int = 0): Long? = r.readScalar(type, valueOffset, index)
        fun asLongs(): List<Long> = (0 until count.coerceAtMost(64).toInt()).mapNotNull { asLong(it) }
        fun asString(): String? = if (type == 2) r.readAscii(valueOffset, count.toInt()) else null
    }

    /** 最小 TIFF 读取器：遍历 IFD0 / IFD1 / SubIFD / ExifIFD，越界一律静默跳过。 */
    class TiffReader(private val b: ByteArray, private val base: Int) {
        private val little = b[base] == 'I'.code.toByte()
        private val ifds = mutableListOf<Map<Int, TiffValue>>()

        private fun u16(at: Int): Int? {
            val p = base + at
            if (p < 0 || p + 2 > b.size) return null
            val x = b[p].toInt() and 0xFF
            val y = b[p + 1].toInt() and 0xFF
            return if (little) x or (y shl 8) else (x shl 8) or y
        }

        private fun u32(at: Int): Long? {
            val p = base + at
            if (p < 0 || p + 4 > b.size) return null
            var v = 0L
            for (k in 0 until 4) {
                val byte = (b[p + k].toLong() and 0xFF)
                v = if (little) v or (byte shl (8 * k)) else (v shl 8) or byte
            }
            return v
        }

        internal fun readScalar(type: Int, offset: Int, index: Int): Long? = when (type) {
            1, 7 -> b.getOrNull(base + offset + index)?.let { it.toLong() and 0xFF }
            3 -> u16(offset + index * 2)?.toLong()
            4, 13 -> u32(offset + index * 4)
            else -> null
        }

        internal fun readAscii(offset: Int, count: Int): String? {
            val p = base + offset
            if (count <= 0 || p < 0 || p + count > b.size) return null
            return String(b, p, count, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        }

        private fun typeSize(type: Int): Int = when (type) {
            1, 2, 6, 7 -> 1
            3, 8 -> 2
            4, 9, 11, 13 -> 4
            5, 10, 12 -> 8
            else -> 0
        }

        private fun readIfd(offset: Long, depth: Int, visited: MutableSet<Long>) {
            if (depth > 4 || offset <= 0 || offset > Int.MAX_VALUE || !visited.add(offset)) return
            val at = offset.toInt()
            val n = u16(at) ?: return
            if (n == 0 || n > 512) return
            val entries = HashMap<Int, TiffValue>()
            for (k in 0 until n) {
                val e = at + 2 + k * 12
                val tag = u16(e) ?: return
                val type = u16(e + 2) ?: return
                val count = u32(e + 4) ?: return
                val size = typeSize(type).toLong() * count
                val valueOffset = if (size in 1..4) e + 8 else (u32(e + 8)?.toInt() ?: continue)
                entries[tag] = TiffValue(this, type, count, valueOffset)
            }
            ifds += entries
            entries[TAG_EXIF_IFD]?.asLong()?.let { readIfd(it, depth + 1, visited) }
            entries[TAG_SUB_IFDS]?.asLongs()?.forEach { readIfd(it, depth + 1, visited) }
            // 主链的下一个 IFD（IFD1 = EXIF 缩略图）
            u32(at + 2 + n * 12)?.let { readIfd(it, depth, visited) }
        }

        /** 额外挂一个 IFD（如尼康 MakerNote 的 PreviewIFD），并入 [jpegRanges]。 */
        fun readExtraIfd(offset: Long) {
            if (ifds.isEmpty()) walk { _, _ -> }
            readIfd(offset, 3, mutableSetOf())
        }

        fun walk(onTag: (Int, TiffValue) -> Unit) {
            if (ifds.isEmpty()) u32(4)?.let { readIfd(it, 0, mutableSetOf()) }
            ifds.forEach { ifd -> ifd.forEach { (tag, v) -> onTag(tag, v) } }
        }

        /** 各 IFD 里声明的 JPEG 区间（JPEGInterchangeFormat 或 JPEG 压缩的单条带）。偏移相对 TIFF 头。 */
        fun jpegRanges(): List<LongRange> {
            if (ifds.isEmpty()) walk { _, _ -> }
            val out = mutableListOf<LongRange>()
            for (ifd in ifds) {
                val off = ifd[TAG_JPEG_OFFSET]?.asLong()
                val len = ifd[TAG_JPEG_LENGTH]?.asLong()
                if (off != null && len != null && off > 0 && len > 0) out += off until off + len
                val compression = ifd[TAG_COMPRESSION]?.asLong()
                val strips = ifd[TAG_STRIP_OFFSETS]
                val bytes = ifd[TAG_STRIP_BYTES]
                // 只认 6（旧式 JPEG）：7 在 NEF 里也可能是无损 JPEG 编码的 RAW 数据，解不出图
                if (compression == 6L && strips != null && bytes != null && strips.count == 1L) {
                    val so = strips.asLong()
                    val sb = bytes.asLong()
                    if (so != null && sb != null && so > 0 && sb > 0) out += so until so + sb
                }
            }
            return out
        }
    }
}

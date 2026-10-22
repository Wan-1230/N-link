package com.nikonlink.app.camera.gallery

import com.nikonlink.app.device.ptp.MtpObjectProp
import com.nikonlink.app.device.ptp.MtpObjectPropListParser
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpDataResult
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.shared.common.AppEventLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * STA 相册直读引擎（解析见 [StaDirectRead]）。
 *
 * 只在「WiFi PTP/IP + STA 会话」下由 [TransferManager] 调用；AP / USB 路径完全不经过这里。
 * 探测结论按 [PtpSessionManager.connectGeneration] 缓存，换一次会话重新探测一次。
 *
 * 诊断事件（导出日志里搜这几个 phase）：
 * - `album_access`：探测结论 + 每条探测命令的响应码 / 字节数
 * - `sta_list`：列表来源（9434 / 9421）、条数、文件名来源、耗时
 * - `sta_thumb` / `sta_hd`：前几张缩略图 / 高清的来源与大小，之后只计数
 * - `sta_name`：下载时从文件头补出的真实文件名
 */
internal class StaDirectAlbum(
    private val ptp: PtpSessionManager,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "StaDirectAlbum"
        private const val CMD_TIMEOUT_MS = 8000L
        /** 网格缩略图读文件头的长度（256KB：NEF 的 MakerNote 小预览与 IFD 全在里面）。 */
        private const val HEAD_BYTES = 256 * 1024
        /** 内嵌 JPEG 单段读取上限：高清档放到 6MB（Z 系 NEF 全尺寸内嵌预览约 1~4MB）。 */
        private const val MAX_EMBEDDED_BYTES = 6 * 1024 * 1024
        /**
         * 网格档区间：取区间内**最大**的内嵌 JPEG。Z30 JPEG 实测三段：IFD1 160×120 ≈10.7KB、
         * MPF 640×424 ≈178KB、MPF 1620×1080 ≈759KB —— 上限卡在 400KB，选中 640 那张；
         * 区间内没有时退到不超过上限的最大一张（即小图），见 [StaDirectRead.pickGridJpeg]。
         */
        private const val GRID_MIN_BYTES = 8 * 1024L
        private const val GRID_MAX_BYTES = 400 * 1024L
        /** 探测最多试几个媒体句柄（第一个恰好是坏句柄 / 正在写卡时再给一次机会）。 */
        private const val PROBE_SAMPLES = 2
        /** 0x9530 GetPartialImage 单次块大小（实测可用值）。 */
        private const val PARTIAL_IMAGE_CHUNK = 10240
        private const val PARTIAL_IMAGE_MAX = 32 * 1024 * 1024
        private const val HEAD_CACHE_MAX = 48
        private const val LOG_SAMPLE = 3
        private const val PER_HANDLE_NAME_MAX = 3000
        /** 保护位逐个读取：连续失败几次就收手（机身拒绝时不要打满整卡）。 */
        private const val PROTECT_FAIL_BREAK = 3
        /** 保护位按需读取总预算；超时返回空，让上层明确提示"读不到"而不是给半份清单。 */
        private const val PROTECT_READ_BUDGET_MS = 20_000L

        private const val FORMAT_JPEG = 0x3801
        private const val FORMAT_NEF = 0xB103
        private const val FORMAT_MOV = 0x300D
    }

    /** 已缓存结论（只缓存 DIRECT / OBJECTINFO）所属的会话代号。 */
    @Volatile private var probedGeneration = -1
    @Volatile var access: StaDirectRead.Access = StaDirectRead.Access.OBJECTINFO
        private set
    /** 本类各缓存（文件头 / 名字 / 大小）所属的会话代号；换会话才清空。 */
    @Volatile private var cacheGeneration = -1
    /** 两次相册加载并发进来时只探测一次，后到的等结论（2026-10-19 日志里隔 17ms 各探了一遍）。 */
    private val probeMutex = Mutex()

    /** 本会话里 0x9530 是否可用：null = 还没试过。 */
    @Volatile private var partialImageOk: Boolean? = null

    private val headCache = object : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?) = size > HEAD_CACHE_MAX
    }
    private val resolvedNames = ConcurrentHashMap<Int, String>()
    private val sizes = ConcurrentHashMap<Int, Long>()
    private val thumbLogged = AtomicInteger(0)
    private val hdLogged = AtomicInteger(0)
    private val thumbMiss = AtomicInteger(0)

    /** 当前会话是否应走直读（已探测且结论为 DIRECT）。 */
    fun isDirectActive(): Boolean =
        ptp.isStaSession && probedGeneration == ptp.connectGeneration && access == StaDirectRead.Access.DIRECT

    private fun resetForNewSession() {
        synchronized(headCache) { headCache.clear() }
        resolvedNames.clear()
        sizes.clear()
        partialImageOk = null
        thumbLogged.set(0)
        hdLogged.set(0)
        thumbMiss.set(0)
    }

    /**
     * 探测 STA 会话下相册能怎么读：
     * 抽一个**媒体**句柄 → 0x1008 能读 = OBJECTINFO（固件没锁，走老路）；
     * 被拒 → 0x9421 + 0x9431 都通 = DIRECT；都不通 = UNAVAILABLE。
     *
     * 2026-10-19 真机：注册后首轮句柄里只有目录 `0x11004000`，旧版拿它当样本，
     * 0x9431 回 0x2009（句柄无效）就判了整机不可用、且整会话缓存，必须重连才恢复。
     * 现在：目录句柄不参与探测；没有媒体句柄返回 NO_MEDIA（不下结论、不缓存，调用方重列）；
     * UNAVAILABLE 也不缓存，下次加载重新探测。只有 DIRECT / OBJECTINFO 会缓存到会话结束。
     */
    suspend fun probe(handles: List<Int>): StaDirectRead.Access = probeMutex.withLock {
        val gen = ptp.connectGeneration
        if (cacheGeneration != gen) {
            resetForNewSession()
            cacheGeneration = gen
        }
        if (probedGeneration == gen) return@withLock access

        val hist = StaDirectRead.prefixHistogram(handles)
        val media = handles.filter { StaDirectRead.isMediaHandle(it) }
        if (media.isEmpty()) {
            eventLogger.event(
                "album_access", "mode" to StaDirectRead.Access.NO_MEDIA.name,
                "handles" to handles.size, "prefix" to hist
            )
            return@withLock StaDirectRead.Access.NO_MEDIA
        }

        var mode = StaDirectRead.Access.UNAVAILABLE
        for (sample in media.take(PROBE_SAMPLES)) {
            mode = probeOne(sample, handles.size, hist)
            if (mode != StaDirectRead.Access.UNAVAILABLE) break
        }
        if (mode == StaDirectRead.Access.DIRECT || mode == StaDirectRead.Access.OBJECTINFO) {
            commit(gen, mode)
        }
        mode
    }

    private suspend fun probeOne(sample: Int, total: Int, hist: String): StaDirectRead.Access {
        val hex = "0x%08X".format(sample)
        val info = withTimeoutOrNull(CMD_TIMEOUT_MS) {
            ptp.sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, listOf(sample))
        }
        val infoCode = info.codeHex()
        if (info is PtpDataResult.Success && info.data.size >= 53) {
            eventLogger.event(
                "album_access", "mode" to "OBJECTINFO", "handle" to hex, "info" to infoCode,
                "handles" to total, "prefix" to hist
            )
            return StaDirectRead.Access.OBJECTINFO
        }

        val size = withTimeoutOrNull(CMD_TIMEOUT_MS) { ptp.getObjectSize64(sample) }
        val head = withTimeoutOrNull(CMD_TIMEOUT_MS) { ptp.getPartialObjectEx(sample, 0L, 65536) }
        val mode = if (size != null && head != null && head.isNotEmpty()) {
            StaDirectRead.Access.DIRECT
        } else {
            StaDirectRead.Access.UNAVAILABLE
        }
        if (size != null) sizes[sample] = size
        eventLogger.event(
            "album_access",
            "mode" to mode.name,
            "handle" to hex,
            "info" to infoCode,
            "size_9421" to (size ?: -1),
            "head_9431" to (head?.size ?: -1),
            "head_magic" to head?.take(4)?.joinToString("") { "%02x".format(it) },
            "reject" to "0x%x".format(ptp.lastDataRejectCode),
            "handles" to total,
            "prefix" to hist
        )
        Timber.tag(TAG).i("STA album access=%s sample=%s size=%s head=%s", mode, hex, size, head?.size)
        return mode
    }

    private fun commit(gen: Int, mode: StaDirectRead.Access) {
        access = mode
        probedGeneration = gen
    }

    /**
     * 直读模式下构建相册列表：类型取自句柄高 8 位，大小 / 时间优先 0x9434 一次取回，
     * 缺的逐个 0x9421 补；文件名先试 MTP 只取文件名的两种问法，取不到用占位名，
     * 下载时再从文件头补真实名（[resolveFileName]）。
     */
    suspend fun buildList(storageIds: List<Int>, handles: List<Int>): List<CameraFile> {
        val start = System.currentTimeMillis()
        val wanted = handles.filter { StaDirectRead.formatFromHandle(it) != null }
        val handleSet = wanted.toHashSet()

        // ① 0x9434：每张卡一次往返拿全部 handle/size/time
        val meta = HashMap<Int, StaDirectRead.MetaEntry>()
        var metaCode = "skipped"
        val ops = ptp.lastSupportedOperations
        if (ops == null || ops.contains(PtpConstants.OP_NIKON_GET_OBJECTS_METADATA)) {
            val ids = storageIds.ifEmpty { listOf(-1) }
            for (sid in ids) {
                val r = withTimeoutOrNull(CMD_TIMEOUT_MS * 2) {
                    ptp.sendCommandWithData(PtpConstants.OP_NIKON_GET_OBJECTS_METADATA, listOf(sid, 0, 0))
                }
                metaCode = r.codeHex()
                val parsed = (r as? PtpDataResult.Success)?.data?.let { StaDirectRead.parseObjectsMetaData(it) }
                parsed?.forEach { if (it.handle in handleSet) meta[it.handle] = it }
            }
        }

        // ② 缺大小的逐个 0x9421
        var sizeCalls = 0
        for (h in wanted) {
            if (meta[h]?.size?.takeIf { it > 0 } != null || sizes.containsKey(h)) continue
            sizeCalls++
            withTimeoutOrNull(CMD_TIMEOUT_MS) { ptp.getObjectSize64(h) }?.let { sizes[h] = it }
        }
        meta.values.forEach { if (it.size > 0) sizes.putIfAbsent(it.handle, it.size) }

        // ③ 文件名：0x9805 只问 0xDC07 → 0x9803 逐个 → 占位
        val nameSource = fetchNames(wanted)

        val files = wanted.mapNotNull { h ->
            val format = StaDirectRead.formatFromHandle(h) ?: return@mapNotNull null
            val size = sizes[h] ?: return@mapNotNull null
            CameraFile(
                handle = h,
                fileName = resolvedNames[h] ?: StaDirectRead.placeholderName(h, format),
                size = size,
                formatCode = when (format) {
                    CameraFileFormat.RAW -> FORMAT_NEF
                    CameraFileFormat.VIDEO -> FORMAT_MOV
                    else -> FORMAT_JPEG
                },
                storageId = storageIds.firstOrNull() ?: 0,
                format = format,
                captureTimeMillis = meta[h]?.captureTimeMillis
            )
        }
        eventLogger.event(
            "sta_list",
            "handles" to handles.size,
            "prefix" to StaDirectRead.prefixHistogram(handles),
            "typed" to wanted.size,
            "files" to files.size,
            "meta_9434" to metaCode,
            "meta_hits" to meta.size,
            "size_9421_calls" to sizeCalls,
            "names" to nameSource,
            "with_time" to files.count { it.captureTimeMillis != null },
            "ms" to (System.currentTimeMillis() - start)
        )
        return files
    }

    /** 返回文件名来源标签（写进 sta_list）。 */
    private suspend fun fetchNames(handles: List<Int>): String {
        if (handles.isEmpty()) return "none"
        val ops = ptp.lastSupportedOperations
        if (ops == null || ops.contains(PtpConstants.OP_MTP_GET_OBJECT_PROP_LIST)) {
            val r = withTimeoutOrNull(CMD_TIMEOUT_MS * 2) {
                ptp.sendCommandWithData(
                    PtpConstants.OP_MTP_GET_OBJECT_PROP_LIST,
                    listOf(-1, 0, com.nikonlink.app.device.ptp.MtpObjectProp.OBJECT_FILE_NAME, 0, 0)
                )
            }
            val props = (r as? PtpDataResult.Success)?.data?.let { MtpObjectPropListParser.parse(it) }
            var hits = 0
            props?.forEach { p ->
                val name = p.fileName?.takeIf { it.isNotBlank() } ?: return@forEach
                resolvedNames[p.handle] = name
                hits++
            }
            if (hits > 0) return "9805:$hits"
        }
        if (handles.size <= PER_HANDLE_NAME_MAX &&
            (ops == null || ops.contains(PtpConstants.OP_MTP_GET_OBJECT_PROP_VALUE))
        ) {
            val first = readNameProp(handles.first()) ?: return "placeholder"
            resolvedNames[handles.first()] = first
            var hits = 1
            for (h in handles.drop(1)) {
                readNameProp(h)?.let { resolvedNames[h] = it; hits++ }
            }
            return "9803:$hits"
        }
        return "placeholder"
    }

    private suspend fun readNameProp(handle: Int): String? {
        val r = withTimeoutOrNull(CMD_TIMEOUT_MS) {
            ptp.sendCommandWithData(
                PtpConstants.OP_MTP_GET_OBJECT_PROP_VALUE,
                listOf(handle, com.nikonlink.app.device.ptp.MtpObjectProp.OBJECT_FILE_NAME)
            )
        } as? PtpDataResult.Success ?: return null
        return readPtpString(r.data)?.takeIf { it.isNotBlank() }
    }

    /**
     * 按需读取机内保护位（0xDC03），只读、不写。
     *
     * STA「连接到电脑」下 0x1008/0x9805 被机身锁成 0x200F，列表阶段拿不到保护位；
     * 这里按需补读：先试一次 `0x9805` 只问保护列，失败再逐个 `0x9803`。
     * 机身若连 0x9803 也拒，**返回空 Map** —— 上层据此提示"当前连接方式读不到"，
     * 不能把未知当成"全部未保护"（会把用户按过保护键的照片筛没）。
     *
     * 逐个读取有 3 连败熔断与 20s 总预算，避免在拒读机身上把整卡句柄打一遍。
     * 只有**全部目标句柄都拿到值**才返回，半份清单同样视为失败。
     */
    suspend fun readProtection(handles: List<Int>): Map<Int, Int?> {
        val wanted = handles.filter { StaDirectRead.isMediaHandle(it) }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val start = System.currentTimeMillis()
        val statuses = LinkedHashMap<Int, Int?>()

        // ① 0x9805 只问 0xDC03：一次往返覆盖全卡（与文件名批量读取同一问法）
        val bulk = withTimeoutOrNull(CMD_TIMEOUT_MS * 2) {
            ptp.sendCommandWithData(
                PtpConstants.OP_MTP_GET_OBJECT_PROP_LIST,
                listOf(
                    MtpObjectProp.ALL, 0, MtpObjectProp.PROTECTION_STATUS, 0, 0
                )
            )
        }
        val bulkCode = bulk.codeHex()
        if (bulk is PtpDataResult.Success) {
            MtpObjectPropListParser.parse(bulk.data)?.forEach { p ->
                val value = p.protectionStatus
                if (value != null && p.handle in wanted) statuses[p.handle] = value
            }
        }

        // ② 批量没覆盖到的逐个 0x9803
        var probes = 0
        var consecutiveFailures = 0
        for (h in wanted) {
            if (statuses.containsKey(h)) continue
            if (System.currentTimeMillis() - start > PROTECT_READ_BUDGET_MS) break
            probes++
            val r = withTimeoutOrNull(CMD_TIMEOUT_MS) {
                ptp.sendCommandWithData(
                    PtpConstants.OP_MTP_GET_OBJECT_PROP_VALUE,
                    listOf(h, MtpObjectProp.PROTECTION_STATUS)
                )
            }
            val value = (r as? PtpDataResult.Success)?.data
                ?.takeIf { it.size >= 2 }
                ?.let {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                        .short.toInt() and 0xFFFF
                }
            if (value == null) {
                consecutiveFailures++
                if (consecutiveFailures >= PROTECT_FAIL_BREAK) break
            } else {
                consecutiveFailures = 0
                statuses[h] = value
            }
        }

        val complete = statuses.size == wanted.size
        eventLogger.event(
            "sta_protect",
            "requested" to wanted.size,
            "hits" to statuses.size,
            "bulk_9805" to bulkCode,
            "probes_9803" to probes,
            "complete" to complete,
            "ms" to (System.currentTimeMillis() - start)
        )
        return if (complete) statuses else emptyMap()
    }

    /** 新对象事件（0xC101）在直读模式下的单条构建。 */
    suspend fun fileFor(handle: Int): CameraFile? {
        val format = StaDirectRead.formatFromHandle(handle) ?: return null
        val size = sizes[handle] ?: withTimeoutOrNull(CMD_TIMEOUT_MS) { ptp.getObjectSize64(handle) } ?: return null
        sizes[handle] = size
        val head = head(handle, size)
        val info = head?.let { StaDirectRead.parseHead(it) }
        val name = resolvedNames[handle]
            ?: StaDirectRead.nameFromNumber(info?.fileNumber, format, handle)
            ?: StaDirectRead.placeholderName(handle, format)
        return CameraFile(
            handle = handle,
            fileName = name,
            size = size,
            formatCode = when (format) {
                CameraFileFormat.RAW -> FORMAT_NEF
                CameraFileFormat.VIDEO -> FORMAT_MOV
                else -> FORMAT_JPEG
            },
            storageId = 0,
            format = format,
            captureTimeMillis = info?.captureTimeMillis
        )
    }

    /** 读（并缓存）文件头：min(size, 256KB)，0x9431 一次往返。 */
    private suspend fun head(handle: Int, knownSize: Long? = null): ByteArray? {
        synchronized(headCache) { headCache[handle] }?.let { return it }
        val size = knownSize ?: sizes[handle]
        val len = if (size != null && size > 0) minOf(size, HEAD_BYTES.toLong()).toInt() else HEAD_BYTES
        val bytes = withTimeoutOrNull(CMD_TIMEOUT_MS) { ptp.getPartialObjectEx(handle, 0L, len) }
            ?.takeIf { it.isNotEmpty() } ?: return null
        synchronized(headCache) { headCache[handle] = bytes }
        return bytes
    }

    private suspend fun readRange(handle: Int, range: LongRange, head: ByteArray): ByteArray? {
        val len = (range.last - range.first + 1)
        if (len <= 0 || len > MAX_EMBEDDED_BYTES) return null
        if (range.last < head.size) return head.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
        return withTimeoutOrNull(CMD_TIMEOUT_MS * 2) { ptp.getPartialObjectEx(handle, range.first, len.toInt()) }
    }

    /**
     * 网格缩略图：从文件头切内嵌 JPEG（区间内最大一张，Z30 JPEG 即 MPF 640×424 预览）。
     * 那张读失败时退到不超过区间上限的次大一张（通常 IFD1 160×120），再退 0x9530。
     */
    suspend fun thumbnail(handle: Int): ByteArray? {
        val head = head(handle) ?: return logThumb(handle, "head_fail", null, 0)
        val info = StaDirectRead.parseHead(head)
        rememberName(handle, info)
        val ranges = info.embeddedJpegs
        val primary = StaDirectRead.pickGridJpeg(ranges, GRID_MIN_BYTES, GRID_MAX_BYTES)
        val fallback = primary?.let { p ->
            StaDirectRead.pickGridJpeg(ranges.filter { it != p }, 1L, (p.last - p.first).coerceAtLeast(1L))
        }
        for ((label, pick) in listOf("embedded" to primary, "embedded_small" to fallback)) {
            if (pick == null) continue
            val jpeg = readRange(handle, pick, head)?.takeIf { StaDirectRead.isJpeg(it) }
            if (jpeg != null) return logThumb(handle, label, jpeg, head.size, ranges.size)
        }
        // JPEG 原图本身就很小（或头里根本没有内嵌图）时，整段头可能就是一张完整 JPEG
        if (StaDirectRead.isCompleteJpeg(head)) return logThumb(handle, "whole_head", head, head.size, ranges.size)
        val generated = partialImage(handle)
        return logThumb(handle, if (generated != null) "9530" else "miss", generated, head.size, ranges.size)
    }

    /** 高清：最大的内嵌 JPEG（NEF 全尺寸预览 / JPEG 的 MPF 大预览）→ 0x9530 → null。 */
    suspend fun hdPreview(handle: Int): ByteArray? {
        val head = head(handle) ?: return null
        val info = StaDirectRead.parseHead(head)
        rememberName(handle, info)
        val pick = info.embeddedJpegs.firstOrNull { (it.last - it.first + 1) <= MAX_EMBEDDED_BYTES }
        val jpeg = pick?.let { readRange(handle, it, head) }?.takeIf { StaDirectRead.isJpeg(it) }
            ?: partialImage(handle)
        if (hdLogged.getAndIncrement() < LOG_SAMPLE) {
            eventLogger.event(
                "sta_hd",
                "handle" to "0x%08X".format(handle),
                "src" to when {
                    jpeg == null -> "miss"
                    pick != null && jpeg.size.toLong() == pick.last - pick.first + 1 -> "embedded"
                    else -> "9530"
                },
                "bytes" to (jpeg?.size ?: 0),
                "ranges" to info.embeddedJpegs.size
            )
        }
        return jpeg
    }

    /**
     * 0x9530 GetPartialImage(h, type, offset, 10240)：机身解码生成的预览，按块循环取，
     * 响应参数[1] 是这张预览的总长度。可用于高清预览（含 RAW）。
     * 先试 type=0 再试 1；任一次被拒就记下本会话不可用，不再浪费往返。
     */
    private suspend fun partialImage(handle: Int): ByteArray? {
        if (partialImageOk == false) return null
        val ops = ptp.lastSupportedOperations
        if (ops != null && !ops.contains(PtpConstants.OP_NIKON_GET_PARTIAL_IMAGE)) {
            partialImageOk = false
            return null
        }
        for (type in listOf(0, 1)) {
            val out = java.io.ByteArrayOutputStream()
            var total = -1L
            var failed = false
            var firstCode = ""
            while (total < 0 || out.size() < total) {
                val r = withTimeoutOrNull(CMD_TIMEOUT_MS) {
                    ptp.sendCommandWithData(
                        PtpConstants.OP_NIKON_GET_PARTIAL_IMAGE,
                        listOf(handle, type, out.size(), PARTIAL_IMAGE_CHUNK)
                    )
                }
                if (firstCode.isEmpty()) firstCode = r.codeHex()
                val ok = r as? PtpDataResult.Success
                val chunk = ok?.data
                if (ok == null || chunk == null || chunk.isEmpty()) { failed = true; break }
                if (total < 0) {
                    total = (ok.response.parameters.getOrNull(1)?.toLong()?.and(0xFFFFFFFFL)) ?: -1L
                    if (total <= 0 || total > PARTIAL_IMAGE_MAX) { failed = true; break }
                }
                out.write(chunk)
            }
            val bytes = out.toByteArray()
            if (!failed && StaDirectRead.isJpeg(bytes)) {
                partialImageOk = true
                return bytes
            }
            if (partialImageOk == null && type == 1) {
                partialImageOk = false
                eventLogger.event("sta_9530", "ok" to false, "code" to firstCode, "handle" to "0x%08X".format(handle))
            }
        }
        return null
    }

    private fun rememberName(handle: Int, info: StaDirectRead.HeadInfo) {
        if (resolvedNames.containsKey(handle)) return
        val format = StaDirectRead.formatFromHandle(handle) ?: return
        StaDirectRead.nameFromNumber(info.fileNumber, format, handle)?.let { resolvedNames[handle] = it }
    }

    /**
     * 下载落盘前补真实文件名：缓存 → 读文件头里的卡内编号 → 原样。
     * 只在占位名时才读头（一次 256KB 往返，相对整张原图可以忽略）。
     */
    suspend fun resolveFileName(file: CameraFile): String {
        resolvedNames[file.handle]?.let { return it }
        val format = StaDirectRead.formatFromHandle(file.handle) ?: return file.fileName
        if (file.fileName != StaDirectRead.placeholderName(file.handle, format)) return file.fileName
        val head = head(file.handle, file.size) ?: return file.fileName
        val info = StaDirectRead.parseHead(head)
        rememberName(file.handle, info)
        val name = resolvedNames[file.handle] ?: return file.fileName
        eventLogger.event("sta_name", "handle" to "0x%08X".format(file.handle), "name" to name)
        return name
    }

    private fun logThumb(handle: Int, src: String, jpeg: ByteArray?, headBytes: Int, ranges: Int = 0): ByteArray? {
        if (jpeg == null) thumbMiss.incrementAndGet()
        if (thumbLogged.getAndIncrement() < LOG_SAMPLE || (jpeg == null && thumbMiss.get() <= LOG_SAMPLE)) {
            eventLogger.event(
                "sta_thumb",
                "handle" to "0x%08X".format(handle),
                "src" to src,
                "bytes" to (jpeg?.size ?: 0),
                "head" to headBytes,
                "ranges" to ranges,
                "miss_total" to thumbMiss.get()
            )
        }
        return jpeg
    }

    private fun PtpDataResult?.codeHex(): String = when (this) {
        null -> "timeout"
        is PtpDataResult.Success -> "0x2001"
        is PtpDataResult.Failure -> "0x%x".format(response.responseCode)
    }

    /** PTP 字符串：u8 字符数（含结尾 0）+ UTF-16LE。 */
    private fun readPtpString(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val n = data[0].toInt() and 0xFF
        if (n == 0 || data.size < 1 + n * 2) return null
        return String(data, 1, n * 2, Charsets.UTF_16LE).trimEnd('\u0000')
    }
}

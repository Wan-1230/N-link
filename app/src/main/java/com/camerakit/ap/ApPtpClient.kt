package com.camerakit.ap

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton

/**
 * PTP/IP (ISO 15740 over TCP) client for Nikon cameras.
 *
 * Wire format follows the public PTP/IP specification and compatibility
 * observations from open-source PTP protocol implementations, which is
 * proven against Nikon hardware:
 * - Every multi-byte field is LITTLE-ENDIAN, both in the PTP/IP packet
 *   layer (length, type) and in the embedded PTP datasets.
 * - Init Command Request: len, type=1, 16-byte GUID, UTF-16LE hostname
 *   including a NUL terminator, u16 versionMinor, u16 versionMajor.
 * - A second TCP connection carries the event channel (Init Event
 *   Request type=3, carrying the connection number from the command ack).
 * - Commands: len, type=6, u32 dataPhaseInfo, u16 opcode,
 *   u32 transactionId, params.
 * - Responses: type=7 (u16 respCode, u32 transactionId, params).
 * - Data phase: type=9 start (u64 total length), type=10 chunks,
 *   type=12 end.
 */
@Singleton
class ApPtpClient(private val socketFactory: (String) -> Socket = { Socket() }) {

    companion object {
        const val PTP_IP_PORT = 15740
        private const val TAG = "ApPtp"

        // PTP/IP packet types
        private const val PKT_INIT_CMD_REQUEST = 1
        private const val PKT_INIT_CMD_ACK = 2
        private const val PKT_INIT_EVENT_REQUEST = 3
        private const val PKT_INIT_EVENT_ACK = 4
        private const val PKT_INIT_FAIL = 5
        private const val PKT_CMD = 6
        private const val PKT_CMD_RESPONSE = 7
        private const val PKT_EVENT = 8
        private const val PKT_START_DATA = 9
        private const val PKT_DATA = 10
        private const val PKT_END_DATA = 12

        // PTP operation codes
        const val OP_GET_DEVICE_INFO = 0x1001
        const val OP_OPEN_SESSION = 0x1002
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_GET_THUMB = 0x100A
        const val OP_START_LIVE_VIEW = 0x9201
        const val OP_END_LIVE_VIEW = 0x9202
        const val OP_LIVE_VIEW_IMAGE = 0x9203
        const val OP_CAPTURE_MEDIA = 0x9207
        const val OP_START_MOVIE = 0x920A
        const val OP_END_MOVIE = 0x920B

        // PTP response codes
        private const val RC_OK = 0x2001

        // ObjectFormat codes
        const val FMT_ASSOCIATION = 0x3001

        private const val SESSION_ID = 1
    }

    data class PtpResponse(val code: Int, val data: ByteArray) {
        val isOk: Boolean get() = code == RC_OK
    }

    data class ObjectInfo(
        val handle: Long,
        val filename: String,
        val size: Long,
        val format: Int,
        val isDirectory: Boolean,
        val captureTime: Long = 0
    )

    private var cmdSocket: Socket? = null
    private var cmdIn: DataInputStream? = null
    private var cmdOut: DataOutputStream? = null
    private var evtSocket: Socket? = null

    private val transactionId = AtomicInteger(0)
    private val ioMutex = Mutex()
    private val objectInfoCache = java.util.concurrent.ConcurrentHashMap<Long, ObjectInfo>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null
    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording = _recording.asStateFlow()
    var sessionGeneration: Long = 0
        private set
    var supportedOperations: Set<Int> = emptySet()
        private set
    private var imageFormats: Set<Int> = emptySet()
    private val _propertyChanges = MutableSharedFlow<Int>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val propertyChanges = _propertyChanges.asSharedFlow()
    var lastError: String? = null
        private set
    private val _diagnostics = MutableStateFlow("")
    val diagnostics = _diagnostics.asStateFlow()
    private fun record(message: String) {
        Log.i(TAG, message)
        _diagnostics.value = (_diagnostics.value + "\n" + message).takeLast(12000)
    }

    var cameraName: String? = null
        private set

    /** Last enumeration failure reason for UI display; null after a successful listing. */
    var lastListError: String? = null
        private set

    val isConnected: Boolean
        get() = cmdSocket?.isConnected == true && cmdSocket?.isClosed == false

    /**
     * Connect to the camera: command-channel init, event-channel init,
     * then PTP OpenSession. All three must succeed.
     */
    suspend fun connect(host: String = "192.168.1.1", port: Int = PTP_IP_PORT): kotlin.Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                disconnect()

                // --- Command channel ---
                val cmd = socketFactory(host)
                cmdSocket = cmd
                cmd.connect(InetSocketAddress(host, port), 5000)
                cmd.soTimeout = 20000
                cmd.tcpNoDelay = true
                cmdSocket = cmd
                cmdIn = DataInputStream(cmd.getInputStream())
                val out = DataOutputStream(cmd.getOutputStream())
                cmdOut = out

                out.write(buildInitCmdRequest("NikonLink"))
                out.flush()
                val cmdAck = readPacket()
                    ?: throw IOException("no response to Init Command Request")
                when (cmdAck.type) {
                    PKT_INIT_CMD_ACK -> { /* ok */ }
                    PKT_INIT_FAIL -> throw IOException("camera refused connection (InitFail)")
                    else -> throw IOException("unexpected packet type ${cmdAck.type} (expected InitCmdAck=2)")
                }
                val connNumber = readLe32(cmdAck.payload, 0)
                cameraName = readUtf16Z(cmdAck.payload, 20)
                Log.i(TAG, "InitCmdAck: connNumber=$connNumber camera=$cameraName")

                // --- Event channel (second TCP connection) ---
                val evt = socketFactory(host)
                evtSocket = evt
                evt.connect(InetSocketAddress(host, port), 5000)
                evt.soTimeout = 20000
                evt.tcpNoDelay = true
                evtSocket = evt
                val evtOut = DataOutputStream(evt.getOutputStream())
                evtOut.write(buildInitEventRequest(connNumber))
                evtOut.flush()
                val evtIn = DataInputStream(evt.getInputStream())
                val evtAck = readPacketLocked(evtIn) ?: throw IOException("事件通道无响应")
                if (evtAck.type != PKT_INIT_EVENT_ACK) {
                    throw IOException("event channel failed, type=${evtAck.type} (expected 4)")
                }
                Log.i(TAG, "Event channel established")
                evt.soTimeout = 0
                eventJob = scope.launch {
                    try {
                        while (isActive) {
                            val packet = readPacketLocked(evtIn) ?: break
                            when (packet.type) {
                                13 -> { evtOut.write(simplePacket(14)); evtOut.flush() }
                                PKT_EVENT -> {
                                    val code = readLe16(packet.payload, 0)
                                    if (code == 0x4006 && packet.payload.size >= 10) _propertyChanges.tryEmit(readLe32(packet.payload, 6))
                                    if (code == 0xC10A) _recording.value = true
                                    if (code == 0xC108 || code == 0xC105) _recording.value = false
                                    record("相机事件 0x${code.toString(16)}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive && cmdSocket === cmd) {
                            lastError = "相机连接已断开，请重新连接。若正在录像，请在相机上确认录制状态。"
                            record("事件通道关闭：${e.message}")
                            disconnect()
                        }
                    }
                }

                // --- PTP session ---
                val open = transact(OP_OPEN_SESSION, listOf(SESSION_ID))
                    ?: throw IOException("no response to OpenSession")
                if (!open.isOk) {
                    throw IOException("OpenSession failed: 0x${open.code.toString(16)}")
                }
                // Best-effort GetDeviceInfo: some firmwares expect it before enumeration.
                val devInfo = transact(OP_GET_DEVICE_INFO)
                if (devInfo?.isOk == true) {
                    supportedOperations = ApPtpWire.operations(devInfo.data)
                    imageFormats = ApPtpWire.imageFormats(devInfo.data)
                    record("相机文件格式：${imageFormats.joinToString { "0x${it.toString(16)}" }}")
                    record("$cameraName 支持 ${supportedOperations.size} 个操作：${supportedOperations.sorted().joinToString { "0x${it.toString(16)}" }}")
                } else throw IOException("无法读取相机能力：${lastError ?: devInfo?.code}")
                Log.i(
                    TAG,
                    "GetDeviceInfo: " + if (devInfo?.isOk == true) {
                        "ok (${devInfo.data.size} bytes)"
                    } else {
                        "failed (0x${devInfo?.code?.toString(16) ?: "no response"}) - continuing"
                    }
                )
                Log.i(TAG, "PTP session open, camera $cameraName ready")
                _connected.value = true
                kotlin.Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "PTP-IP connection failed", e)
                disconnect()
                kotlin.Result.failure(e)
            }
        }

    /**
     * Run one PTP operation, reassembling the data phase in memory.
     */
    suspend fun transact(opcode: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null): PtpResponse? =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val out = cmdOut ?: return@withLock null
                val input = cmdIn ?: return@withLock null
                try {
                    out.write(buildCmdPacket(opcode, params, if (dataOut == null) 1 else 2))
                    if (dataOut != null) {
                        val tid = transactionId.get() - 1
                        val start = ByteArray(12).also { writeLe32(it, 0, tid); writeLe32(it, 4, dataOut.size) }
                        out.write(packet(9, start))
                        val end = ByteArray(4 + dataOut.size).also { writeLe32(it, 0, tid); dataOut.copyInto(it, 4) }
                        out.write(packet(12, end))
                    }
                    out.flush()
                    val data = ByteArrayOutputStream()
                    val reply = ApPtpWire.receive(input, transactionId.get() - 1, data, 32L * 1024 * 1024,
                        pong = { out.write(simplePacket(14)); out.flush() })
                    lastError = if (reply.code == RC_OK) null else "操作 0x${opcode.toString(16)}：${responseMessage(reply.code)}"
                    if (opcode != OP_LIVE_VIEW_IMAGE && opcode != OP_GET_THUMB) record("操作 0x${opcode.toString(16)} → 0x${reply.code.toString(16)} (${reply.bytes} 字节)")
                    PtpResponse(reply.code, data.toByteArray())
                } catch (e: Exception) {
                    Log.e(TAG, "transact(0x${opcode.toString(16)}) failed", e)
                    lastError = "操作 0x${opcode.toString(16)} 失败：${e.message}，请重新连接相机"
                    record(lastError!!)
                    disconnect()
                    null
                }
            }
        }

    /**
     * List all still/video objects on the camera. Tries the all-storages form
     * of GetObjectHandles first; some Nikon bodies reject 0xFFFFFFFF and need
     * per-storage enumeration via GetStorageIDs, so fall back to that.
     */
    suspend fun listObjects(onProgress: (List<ObjectInfo>, Int, Int) -> Unit = { _, _, _ -> }): List<ObjectInfo> {
        lastListError = null

        val allHandles = handlesForStorage(-1)
        if (!isConnected) { lastListError = lastError ?: "相机未连接"; return emptyList() }
        if (allHandles != null) {
            val infos = readObjectInfos(allHandles, onProgress)
            if (infos.isNotEmpty()) { lastListError = if (infos.size < allHandles.size) "部分文件信息读取失败：${infos.size}/${allHandles.size}" else null; return infos }
            if (allHandles.isNotEmpty()) { lastListError = lastError ?: "读取文件信息失败"; return emptyList() }
            // Some models report success but zero objects for "all storages";
            // retry per storage before giving up.
        }

        val ids = storageIds()
        if (ids.isEmpty()) {
            if (lastListError == null) {
                lastListError = "相机未返回存储卡列表，可能拒绝了请求"
            }
            return emptyList()
        }
        Log.i(TAG, "storage IDs: ${ids.joinToString(" ") { "0x${it.toString(16)}" }}")
        var failed = false
        val handles = ids.flatMap { sid -> handlesForStorage(sid) ?: emptyList<Long>().also { failed = true } }.distinct()
        if (handles.isEmpty()) {
            lastListError = if (failed) lastError ?: "读取存储卡失败" else null
            return emptyList()
        }
        val infos = readObjectInfos(handles, onProgress)
        lastListError = if (infos.size < handles.size || failed) "部分文件读取失败：${infos.size}/${handles.size}；${lastError.orEmpty()}" else null
        return infos
    }

    private suspend fun readObjectInfos(handles: List<Long>, onProgress: (List<ObjectInfo>, Int, Int) -> Unit): List<ObjectInfo> {
        val result = mutableListOf<ObjectInfo>()
        for ((index, handle) in handles.withIndex()) {
            currentCoroutineContext().ensureActive()
            getObjectInfo(handle)?.let { result.add(it) }
            if (index % 50 == 0 || index == handles.lastIndex) onProgress(result.toList(), index + 1, handles.size)
            if (!isConnected) break
        }
        return result
    }

    /** Returns handles on one storage, or null when the camera rejected the request. */
    private suspend fun handlesForStorage(storageId: Int, format: Int = 0): List<Long>? {
        val resp = transact(OP_GET_OBJECT_HANDLES, listOf(storageId, format, 0)) ?: run {
            if (storageId == -1) lastListError = "请求无响应，连接可能已断开"
            return null
        }
        if (!resp.isOk) {
            Log.w(TAG, "GetObjectHandles(0x${storageId.toString(16)}) -> 0x${resp.code.toString(16)}")
            lastListError = "相机返回错误 0x${resp.code.toString(16)}"
            return null
        }
        return ApPtpWire.array32(resp.data).also { record("存储 0x${storageId.toUInt().toString(16)} / 格式 0x${format.toString(16)}：${it.size} 个对象") }
    }

    private suspend fun storageIds(): List<Int> {
        val resp = transact(OP_GET_STORAGE_IDS) ?: return emptyList()
        if (!resp.isOk || resp.data.size < 4) {
            Log.w(TAG, "GetStorageIDs failed: 0x${resp.code.toString(16)}")
            return emptyList()
        }
        return ApPtpWire.array32(resp.data).map { it.toInt() }
    }

    /** Fetch and parse the ObjectInfo dataset for one handle. */
    suspend fun getObjectInfo(handle: Long): ObjectInfo? {
        objectInfoCache[handle]?.let { return it }
        val resp = transact(OP_GET_OBJECT_INFO, listOf(handle.toInt())) ?: return null
        if (!resp.isOk) return null
        val d = resp.data
        if (d.size < 53) return null
        val format = readLe16(d, 4)
        val size = readLe32(d, 8).toLong() and 0xFFFFFFFFL
        val nameLen = d[52].toInt() and 0xFF
        val name = if (nameLen > 0 && 53 + nameLen * 2 <= d.size) {
            buildString {
                for (i in 0 until nameLen) {
                    val c = readLe16(d, 53 + i * 2)
                    if (c == 0) break
                    append(c.toChar())
                }
            }
        } else ""
        return ObjectInfo(
            handle = handle,
            filename = name.ifEmpty { "OBJ_%04X".format(handle) },
            size = size,
            format = format,
            isDirectory = format == FMT_ASSOCIATION,
            captureTime = runCatching {
                val next = ApPtpWire.string(d, 52).second
                val date = ApPtpWire.string(d, next).first
                java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US).parse(date.take(15))?.time ?: 0
            }.getOrDefault(0)
        ).also { objectInfoCache[handle] = it }
    }

    /**
     * Download one object, streaming chunks to [sink] without buffering the
     * whole file in memory.
     */
    suspend fun downloadObject(handle: Long, sink: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val out = cmdOut ?: return@withLock false
                val input = cmdIn ?: return@withLock false
                try {
                    sink.use { s ->
                        out.write(buildCmdPacket(OP_GET_OBJECT, listOf(handle.toInt())))
                        out.flush()
                        val reply = ApPtpWire.receive(input, transactionId.get() - 1, s, onProgress = onProgress,
                            pong = { out.write(simplePacket(14)); out.flush() })
                        lastError = if (reply.code == RC_OK) null else responseMessage(reply.code)
                        record("下载 handle=$handle ${reply.bytes} 字节 → 0x${reply.code.toString(16)}")
                        reply.code == RC_OK
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "downloadObject($handle) failed", e)
                    lastError = "传输中断：${e.message}"
                    disconnect()
                    false
                }
            }
        }

    fun disconnect() {
        sessionGeneration++
        _connected.value = false
        _recording.value = false
        eventJob?.cancel()
        eventJob = null
        try { cmdIn?.close() } catch (_: Exception) {}
        try { cmdOut?.close() } catch (_: Exception) {}
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { evtSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        cmdIn = null
        cmdOut = null
        evtSocket = null
        cameraName = null
        supportedOperations = emptySet()
        imageFormats = emptySet()
        objectInfoCache.clear()
        albumIndex = null
        albumQuery = null
        transactionId.set(0)
    }

    private fun simplePacket(type: Int) = ByteArray(8).also { writeLe32(it, 0, 8); writeLe32(it, 4, type) }
    private fun packet(type: Int, payload: ByteArray) = ByteArray(8 + payload.size).also {
        writeLe32(it, 0, it.size); writeLe32(it, 4, type); payload.copyInto(it, 8)
    }

    suspend fun property(code: Int): CameraProperty? {
        val response = transact(0x1014, listOf(code)) ?: return null
        if (!response.isOk) return null
        return CameraProperty.decode(response.data).also {
            record("参数 0x${code.toString(16)}：${it.current} (${it.label()})，可写=${it.writable}，候选=${it.choices().size}")
        }
    }

    suspend fun setProperty(property: CameraProperty, value: Long): kotlin.Result<CameraProperty> = runCatching {
        val response = transact(0x1016, listOf(property.code), property.encode(value)) ?: throw IOException(lastError)
        if (!response.isOk) throw IOException(responseMessage(response.code))
        this@ApPtpClient.property(property.code) ?: throw IOException("参数已发送，无法读取相机确认值")
    }

    suspend fun propertyValue(property: CameraProperty): CameraProperty? {
        val response = transact(0x1015, listOf(property.code)) ?: return null
        if (!response.isOk) return null
        return property.copy(current = CameraProperty.scalar(response.data, property.type))
    }

    data class ObjectPage(val objects: List<ObjectInfo>, val nextOffset: Int, val total: Int)
    private var albumIndex: AlbumIndex? = null
    private var albumQuery: AlbumQuery? = null
    suspend fun browsePage(offset: Int, refresh: Boolean = false, query: AlbumQuery = AlbumQuery()): ObjectPage {
        if (recording.value) throw IOException("相机正在录像，请先停止录制再浏览存储卡")
        if (refresh || albumIndex == null || albumQuery != query) {
            val groups = mutableListOf<List<Long>>()
            val formats = imageFormats.filter { it != 0 && it != FMT_ASSOCIATION }.toSet().ifEmpty { setOf(0x3801, 0x3802, 0x300D, 0xB982) }
            for (storage in storageIds().ifEmpty { listOf(-1) }) {
                val all = handlesForStorage(storage) ?: throw IOException(lastError ?: "无法读取存储卡")
                val covered = mutableSetOf<Long>()
                for (format in formats) {
                    currentCoroutineContext().ensureActive()
                    val handles = handlesForStorage(storage, format) ?: continue
                    val unique = handles.filter { it !in covered }
                    covered.addAll(handles)
                    if (query.acceptsFormat(format) && unique.isNotEmpty()) groups.add(unique)
                }
                val remaining = all.filter { it !in covered }
                if (remaining.isNotEmpty()) groups.add(remaining)
            }
            albumIndex = AlbumIndex(groups, query.oldestFirst)
            albumQuery = query
        }
        val index = albumIndex ?: throw IOException("文件列表已失效，请重试")
        val page = index.page(offset, 36) { currentCoroutineContext().ensureActive(); getObjectInfo(it) }
        return ObjectPage(page.filter { query.accepts(it) }, offset + page.size, index.total)
    }

    suspend fun thumbnail(handle: Long): ByteArray? {
        if (OP_GET_THUMB !in supportedOperations) return null
        val r = transact(OP_GET_THUMB, listOf(handle.toInt())) ?: return null
        return if (r.isOk) ApPtpWire.jpeg(r.data) else null
    }

    suspend fun preview(handle: Long): ByteArray? {
        if (0x90C4 in supportedOperations) {
            val response = transact(0x90C4, listOf(handle.toInt()))
            if (response?.isOk == true) ApPtpWire.jpeg(response.data)?.let { return it }
        }
        return thumbnail(handle)
    }

    suspend fun startLiveView(): kotlin.Result<Unit> = runCatching {
        requireSupported(OP_START_LIVE_VIEW)
        requireSupported(OP_LIVE_VIEW_IMAGE)
        if (0x90C2 in supportedOperations) {
            val mode = transact(0x90C2, listOf(1)) ?: throw IOException(lastError)
            if (!mode.isOk && mode.code != 0xA003) throw IOException(responseMessage(mode.code))
        }
        val status = transact(0x1015, listOf(0xD1A2))
        if (status?.isOk != true || status.data.firstOrNull() != 1.toByte()) {
            val r = transact(OP_START_LIVE_VIEW) ?: throw IOException(lastError)
            if (!r.isOk && r.code != 0x2019) throw IOException(responseMessage(r.code))
        }
        delay(400)
    }

    suspend fun liveViewFrame(): kotlin.Result<ByteArray?> = runCatching {
        val r = transact(OP_LIVE_VIEW_IMAGE) ?: throw IOException(lastError)
        if (r.code == 0x2019 || r.code == 0xA004) return@runCatching null
        if (!r.isOk) throw IOException(responseMessage(r.code))
        ApPtpWire.jpeg(r.data) ?: throw IOException("相机返回的监看数据中没有完整 JPEG 图像")
    }

    suspend fun endLiveView() { if (OP_END_LIVE_VIEW in supportedOperations) transact(OP_END_LIVE_VIEW) }

    suspend fun capture(): kotlin.Result<Unit> = runCatching {
        selectShootingMode(0)
        val opcode = if (OP_CAPTURE_MEDIA in supportedOperations) OP_CAPTURE_MEDIA else 0x100E
        requireSupported(opcode)
        val params = if (opcode == OP_CAPTURE_MEDIA) listOf(-1, 0) else listOf(0, 0)
        var r = transact(opcode, params) ?: throw IOException(lastError)
        if (opcode == OP_CAPTURE_MEDIA && r.code == 0x2006) {
            r = transact(opcode, listOf(-1)) ?: throw IOException(lastError)
        }
        if (!r.isOk) throw IOException(responseMessage(r.code))
    }

    suspend fun movie(start: Boolean): kotlin.Result<Unit> = runCatching {
        val opcode = if (start) OP_START_MOVIE else OP_END_MOVIE
        requireSupported(opcode)
        try {
        if (start) {
            selectShootingMode(1)
            if (0x9435 in supportedOperations) {
                val mode = transact(0x9435, listOf(1)) ?: throw IOException(lastError)
                if (!mode.isOk) throw IOException(responseMessage(mode.code))
            }
            startLiveView().getOrThrow()
        }
        val r = transact(opcode) ?: throw IOException(lastError)
        if (!r.isOk) {
            val reason = if (start) movieProhibitReason() else null
            throw IOException(reason ?: responseMessage(r.code))
        }
        _recording.value = start
        if (!start && 0x9435 in supportedOperations) {
            delay(500)
            transact(0x9435, listOf(0))
        }
        } catch (e: Exception) {
            if (start && !_recording.value && isConnected && 0x9435 in supportedOperations) {
                withContext(NonCancellable) { transact(0x9435, listOf(0)) }
            }
            throw e
        }
    }

    /** Nikon's live-view selector gates movie capture independently of application mode. */
    private suspend fun selectShootingMode(value: Long) {
        val selector = property(0xD1A6) ?: return
        if (selector.current == value) return
        if (!selector.writable) throw IOException(if (value == 1L) "请在相机上切到视频模式后重试" else "请在相机上切到照片模式后重试")
        val status = transact(0x1015, listOf(0xD1A2))
        val wasLive = status?.isOk == true && status.data.firstOrNull() == 1.toByte()
        if (wasLive) endLiveView()
        try {
            val confirmed = setProperty(selector, value).getOrThrow()
            if (confirmed.current != value) throw IOException("相机未切换到${if (value == 1L) "视频" else "照片"}模式")
        } finally {
            if (wasLive && isConnected) withContext(NonCancellable) { startLiveView() }
        }
    }

    private suspend fun movieProhibitReason(): String? {
        val property = transact(0x1015, listOf(0xD0A4)) ?: return null
        if (!property.isOk || property.data.size < 4) return null
        val condition = ApPtpWire.i32(property.data, 0)
        record("录像限制条件：0x${condition.toUInt().toString(16)}")
        val reasons = mapOf(0 to "未插入存储卡", 1 to "存储卡错误", 2 to "存储卡未格式化",
            3 to "存储卡已满", 9 to "相机正在写入缓存", 10 to "相机已在录制", 11 to "存储卡写保护",
            12 to "请先退出放大取景", 13 to "请在相机上切换到视频模式", 14 to "相机未进入录像应用模式")
        return reasons.filterKeys { condition and (1 shl it) != 0 }.values.takeIf { it.isNotEmpty() }?.joinToString("；")
    }

    private fun requireSupported(opcode: Int) {
        if (opcode !in supportedOperations) throw IOException("相机在当前 Wi-Fi 模式下未提供此功能（0x${opcode.toString(16)}）")
    }

    private fun responseMessage(code: Int): String = when (code) {
        0x2005 -> "当前连接模式不支持该操作"
        0x200F -> "访问被拒绝，请检查相机连接模式"
        0x2019 -> "相机忙，请稍后重试"
        0x2009 -> "文件句柄已失效，请刷新相册"
        0x201D -> "参数被相机拒绝"
        0x2006 -> "相机不接受这组拍摄参数"
        0xA004 -> "相机当前状态不允许该操作"
        0xA00B -> "相机未进入实时取景状态"
        else -> "相机错误 0x${code.toString(16)}"
    }

    // --- Packet reading ---

    private class Packet(val type: Int, val payload: ByteArray)

    /** Must be called with [ioMutex] held. */
    private fun readPacketLocked(input: DataInputStream): Packet? {
        val header = ByteArray(8)
        input.readFully(header)
        val len = readLe32(header, 0)
        val type = readLe32(header, 4)
        if (len < 8 || len > 64 * 1024 * 1024) {
            Log.e(TAG, "bogus packet length $len (type $type)")
            return null
        }
        val payload = ByteArray(len - 8)
        input.readFully(payload)
        return Packet(type, payload)
    }

    /** Reads one packet from the command channel (used during connect). */
    private fun readPacket(): Packet? {
        val input = cmdIn ?: return null
        return readPacketLocked(input)
    }

    // --- Packet building (all little-endian) ---

    private fun buildInitCmdRequest(hostName: String): ByteArray {
        // GUID(16) + UTF-16LE hostname incl. NUL + u16 versionMinor + u16 versionMajor
        val nameStr = hostName.toByteArray(Charsets.UTF_16LE)
        val nameBytes = nameStr + byteArrayOf(0, 0) // terminating NUL in UTF-16
        val packetLen = 8 + 16 + nameBytes.size + 4
        val p = ByteArray(packetLen)
        writeLe32(p, 0, packetLen)
        writeLe32(p, 4, PKT_INIT_CMD_REQUEST)
        repeat(16) { i -> p[8 + i] = (Math.random() * 256).toInt().toByte() }
        System.arraycopy(nameBytes, 0, p, 24, nameBytes.size)
        writeLe16(p, 24 + nameBytes.size, 0) // versionMinor
        writeLe16(p, 24 + nameBytes.size + 2, 1) // versionMajor
        return p
    }

    private fun buildInitEventRequest(connectionNumber: Int): ByteArray {
        val p = ByteArray(12)
        writeLe32(p, 0, 12)
        writeLe32(p, 4, PKT_INIT_EVENT_REQUEST)
        writeLe32(p, 8, connectionNumber)
        return p
    }

    private fun buildCmdPacket(opcode: Int, params: List<Int>, phase: Int = 1): ByteArray {
        // len, type=6, u32 dataPhaseInfo, u16 opcode, u32 transId, params
        val p = ByteArray(18 + params.size * 4)
        writeLe32(p, 0, p.size)
        writeLe32(p, 4, PKT_CMD)
        writeLe32(p, 8, phase)
        writeLe16(p, 12, opcode)
        writeLe32(p, 14, transactionId.getAndIncrement())
        params.forEachIndexed { i, v -> writeLe32(p, 18 + i * 4, v) }
        return p
    }

    // --- Little-endian helpers ---

    private fun readLe16(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(d: ByteArray, o: Int): Int =
        (d[o].toInt() and 0xFF) or
            ((d[o + 1].toInt() and 0xFF) shl 8) or
            ((d[o + 2].toInt() and 0xFF) shl 16) or
            ((d[o + 3].toInt() and 0xFF) shl 24)

    private fun readLe64(d: ByteArray, o: Int): Long =
        (readLe32(d, o).toLong() and 0xFFFFFFFFL) or
            ((readLe32(d, o + 4).toLong() and 0xFFFFFFFFL) shl 32)

    private fun writeLe16(d: ByteArray, o: Int, v: Int) {
        d[o] = (v and 0xFF).toByte()
        d[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeLe32(d: ByteArray, o: Int, v: Int) {
        d[o] = (v and 0xFF).toByte()
        d[o + 1] = ((v shr 8) and 0xFF).toByte()
        d[o + 2] = ((v shr 16) and 0xFF).toByte()
        d[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun readUtf16Z(d: ByteArray, o: Int): String = buildString {
        var i = o
        while (i + 1 < d.size) {
            val c = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
            if (c == 0) break
            append(c.toChar())
            i += 2
        }
    }
}

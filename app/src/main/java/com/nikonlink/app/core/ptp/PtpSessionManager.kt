package com.nikonlink.app.core.ptp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PTP/IP 会话管理器
 *
 * PRD 4.1: 基于 ISO 15740 标准，实现命令请求（Command）、数据流（Data）、事件通知（Event）三通道
 * PRD 5.1: 照片传输速率 > 10MB/s (WiFi 5GHz)
 */
@Singleton
class PtpSessionManager @Inject constructor(
    private val identityStore: PtpClientIdentity
) {

    companion object {
        private const val TAG = "PtpSession"
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 30000
        private const val PAIRING_TIMEOUT_MS = 60000L
        private const val KEEP_ALIVE_INTERVAL_MS = 15000L
        private const val EVENT_PING_TIMEOUT_MS = 1500
    }

    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandOutput: java.io.OutputStream? = null
    private var commandInput: java.io.InputStream? = null
    private var eventOutput: java.io.OutputStream? = null
    private var eventInput: java.io.InputStream? = null

    private val transactionId = AtomicInteger(0)
    private val commandMutex = Mutex()
    private var sessionId: Int = 0
    private var scope: CoroutineScope? = null
    private var keepAliveJob: Job? = null
    private var eventListenerJob: Job? = null
    private val clientGuid = identityStore.clientGuid
    private val clientName = identityStore.clientName

    private val _sessionState = MutableStateFlow(PtpSessionState.DISCONNECTED)
    val sessionState: StateFlow<PtpSessionState> = _sessionState.asStateFlow()

    private val _events = MutableSharedFlow<PtpEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PtpEvent> = _events.asSharedFlow()

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    fun stop() {
        keepAliveJob?.cancel()
        eventListenerJob?.cancel()
        closeSession()
        scope = null
    }

    /**
     * 建立 PTP/IP 连接（双 Socket：Command + Event）
     */
    suspend fun connect(
        host: String,
        port: Int,
        pairingMode: Boolean = false,
        onWifiConnected: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (isConnected()) return@withContext true
        try {
            closeSession()
            _sessionState.value = PtpSessionState.CONNECTING
            Timber.tag(TAG).i("Connecting to $host:$port")

            val readTimeout = if (pairingMode) {
                PAIRING_TIMEOUT_MS.toInt()
            } else {
                READ_TIMEOUT_MS
            }

            // 建立 Command 通道
            commandSocket = Socket().apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                soTimeout = readTimeout
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 4 * 1024 * 1024  // mirror WMU's enlarged TCP window
                sendBufferSize = 1024 * 1024
            }
            // WiFi 到相机端口的 TCP 连接已建立，此时相机端会进入配对确认界面
            onWifiConnected?.invoke()
            commandOutput = commandSocket!!.getOutputStream()
            commandInput = commandSocket!!.getInputStream()

            // 发送初始化命令
            val initPacket = InitCommandPacket(
                clientGuid = clientGuid,
                clientName = clientName
            )
            sendPacket(initPacket)

            // 等待初始化响应
            val response = PtpPacket.fromStream(commandInput!!)
            if (response !is InitResponsePacket) {
                Timber.tag(TAG).e("Unexpected init response: $response")
                _sessionState.value = PtpSessionState.ERROR
                return@withContext false
            }

            Timber.tag(TAG).i("Init response: server=${response.serverName}, session=${response.sessionId}")

            // 建立 Event 通道
            eventSocket = Socket().apply {
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                // 配对模式下相机可能等待用户按 OK，超时后由上层重试
                soTimeout = if (pairingMode) readTimeout else 0
                keepAlive = true
                receiveBufferSize = 4 * 1024 * 1024
            }
            eventInput = eventSocket!!.getInputStream()
            eventOutput = eventSocket!!.getOutputStream()

            // 发送事件通道初始化请求
            eventOutput!!.write(InitEventRequestPacket(response.sessionId).toBytes())
            eventOutput!!.flush()

            // 等待事件通道初始化确认
            val eventResponse = PtpPacket.fromStream(eventInput!!)
            if (eventResponse !is InitEventAckPacket) {
                Timber.tag(TAG).e("Unexpected event init response: $eventResponse")
                _sessionState.value = PtpSessionState.ERROR
                return@withContext false
            }

            // 参考影犀日志: EventAck 后客户端主动发送 PING，收到 PONG 才继续。
            // 部分相机在缺少这一握手时会把连接判定为失败，并在数秒后断开。
            eventSocket?.soTimeout = EVENT_PING_TIMEOUT_MS
            runCatching {
                eventOutput?.write(PingPacket.toBytes())
                eventOutput?.flush()
                val pong = PtpPacket.fromStream(eventInput!!)
                if (pong is PongPacket) {
                    Timber.tag(TAG).i("Event ping answered with Pong")
                } else {
                    Timber.tag(TAG).w("Event ping did not get Pong (${pong?.type})")
                }
            }.onFailure {
                Timber.tag(TAG).w("Event ping timed out: ${it.message}")
            }

            // Fix P0-2: 与 Nikon_connect 一致，OpenSession 前先 GetDeviceInfo，
            // 让相机识别客户端并在屏幕显示连接状态
            val deviceInfo = sendCommandWithData(PtpConstants.OP_GET_DEVICE_INFO)
            if (deviceInfo is PtpDataResult.Success) {
                Timber.tag(TAG).i("GetDeviceInfo OK (${deviceInfo.data.size} bytes) before OpenSession")
            } else {
                Timber.tag(TAG).w("GetDeviceInfo before OpenSession not OK (non-fatal)")
            }

            // Fix P0-2: OpenSession 参数固定为 1（标准会话 ID），而非 connectionNumber
            val openResult = sendCommand(PtpConstants.OP_OPEN_SESSION, listOf(1))
            if (!openResult.isOk) {
                Timber.tag(TAG).e("Failed to open session: 0x${openResult.responseCode.toString(16)}")
                _sessionState.value = PtpSessionState.ERROR
                return@withContext false
            }
            sessionId = response.sessionId

            // 参考影犀日志: OpenSession 后先排空 Nikon GetEventEx (0x90C7)，
            // 清除相机缓存的旧事件，避免干扰后续异步事件监听
            runCatching {
                sendCommand(PtpConstants.OP_NIKON_CHECK_EVENT, listOf(0xFFFFFFFF.toInt(), 0, 0))
                Timber.tag(TAG).i("GetEventEx drain after OpenSession")
            }

            // 缩短断联检测时间：会话建立后命令通道超时降至 10s
            commandSocket?.soTimeout = 10000
            eventSocket?.soTimeout = 0
            _sessionState.value = PtpSessionState.CONNECTED
            startKeepAlive()
            startEventListener()

            Timber.tag(TAG).i("✓ PTP session established (session=$sessionId)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Connection failed")
            _sessionState.value = PtpSessionState.ERROR
            closeSession()
            false
        }
    }

    /**
     * 发送 PTP 命令并等待响应
     */
    suspend fun sendCommand(operationCode: Int, parameters: List<Int> = emptyList()): CommandResponsePacket {
        val txId = transactionId.incrementAndGet()
        val packet = CommandRequestPacket(txId, operationCode, parameters)

        return withContext(Dispatchers.IO) {
            commandMutex.withLock {
                try {
                    // 回退说明: 上一版在此加了 sessionState != CONNECTED 拦截，
                    // 但 connect() 握手阶段状态还是 CONNECTING，导致 OpenSession 被拦截、无法连接。
                    // 现仅保留空流防护（防断联后 NPE），不再检查会话状态。
                    if (commandOutput == null || commandInput == null) {
                        Timber.tag(TAG).w("sendCommand skipped (stream closed): op=0x${operationCode.toString(16)}")
                        return@withLock CommandResponsePacket(txId, PtpConstants.RESPONSE_GENERAL_ERROR)
                    }
                    sendPacket(packet)
                    // 等待命令响应（可能先收到数据包）
                    var response: PtpPacket?
                    do {
                        response = PtpPacket.fromStream(commandInput!!)
                        if (response is CommandResponsePacket && response.transactionId != txId) {
                            Timber.tag(TAG).w(
                                "Ignoring stale response: expected tx=$txId, got tx=${response.transactionId}"
                            )
                            response = null
                        }
                    } while (response != null && response !is CommandResponsePacket)

                    (response as? CommandResponsePacket)
                        ?: CommandResponsePacket(txId, PtpConstants.RESPONSE_GENERAL_ERROR)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Command failed: op=0x${operationCode.toString(16)}")
                    CommandResponsePacket(txId, PtpConstants.RESPONSE_GENERAL_ERROR)
                }
            }
        }
    }

    /**
     * 发送命令并接收数据（用于获取文件、缩略图等）
     * PRD 2.1: 照片传输速率 > 10MB/s
     */
    suspend fun sendCommandWithData(
        operationCode: Int,
        parameters: List<Int> = emptyList(),
        onProgress: ((Long, Long) -> Unit)? = null
    ): PtpDataResult {
        val txId = transactionId.incrementAndGet()
        val packet = CommandRequestPacket(txId, operationCode, parameters)

        return withContext(Dispatchers.IO) {
            commandMutex.withLock {
                try {
                    sendPacket(packet)

                    val dataChunks = mutableListOf<ByteArray>()
                    var declaredSize = 0L
                    var receivedSize = 0L
                    var response: CommandResponsePacket? = null

                    // 读取数据包直到收到本事务的响应
                    while (true) {
                        val pkt = PtpPacket.fromStream(commandInput!!) ?: break
                        when (pkt) {
                            is StartDataPacket -> {
                                declaredSize = pkt.totalSize.toLong()
                                onProgress?.invoke(0L, declaredSize)
                            }
                            is DataPacket -> {
                                dataChunks.add(pkt.data)
                                receivedSize += pkt.data.size
                                onProgress?.invoke(receivedSize, progressTotal(receivedSize, declaredSize))
                            }
                            is EndDataPacket -> {
                                dataChunks.add(pkt.data)
                                receivedSize += pkt.data.size
                                onProgress?.invoke(receivedSize, progressTotal(receivedSize, declaredSize))
                            }
                            is CommandResponsePacket -> {
                                if (pkt.transactionId == txId) {
                                    response = pkt
                                    break
                                }
                                Timber.tag(TAG).w(
                                    "Ignoring stale response: expected tx=$txId, got tx=${pkt.transactionId}"
                                )
                            }
                            else -> { /* skip unknown */ }
                        }
                    }

                    val finalResponse = response
                        ?: CommandResponsePacket(txId, PtpConstants.RESPONSE_GENERAL_ERROR)
                    if (finalResponse.isOk) {
                        val combinedData = ByteArray(receivedSize.toInt())
                        var offset = 0
                        dataChunks.forEach { chunk ->
                            System.arraycopy(chunk, 0, combinedData, offset, chunk.size)
                            offset += chunk.size
                        }
                        PtpDataResult.Success(combinedData, finalResponse)
                    } else {
                        // 全链路优化: 数据命令被拒时必须记录响应码，
                        // 否则下载失败静默无日志可查
                        Timber.tag(TAG).w(
                            "Data command rejected: op=0x${operationCode.toString(16)} " +
                                "code=0x${finalResponse.responseCode.toString(16)} recv=$receivedSize"
                        )
                        PtpDataResult.Failure(finalResponse)
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Data command failed: op=0x${operationCode.toString(16)}")
                    PtpDataResult.Failure(CommandResponsePacket(txId, PtpConstants.RESPONSE_GENERAL_ERROR))
                }
            }
        }
    }

    private fun progressTotal(received: Long, declared: Long): Long {
        return if (declared > 0 && declared != 0xFFFFFFFFL && declared >= received) declared else 0L
    }

    /**
     * 获取设备信息
     */
    suspend fun getDeviceInfo(): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_GET_DEVICE_INFO)
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 获取存储 ID 列表
     */
    suspend fun getStorageIds(): List<Int> {
        val result = sendCommandWithData(PtpConstants.OP_GET_STORAGE_IDS)
        if (result is PtpDataResult.Success) {
            val buffer = java.nio.ByteBuffer.wrap(result.data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            return (0 until count).map { buffer.int }
        }
        return emptyList()
    }

    /**
     * 获取单个存储卡的 PTP StorageInfo 原始数据。
     */
    suspend fun getStorageInfo(storageId: Int): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_GET_STORAGE_INFO, listOf(storageId))
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 获取对象句柄列表（照片列表）
     * PRD 2.1: 浏览相机存储卡照片列表
     */
    suspend fun getObjectHandles(storageId: Int, formatCode: Int = 0, associationHandle: Int = 0): List<Int> {
        val result = sendCommandWithData(
            PtpConstants.OP_GET_OBJECT_HANDLES,
            listOf(storageId, formatCode, associationHandle)
        )
        if (result is PtpDataResult.Success) {
            val buffer = java.nio.ByteBuffer.wrap(result.data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            return (0 until count).map { buffer.int }
        }
        return emptyList()
    }

    /**
     * 获取对象信息（文件元数据）
     */
    suspend fun getObjectInfo(handle: Int): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, listOf(handle))
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 获取缩略图
     * PRD 2.1: 支持缩略图预览
     */
    suspend fun getThumbnail(handle: Int): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_GET_THUMBNAIL, listOf(handle))
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 获取完整对象（照片下载）
     * PRD 2.1: 选择性下载原图
     */
    suspend fun getObject(handle: Int, onProgress: ((Long, Long) -> Unit)? = null): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_GET_OBJECT, listOf(handle), onProgress)
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 获取部分对象（断点续传）
     * PRD 2.1: 断点续传 - 传输中断后自动从断点恢复
     */
    suspend fun getPartialObject(handle: Int, offset: Int, maxBytes: Int): ByteArray? {
        val result = sendCommandWithData(
            PtpConstants.OP_GET_PARTIAL_OBJECT,
            listOf(handle, offset, maxBytes)
        )
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 删除相机存储卡中的对象。
     */
    suspend fun deleteObject(handle: Int): Boolean {
        return sendCommand(PtpConstants.OP_DELETE_OBJECT, listOf(handle)).isOk
    }

    /**
     * 触发拍摄
     * PRD 2.2: 远程快门
     */
    suspend fun initiateCapture(): Boolean {
        // ISO 15740: InitiateCapture takes storage ID and object format (both 0 = default).
        val response = sendCommand(PtpConstants.OP_INITIATE_CAPTURE, listOf(0, 0))
        return response.isOk
    }

    /**
     * Nikon AF drive. 0x90C1 is a no-parameter toggle (mirrors libgphoto2).
     */
    suspend fun afDrive(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_AF_DRIVE)
        return response.isOk
    }

    suspend fun afDriveCancel(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_AF_DRIVE_CANCEL)
        return response.isOk
    }

    suspend fun startMovieRecording(): Boolean {
        return sendCommand(PtpConstants.OP_NIKON_START_MOVIE_REC_IN_CARD).isOk
    }

    suspend fun stopMovieRecording(): Boolean {
        return sendCommand(PtpConstants.OP_NIKON_END_MOVIE_REC).isOk
    }

    /**
     * 获取设备属性值
     * PRD 2.4: 曝光三要素实时读取
     */
    suspend fun getDevicePropValue(propCode: Int): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_GET_DEVICE_PROP_VALUE, listOf(propCode))
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 设置设备属性值
     * PRD 2.4: 参数变更实时同步至相机（< 100ms 响应）
     */
    suspend fun setDevicePropValue(propCode: Int, value: ByteArray): Boolean {
        val txId = transactionId.incrementAndGet()
        // PTP/IP 发送数据：Cmd_Request(dataPhase=2) → StartData → Data → EndData → Cmd_Response
        val cmdPacket = CommandRequestPacket(
            txId,
            PtpConstants.OP_SET_DEVICE_PROP_VALUE,
            listOf(propCode),
            dataPhase = true
        )
        return withContext(Dispatchers.IO) {
            commandMutex.withLock {
                try {
                    sendPacket(cmdPacket)
                    sendPacket(StartDataPacket(txId, value.size))
                    sendPacket(EndDataPacket(txId, value))
                    // 等待响应
                    val response = PtpPacket.fromStream(commandInput!!)
                    (response as? CommandResponsePacket)?.isOk ?: false
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Set prop failed: 0x${propCode.toString(16)}")
                    false
                }
            }
        }
    }

    /**
     * Nikon Live View 相关操作
     * PRD 2.3: 实时取景监看
     */
    suspend fun startLiveView(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
        return response.isOk
    }

    suspend fun stopLiveView(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_END_LIVE_VIEW)
        return response.isOk
    }

    suspend fun getLiveViewImage(): ByteArray? {
        val result = sendCommandWithData(PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE)
        return (result as? PtpDataResult.Success)?.data
    }

    /**
     * 关闭会话并断开连接
     */
    fun closeSession() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        eventListenerJob?.cancel()
        eventListenerJob = null
        closeSockets()
        sessionId = 0
        _sessionState.value = PtpSessionState.DISCONNECTED
        Timber.tag(TAG).i("Session closed")
    }

    private fun closeSockets() {
        try {
            commandSocket?.close()
        } catch (_: Exception) {}
        try {
            eventSocket?.close()
        } catch (_: Exception) {}
        commandSocket = null
        eventSocket = null
        commandOutput = null
        commandInput = null
        eventOutput = null
        eventInput = null
    }

    fun isConnected(): Boolean = _sessionState.value == PtpSessionState.CONNECTED

    @Synchronized
    private fun sendPacket(packet: PtpPacket) {
        commandOutput?.write(packet.toBytes())
        commandOutput?.flush()
    }

        /** 事件通道最近活动时间（用于保活容错判定） */
    @Volatile
    private var lastEventActivityAt = 0L

    private fun startKeepAlive() {
        lastEventActivityAt = System.currentTimeMillis()
        keepAliveJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEP_ALIVE_INTERVAL_MS)
                // 参考影犀日志: 保活心跳走 event 通道 Ping(packetType=13)，
                // 相机回 Pong(type=14) 由 event 监听器处理并刷新 lastEventActivityAt；
                // 影犀全程不发 DeviceReady 命令，命令通道完全留给业务。
                val pingOk = try {
                    eventOutput?.write(PingPacket.toBytes())
                    eventOutput?.flush()
                    true
                } catch (e: Exception) {
                    Timber.tag(TAG).w("keepAlive ping write failed: ${e.message}")
                    false
                }
                if (!pingOk) {
                    markLinkError()
                    break
                }
                // 参考影犀日志: 相机会在长时间空闲后主动断链（约3.5分钟）；
                // event 通道 60 秒无任何活动即判定链路已死，交给上层恢复流程
                if (System.currentTimeMillis() - lastEventActivityAt > 60_000) {
                    Timber.tag(TAG).w("keepAlive: no event-channel activity for 60s, link dead")
                    markLinkError()
                    break
                }
            }
        }
    }

    private fun markLinkError() {
        keepAliveJob?.cancel()
        eventListenerJob?.cancel()
        closeSockets()
        sessionId = 0
        _sessionState.value = PtpSessionState.ERROR
    }

    private fun startEventListener() {
        eventListenerJob = scope?.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val packet = PtpPacket.fromStream(eventInput!!) ?: break
                    lastEventActivityAt = System.currentTimeMillis()
                    when (packet) {
                        is EventResponsePacket -> {
                            val event = PtpEvent(
                                eventCode = packet.eventCode,
                                transactionId = packet.transactionId,
                                parameters = packet.parameters
                            )
                            _events.tryEmit(event)
                            Timber.tag(TAG).d("Event received: code=0x${packet.eventCode.toString(16)}")
                        }
                        is PingPacket -> {
                            // Nikon sends ProbeRequest on the event channel to keep the link
                            // alive; it drops the session if the client does not answer.
                            eventOutput?.write(PongPacket.toBytes())
                            eventOutput?.flush()
                            Timber.tag(TAG).v("Camera probe answered with Pong")
                        }
                        is PongPacket -> {
                            Timber.tag(TAG).v("Pong received")
                        }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Timber.tag(TAG).w("Event listener error: ${e.message}")
                    markLinkError()
                }
            }
        }
    }
}

/**
 * PTP 会话状态
 */
enum class PtpSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * PTP 事件
 */
data class PtpEvent(
    val eventCode: Int,
    val transactionId: Int,
    val parameters: List<Int>
)

/**
 * PTP 数据命令结果
 */
sealed class PtpDataResult {
    data class Success(val data: ByteArray, val response: CommandResponsePacket) : PtpDataResult()
    data class Failure(val response: CommandResponsePacket) : PtpDataResult()
}

package com.nikonlink.app.device.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import com.nikonlink.app.device.ptp.MtpObjectPropListParser
import com.nikonlink.app.device.ptp.MtpObjectProps
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.shared.common.AppEventLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * USB PTP 有线连接管理器
 *
 * 通过 USB OTG 线直连相机，使用 PTP over USB 协议通信。
 * 相比 BLE/WiFi 无线连接，USB 有线连接具有：
 * - 零配对：即插即用，无需 BLE 扫描/配对流程
 * - 高带宽：USB 2.0/3.0 传输速率远超 WiFi
 * - 低延迟：< 50ms 命令响应
 * - 高稳定：不受无线干扰、不存在断联问题
 *
 * PRD 补充：增加有线连接方式作为首选连接通道
 *
 * 尼康 Z50II 设置：网络菜单 → USB → MTP/PTP
 */
@Singleton
class UsbPtpManager @Inject constructor(
    private val context: Context,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "UsbPtp"
        private const val ACTION_USB_PERMISSION = "com.nikonlink.app.USB_PERMISSION"
        private const val BULK_TIMEOUT_MS = 5000

        /** 监看帧专用短超时：一帧卡顿只损失 2.5s，而非拖满 5s 后连续失败停监看 */
        private const val LV_FRAME_TIMEOUT_MS = 2500

        /** bulk IN 单次读取窗口。容器可跨多次读取到达，由事务层负责重组/流式消费 */
        private const val READ_CHUNK_SIZE = 64 * 1024

        private const val EVENT_POLL_INTERVAL_MS = 200L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkOut: UsbEndpoint? = null   // 命令/数据发送
    private var bulkIn: UsbEndpoint? = null    // 响应/数据接收
    private var interruptIn: UsbEndpoint? = null  // 事件接收

    private val transactionId = AtomicInteger(0)
    private val commandMutex = Mutex()

    /**
     * RC-13 链路观测：最近一次命令尝试/成功时间戳。
     * isAlive() = 从未发过命令，或最近一次尝试已得到成功响应。
     */
    @Volatile private var lastCommandAttemptAtMs = 0L
    @Volatile private var lastCommandOkAtMs = 0L
    @Volatile private var lvFrameOkLogged = false

    private var scope: CoroutineScope? = null
    private var eventPollJob: Job? = null
    private var keepAliveJob: Job? = null
    private var started = false

    private val _usbState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val usbState: StateFlow<UsbConnectionState> = _usbState.asStateFlow()

    /**
     * 模块 6：USB 失败的分类提示（设备页按状态展示，禁止统一报「未检测到相机」）。
     * 物理未识别 / 无权限 / 接口被占用 / 会话打开失败 / 链路超时各有独立文案；
     * 进入 CONNECTED 或用户拔出（DETACHED → disconnect）时清空。
     */
    private val _usbErrorMessage = MutableStateFlow<String?>(null)
    val usbErrorMessage: StateFlow<String?> = _usbErrorMessage.asStateFlow()

    /** 失败后的退避重连任务（1s/2s/4s 三次）；物理拔出或手动断开时撤销 */
    private var reconnectJob: Job? = null

    private val _deviceInfo = MutableStateFlow<UsbCameraInfo?>(null)
    val deviceInfo: StateFlow<UsbCameraInfo?> = _deviceInfo.asStateFlow()

    private val _events = MutableSharedFlow<UsbPtpEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<UsbPtpEvent> = _events.asSharedFlow()

    /** USB 设备插入/拔出广播 */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && UsbPtpProtocol.isNikonCamera(device.vendorId, device.productId)) {
                        Timber.tag(TAG).i("Nikon camera attached: ${device.deviceName}")
                        requestPermissionAndConnect(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Timber.tag(TAG).w("USB device detached")
                    // 物理拔出：撤销退避重连并清空错误提示（属正常断开而非故障）
                    reconnectJob?.cancel()
                    reconnectJob = null
                    _usbErrorMessage.value = null
                    disconnect()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && device != null) {
                        Timber.tag(TAG).i("USB permission granted")
                        openConnection(device)
                    } else {
                        Timber.tag(TAG).w("USB permission denied")
                        // 模块 6：无权限 ≠ 未检测到相机，单独提示
                        _usbErrorMessage.value =
                            "USB 权限被拒绝：请在弹窗中允许访问 USB 设备，或到系统设置授予"
                        _usbState.value = UsbConnectionState.PERMISSION_DENIED
                    }
                }
            }
        }
    }

    fun start(scope: CoroutineScope) {
        if (started) {
            this.scope = scope
            return
        }
        started = true
        this.scope = scope
        registerReceivers()
        // 检查是否已有相机连接
        checkExistingDevice()
        Timber.tag(TAG).i("UsbPtpManager started")
    }

    fun stop() {
        if (!started) return
        started = false
        keepAliveJob?.cancel()
        keepAliveJob = null
        eventPollJob?.cancel()
        disconnect()
        unregisterReceivers()
        scope = null
    }

    /**
     * 检查是否已有尼康相机通过 USB 连接
     */
    fun checkExistingDevice() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (UsbPtpProtocol.isNikonCamera(device.vendorId, device.productId)) {
                Timber.tag(TAG).i("Found existing Nikon camera: VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)}")
                requestPermissionAndConnect(device)
                return
            }
        }
        Timber.tag(TAG).d("No Nikon camera found on USB bus")
    }

    /**
     * 请求 USB 权限并连接
     */
    private fun requestPermissionAndConnect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openConnection(device)
        } else {
            _usbState.value = UsbConnectionState.REQUESTING_PERMISSION
            // Fix P0-3: Android 12+ 必须用 FLAG_MUTABLE，系统才能向广播附加
            // EXTRA_PERMISSION_GRANTED/EXTRA_DEVICE 结果，否则永远判定拒绝
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    /**
     * 打开 USB 连接，建立 PTP 会话。
     * 模块 6：每个失败点写入 [usbErrorMessage] 分类提示，并安排带退避的重连
     * （OTG 枚举延迟 / 相机休眠等瞬时失败 1s/2s/4s 内自动恢复）。
     */
    private fun openConnection(device: UsbDevice) {
        reconnectJob?.cancel()
        reconnectJob = null
        _usbState.value = UsbConnectionState.CONNECTING

        try {
            val connection = usbManager.openDevice(device) ?: run {
                Timber.tag(TAG).e("Failed to open USB device")
                eventLogger.event("usb_open", "ok" to false, "reason" to "openDevice_null")
                _usbErrorMessage.value =
                    "USB 设备打开失败：OTG 线/供电异常，请重新插拔数据线后重试"
                _usbState.value = UsbConnectionState.ERROR
                scheduleReconnect()
                return
            }

            // 查找 PTP 接口 (Class 0x06 = Image, SubClass 0x01 = Still Image, Protocol 0x01 = PTP)
            var ptpInterface: UsbInterface? = null
            var outEndpoint: UsbEndpoint? = null
            var inEndpoint: UsbEndpoint? = null
            var interruptEndpoint: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.interfaceSubclass == 0x01 &&
                    intf.interfaceProtocol == 0x01
                ) {
                    ptpInterface = intf
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        when {
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    ep.direction == UsbConstants.USB_DIR_OUT -> outEndpoint = ep
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> inEndpoint = ep
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                                    ep.direction == UsbConstants.USB_DIR_IN -> interruptEndpoint = ep
                        }
                    }
                    break
                }
            }

            if (ptpInterface == null || outEndpoint == null || inEndpoint == null) {
                Timber.tag(TAG).e("PTP interface/endpoints not found")
                eventLogger.event("usb_open", "ok" to false, "reason" to "no_ptp_interface")
                _usbErrorMessage.value =
                    "未识别到相机 PTP 接口：请使用数据线（非仅充电线）连接，并确认相机 USB 模式为 PTP/MTP"
                connection.close()
                _usbState.value = UsbConnectionState.ERROR
                scheduleReconnect()
                return
            }

            runCatching { connection.claimInterface(ptpInterface, true) }.onFailure { e ->
                Timber.tag(TAG).e(e, "Claim USB interface failed")
                eventLogger.event("usb_open", "ok" to false, "reason" to "claim_failed")
                _usbErrorMessage.value =
                    "USB 接口被占用：请关闭其它正在使用相机的应用后重新插拔"
                connection.close()
                _usbState.value = UsbConnectionState.ERROR
                scheduleReconnect()
                return
            }

            usbConnection = connection
            usbInterface = ptpInterface
            bulkOut = outEndpoint
            bulkIn = inEndpoint
            interruptIn = interruptEndpoint

            _deviceInfo.value = UsbCameraInfo(
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                cameraModel = UsbPtpProtocol.getCameraName(device.productId),
                serialNumber = device.serialNumber ?: "unknown"
            )
            eventLogger.event(
                "usb_open",
                "ok" to true,
                "model" to (_deviceInfo.value?.cameraModel ?: "unknown")
            )

            // 打开 PTP 会话
            scope?.launch {
                val sessionOk = openPtpSession()
                if (sessionOk) {
                    eventLogger.event("usb_session", "ok" to true)
                    // 相机名称以**机身自报的 Model** 为准：PID 映射表覆盖不全且存在
                    // 一 PID 多机型，用它做显示名必然出现「名称不正确」。
                    // 拿到 Model 即回填，拿不到就保留 PID 兜底名，行为不劣于旧版。
                    runCatching {
                        getDeviceInfo()?.let { UsbPtpProtocol.parseDeviceInfoModel(it) }
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { model ->
                        val old = _deviceInfo.value?.cameraModel
                        _deviceInfo.value = _deviceInfo.value?.copy(cameraModel = model)
                        Timber.tag(TAG).i("USB camera model from DeviceInfo: $model (pid fallback was $old)")
                    }
                    // 会话建立后向相机反馈，保持相机处于持续连接状态
                    val ready = sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
                    Timber.tag(TAG).d("USB DeviceReady response=${ready?.responseCode}")
                    // 模块 6：取流管线预初始化——会话就绪即下发 LiveView 图像配置
                    // （0xD1AC=3，与 LiveViewManager.startLiveView 同一口径），
                    // 解决「显示连接成功但监看无画面」的首帧时序竞争
                    runCatching {
                        setDevicePropValue(PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE, byteArrayOf(3))
                    }.onSuccess { ok ->
                        Timber.tag(TAG).i("USB LV pipeline pre-init 0xD1AC=3: $ok")
                    }
                    _usbErrorMessage.value = null
                    _usbState.value = UsbConnectionState.CONNECTED
                    startEventPolling()
                    startKeepAlive()
                    Timber.tag(TAG).i("✓ USB PTP connected: ${_deviceInfo.value?.cameraModel}")
                } else {
                    eventLogger.event("usb_session", "ok" to false)
                    _usbErrorMessage.value =
                        "PTP 会话打开失败：相机无响应，请点亮相机屏幕并确认 USB 模式为 PTP/MTP 后重试"
                    _usbState.value = UsbConnectionState.ERROR
                    disconnect(silent = true)
                    scheduleReconnect()
                }
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "USB connection failed")
            _usbErrorMessage.value = "USB 连接异常：${e.message ?: "未知错误"}（接口可能被占用，请重新插拔）"
            _usbState.value = UsbConnectionState.ERROR
            scheduleReconnect()
        }
    }

    /**
     * 模块 6：失败后带退避的自动重连。
     * 退避序列 1s→15s 持续尝试（覆盖「相机休眠后用户才点亮相机」的常见场景——
     * 旧版只试 3 次共 7s，相机还在睡眠就已放弃，用户只能拔线重插）。
     * 仅当设备仍在 USB 总线上时重试；物理拔出（DETACHED）或手动断开会撤销任务。
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val scope = this.scope ?: return
        reconnectJob = scope.launch {
            // ~68s 覆盖窗口：相机自动休眠最短 30s，唤醒后第一个退避点即可命中
            val delays = longArrayOf(1000, 2000, 4000, 8000, 15000, 15000, 15000)
            delays.forEachIndexed { attempt, delayMs ->
                delay(delayMs)
                if (_usbState.value == UsbConnectionState.CONNECTED ||
                    _usbState.value == UsbConnectionState.CONNECTING
                ) return@launch
                val device = usbManager.deviceList.values.firstOrNull {
                    UsbPtpProtocol.isNikonCamera(it.vendorId, it.productId)
                }
                if (device == null) {
                    Timber.tag(TAG).i("Reconnect attempt ${attempt + 1}: no camera on bus, stop")
                    return@launch
                }
                Timber.tag(TAG).i("USB reconnect attempt ${attempt + 1}/${delays.size}")
                if (usbManager.hasPermission(device)) {
                    openConnection(device)
                } else {
                    // 权限弹窗已挂起，用户批准后广播路径会继续连接
                    requestPermissionAndConnect(device)
                    return@launch
                }
                // openConnection 为异步会话建立；失败会再次 scheduleReconnect 排队
                if (_usbState.value == UsbConnectionState.CONNECTING) return@launch
            }
            // 退避窗口耗尽仍未连上：给出明确指引，用户插拔后 ATTACHED 广播会重新拉起
            if (_usbState.value != UsbConnectionState.CONNECTED &&
                _usbState.value != UsbConnectionState.CONNECTING
            ) {
                _usbErrorMessage.value =
                    "USB 自动重连未成功：请重新插拔数据线，或点亮相机屏幕后下拉刷新"
            }
        }
    }

    /**
     * 打开 PTP 会话 (OpenSession 0x1002)
     */
    private suspend fun openPtpSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendCommand(PtpConstants.OP_OPEN_SESSION, listOf(1))
            // Fix P0-3: 接受 OK 或 SESSION_ALREADY_OPEN（重复连接时相机可能已开会话）
            val ok = response?.isOk == true ||
                    response?.responseCode == PtpConstants.RESPONSE_SESSION_ALREADY_OPEN
            if (!ok) Timber.tag(TAG).e("OpenSession code=0x${response?.responseCode?.toString(16)}")
            ok
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "OpenSession failed")
            false
        }
    }

    /**
     * 发送 PTP 命令并等待响应
     *
     * @param retryOnTimeout 仅对幂等命令置 true（如保活 DeviceReady）。
     *                       快门/删除/模式切换等有副作用的命令超时后**绝不重发**——
     *                       超时只代表响应未归，相机可能已执行，重试会造成双拍/双删。
     */
    suspend fun sendCommand(
        operationCode: Int,
        params: List<Int> = emptyList(),
        retryOnTimeout: Boolean = false
    ): UsbPtpResponse? {
        val outcome = transact(
            operationCode = operationCode,
            params = params,
            allowRetry = retryOnTimeout
        )
        return outcome.response
    }

    /**
     * 发送命令并接收数据（获取文件/缩略图/设备信息等）
     *
     * @param sink 非空时数据阶段流式写入 sink（内存 O(64KB)，用于大文件下载），
     *             此时返回 ByteArray(0) 哨兵值而非完整载荷
     * @param timeoutMs 单次 bulkTransfer 读写超时
     */
    suspend fun sendCommandWithData(
        operationCode: Int,
        params: List<Int> = emptyList(),
        onProgress: ((Long, Long) -> Unit)? = null,
        sink: java.io.OutputStream? = null,
        timeoutMs: Int = BULK_TIMEOUT_MS,
        allowRetry: Boolean = true
    ): ByteArray? {
        val outcome = transact(
            operationCode = operationCode,
            params = params,
            sink = sink,
            onProgress = onProgress,
            timeoutMs = timeoutMs,
            allowRetry = allowRetry
        )
        return outcome.data
    }

    /** 一次事务的完整结果：响应 + 数据（sink 模式下 data 为哨兵空数组） */
    private class TransactOutcome(
        val response: UsbPtpResponse?,
        val data: ByteArray?,
        val receivedBytes: Long
    ) {
        companion object {
            val FAILURE = TransactOutcome(null, null, 0L)
        }
    }

    /** 事务内可恢复失败（超时/流损坏/连接关闭），由重试层决定是否 clearHalt 后重来 */
    private class TransactException(message: String) : Exception(message)

    /**
     * USB PTP 事务核心：命令（+可选数据出）→ 响应/数据入容器重组。
     *
     * 根因修复（RC1）：旧实现假设「一次 64KB bulkTransfer = 一个完整容器」，
     * 而 USB bulk IN 是无消息边界的字节流——超过 64KB 的容器（GetObject 下载、
     * 0x90C4 高清缩略图、多数 0x9203 监看帧）的续包被当垃圾丢弃、嵌在数据尾部的
     * Response 永远识别不到，导致每次必然 5s 超时且数据损坏/丢失。
     * 现按「先读 12 字节头，再按声明长度组装/流式消费」的标准做法（gphoto2 同款）：
     * - 容器跨读重组：按 header.length 继续读取直至消费完整容器
     * - 大数据容器流式直写 sink / 累积缓冲（内存 O(64KB)，不再整容器驻留）
     * - 粘连拆分：一次读取同时含数据尾部 + Response 头时正确切片
     * - txId 校验：响应/数据容器的事务号必须匹配，不符即判定流失步
     * - 失败恢复：clearHalt 两个 bulk 端点后按 allowRetry 重试一次
     */
    private suspend fun transact(
        operationCode: Int,
        params: List<Int>,
        dataOut: ByteArray? = null,
        sink: java.io.OutputStream? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
        timeoutMs: Int = BULK_TIMEOUT_MS,
        allowRetry: Boolean = false
    ): TransactOutcome = withContext(Dispatchers.IO) {
        commandMutex.withLock {
            val maxAttempts = if (allowRetry) 2 else 1
            repeat(maxAttempts) { attempt ->
                if (attempt > 0) {
                    Timber.tag(TAG).w("transact retry op=0x${operationCode.toString(16)} attempt=$attempt")
                    clearHaltBothEndpoints()
                    delay(150)
                }
                try {
                    return@withContext executeTransactOnce(
                        operationCode, params, dataOut, sink, onProgress, timeoutMs
                    )
                } catch (e: TransactException) {
                    Timber.tag(TAG).w("transact fail op=0x${operationCode.toString(16)}: ${e.message}")
                }
            }
            TransactOutcome.FAILURE
        }
    }

    /** 单次事务尝试（不含重试）。所有 bulkTransfer 均在 commandMutex 持有期间执行。 */
    private fun executeTransactOnce(
        operationCode: Int,
        params: List<Int>,
        dataOut: ByteArray?,
        sink: java.io.OutputStream?,
        onProgress: ((Long, Long) -> Unit)?,
        timeoutMs: Int
    ): TransactOutcome {
        val conn = usbConnection ?: throw TransactException("connection closed")
        val out = bulkOut ?: throw TransactException("bulk out endpoint missing")
        val inp = bulkIn ?: throw TransactException("bulk in endpoint missing")

        lastCommandAttemptAtMs = System.currentTimeMillis()
        val txId = transactionId.incrementAndGet()

        // ---- 命令阶段（+ 可选数据出阶段）----
        val command = UsbPtpProtocol.buildCommandContainer(txId, operationCode, params)
        if (conn.bulkTransfer(out, command, command.size, timeoutMs) < 0) {
            throw TransactException("bulk out failed op=0x${operationCode.toString(16)}")
        }
        if (dataOut != null) {
            val dataContainer = UsbPtpProtocol.buildDataContainer(txId, dataOut)
            if (conn.bulkTransfer(out, dataContainer, dataContainer.size, timeoutMs) < 0) {
                throw TransactException("bulk out data failed op=0x${operationCode.toString(16)}")
            }
        }

        // ---- 数据入/响应阶段：流式容器重组 ----
        var pending = ByteArray(0)          // 尚未组成完整容器的字节流尾巴
        val accumulator = if (sink == null) java.io.ByteArrayOutputStream() else null
        var receivedBytes = 0L
        var response: UsbPtpResponse? = null
        val chunk = ByteArray(READ_CHUNK_SIZE)

        fun emitPayload(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            if (sink != null) sink.write(buffer, offset, length)
            else accumulator!!.write(buffer, offset, length)
            receivedBytes += length
            onProgress?.invoke(receivedBytes, 0L)
        }

        fun consumeContainer(header: UsbPtpProtocol.ContainerHeader, source: ByteArray) {
            when (header.type) {
                UsbPtpProtocol.TYPE_DATA -> {
                    if (header.transactionId != txId) throw TransactException("data txid mismatch")
                    emitPayload(source, UsbPtpProtocol.HEADER_SIZE, header.length - UsbPtpProtocol.HEADER_SIZE)
                }
                UsbPtpProtocol.TYPE_RESPONSE -> {
                    if (header.transactionId != txId) throw TransactException("response txid mismatch")
                    response = UsbPtpProtocol.parseResponseContainer(
                        source.copyOfRange(0, header.length)
                    )
                }
                UsbPtpProtocol.TYPE_EVENT -> {
                    // 少数机型把事件混在 bulk 管道下发，解析后交事件流，不计入本事务结果
                    UsbPtpProtocol.parseEventContainer(source.copyOfRange(0, header.length))
                        ?.let { _events.tryEmit(it) }
                }
                else -> throw TransactException(
                    "unexpected container type 0x${header.type.toString(16)} op=0x${operationCode.toString(16)}"
                )
            }
        }

        while (response == null) {
            if (pending.size >= UsbPtpProtocol.HEADER_SIZE) {
                val header = UsbPtpProtocol.parseContainerHeader(pending)
                    ?: throw TransactException("container header unreadable")
                if (header.length < UsbPtpProtocol.HEADER_SIZE || header.length > UsbPtpProtocol.MAX_PAYLOAD_SIZE) {
                    throw TransactException("container length invalid: ${header.length}")
                }
                if (pending.size >= header.length) {
                    // 容器已完整到达（含数据容器与后续 Response 粘连在同一次读取的情形）
                    consumeContainer(header, pending)
                    pending = pending.copyOfRange(header.length, pending.size)
                    continue
                }
                if (header.type == UsbPtpProtocol.TYPE_DATA) {
                    // 大数据容器跨读到达：头部之后的既有字节直写，随后进入流式消费，
                    // 容器结束后再出现的字节（粘连的 Response 等）截回 pending
                    emitPayload(pending, UsbPtpProtocol.HEADER_SIZE, pending.size - UsbPtpProtocol.HEADER_SIZE)
                    var consumed = pending.size.toLong()
                    pending = ByteArray(0)
                    var idleReads = 0
                    while (consumed < header.length) {
                        val read = conn.bulkTransfer(inp, chunk, chunk.size, timeoutMs)
                        if (read < 0) throw TransactException("bulk in failed during data phase")
                        if (read == 0) {
                            if (++idleReads > 64) throw TransactException("data phase stalled")
                            continue
                        }
                        idleReads = 0
                        val take = minOf(read.toLong(), header.length - consumed).toInt()
                        emitPayload(chunk, 0, take)
                        consumed += take
                        if (take < read) pending = chunk.copyOfRange(take, read)
                    }
                    continue
                }
                // 非数据容器未完整到达（Response/Event 都是小容器）：继续读
            }

            val read = conn.bulkTransfer(inp, chunk, chunk.size, timeoutMs)
            if (read < 0) throw TransactException("bulk in failed/timeout op=0x${operationCode.toString(16)}")
            if (read > 0) {
                pending = if (pending.isEmpty()) chunk.copyOf(read)
                else pending + chunk.copyOfRange(0, read)
            }
        }

        val resp = response!!
        if (resp.isOk || resp.responseCode == PtpConstants.RESPONSE_DEVICE_BUSY) {
            lastCommandOkAtMs = System.currentTimeMillis()
        } else {
            Timber.tag(TAG).w(
                "transact rejected: op=0x${operationCode.toString(16)} code=0x${resp.responseCode.toString(16)}"
            )
        }
        return TransactOutcome(
            response = resp,
            data = if (sink == null) accumulator?.toByteArray() else ByteArray(0),
            receivedBytes = receivedBytes
        )
    }

    /**
     * 流失步/超时后的标准 USB 恢复：CLEAR_FEATURE(ENDPOINT_HALT) 两个 bulk 端点。
     * 与 libgphoto2 的 usb_clear_halt 等价，清掉端点停顿状态让后续事务重新开始。
     */
    private fun clearHaltBothEndpoints() {
        val conn = usbConnection ?: return
        listOf(bulkOut, bulkIn).forEach { ep ->
            ep ?: return@forEach
            runCatching {
                conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 0)
            }
        }
    }

    /**
     * 触发拍摄
     */
    suspend fun capture(): Boolean {
        val response = sendCommand(PtpConstants.OP_INITIATE_CAPTURE, listOf(0))
        return response?.isOk ?: false
    }

    /**
     * 获取设备信息
     */
    suspend fun getDeviceInfo(): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_DEVICE_INFO)
    }

    /**
     * 获取对象句柄列表
     */
    suspend fun getObjectHandles(storageId: Int = 0xFFFFFFFF.toInt()): List<Int> {
        val data = sendCommandWithData(
            PtpConstants.OP_GET_OBJECT_HANDLES,
            listOf(storageId, 0, 0)
        ) ?: return emptyList()

        if (data.size < 4) return emptyList()
        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        return (0 until count).mapNotNull {
            if (buffer.remaining() >= 4) buffer.int else null
        }
    }

    /**
     * MTP 批量元数据：0x9805 GetObjectPropList，**一次事务**取回全部对象的
     * 文件名/大小/日期/格式等属性。
     *
     * 这是相册首屏速度的关键：逐个 GetObjectInfo 需要 N 次串行往返（PTP 命令通道
     * 被 commandMutex 强制串行），2000 个对象约 80~200 秒，远超任何合理等待；
     * 批量路径把它压缩成 1 次往返（数百 KB 载荷在 USB 2.0 上亚秒级返回）。
     *
     * 并非所有机身/USB 模式都支持 MTP 扩展，因此返回 null 是**正常结果而非错误**，
     * 调用方必须回退到逐个 GetObjectInfo（行为与旧版一致，不会更差）。
     *
     * @return 解析后的对象属性列表；不支持/响应为空/载荷不可信时返回 null
     */
    suspend fun getObjectPropList(
        handle: Int = -1,          // 0xFFFFFFFF = 全部对象
        formatCode: Int = 0,       // 0 = 全部格式
        propCode: Int = -1,        // 0xFFFFFFFF = 全部属性
        groupCode: Int = 0,
        depth: Int = -1            // 0xFFFFFFFF = 全部层级
    ): List<MtpObjectProps>? {
        val data = sendCommandWithData(
            PtpConstants.OP_MTP_GET_OBJECT_PROP_LIST,
            listOf(handle, formatCode, propCode, groupCode, depth),
            allowRetry = true
        ) ?: run {
            Timber.tag(TAG).i("MTP 0x9805 unsupported/empty -> fallback to per-object GetObjectInfo")
            return null
        }
        if (data.isEmpty()) {
            Timber.tag(TAG).i("MTP 0x9805 empty payload -> fallback to per-object GetObjectInfo")
            return null
        }
        val parsed = MtpObjectPropListParser.parse(data)
        if (parsed == null) {
            Timber.tag(TAG).w("MTP 0x9805 payload unparsable (${data.size}B) -> fallback")
            return null
        }
        Timber.tag(TAG).i("MTP 0x9805 ok: ${parsed.size} objects / ${data.size}B in one round-trip")
        return parsed
    }

    /**
     * 获取对象（照片下载）。
     * sink 非空时数据阶段流式写盘（内存 O(64KB)），返回哨兵空数组表示事务成功。
     */
    suspend fun getObject(
        handle: Int,
        onProgress: ((Long, Long) -> Unit)? = null,
        sink: java.io.OutputStream? = null
    ): ByteArray? {
        return sendCommandWithData(
            PtpConstants.OP_GET_OBJECT, listOf(handle), onProgress, sink = sink
        )
    }

    /**
     * 获取缩略图
     */
    suspend fun getThumbnail(handle: Int): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_THUMBNAIL, listOf(handle))
    }

    /**
     * 获取高清缩略图（Nikon 厂商扩展 0x90C4），不支持时机身返回错误响应 → null。
     */
    suspend fun getLargeThumbnail(handle: Int): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_NIKON_GET_LARGE_THUMB, listOf(handle))
    }

    /**
     * 获取设备属性值
     */
    suspend fun getDevicePropValue(propCode: Int): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_DEVICE_PROP_VALUE, listOf(propCode))
    }

    /**
     * 设置相机属性（USB PTP Data Phase）
     * PRD 2.4: 光圈/快门/ISO 等参数通过 USB 有线实时调整
     */
    suspend fun setDevicePropValue(propCode: Int, value: ByteArray): Boolean {
        val response = transact(
            operationCode = PtpConstants.OP_SET_DEVICE_PROP_VALUE,
            params = listOf(propCode),
            dataOut = value
        )
        return response.response?.isOk == true
    }

    /**
     * USB 有线 Live View
     * PRD 2.3: 与 WiFi 通道共用同一套 Nikon PTP 操作码
     */
    suspend fun startLiveView(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
        val ok = response?.isOk ?: false
        if (ok) lvFrameOkLogged = false
        eventLogger.event(
            "lv_start",
            "ok" to ok,
            "code" to (response?.responseCode ?: -1)
        )
        return ok
    }

    suspend fun stopLiveView(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_END_LIVE_VIEW)
        val ok = response?.isOk ?: false
        if (!ok) eventLogger.event("lv_stop_fail", "code" to (response?.responseCode ?: -1))
        return ok
    }

    /**
     * 监看帧快速路径：短超时（卡一帧只损失 2.5s）+ 不重试（旧帧无重取价值，
     * 重试只会加大端到端延迟；帧循环自己的连续错误计数负责断链判定）。
     */
    suspend fun getLiveViewImage(): ByteArray? {
        val data = sendCommandWithData(
            PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE,
            timeoutMs = LV_FRAME_TIMEOUT_MS,
            allowRetry = false
        )
        if (data == null || data.isEmpty()) {
            eventLogger.event("lv_fail", "empty" to (data != null))
        } else if (!lvFrameOkLogged) {
            lvFrameOkLogged = true
            eventLogger.event("lv_frame", "bytes" to data.size)
        }
        return data
    }

    /**
     * RC-12 链路判活：连接着且最近一次命令尝试已得到成功响应。
     * 有命令在途（attempt > ok）时视为不可信，调用方应回退 WiFi 通道。
     */
    fun isAlive(): Boolean {
        if (!isConnected()) return false
        if (lastCommandAttemptAtMs == 0L) return true
        return lastCommandOkAtMs >= lastCommandAttemptAtMs
    }

    /**
     * USB 有线遥控拍摄
     * PRD 2.2: 与 WiFi 遥控共用 Nikon AF/快门指令
     */
    suspend fun afDrive(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_AF_DRIVE)
        return response?.isOk ?: false
    }

    suspend fun afDriveCancel(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_AF_DRIVE_CANCEL)
        return response?.isOk ?: false
    }

    suspend fun changeAfArea(x: Int, y: Int): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_CHANGE_AF_AREA, listOf(x, y))
        return response?.isOk ?: false
    }

    suspend fun manualFocusDrive(direction: Int, speed: Int = 2): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_MF_DRIVE, listOf(direction, speed))
        return response?.isOk ?: false
    }

    suspend fun startMovieRecording(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_START_MOVIE_REC_IN_CARD)
        return response?.isOk ?: false
    }

    suspend fun stopMovieRecording(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_END_MOVIE_REC)
        return response?.isOk ?: false
    }

    /** 录像命令带响应码版本（WiFi 通道同名方法的 USB 对应实现） */
    suspend fun startMovieRecordingResult(): Pair<Boolean, Int> {
        val response = sendCommand(PtpConstants.OP_NIKON_START_MOVIE_REC_IN_CARD)
        return (response?.isOk ?: false) to (response?.responseCode ?: PtpConstants.RESPONSE_GENERAL_ERROR)
    }

    suspend fun stopMovieRecordingResult(): Pair<Boolean, Int> {
        val response = sendCommand(PtpConstants.OP_NIKON_END_MOVIE_REC)
        return (response?.isOk ?: false) to (response?.responseCode ?: PtpConstants.RESPONSE_GENERAL_ERROR)
    }

    suspend fun initiateOpenCapture(): Boolean {
        val response = sendCommand(PtpConstants.OP_INITIATE_OPEN_CAPTURE, listOf(0))
        return response?.isOk ?: false
    }

    suspend fun terminateOpenCapture(): Boolean {
        val response = sendCommand(PtpConstants.OP_TERMINATE_OPEN_CAPTURE, listOf(0))
        return response?.isOk ?: false
    }

    /**
     * 获取存储 ID 列表
     */
    suspend fun getStorageIds(): List<Int> {
        val data = sendCommandWithData(PtpConstants.OP_GET_STORAGE_IDS) ?: return emptyList()
        if (data.size < 4) return emptyList()
        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int
        return (0 until count).mapNotNull {
            if (buffer.remaining() >= 4) buffer.int else null
        }
    }

    /**
     * 获取单个存储卡的 PTP StorageInfo 原始数据。
     */
    suspend fun getStorageInfo(storageId: Int): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_STORAGE_INFO, listOf(storageId))
    }

    /**
     * 获取对象信息（文件元数据）
     */
    suspend fun getObjectInfo(handle: Int): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, listOf(handle))
    }

    /**
     * 断点续传：获取对象部分数据。sink 非空时流式写盘。
     */
    suspend fun getPartialObject(
        handle: Int,
        offset: Int,
        maxBytes: Int,
        sink: java.io.OutputStream? = null
    ): ByteArray? {
        return sendCommandWithData(
            PtpConstants.OP_GET_PARTIAL_OBJECT,
            listOf(handle, offset, maxBytes),
            sink = sink
        )
    }

    /**
     * 删除相机存储卡中的对象。
     */
    suspend fun deleteObject(handle: Int): Boolean {
        val response = sendCommand(PtpConstants.OP_DELETE_OBJECT, listOf(handle))
        return response?.isOk == true
    }

    /**
     * 轮询事件（通过 Interrupt IN 端点）
     */
    private fun startEventPolling() {
        eventPollJob?.cancel()
        val interrupt = interruptIn ?: return

        eventPollJob = scope?.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val conn = usbConnection ?: break
                    val buffer = ByteArray(64)
                    val read = conn.bulkTransfer(interrupt, buffer, buffer.size, 1000)
                    if (read >= UsbPtpProtocol.HEADER_SIZE) {
                        val event = UsbPtpProtocol.parseEventContainer(buffer.copyOf(read))
                        if (event != null) {
                            _events.tryEmit(event)
                            Timber.tag(TAG).d("Event: code=0x${event.eventCode.toString(16)}")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) delay(EVENT_POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * 定期向相机发送 DeviceReady，保持会话不被相机判定失效。
     * 模块 6：连续失败判定断联后，标记链路超时文案并触发退避重连。
     *
     * keepAlivePaused：监看运行期间由 LiveViewManager 暂停保活——
     * GetLiveViewImage 帧本身就是持续流量（天然保活），而 DeviceReady 与帧
     * 竞争 commandMutex 只会平白拉高每帧延迟。
     */
    @Volatile private var keepAlivePaused = false

    fun setKeepAlivePaused(paused: Boolean) {
        keepAlivePaused = paused
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope?.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(5000)
                if (keepAlivePaused) continue
                try {
                    val response = sendCommand(
                        PtpConstants.OP_NIKON_DEVICE_READY,
                        retryOnTimeout = true
                    )
                    // Fix P0-3: 允许 DeviceBusy/单次失败，连续失败才判定断联
                    val ok = response != null &&
                            (response.isOk || response.responseCode == PtpConstants.RESPONSE_DEVICE_BUSY)
                    if (ok) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= 2) {
                            _usbErrorMessage.value =
                                "USB 链路超时断开：相机未响应保活，正在尝试自动重连…"
                            _usbState.value = UsbConnectionState.ERROR
                            scheduleReconnect()
                            break
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "USB keep-alive failed")
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        _usbErrorMessage.value =
                            "USB 链路超时断开：相机未响应保活，正在尝试自动重连…"
                        _usbState.value = UsbConnectionState.ERROR
                        scheduleReconnect()
                        break
                    }
                }
            }
        }
    }

    /**
     * 断开 USB 连接。
     * @param silent true = 故障路径的清理（不清错误提示、不改状态，由调用方决定 UI 呈现）
     */
    fun disconnect(silent: Boolean = false) {
        keepAliveJob?.cancel()
        keepAliveJob = null
        eventPollJob?.cancel()
        eventPollJob = null

        try {
            usbInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (_: Exception) {}

        usbConnection = null
        usbInterface = null
        bulkOut = null
        bulkIn = null
        interruptIn = null
        lastCommandAttemptAtMs = 0L
        lastCommandOkAtMs = 0L
        lvFrameOkLogged = false
        _deviceInfo.value = null
        if (!silent) {
            _usbErrorMessage.value = null
            _usbState.value = UsbConnectionState.DISCONNECTED
        }
        eventLogger.event("usb_session", "ok" to false, "reason" to "disconnected")
        Timber.tag(TAG).i("USB disconnected (silent=$silent)")
    }

    fun isConnected(): Boolean = _usbState.value == UsbConnectionState.CONNECTED

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
    }

    private fun unregisterReceivers() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}

/**
 * USB 连接状态
 */
enum class UsbConnectionState {
    DISCONNECTED,
    REQUESTING_PERMISSION,
    PERMISSION_DENIED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * USB 相机信息
 */
data class UsbCameraInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val cameraModel: String,
    val serialNumber: String
)

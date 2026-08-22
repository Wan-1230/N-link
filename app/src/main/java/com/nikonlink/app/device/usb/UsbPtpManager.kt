package com.nikonlink.app.device.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import com.nikonlink.app.device.ptp.PtpConstants
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
    private val context: Context
) {
    companion object {
        private const val TAG = "UsbPtp"
        private const val ACTION_USB_PERMISSION = "com.nikonlink.app.USB_PERMISSION"
        private const val BULK_TIMEOUT_MS = 5000
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
    private var scope: CoroutineScope? = null
    private var eventPollJob: Job? = null
    private var keepAliveJob: Job? = null
    private var started = false

    private val _usbState = MutableStateFlow(UsbConnectionState.DISCONNECTED)
    val usbState: StateFlow<UsbConnectionState> = _usbState.asStateFlow()

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
     * 打开 USB 连接，建立 PTP 会话
     */
    private fun openConnection(device: UsbDevice) {
        _usbState.value = UsbConnectionState.CONNECTING

        try {
            val connection = usbManager.openDevice(device) ?: run {
                Timber.tag(TAG).e("Failed to open USB device")
                _usbState.value = UsbConnectionState.ERROR
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
                connection.close()
                _usbState.value = UsbConnectionState.ERROR
                return
            }

            connection.claimInterface(ptpInterface, true)

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

            // 打开 PTP 会话
            scope?.launch {
                val sessionOk = openPtpSession()
                if (sessionOk) {
                    // 会话建立后向相机反馈，保持相机处于持续连接状态
                    val ready = sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
                    Timber.tag(TAG).d("USB DeviceReady response=${ready?.responseCode}")
                    _usbState.value = UsbConnectionState.CONNECTED
                    startEventPolling()
                    startKeepAlive()
                    Timber.tag(TAG).i("✓ USB PTP connected: ${_deviceInfo.value?.cameraModel}")
                } else {
                    _usbState.value = UsbConnectionState.ERROR
                    disconnect()
                }
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "USB connection failed")
            _usbState.value = UsbConnectionState.ERROR
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
     */
    suspend fun sendCommand(operationCode: Int, params: List<Int> = emptyList()): UsbPtpResponse? {
        val conn = usbConnection ?: return null
        val out = bulkOut ?: return null
        val inp = bulkIn ?: return null

        return withContext(Dispatchers.IO) {
            commandMutex.withLock {
                try {
                    val txId = transactionId.incrementAndGet()
                    val container = UsbPtpProtocol.buildCommandContainer(txId, operationCode, params)

                    // 发送命令
                    val sent = conn.bulkTransfer(out, container, container.size, BULK_TIMEOUT_MS)
                    if (sent < 0) {
                        Timber.tag(TAG).e("Bulk OUT failed")
                        return@withLock null
                    }

                    // 读取响应（可能先收到 Data 包）
                    val responseBuffer = ByteArray(4096)
                    val read = conn.bulkTransfer(inp, responseBuffer, responseBuffer.size, BULK_TIMEOUT_MS)
                    if (read < UsbPtpProtocol.HEADER_SIZE) {
                        Timber.tag(TAG).e("Bulk IN failed or too short: $read")
                        return@withLock null
                    }

                    val data = responseBuffer.copyOf(read)
                    UsbPtpProtocol.parseResponseContainer(data)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "sendCommand error: op=0x${operationCode.toString(16)}")
                    null
                }
            }
        }
    }

    /**
     * 发送命令并接收数据（获取文件/缩略图/设备信息等）
     */
    suspend fun sendCommandWithData(
        operationCode: Int,
        params: List<Int> = emptyList(),
        onProgress: ((Long, Long) -> Unit)? = null
    ): ByteArray? {
        val conn = usbConnection ?: return null
        val out = bulkOut ?: return null
        val inp = bulkIn ?: return null

        return withContext(Dispatchers.IO) {
            commandMutex.withLock {
                try {
                    val txId = transactionId.incrementAndGet()
                    val container = UsbPtpProtocol.buildCommandContainer(txId, operationCode, params)

                    val sent = conn.bulkTransfer(out, container, container.size, BULK_TIMEOUT_MS)
                    if (sent < 0) return@withLock null

                    // 读取数据包（可能多个）
                    val chunks = mutableListOf<ByteArray>()
                    var gotResponse = false
                    var receivedBytes = 0L

                    var responseCode = 0
                    while (!gotResponse) {
                        val buffer = ByteArray(65536)
                        val read = conn.bulkTransfer(inp, buffer, buffer.size, BULK_TIMEOUT_MS)
                        if (read < UsbPtpProtocol.HEADER_SIZE) break

                        val data = buffer.copyOf(read)
                        val headerType = java.nio.ByteBuffer.wrap(data, 4, 2)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

                        when (headerType) {
                            UsbPtpProtocol.TYPE_DATA -> {
                                val parsed = UsbPtpProtocol.parseDataContainer(data)
                                if (parsed != null) {
                                    chunks.add(parsed.payload)
                                    receivedBytes += parsed.payload.size
                                    // USB PTP 数据容器不携带总长度，未知总大小时进度交给上层按 0 处理
                                    onProgress?.invoke(receivedBytes, 0L)
                                }
                            }
                            UsbPtpProtocol.TYPE_RESPONSE -> {
                                responseCode = UsbPtpProtocol.parseResponseContainer(data)?.responseCode ?: 0
                                gotResponse = true
                            }
                        }
                    }

                    if (responseCode != 0x2001) {
                        Timber.tag(TAG).w(
                            "sendCommandWithData rejected: op=0x${operationCode.toString(16)} code=0x${responseCode.toString(16)}"
                        )
                        return@withLock null
                    }
                    if (chunks.isEmpty()) return@withLock ByteArray(0)

                    // 合并所有数据块
                    val totalSize = chunks.sumOf { it.size }
                    val result = ByteArray(totalSize)
                    var offset = 0
                    chunks.forEach { chunk ->
                        System.arraycopy(chunk, 0, result, offset, chunk.size)
                        offset += chunk.size
                    }
                    result
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "sendCommandWithData error")
                    null
                }
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
     * 获取对象（照片下载）
     */
    suspend fun getObject(
        handle: Int,
        onProgress: ((Long, Long) -> Unit)? = null
    ): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_OBJECT, listOf(handle), onProgress)
    }

    /**
     * 获取缩略图
     */
    suspend fun getThumbnail(handle: Int): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_GET_THUMBNAIL, listOf(handle))
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
        val conn = usbConnection ?: return false
        val out = bulkOut ?: return false
        val inp = bulkIn ?: return false

        return withContext(Dispatchers.IO) {
            commandMutex.withLock {
                try {
                    val txId = transactionId.incrementAndGet()
                    val command = UsbPtpProtocol.buildCommandContainer(
                        txId,
                        PtpConstants.OP_SET_DEVICE_PROP_VALUE,
                        listOf(propCode)
                    )
                    if (conn.bulkTransfer(out, command, command.size, BULK_TIMEOUT_MS) < 0) {
                        Timber.tag(TAG).e("SetDevicePropValue command failed")
                        return@withLock false
                    }

                    val data = UsbPtpProtocol.buildDataContainer(txId, value)
                    if (conn.bulkTransfer(out, data, data.size, BULK_TIMEOUT_MS) < 0) {
                        Timber.tag(TAG).e("SetDevicePropValue data failed")
                        return@withLock false
                    }

                    val buffer = ByteArray(4096)
                    val read = conn.bulkTransfer(inp, buffer, buffer.size, BULK_TIMEOUT_MS)
                    if (read < UsbPtpProtocol.HEADER_SIZE) return@withLock false
                    UsbPtpProtocol.parseResponseContainer(buffer.copyOf(read))?.isOk ?: false
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "setDevicePropValue error")
                    false
                }
            }
        }
    }

    /**
     * USB 有线 Live View
     * PRD 2.3: 与 WiFi 通道共用同一套 Nikon PTP 操作码
     */
    suspend fun startLiveView(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
        return response?.isOk ?: false
    }

    suspend fun stopLiveView(): Boolean {
        val response = sendCommand(PtpConstants.OP_NIKON_END_LIVE_VIEW)
        return response?.isOk ?: false
    }

    suspend fun getLiveViewImage(): ByteArray? {
        return sendCommandWithData(PtpConstants.OP_NIKON_GET_LIVE_VIEW_IMAGE)
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
     * 断点续传：获取对象部分数据
     */
    suspend fun getPartialObject(handle: Int, offset: Int, maxBytes: Int): ByteArray? {
        return sendCommandWithData(
            PtpConstants.OP_GET_PARTIAL_OBJECT,
            listOf(handle, offset, maxBytes)
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
     */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope?.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(5000)
                try {
                    val response = sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
                    // Fix P0-3: 允许 DeviceBusy/单次失败，连续失败才判定断联
                    val ok = response != null &&
                            (response.isOk || response.responseCode == PtpConstants.RESPONSE_DEVICE_BUSY)
                    if (ok) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= 2) {
                            _usbState.value = UsbConnectionState.ERROR
                            break
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "USB keep-alive failed")
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        _usbState.value = UsbConnectionState.ERROR
                        break
                    }
                }
            }
        }
    }

    /**
     * 断开 USB 连接
     */
    fun disconnect() {
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
        _deviceInfo.value = null
        _usbState.value = UsbConnectionState.DISCONNECTED
        Timber.tag(TAG).i("USB disconnected")
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

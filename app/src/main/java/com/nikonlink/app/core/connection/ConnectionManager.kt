package com.nikonlink.app.core.connection

import com.nikonlink.app.core.ble.BleConnectionState
import com.nikonlink.app.core.ble.BleManager
import com.nikonlink.app.core.ble.WifiCredential
import com.nikonlink.app.core.common.*
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.ptp.PtpSessionState
import com.nikonlink.app.core.ptp.PtpIpProbe
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.core.usb.UsbPtpManager
import com.nikonlink.app.core.wifi.WifiManager
import com.nikonlink.app.core.wifi.WifiCameraCandidate
import com.nikonlink.app.core.wifi.WifiScanner
import com.nikonlink.app.data.repository.DeviceRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接管理器 - 协调 BLE、WiFi、PTP 三大子系统
 *
 * PRD 3.1 双通道架构:
 * BLE 通道永远在线（低功耗），WiFi 通道按需唤醒（高带宽）
 *
 * PRD 3.5 断联自动恢复:
 * - WiFi 断开，BLE 正常 → 通过 BLE 发送 WiFi 重连指令
 * - BLE 断开，WiFi 正常 → 后台自动重新扫描 BLE
 * - 双通道均断开 → 全量重连流程（指数退避）
 * - 相机关机 → 低功耗等待模式
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val bleManager: BleManager,
    private val wifiManager: WifiManager,
    private val wifiScanner: WifiScanner,
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val stateMachine: ConnectionStateMachine,
    private val deviceRepository: DeviceRepository
) {
    companion object {
        private const val TAG = "ConnectionMgr"
        private const val WIFI_UPGRADE_DELAY_MS = 1000L
        private const val PAIRING_TIMEOUT_MS = 90000L
        private const val PAIRING_RETRY_DELAY_MS = 3000L
    }

    private var scope: CoroutineScope? = null
    private var pairedDeviceAddress: String? = null
    private var stateListener: ((ConnectionState, ConnectionState) -> Unit)? = null
    private var userDisconnectRequested = false
    private var connectedSince: Long? = null
    private var pairingJob: Job? = null
    private var recoveryJob: Job? = null

    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()

    private val _statusMessage = MutableStateFlow("未连接")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectionHint = MutableStateFlow<ConnectionHint?>(null)
    val connectionHint: StateFlow<ConnectionHint?> = _connectionHint.asStateFlow()

    /** 对外暴露的连接状态（来自状态机） */
    val connectionState: StateFlow<ConnectionState> = stateMachine.state

    fun start(scope: CoroutineScope) {
        this.scope = scope
        bleManager.start(scope)
        wifiManager.start(scope)
        ptpSession.start(scope)
        usbPtpManager.start(scope)
        stateMachine.start(scope)

        observeBleEvents()
        observeWifiEvents()
        observePtpEvents()
        observeUsbEvents()
        observeStateMachine()
        observeReconnectTrigger()
        startMetricsUpdater()
        scope.launch { reconnectLastDeviceIfPaired() }

        Timber.tag(TAG).i("ConnectionManager started")
    }

    fun stop() {
        pairingJob?.cancel()
        pairingJob = null
        stateListener?.let { stateMachine.removeStateListener(it) }
        stateListener = null
        bleManager.stop()
        wifiManager.stop()
        ptpSession.stop()
        usbPtpManager.stop()
        stateMachine.stop()
        _connectionHint.value = null
        userDisconnectRequested = false
        connectedSince = null
        scope = null
        Timber.tag(TAG).i("ConnectionManager stopped")
    }

    /**
     * 用户触发连接（首次配对或手动重连）
     */
    fun connectToDevice(address: String) {
        pairedDeviceAddress = address
        userDisconnectRequested = false
        stateMachine.dispatch(ConnectionEvent.StartConnect)
        scope?.launch(Dispatchers.IO) {
            try {
                deviceRepository.savePairedDevice(address, address, "")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to persist paired device")
            }
        }
    }

    /**
     * 用户主动断开：进入 DISCONNECTED，不触发自动重连。
     */
    fun disconnectDevice() {
        pairingJob?.cancel()
        pairingJob = null
        _connectionHint.value = null
        userDisconnectRequested = true
        stopScan()
        wifiManager.disconnect()
        bleManager.disconnect()
        ptpSession.closeSession()
        stateMachine.dispatch(ConnectionEvent.CameraShutdown)
        userDisconnectRequested = false
        Timber.tag(TAG).i("Device disconnected by user")
    }

    /**
     * 通过 WiFi 直连相机（AP/STA 均适用）。
     * 视频中的影控台流程：手机与相机在同一 WiFi 后扫描发现相机，再建立 PTP/IP。
     */
    fun connectToWifiCamera(ipAddress: String, port: Int = 15740, deviceName: String? = null) {
        pairingJob?.cancel()
        pairedDeviceAddress = "wifi:$ipAddress:$port"
        userDisconnectRequested = false
        wifiManager.bindToActiveWifi()
        stateMachine.dispatch(ConnectionEvent.StartConnect)
        _connectionHint.value = null

        pairingJob = scope?.launch(Dispatchers.IO) {
            try {
                val deadline = System.currentTimeMillis() + PAIRING_TIMEOUT_MS
                var ok = false
                while (System.currentTimeMillis() < deadline && !ok) {
                    ensureActive()
                    ok = ptpSession.connect(
                        ipAddress,
                        port,
                        pairingMode = true
                    ) {
                        if (_connectionHint.value == null) {
                            _connectionHint.value =
                                ConnectionHint("请在相机配对完成画面按 OK，然后等待即可")
                        }
                    }
                    if (!ok) {
                        Timber.tag(TAG).w("Pairing attempt failed, waiting for camera OK and retrying")
                        if (_connectionHint.value != null) {
                            _connectionHint.value =
                                ConnectionHint(
                                    "请稍候，相机正在完成重新连接。请勿切换相机网络设置界面或启动其他连接。"
                                )
                        }
                        delay(PAIRING_RETRY_DELAY_MS)
                    }
                }

                if (ok) {
                    // Fix 任务1(STA): 直连成功后不再发 BLE 指令令相机切换 WiFi 模式，
                    // 避免 STA 模式下相机会话被重置导致后续操作失败
                    _connectionHint.value = ConnectionHint(
                        "配对完成。OK确定",
                        kind = ConnectionHintKind.PAIRING_COMPLETE
                    )
                    stateMachine.dispatch(ConnectionEvent.WifiConnected)
                    try {
                        deviceRepository.savePairedDevice(
                            "wifi:$ipAddress:$port",
                            deviceName ?: "尼康相机",
                            ipAddress
                        )
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to persist WiFi camera")
                    }
                } else {
                    _connectionHint.value = null
                    stateMachine.dispatch(
                        ConnectionEvent.ErrorOccurred("WiFi 相机连接失败", recoverable = false)
                    )
                }
            } finally {
                pairingJob = null
            }
        }
    }

    /**
     * 用户取消相机端配对确认。
     */
    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _connectionHint.value = null
        userDisconnectRequested = true
        ptpSession.closeSession()
        stateMachine.dispatch(ConnectionEvent.CameraShutdown)
        userDisconnectRequested = false
        Timber.tag(TAG).i("Pairing cancelled by user")
    }

    /**
     * 用户确认配对完成提示。
     */
    fun confirmPairingComplete() {
        _connectionHint.value = null
    }

    /**
     * 扫描当前 WiFi 网络中的尼康相机（UDP 5353 + TCP 15740 探测）。
     */
    suspend fun scanWifiCameras(timeoutMs: Long = 12000L): List<WifiCameraCandidate> {
        val candidates = wifiScanner.scan(timeoutMs).toMutableList()
        // 参考影犀 STA 日志: 扫描失败时直接复用历史 IP 发起 PTP/IP，避免每次都全段盲扫。
        val last = runCatching { deviceRepository.getLastAutoConnectDevice() }.getOrNull()
        if (last != null && last.address.startsWith("wifi:")) {
            val parts = last.address.removePrefix("wifi:").split(":")
            val ip = parts.firstOrNull().orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 15740
            // 参考影犀 rediscoverNikonStaHistoryByInitAckScan：
            // 历史 IP 需要先通过 PTP/IP Init Ack 确认，避免把已失效地址展示给用户。
            if (ip.isNotEmpty() &&
                candidates.none { it.ipAddress == ip } &&
                PtpIpProbe.probe(ip, port, 1000L)
            ) {
                candidates.add(
                    WifiCameraCandidate(ip, port, last.deviceName.ifBlank { "尼康相机(历史)" }, "sta-history")
                )
            }
        }
        return candidates
    }

    /**
     * 后台健康检查/启动时恢复最后配对的设备。
     */
    suspend fun reconnectLastDevice(): Boolean {
        return try {
            val device = deviceRepository.getLastAutoConnectDevice()
            if (device != null) {
                if (device.address.startsWith("wifi:")) {
                    val parts = device.address.removePrefix("wifi:").split(":")
                    val ip = parts.firstOrNull().orEmpty()
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 15740
                    if (ip.isNotEmpty()) {
                        connectToWifiCamera(ip, port, device.deviceName)
                    }
                } else {
                    connectToDevice(device.address)
                }
                true
            } else {
                startScan()
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Reconnect to last device failed")
            false
        }
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    val discoveredDevices = bleManager.discoveredDevices

    /**
     * 监听 BLE 事件并驱动状态机
     */
    private fun observeBleEvents() {
        scope?.launch {
            bleManager.connectionState.collect { bleState ->
                when (bleState) {
                    com.nikonlink.app.core.ble.BleConnectionState.CONNECTED -> {
                        stateMachine.dispatch(ConnectionEvent.BleConnected)
                    }
                    com.nikonlink.app.core.ble.BleConnectionState.DISCONNECTED -> {
                        if (stateMachine.state.value != ConnectionState.DISCONNECTED && !userDisconnectRequested) {
                            stateMachine.dispatch(ConnectionEvent.BleDisconnected)
                        }
                    }
                    else -> {}
                }
            }
        }

        // BLE 连接失败（地址无效/GATT 错误）进入可恢复重试
        scope?.launch {
            bleManager.connectFailed.collect {
                Timber.tag(TAG).w("BLE connect failed, scheduling retry")
                stateMachine.dispatch(ConnectionEvent.ErrorOccurred("BLE 连接失败", recoverable = true))
            }
        }

        // BLE 配对阶段提示（相机端按 OK 后才会进入持续连接）
        scope?.launch {
            bleManager.pairingMessage.collect { message ->
                _connectionHint.value = message?.let {
                    ConnectionHint(it, kind = ConnectionHintKind.WAITING_FOR_CAMERA)
                }
            }
        }

        // 监听 WiFi 凭证（BLE 通道交换）
        scope?.launch {
            bleManager.wifiCredential.collect { credential ->
                Timber.tag(TAG).i("WiFi credential received, upgrading channel")
                upgradeToWifi(credential)
            }
        }

        // 监听新文件通知
        scope?.launch {
            bleManager.fileNotification.collect { notification ->
                Timber.tag(TAG).i("New file: ${notification.fileName} (${notification.fileSize} bytes)")
                // 如果有新文件且 WiFi 未连接，触发 WiFi 升级
                if (!wifiManager.isConnected()) {
                    requestWifiReconnect()
                }
            }
        }
    }

    /**
     * 通过 BLE 通知相机恢复 WiFi AP，同时用已缓存凭证在手机侧重连。
     */
    fun requestWifiReconnect() {
        scope?.launch {
            delay(WIFI_UPGRADE_DELAY_MS)
            if (bleManager.isConnected()) {
                bleManager.requestWifiReconnect()
            } else {
                Timber.tag(TAG).i("BLE not connected, trying cached WiFi directly")
            }
            if (!wifiManager.isConnected()) {
                wifiManager.reconnect()
            }
        }
    }

    /**
     * 等待 PTP 会话就绪，供传输页在 WiFi 通道建立后继续读取照片。
     */
    suspend fun awaitPtpSession(timeoutMs: Long = 20000L): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!ptpSession.isConnected()) {
                delay(250)
            }
            true
        } ?: false
    }

    /**
     * 监听 WiFi 事件
     */
    private fun observeWifiEvents() {
        scope?.launch {
            wifiManager.wifiState.collect { wifiState ->
                when (wifiState) {
                    com.nikonlink.app.core.wifi.WifiChannelState.CONNECTED -> {
                        // WiFi 网络就绪后先建立 PTP 会话，避免网络恢复但相机未在线时误报已连接
                        stateMachine.dispatch(ConnectionEvent.WifiUpgradeRequested)
                        establishPtpSession()
                    }
                    com.nikonlink.app.core.wifi.WifiChannelState.DISCONNECTED -> {
                        if (stateMachine.state.value == ConnectionState.FULLY_CONNECTED ||
                            stateMachine.state.value == ConnectionState.WIFI_UPGRADING) {
                            stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
                            // PRD 3.5: WiFi 断开，BLE 正常 → 通过 BLE 发送 WiFi 重连指令
                            if (bleManager.isConnected()) {
                                requestWifiReconnect()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // WiFi 网络丢失时尝试重连
        scope?.launch {
            wifiManager.networkLost.collect {
                Timber.tag(TAG).w("WiFi network lost, attempting recovery")
                if (bleManager.isConnected()) {
                    requestWifiReconnect()
                }
            }
        }
    }

    /**
     * 监听状态机变化，更新 UI 状态
     */
    private fun observeStateMachine() {
        stateListener?.let { stateMachine.removeStateListener(it) }
        stateListener = { oldState, newState ->
            _statusMessage.value = when (newState) {
                ConnectionState.DISCONNECTED -> "未连接"
                ConnectionState.CONNECTING -> "正在连接..."
                ConnectionState.BLE_CONNECTED -> {
                    if (pairedDeviceAddress?.startsWith("wifi:") == true) {
                        "连接中断，正在恢复..."
                    } else {
                        "BLE 已连接"
                    }
                }
                ConnectionState.WIFI_UPGRADING -> "正在建立 WiFi 通道..."
                ConnectionState.FULLY_CONNECTED -> {
                    if (pairedDeviceAddress?.startsWith("wifi:") == true) {
                        "WiFi 已连接，相机确认成功"
                    } else {
                        "BLE + WiFi 已连接"
                    }
                }
                ConnectionState.ERROR_WAITING_RETRY -> "连接中断，正在重试..."
            }
            when (newState) {
                ConnectionState.DISCONNECTED -> connectedSince = null
                ConnectionState.BLE_CONNECTED,
                ConnectionState.FULLY_CONNECTED -> {
                    if (connectedSince == null) connectedSince = System.currentTimeMillis()
                }
                else -> {}
            }
        }
        stateMachine.addStateListener(stateListener!!)
    }

    /**
     * PTP/IP 会话断开后自动恢复（WiFi 直连场景没有 BLE 兜底）。
     */
    private fun observePtpEvents() {
        scope?.launch {
            ptpSession.sessionState.collect { state ->
                if ((state == PtpSessionState.ERROR || state == PtpSessionState.DISCONNECTED) &&
                    !userDisconnectRequested &&
                    pairingJob == null &&
                    stateMachine.state.value == ConnectionState.FULLY_CONNECTED &&
                    pairedDeviceAddress?.startsWith("wifi:") == true
                ) {
                    Timber.tag(TAG).w("PTP session lost, attempting recovery")
                    stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
                    recoverWifiSession()
                }
            }
        }
    }

    /**
     * USB 有线通道独立于无线状态机运行；传输层始终优先使用 USB。
     */
    private fun observeUsbEvents() {
        scope?.launch {
            usbPtpManager.usbState.collect { state ->
                Timber.tag(TAG).d("USB state changed: $state")
            }
        }
    }

    private fun recoverWifiSession() {
        val address = pairedDeviceAddress ?: return
        if (recoveryJob?.isActive == true) return
        val parts = address.removePrefix("wifi:").split(":")
        val ip = parts.firstOrNull().orEmpty()
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 15740
        if (ip.isEmpty()) return

        recoveryJob = scope?.launch(Dispatchers.IO) {
            wifiManager.bindToActiveWifi()
            // Fix 真机日志: 首次恢复时相机可能还在清理旧会话，返回异常包导致握手失败；
            // 改为最多重试 3 次（间隔递增），只有全部失败才判定不可恢复
            var ok = false
            var attempt = 0
            while (!ok && attempt < 3) {
                attempt++
                delay(2000L * attempt)
                ok = ptpSession.connect(ip, port, pairingMode = true)
                if (!ok) Timber.tag(TAG).w("PTP recovery attempt $attempt failed")
            }
            if (ok) {
                Timber.tag(TAG).i("PTP session recovered (attempt $attempt)")
                stateMachine.dispatch(ConnectionEvent.WifiConnected)
            } else {
                Timber.tag(TAG).w("PTP session recovery failed after 3 attempts")
                stateMachine.dispatch(
                    ConnectionEvent.ErrorOccurred("WiFi 连接已断开", recoverable = false)
                )
            }
            recoveryJob = null
        }
    }

    /**
     * 状态机进入 CONNECTING 时真正发起 BLE 连接（自动重连闭环）。
     */
    private fun observeReconnectTrigger() {
        scope?.launch {
            stateMachine.state.collect { state ->
                if (state == ConnectionState.CONNECTING && !userDisconnectRequested) {
                    val address = pairedDeviceAddress
                    if (address != null &&
                        !address.startsWith("wifi:") &&
                        bleManager.connectionState.value != BleConnectionState.CONNECTED &&
                        bleManager.connectionState.value != BleConnectionState.CONNECTING
                    ) {
                        Timber.tag(TAG).i("Auto-reconnecting to $address")
                        bleManager.connect(address)
                    }
                }
            }
        }
    }

    /**
     * WiFi 通道升级
     * PRD 3.1: BLE→WiFi 通道升级
     */
    private suspend fun upgradeToWifi(credential: WifiCredential) {
        if (wifiManager.isConnected()) return
        if (wifiManager.wifiState.value == com.nikonlink.app.core.wifi.WifiChannelState.CONNECTING) return
        stateMachine.dispatch(ConnectionEvent.WifiUpgradeRequested)
        val success = wifiManager.connectToCamera(credential)
        if (!success) {
            Timber.tag(TAG).w("WiFi upgrade failed, staying on BLE only")
            stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
        }
    }

    /**
     * 建立 PTP/IP 会话
     */
    private suspend fun establishPtpSession() {
        if (ptpSession.isConnected()) {
            stateMachine.dispatch(ConnectionEvent.WifiConnected)
            return
        }
        // 等待 WiFi 网络稳定
        delay(500)
        wifiManager.bindToActiveWifi()
        val credential = bleManager.wifiCredential.replayCache.firstOrNull()
        val host = credential?.ipAddress ?: "192.168.1.1"
        val port = credential?.port ?: 15740

        val success = ptpSession.connect(host, port)
        if (success) {
            Timber.tag(TAG).i("✓ PTP session ready")
            stateMachine.dispatch(ConnectionEvent.WifiConnected)
        } else {
            Timber.tag(TAG).w("PTP session failed, will retry on next WiFi reconnect")
            stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
        }
    }

    /**
     * 定期更新连接指标
     * PRD 3.4: 连接状态可视化
     */
    private fun startMetricsUpdater() {
        scope?.launch {
            while (isActive) {
                delay(2000)
                _connectionMetrics.value = ConnectionMetrics(
                    bleRssi = bleManager.rssi.value,
                    wifiRssi = wifiManager.wifiRssi.value,
                    lastHeartbeatTime = bleManager.lastHeartbeatAt.value,
                    connectionDuration = connectedSince?.let { System.currentTimeMillis() - it } ?: 0L,
                    activeChannels = buildSet {
                        if (bleManager.isConnected()) add(ChannelType.BLE)
                        if (wifiManager.isConnected() || ptpSession.isConnected()) add(ChannelType.WIFI)
                        if (usbPtpManager.isConnected()) add(ChannelType.USB)
                    },
                    reconnectCount = stateMachine.retryCount.value
                )
            }
        }
    }

    private suspend fun reconnectLastDeviceIfPaired() {
        try {
            val device = deviceRepository.getLastAutoConnectDevice()
            if (device != null && pairedDeviceAddress == null) {
                Timber.tag(TAG).i("Restoring connection to ${device.deviceName} [${device.address}]")
                if (device.address.startsWith("wifi:")) {
                    val parts = device.address.removePrefix("wifi:").split(":")
                    val ip = parts.firstOrNull().orEmpty()
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 15740
                    if (ip.isNotEmpty()) {
                        connectToWifiCamera(ip, port, device.deviceName)
                    }
                } else {
                    connectToDevice(device.address)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to restore last connection")
        }
    }

    /**
     * 获取 PTP 会话（供 feature 层使用）
     */
    fun getPtpSession(): PtpSessionManager = ptpSession

    fun isFullyConnected(): Boolean = stateMachine.state.value == ConnectionState.FULLY_CONNECTED
    fun isBleConnected(): Boolean = bleManager.isConnected()

    /**
     * 连接状态机与底层会话是否一致。
     * 用于健康检查，避免 UI 显示已连接但 PTP/IP 或 USB 会话实际已死亡。
     */
    fun isLinkHealthy(): Boolean {
        return when (stateMachine.state.value) {
            ConnectionState.FULLY_CONNECTED ->
                ptpSession.isConnected() || usbPtpManager.isConnected()
            ConnectionState.BLE_CONNECTED -> bleManager.isConnected()
            else -> true
        }
    }
}

/**
 * 连接向导提示类型
 */
enum class ConnectionHintKind {
    WAITING_FOR_CAMERA,
    PAIRING_COMPLETE
}

/**
 * 连接向导提示
 */
data class ConnectionHint(
    val message: String,
    val kind: ConnectionHintKind = ConnectionHintKind.WAITING_FOR_CAMERA
)

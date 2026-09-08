package com.nikonlink.app.device.connect

import android.content.Context
import com.nikonlink.app.device.ble.BleConnectionState
import com.nikonlink.app.device.ble.BleManager
import com.nikonlink.app.device.ble.WifiCredential
import com.nikonlink.app.device.model.ConnectionMetrics
import com.nikonlink.app.device.model.ChannelType
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.model.ConnectionEvent
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.ptp.PtpSessionState
import com.nikonlink.app.device.ptp.PtpIpProbe
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.device.wifi_sta.WifiCameraCandidate
import com.nikonlink.app.device.wifi_sta.WifiDirectConnector
import com.nikonlink.app.device.wifi_sta.WifiScanner
import com.nikonlink.app.device.data.DeviceRepository
import com.nikonlink.app.camera.gallery.TransferManager
import com.nikonlink.app.shared.common.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
    private val wifiManager: WifiManager,
    private val wifiScanner: WifiScanner,
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val stateMachine: ConnectionStateMachine,
    private val deviceRepository: DeviceRepository,
    private val transferManager: TransferManager,
    private val connector: WifiDirectConnector,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "ConnectionMgr"
        private const val WIFI_UPGRADE_DELAY_MS = 1000L
        private const val PREFS_NAME = "nl_settings"
        private const val PREFS_WIFI_5G_PREFER = "wifi_band_5g_prefer"
    }

    private var scope: CoroutineScope? = null
    private var pairedDeviceAddress: String? = null
    private var stateListener: ((ConnectionState, ConnectionState) -> Unit)? = null
    private var userDisconnectRequested = false
    private var connectedSince: Long? = null
    private var pairingJob: Job? = null
    private var recoveryJob: Job? = null
    /** STA 直连场景：WiFi 路由器断网标记（网络恢复后触发 PTP 重建） */
    private var staNetworkLost = false

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
        transferManager.start(scope)
        stateMachine.start(scope)
        connector.start(scope)

        observeBleEvents()
        observeWifiEvents()
        observePtpEvents()
        observeUsbEvents()
        observeStateMachine()
        observeReconnectTrigger()
        observeStaRecovery()
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
        transferManager.stop()
        stateMachine.stop()
        connector.stop()
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
        connector.cancelActive()
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
     * RC-1/2/7/8：连接全部收敛到 WifiDirectConnector 单一入口——
     * Mutex + activeJob 槽位消灭并发 pair_start 风暴，generation 令牌保证
     * 旧任务在每个挂起点失效，指数退避把重试窗口拉长到 ~90s。
     */
    fun connectToWifiCamera(ipAddress: String, port: Int = 15740, deviceName: String? = null) {
        val endpoint = WifiEndpoint.parse("wifi:$ipAddress:$port")
        if (endpoint == null) {
            eventLogger.event("sta_fail", "reason" to "invalid_endpoint", "host" to ipAddress)
            stateMachine.dispatch(
                ConnectionEvent.ErrorOccurred("IP 地址无效，请填写形如 192.168.1.1 的 IPv4 地址", recoverable = true)
            )
            return
        }

        _connectionHint.value = null
        pairedDeviceAddress = endpoint.address
        userDisconnectRequested = false

        // RC-1：必须先同步登记 activeJob，再派发 StartConnect，
        // 否则状态机触发的重连观察者会认为没有任务在跑而再次发起连接
        pairingJob = connector.connect(
            endpoint = endpoint,
            mode = WifiDirectConnector.Mode.PAIRING,
            onWaitingCameraOk = {
                if (_connectionHint.value == null) {
                    _connectionHint.value =
                        ConnectionHint("请在相机配对完成画面按 OK，然后等待即可")
                }
            },
            onRetry = {
                if (_connectionHint.value != null) {
                    _connectionHint.value = ConnectionHint(
                        "请稍候，相机正在完成重新连接。请勿切换相机网络设置界面或启动其他连接。"
                    )
                }
            },
            onSuccess = {
                eventLogger.event("pair_ok", "host" to endpoint.host, "port" to endpoint.port)
                _connectionHint.value = ConnectionHint(
                    "配对完成。OK确定",
                    kind = ConnectionHintKind.PAIRING_COMPLETE
                )
                // 回调非挂起上下文，持久化是 fire-and-forget，放独立协程（与旧实现语义一致）
                scope?.launch(Dispatchers.IO) {
                    try {
                        deviceRepository.savePairedDevice(
                            endpoint.address,
                            deviceName ?: "尼康相机",
                            endpoint.host
                        )
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to persist WiFi camera")
                    }
                }
            },
            onFail = {
                _connectionHint.value = null
                eventLogger.event("pair_fail", "host" to endpoint.host, "port" to endpoint.port)
            }
        )
        stateMachine.dispatch(ConnectionEvent.StartConnect)
    }

    /**
     * 用户取消相机端配对确认。
     */
    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        connector.cancelActive()
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
    suspend fun scanWifiCameras(
        timeoutMs: Long = 18_000L,
        hotspotMode: Boolean = false
    ): List<WifiCameraCandidate> {
        // STA: 显式拿到 WiFi Network，让 scan() 内部的 mDNS/探测 Socket 绑定到 WiFi，
        // 避免手机蜂窝数据等其它网络抢走默认路由导致扫描失败。
        val network = wifiManager.currentWifiNetwork()
        val candidates = wifiScanner.scan(timeoutMs, network, hotspotMode).toMutableList()
        // 扫描失败时直接复用历史 IP 发起 PTP/IP，避免每次都全段盲扫。
        // RC-3：地址统一走 WifiEndpoint 归一化（前导零校正、非法段拦截）
        val last = runCatching { deviceRepository.getLastAutoConnectDevice() }.getOrNull()
        val lastEndpoint = last?.let { WifiEndpoint.parse(it.address) }
        if (lastEndpoint != null &&
            candidates.none { it.ipAddress == lastEndpoint.host }
        ) {
            // 历史 IP 需要先通过 PTP/IP Init Ack 确认，避免把已失效地址展示给用户。
            if (PtpIpProbe.probe(lastEndpoint, timeoutMs = 1000L, network = network)) {
                candidates.add(
                    WifiCameraCandidate(
                        lastEndpoint.host,
                        lastEndpoint.port,
                        last.deviceName.ifBlank { "尼康相机(历史)" },
                        "sta-history"
                    )
                )
            }
        }
        return candidates
    }

    /** B4：转发最近一次 WiFi 扫描统计（失败原因可见化的数据源） */
    val lastWifiScanStats: kotlinx.coroutines.flow.StateFlow<WifiScanner.ScanStats?>
        get() = wifiScanner.lastScanStats

    /**
     * 后台健康检查/启动时恢复最后配对的设备。
     */
    suspend fun reconnectLastDevice(): Boolean {
        return try {
            val device = deviceRepository.getLastAutoConnectDevice()
            if (device != null) {
                val endpoint = WifiEndpoint.parse(device.address)
                if (endpoint != null) {
                    connectToWifiCamera(
                        ipAddress = endpoint.host,
                        port = endpoint.port,
                        deviceName = device.deviceName
                    )
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
                    com.nikonlink.app.device.ble.BleConnectionState.CONNECTED -> {
                        stateMachine.dispatch(ConnectionEvent.BleConnected)
                    }
                    com.nikonlink.app.device.ble.BleConnectionState.DISCONNECTED -> {
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
                // 设置「自动下载新照片」开启时，同步最新照片入队
                transferManager.scheduleAutoSync()
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
                    com.nikonlink.app.device.wifi_ap.WifiChannelState.CONNECTED -> {
                        // WiFi 网络就绪后先建立 PTP 会话，避免网络恢复但相机未在线时误报已连接
                        stateMachine.dispatch(ConnectionEvent.WifiUpgradeRequested)
                        establishPtpSession()
                        transferManager.scheduleAutoSync()
                    }
                    com.nikonlink.app.device.wifi_ap.WifiChannelState.DISCONNECTED -> {
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
                if (pairedDeviceAddress?.startsWith("wifi:") == true) {
                    staNetworkLost = true
                    eventLogger.event("wifi_sta", "state" to "network_lost")
                    Timber.tag(TAG).i("STA camera link: network lost, waiting for network restore")
                }
                if (bleManager.isConnected()) {
                    requestWifiReconnect()
                }
            }
        }
    }

    /**
     * STA 直连场景的网络监护：路由器断网后置标记，网络恢复即重建 PTP 会话。
     * 补原实现缺口（旧逻辑只覆盖 BLE+相机AP 场景，纯 WiFi 直连断网后无法自愈）。
     */
    private fun observeStaRecovery() {
        scope?.launch {
            wifiManager.networkAvailable.collect { network ->
                if (staNetworkLost && !userDisconnectRequested &&
                    pairedDeviceAddress?.startsWith("wifi:") == true &&
                    !connector.isActive
                ) {
                    staNetworkLost = false
                    eventLogger.event("wifi_sta", "state" to "network_restored")
                    Timber.tag(TAG).i("STA WiFi network restored, rebuilding PTP session over $network")
                    recoverWifiSession(network)
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
                    !connector.isActive && pairingJob?.isActive != true &&
                    stateMachine.state.value != ConnectionState.DISCONNECTED &&
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

    private fun recoverWifiSession(network: android.net.Network? = null) {
        val endpoint = pairedDeviceAddress?.let { WifiEndpoint.parse(it) } ?: return
        // RC-1：恢复走 connector 单一入口，避免与配对循环并发双写会话
        if (connector.isActive) return

        recoveryJob = scope?.launch(Dispatchers.IO) {
            eventLogger.event("wifi_recover_start", "host" to endpoint.host, "port" to endpoint.port)
            connector.connect(
                endpoint = endpoint,
                mode = WifiDirectConnector.Mode.RESUME
            )
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
                    if (address != null && address.startsWith("wifi:")) {
                        // Fix STA: 状态机重试闭环补齐 WiFi 直连分支（旧逻辑只重连 BLE）
                        if (!connector.isActive && pairingJob?.isActive != true && !ptpSession.isConnected()) {
                            // 地址解析统一走 WifiEndpoint（RC-3 收敛：全项目唯一解析入口）
                            val endpoint = WifiEndpoint.parse(address)
                            if (endpoint != null) {
                                Timber.tag(TAG).i("Auto-reconnecting to WiFi camera ${endpoint.display}")
                                connectToWifiCamera(endpoint.host, endpoint.port)
                            }
                        }
                    } else if (address != null &&
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
        if (wifiManager.wifiState.value == com.nikonlink.app.device.wifi_ap.WifiChannelState.CONNECTING) return
        stateMachine.dispatch(ConnectionEvent.WifiUpgradeRequested)
        // 设置页「5GHz 优先」开关：双频相机 AP 时优先连接 5GHz，失败自动回退 2.4GHz
        val prefer5G = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFS_WIFI_5G_PREFER, false)
        val success = wifiManager.connectToCamera(credential, preferBand5GHz = prefer5G)
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
            // isConnected() 只是内存标志，半开 TCP（相机已休眠/链路已死）时仍为 true——
            // 旧版直接凭脏标志 dispatch WifiConnected，状态栏「未连接却显示已连接」的根因。
            // 现在先探活（DeviceReady 轻量命令），探不通就拆掉脏会话走完整建链。
            val alive = runCatching {
                ptpSession.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY).isOk
            }.getOrDefault(false)
            if (alive) {
                stateMachine.dispatch(ConnectionEvent.WifiConnected)
                return
            }
            Timber.tag(TAG).w("Stale PTP session detected (DeviceReady failed), rebuilding")
            runCatching { ptpSession.closeSession() }
        }
        // 等待 WiFi 网络稳定
        delay(500)
        wifiManager.bindToActiveWifi()
        val credential = bleManager.wifiCredential.replayCache.firstOrNull()
        val host = credential?.ipAddress ?: "192.168.1.1"
        val port = credential?.port ?: 15740

        // Fix STA/AP: 相机可能尚未就绪，连试 3 次再判定失败
        repeat(3) { attempt ->
            val success = ptpSession.connect(host, port)
            if (success) {
                Timber.tag(TAG).i("✓ PTP session ready (attempt ${attempt + 1})")
                stateMachine.dispatch(ConnectionEvent.WifiConnected)
                return
            }
            if (attempt < 2) delay(1500L * (attempt + 1))
        }
        Timber.tag(TAG).w("PTP session failed, will retry on next WiFi reconnect")
        stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
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
                    wifiFrequencyMhz = wifiManager.wifiFrequencyMhz.value,
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
                val endpoint = WifiEndpoint.parse(device.address)
                if (endpoint != null) {
                    connectToWifiCamera(
                        ipAddress = endpoint.host,
                        port = endpoint.port,
                        deviceName = device.deviceName
                    )
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
     * 健康检查入口：WiFi 直连设备会话死亡时走 PTP 快速恢复，
     * 其余（BLE/未配对）走完整重连流程。
     */
    fun recoverLostLink() {
        val address = pairedDeviceAddress
        if (address != null && address.startsWith("wifi:") &&
            !connector.isActive && pairingJob?.isActive != true &&
            stateMachine.state.value != ConnectionState.DISCONNECTED
        ) {
            recoverWifiSession()
        } else {
            scope?.launch { reconnectLastDevice() }
        }
    }

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

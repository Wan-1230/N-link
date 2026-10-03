package com.nikonlink.app.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.app.device.model.CameraDevice
import com.nikonlink.app.device.model.CameraModel
import com.nikonlink.app.device.model.CameraModelCatalog
import com.nikonlink.app.device.model.ConnectionMetrics
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionHint
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.shared.data.PairedDevice
import com.nikonlink.app.device.data.DeviceRepository
import com.nikonlink.app.device.usb.UsbCameraInfo
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_sta.WifiCameraCandidate
import com.nikonlink.app.device.wifi_sta.WifiScanner
import com.nikonlink.app.device.ptp.HostRegistrationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import timber.log.Timber

/**
 * 连接状态仪表盘 ViewModel
 *
 * PRD 3.4 连接状态可视化:
 * - BLE 信号强度（RSSI）
 * - WiFi 信号强度
 * - 最后心跳时间
 * - 当前通道状态（仅BLE / BLE+WiFi）
 * - 连接持续时长
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val usbPtpManager: UsbPtpManager,
    private val deviceRepository: DeviceRepository,
    val settings: com.nikonlink.app.shared.common.AppSettings,
    private val ftpServer: com.nikonlink.app.device.ftp.FtpPushServer,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val metrics: StateFlow<ConnectionMetrics> = connectionManager.connectionMetrics
    val statusMessage: StateFlow<String> = connectionManager.statusMessage
    val connectionHint: StateFlow<ConnectionHint?> = connectionManager.connectionHint

    /** USB 连接状态 */
    val usbState: StateFlow<UsbConnectionState> = usbPtpManager.usbState
    val usbDeviceInfo: StateFlow<UsbCameraInfo?> = usbPtpManager.deviceInfo

    /** 模块 6：USB 失败分类提示（物理未识别/无权限/接口占用/会话失败/链路超时） */
    val usbErrorMessage: StateFlow<String?> = usbPtpManager.usbErrorMessage

    /** 扫描到的设备列表（去重） */
    private val _deviceList = MutableStateFlow<List<CameraDevice>>(emptyList())
    val deviceList: StateFlow<List<CameraDevice>> = _deviceList.asStateFlow()

    private val _wifiDeviceList = MutableStateFlow<List<WifiCameraCandidate>>(emptyList())
    val wifiDeviceList: StateFlow<List<WifiCameraCandidate>> = _wifiDeviceList.asStateFlow()

    private val _isWifiScanning = MutableStateFlow(false)
    val isWifiScanning: StateFlow<Boolean> = _isWifiScanning.asStateFlow()

    /** 历史配对过的相机设备（STA 最近连接） */
    val recentDevices: StateFlow<List<PairedDevice>> = deviceRepository.getPairedDevices()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var lastSelectedAddress: String? = null

    init {
        // USB PTP 由 ConnectionManager 统一管理；这里只消费状态。

        // 收集扫描到的设备，按地址去重
        viewModelScope.launch {
            connectionManager.discoveredDevices.collect { device ->
                val current = _deviceList.value.toMutableList()
                val existingIdx = current.indexOfFirst { it.address == device.address }
                if (existingIdx >= 0) {
                    current[existingIdx] = device  // 更新 RSSI
                } else {
                    current.add(device)
                }
                _deviceList.value = current
            }
        }
    }

    fun startScan() {
        _deviceList.value = emptyList()  // 清空旧列表
        connectionManager.startScan()
    }

    /** B4：最近一次 WiFi 扫描统计（候选为空时 UI 据此生成诊断文案） */
    private val _wifiScanStats = MutableStateFlow<WifiScanner.ScanStats?>(null)
    val wifiScanStats: StateFlow<WifiScanner.ScanStats?> = _wifiScanStats.asStateFlow()

    /** v1.3.2：扫描失败/被门控的原因（UI 直接展示，不再静默） */
    private val _wifiScanError = MutableStateFlow<String?>(null)
    val wifiScanError: StateFlow<String?> = _wifiScanError.asStateFlow()

    fun scanWifi() {
        _isWifiScanning.value = true
        _wifiDeviceList.value = emptyList()
        _wifiScanError.value = null
        val hotspotMode = settings.staSubMode == com.nikonlink.app.shared.common.AppSettings.STA_MODE_PHONE_HOTSPOT
        viewModelScope.launch {
            try {
                val candidates = connectionManager.scanWifiCameras(hotspotMode = hotspotMode)
                _wifiDeviceList.value = candidates
                _wifiScanStats.value = connectionManager.lastWifiScanStats.value
                // v1.3.2（STA 反馈修复）：扫描"没结果"必须说清楚是哪一步卡住。
                // 旧版在异常分支只清空列表，UI 只能笼统显示"未发现相机"——
                // 用户无法区分"没连 WiFi""WiFi 没拿到 IP""相机不在同一网段"。
                _wifiScanError.value = when {
                    candidates.isNotEmpty() -> null
                    else -> when (connectionManager.lastWifiScanStats.value?.gateReason) {
                        "no_wifi_network" -> "手机当前未连接 WiFi，无法搜索同一网络下的相机"
                        "wifi_no_ipv4" -> "WiFi 已连接但未取得 IP 地址（DHCP 未完成），请稍后重试"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                _wifiDeviceList.value = emptyList()
                _wifiScanError.value = "WiFi 扫描失败：" + (e.message ?: e.javaClass.simpleName)
                Timber.tag("DashboardVM").w(e, "WiFi scan failed")
            } finally {
                _isWifiScanning.value = false
            }
        }
    }

    fun stopScan() {
        connectionManager.stopScan()
    }

    fun connectToDevice(address: String) {
        lastSelectedAddress = address
        connectionManager.connectToDevice(address)
    }

    fun connectToWifiCamera(candidate: WifiCameraCandidate) {
        connectionManager.connectToWifiCamera(
            ipAddress = candidate.ipAddress,
            port = candidate.port,
            deviceName = candidate.name
        )
    }

    // ── FR-34①：AP 一键连接的直通（闸门判定、兜底地址轮次、面板武装都在 ConnectionManager）──

    fun apOneTapEnabled(): Boolean = connectionManager.isApOneTapEnabled()

    fun onCameraAp(): Boolean = connectionManager.isOnCameraAp()

    fun connectApDirect() = connectionManager.connectApDirect()

    /** FR-35①：[path] = panel | settings | specifier，落进 ap_panel 事件供真机归因。 */
    fun armApPanel(path: String) = connectionManager.armApPanelConnect(path)

    /**
     * FR-35④：有 BLE 凭证时免面板一键连。
     * @return false = 闸门关或本会话没有凭证，调用方应立刻回落系统面板。
     */
    fun connectApWithCredential(onFailed: () -> Unit): Boolean =
        connectionManager.connectApWithCredential(onFailed)

    fun apPanelArmed(): Boolean = connectionManager.isApPanelArmed()

    fun tryStartArmedApConnect(): Boolean = connectionManager.tryStartArmedApConnect()

    // ── FR-36：设备页「相机全称」的候选来源（归一化在 CameraModelCatalog，识别不出= null）──

    /** PTP/IP 会话建立时那次 `GetDeviceInfo` 记下的机身自报 Model 原文。 */
    fun sessionCameraModel(): String? = connectionManager.sessionCameraModel()

    /** BLE 广播名里认出的机型（广播形如 `Z6III_12345678`，序列号后缀会被剥掉）。 */
    fun discoveredCameraName(): String? = _deviceList.value
        .firstOrNull { it.model != CameraModel.UNKNOWN }
        ?.let { CameraModelCatalog.official(it.model) }

    /** 最近一次配对记录里存的机型（新记录存 Model 原文，历史记录那一列是 IP → 返回 null）。 */
    fun pairedCameraName(): String? = recentDevices.value.firstOrNull()
        ?.let { CameraModelCatalog.official(it.cameraModel) }

    /** 从最近连接列表快速重连 */
    fun connectToRecentDevice(device: PairedDevice) {
        val endpoint = WifiEndpoint.parse(device.address)
        if (endpoint != null) {
            connectionManager.connectToWifiCamera(
                ipAddress = endpoint.host,
                port = endpoint.port,
                deviceName = device.deviceName
            )
            return
        }
        connectToDevice(device.address)
    }

    fun cancelPairing() {
        connectionManager.cancelPairing()
    }

    /** STA 主机注册 UI 状态：Running=进行中（带进度文案）/ Success / Failure */
    private val _hostReg = MutableStateFlow<HostRegUiState?>(null)
    val hostReg: StateFlow<HostRegUiState?> = _hostReg.asStateFlow()

    /** 相机是否已记住本机为信任主机 */
    val staHostRegistered: StateFlow<Boolean> = connectionManager.staHostRegistered

    /** 是否需要引导注册（从未注册过 或 相机拒绝过握手）→ UI 用强调色显示注册按钮 */
    val staRegisterNeeded: StateFlow<Boolean> = connectionManager.staRegisterNeeded

    /**
     * STA 主机注册（ZDROP 式）：AP 模式连上相机后，把本机 GUID 注册为相机信任主机，
     * 之后相机切 STA 模式才会放行握手。前置引导（相机进「连接至 PC」向导）由 UI 完成。
     */
    fun registerStaHost() {
        if (_hostReg.value is HostRegUiState.Running) return
        viewModelScope.launch {
            _hostReg.value = HostRegUiState.Running("正在预备注册…")
            val result = connectionManager.registerStaHost { progress ->
                _hostReg.value = HostRegUiState.Running(progress)
            }
            _hostReg.value = when (result) {
                is HostRegistrationResult.Success ->
                    HostRegUiState.Success(
                        "相机已接受注册。现在可把相机切到 STA 模式（连接本机热点）再用 N-Link 连接；" +
                            "若下次握手仍被相机拒绝，这里会自动退回「需要注册」"
                    )
                is HostRegistrationResult.Failure ->
                    HostRegUiState.Failure(result.detail)
            }
        }
    }

    fun clearHostReg() {
        if (_hostReg.value !is HostRegUiState.Running) _hostReg.value = null
    }

    // ─────────── STA 架构 B：FTP 推送收图（PRD §4.5）───────────
    //
    // 这一组状态与上面 PTP 直连那一组**完全平行、互不引用**：
    // 架构切换时 UI 只换显隐，不会把一方的进度/错误带进另一方
    // （要求 4 的状态隔离）。服务器本身持有独立 scope，不挂在 viewModelScope 上，
    // 所以切页/后台不会中断正在进行的推送。

    /** FTP 服务器状态：Stopped / Listening(host, port, user, pass) / Failed(message) */
    val ftpState: StateFlow<com.nikonlink.app.device.ftp.FtpPushServer.State> = ftpServer.state

    /** 已接收文件（倒序，最多 20 条） */
    val ftpReceived: StateFlow<List<com.nikonlink.app.device.ftp.FtpPushServer.Received>> =
        ftpServer.received

    /** 当前连着的 FTP 客户端数（相机连接时为 1） */
    val ftpClients: StateFlow<Int> = ftpServer.clients

    /** 相机侧要填的登录凭据（密码持久化，避免每次开机去相机里重填） */
    val ftpPassword: String get() = ftpServer.password

    /** 手机当前的局域网地址候选（未启动服务器时给用户看"该填哪个"） */
    fun ftpCandidateAddresses(): List<String> = ftpServer.candidateAddresses()

    fun startFtpServer() = ftpServer.start()

    fun stopFtpServer() = ftpServer.stop()

    fun clearFtpHistory() = ftpServer.clearHistory()

    fun formatFtpBytes(bytes: Long): String = ftpServer.formatBytes(bytes)

    fun formatFtpTime(at: Long): String = ftpServer.formatTime(at)

    fun confirmPairingComplete() {
        connectionManager.confirmPairingComplete()
    }

    fun connectToLastDevice() {
        val addr = lastSelectedAddress ?: _deviceList.value.firstOrNull()?.address
        if (addr != null) {
            connectToDevice(addr)
        }
    }

    fun disconnect() {
        connectionManager.disconnectDevice()
    }

    /** USB 有线连接：检测已插入的相机 */
    fun connectUsb() {
        // 用户主动点了 USB 连接 → 允许在「总线上没有相机」时把原因写进状态
        usbPtpManager.checkExistingDevice(notifyWhenAbsent = true)
    }

    /** 断开 USB */
    fun disconnectUsb() {
        usbPtpManager.disconnect()
    }
}

/** STA 主机注册 UI 状态 */
sealed class HostRegUiState {
    data class Running(val progress: String) : HostRegUiState()
    data class Success(val message: String) : HostRegUiState()
    data class Failure(val message: String) : HostRegUiState()
}

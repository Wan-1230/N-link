package com.nikonlink.app.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.app.device.model.CameraDevice
import com.nikonlink.app.device.model.ConnectionMetrics
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionHint
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.shared.data.PairedDevice
import com.nikonlink.app.device.data.DeviceRepository
import com.nikonlink.app.device.usb.UsbCameraInfo
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.device.wifi_sta.WifiCameraCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val metrics: StateFlow<ConnectionMetrics> = connectionManager.connectionMetrics
    val statusMessage: StateFlow<String> = connectionManager.statusMessage
    val connectionHint: StateFlow<ConnectionHint?> = connectionManager.connectionHint

    /** USB 连接状态 */
    val usbState: StateFlow<UsbConnectionState> = usbPtpManager.usbState
    val usbDeviceInfo: StateFlow<UsbCameraInfo?> = usbPtpManager.deviceInfo

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

    fun scanWifi() {
        _isWifiScanning.value = true
        _wifiDeviceList.value = emptyList()
        viewModelScope.launch {
            try {
                val candidates = connectionManager.scanWifiCameras()
                _wifiDeviceList.value = candidates
            } catch (e: Exception) {
                _wifiDeviceList.value = emptyList()
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

    /** 从最近连接列表快速重连 */
    fun connectToRecentDevice(device: PairedDevice) {
        if (device.address.startsWith("wifi:")) {
            val parts = device.address.removePrefix("wifi:").split(":")
            val ip = parts.firstOrNull().orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 15740
            if (ip.isNotEmpty()) {
                connectionManager.connectToWifiCamera(
                    ipAddress = ip,
                    port = port,
                    deviceName = device.deviceName
                )
                return
            }
        }
        connectToDevice(device.address)
    }

    fun cancelPairing() {
        connectionManager.cancelPairing()
    }

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
        usbPtpManager.checkExistingDevice()
    }

    /** 断开 USB */
    fun disconnectUsb() {
        usbPtpManager.disconnect()
    }
}

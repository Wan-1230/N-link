package com.nikonlink.app.feature.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.R
import com.nikonlink.app.core.common.CameraDevice
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.connection.ConnectionHintKind
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.core.wifi.WifiCameraCandidate
import com.nikonlink.app.databinding.FragmentDashboardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 连接状态仪表盘 Fragment
 * PRD 3.4: BLE/WiFi 信号强度、通道状态、连接持续时长
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()
    private var scannedDevices: List<CameraDevice> = emptyList()
    private var wifiDevices: List<WifiCameraCandidate> = emptyList()
    private var pairingDialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
    }

    private fun setupUi() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                binding.tvConnectionStatus.text = when (state) {
                    ConnectionState.FULLY_CONNECTED -> "已连接"
                    ConnectionState.WIFI_UPGRADING -> "建立高速通道"
                    ConnectionState.BLE_CONNECTED -> {
                        if (viewModel.statusMessage.value.contains("连接中断")) {
                            "连接中断"
                        } else {
                            "BLE 已连接"
                        }
                    }
                    ConnectionState.CONNECTING -> "正在连接"
                    ConnectionState.ERROR_WAITING_RETRY -> "等待重连"
                    ConnectionState.DISCONNECTED -> "未连接"
                }
                val colorRes = when (state) {
                    ConnectionState.FULLY_CONNECTED -> R.color.status_connected
                    ConnectionState.BLE_CONNECTED,
                    ConnectionState.WIFI_UPGRADING,
                    ConnectionState.CONNECTING -> R.color.status_connecting
                    else -> R.color.status_disconnected
                }
                binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), colorRes)
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statusMessage.collect { msg ->
                binding.tvStatusMessage.text = msg
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.metrics.collect { metrics ->
                binding.tvBleRssi.text = "BLE RSSI: ${metrics.bleRssi} dBm"
                binding.tvChannels.text = "活跃通道: ${metrics.activeChannels.joinToString(", ").ifEmpty { "无" }}"
                binding.tvReconnectCount.text = "重连次数: ${metrics.reconnectCount}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deviceList.collect { devices ->
                scannedDevices = devices
                if (devices.isNotEmpty()) {
                    binding.tvStatusMessage.text = "发现 ${devices.size} 个设备"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.wifiDeviceList.collect { devices ->
                wifiDevices = devices
                if (devices.isNotEmpty()) {
                    binding.tvStatusMessage.text = "发现 ${devices.size} 个 WiFi 相机"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isWifiScanning.collect { scanning ->
                binding.btnWifiScan.isEnabled = !scanning
                binding.btnWifiScan.text = if (scanning) "WiFi 扫描中..." else "WiFi 扫描"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionHint.collect { hint ->
                if (hint != null) {
                    val builder = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(
                            if (hint.kind == ConnectionHintKind.PAIRING_COMPLETE) {
                                "配对完成"
                            } else {
                                "连接向导"
                            }
                        )
                        .setMessage(hint.message)
                        .setCancelable(false)
                    if (hint.kind == ConnectionHintKind.PAIRING_COMPLETE) {
                        builder.setPositiveButton("OK确定") { _, _ ->
                            viewModel.confirmPairingComplete()
                        }
                    } else {
                        builder.setNegativeButton("取消") { _, _ ->
                            viewModel.cancelPairing()
                        }
                    }
                    pairingDialog?.dismiss()
                    pairingDialog = builder.show()
                } else {
                    pairingDialog?.dismiss()
                    pairingDialog = null
                }
            }
        }

        binding.btnScan.setOnClickListener {
            viewModel.startScan()
            binding.btnScan.isEnabled = false
            binding.btnScan.text = "扫描中..."
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(15000)
                if (_binding != null) {
                    binding.btnScan.isEnabled = true
                    binding.btnScan.text = "扫描设备"
                }
            }
        }

        binding.btnWifiScan.setOnClickListener {
            viewModel.scanWifi()
            binding.tvStatusMessage.text = "正在扫描 WiFi 相机..."
        }

        binding.btnConnect.setOnClickListener {
            showDeviceDialog()
        }

        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        // USB 有线连接按钮
        binding.btnUsbConnect.setOnClickListener {
            viewModel.connectUsb()
            // Fix P2-5: 移除冗余 Toast，改用状态栏文字反馈
            binding.tvUsbStatus.text = "检测 USB 相机..."
        }

        // 观察 USB 连接状态
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbState.collect { usbState ->
                binding.tvUsbStatus.text = when (usbState) {
                    UsbConnectionState.CONNECTED -> "USB 已连接，相机确认成功"
                    UsbConnectionState.CONNECTING -> "USB 连接中..."
                    UsbConnectionState.REQUESTING_PERMISSION -> "等待 USB 授权..."
                    UsbConnectionState.PERMISSION_DENIED -> "USB 权限被拒绝"
                    UsbConnectionState.ERROR -> "USB 连接错误"
                    UsbConnectionState.DISCONNECTED -> "USB 未连接"
                }
                binding.btnUsbConnect.text = when (usbState) {
                    UsbConnectionState.CONNECTED -> "断开 USB"
                    else -> "USB 连接"
                }
                binding.btnUsbConnect.setOnClickListener {
                    if (usbState == UsbConnectionState.CONNECTED) {
                        viewModel.disconnectUsb()
                    } else {
                        viewModel.connectUsb()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbDeviceInfo.collect { info ->
                if (info != null) {
                    binding.tvUsbStatus.text = "USB: ${info.cameraModel}"
                }
            }
        }
    }

    private fun showDeviceDialog() {
        val entries = buildList {
            scannedDevices.forEach { device -> add(DeviceDialogEntry(device.name, device, null)) }
            wifiDevices.forEach { candidate -> add(DeviceDialogEntry(candidate.name, null, candidate)) }
        }
        val names = entries.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("选择相机设备")
            .setItems(names) { _, which ->
                val entry = entries[which]
                // Fix P2-5: 状态栏文字反馈，替代 Toast
                binding.tvStatusMessage.text = "连接: ${entry.label}"
                when {
                    entry.bleDevice != null -> viewModel.connectToDevice(entry.bleDevice.address)
                    entry.wifiDevice != null -> viewModel.connectToWifiCamera(entry.wifiDevice)
                }
            }
            .setNeutralButton("手动输入 IP") { _, _ ->
                showManualIpDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManualIpDialog() {
        val input = EditText(requireContext()).apply {
            hint = "192.168.1.1 或 192.168.1.1:15740"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(56, 28, 56, 8)
            addView(input)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("手动连接 WiFi 相机")
            .setMessage("STA 模式下请输入相机在局域网中的 IP 地址")
            .setView(container)
            .setPositiveButton("连接") { _, _ ->
                val raw = input.text.toString().trim()
                val parts = raw.removePrefix("wifi:").split(":")
                val ip = parts.firstOrNull().orEmpty()
                val port = parts.getOrNull(1)?.toIntOrNull() ?: 15740
                if (ip.isNotEmpty()) {
                    binding.tvStatusMessage.text = "连接 $ip:$port"
                    viewModel.connectToWifiCamera(
                        WifiCameraCandidate(ip, port, "手动相机", "manual")
                    )
                } else {
                    binding.tvStatusMessage.text = "IP 地址无效"
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private data class DeviceDialogEntry(
        val label: String,
        val bleDevice: CameraDevice?,
        val wifiDevice: WifiCameraCandidate?
    )

    override fun onDestroyView() {
        super.onDestroyView()
        pairingDialog?.dismiss()
        pairingDialog = null
        _binding = null
    }
}

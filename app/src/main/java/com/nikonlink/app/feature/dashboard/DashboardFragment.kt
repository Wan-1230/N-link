package com.nikonlink.app.feature.dashboard

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.MainActivity
import com.nikonlink.app.R
import com.nikonlink.app.core.common.CameraDevice
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.connection.ConnectionHintKind
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.core.wifi.WifiCameraCandidate
import com.nikonlink.app.databinding.FragmentDashboardBinding
import com.nikonlink.app.feature.settings.CameraParamsViewModel
import com.nikonlink.app.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tab1 设备首页
 * 设备连接总览、相机基础信息、快捷功能入口、折叠详情
 * 交互：下拉刷新状态，点击设备卡片快速重连，入口带缩放反馈
 */
@AndroidEntryPoint
class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()

    private var scannedDevices: List<CameraDevice> = emptyList()
    private var wifiDevices: List<WifiCameraCandidate> = emptyList()
    private var pairingDialog: AlertDialog? = null
    private var detailExpanded = false
    private var cameraInfoRequested = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        setupInteractions()
        paramsViewModel.readAll()
    }

    private fun setupObservers() {
        // 连接状态（灰度表达）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                val (text, colorRes, loading) = when (state) {
                    ConnectionState.FULLY_CONNECTED ->
                        Triple("已连接", R.color.on_dark_card, false)
                    ConnectionState.WIFI_UPGRADING ->
                        Triple("建立高速通道", R.color.on_dark_card_variant, true)
                    ConnectionState.BLE_CONNECTED ->
                        if (viewModel.statusMessage.value.contains("连接中断")) {
                            Triple("连接中断", R.color.on_dark_card_variant, false)
                        } else {
                            Triple("BLE 已连接", R.color.on_dark_card_variant, false)
                        }
                    ConnectionState.CONNECTING ->
                        Triple("连接中", R.color.on_dark_card_variant, true)
                    ConnectionState.ERROR_WAITING_RETRY ->
                        Triple("等待重连", R.color.on_dark_card_variant, false)
                    ConnectionState.DISCONNECTED ->
                        Triple("未连接", R.color.on_dark_card_variant, false)
                }
                binding.tvConnectionStatus.text = text
                binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), colorRes)
                )
                binding.progressConnecting.visibility = if (loading) View.VISIBLE else View.GONE

                // 已连接 → 显示断开按钮；否则显示连接按钮
                val connected = state == ConnectionState.FULLY_CONNECTED ||
                        state == ConnectionState.BLE_CONNECTED
                binding.btnConnect.visibility = if (connected) View.GONE else View.VISIBLE
                binding.btnDisconnect.visibility = if (connected) View.VISIBLE else View.GONE
                if (state == ConnectionState.FULLY_CONNECTED && !cameraInfoRequested) {
                    cameraInfoRequested = true
                    paramsViewModel.readAll()
                } else if (state != ConnectionState.FULLY_CONNECTED) {
                    cameraInfoRequested = false
                }
                binding.swipeRefresh.isRefreshing = false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statusMessage.collect { msg -> binding.tvStatusMessage.text = msg }
        }

        // 相机信息：电量 / 存储 / 快门次数 / 固件 / 型号
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.cameraInfo.collect { info ->
                if (info.batteryLevel >= 0) binding.tvBattery.text = "${info.batteryLevel}%"
                if (info.storageTotalMb > 0) {
                    val usedPct =
                        ((info.storageTotalMb - info.storageFreeMb) * 100 / info.storageTotalMb).toInt()
                    binding.pbStorage.progress = usedPct.coerceIn(0, 100)
                    binding.tvStorage.text =
                        if (info.storageDescription.isNotBlank()) {
                            "${info.storageDescription} · ${formatMb(info.storageTotalMb - info.storageFreeMb)} / ${formatMb(info.storageTotalMb)}"
                        } else {
                            "${formatMb(info.storageTotalMb - info.storageFreeMb)} / ${formatMb(info.storageTotalMb)}"
                        }
                    // 估算剩余可拍张数（按单张 ~25MB RAW 计）
                    val shots = (info.storageFreeMb / 25).toInt()
                    binding.tvShotsRemaining.text = "约 $shots 张"
                }
                if (info.modelName.isNotBlank()) binding.tvCameraName.text = info.modelName
                if (info.lensName.isNotBlank()) binding.tvLens.text = info.lensName
                if (info.shutterCount >= 0) binding.tvShutterCount.text = "${info.shutterCount} 次"
                if (info.firmwareVersion.isNotBlank()) binding.tvFirmware.text = info.firmwareVersion
            }
        }

        // 折叠详情：蓝牙信号 / 重连次数
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.metrics.collect { metrics ->
                binding.tvBleSignal.text =
                    if (metrics.bleRssi != 0) "${metrics.bleRssi} dBm" else "—"
                binding.tvReconnectCount.text = "${metrics.reconnectCount} 次"
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

        // 配对引导弹窗（单一流程，不叠加）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionHint.collect { hint ->
                if (hint != null) {
                    val builder = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(
                            if (hint.kind == ConnectionHintKind.PAIRING_COMPLETE) "配对完成"
                            else "连接向导"
                        )
                        .setMessage(hint.message)
                        .setCancelable(false)
                    if (hint.kind == ConnectionHintKind.PAIRING_COMPLETE) {
                        builder.setPositiveButton("确定") { _, _ -> viewModel.confirmPairingComplete() }
                    } else {
                        builder.setNegativeButton("取消") { _, _ -> viewModel.cancelPairing() }
                    }
                    pairingDialog?.dismiss()
                    pairingDialog = builder.show()
                } else {
                    pairingDialog?.dismiss()
                    pairingDialog = null
                }
            }
        }

        // USB 状态合并进卡片状态文字
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbState.collect { usbState ->
                when (usbState) {
                    UsbConnectionState.CONNECTED -> {
                        binding.tvConnectionStatus.text = "USB 已连接"
                        binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(requireContext(), R.color.on_dark_card)
                        )
                        binding.btnConnect.visibility = View.GONE
                        binding.btnDisconnect.visibility = View.VISIBLE
                        if (!cameraInfoRequested) {
                            cameraInfoRequested = true
                            paramsViewModel.readAll()
                        }
                    }
                    UsbConnectionState.DISCONNECTED -> {
                        if (viewModel.connectionState.value == ConnectionState.DISCONNECTED) {
                            binding.tvConnectionStatus.text = "未连接"
                            binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.on_dark_card_variant)
                            )
                            binding.btnConnect.visibility = View.VISIBLE
                            binding.btnDisconnect.visibility = View.GONE
                        }
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbDeviceInfo.collect { info ->
                if (info != null) binding.tvCameraName.text = info.cameraModel
            }
        }
    }

    private fun setupInteractions() {
        // 顶部设置入口 → Tab4
        binding.btnSettingsIcon.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainActivity.TAB_SETTINGS)
        }

        // 连接 / 断开
        binding.btnConnect.pressEffect()
        binding.btnConnect.setOnClickListener { showConnectDialog() }
        binding.btnDisconnect.pressEffect()
        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
            viewModel.disconnectUsb()
        }

        // 点击设备卡片快速重连
        binding.cardDevice.setOnClickListener {
            if (viewModel.connectionState.value == ConnectionState.DISCONNECTED) {
                showConnectDialog()
            }
        }

        // 下拉刷新连接状态
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        binding.swipeRefresh.setOnRefreshListener {
            paramsViewModel.readAll()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(1200)
                if (_binding != null) binding.swipeRefresh.isRefreshing = false
            }
        }

        // 快捷功能网格（缩放反馈 + 跳转）
        binding.cardQuickRemote.pressEffect()
        binding.cardQuickRemote.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainActivity.TAB_REMOTE)
        }
        binding.cardQuickAlbum.pressEffect()
        binding.cardQuickAlbum.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainActivity.TAB_ALBUM)
        }
        binding.cardQuickFirmware.pressEffect()
        binding.cardQuickFirmware.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("固件更新")
                .setMessage("当前已是最新版本，暂无可用固件更新。")
                .setPositiveButton("确定", null)
                .show()
        }
        binding.cardQuickLocation.pressEffect()
        binding.cardQuickLocation.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("位置同步")
                .setMessage("将手机 GPS 位置写入照片需要相机支持位置信息写入，Z 系列机型可通过蓝牙持续同步。功能即将开放。")
                .setPositiveButton("确定", null)
                .show()
        }

        // 相机详情折叠组（展开/收起 + 箭头旋转动画）
        binding.rowDetailHeader.setOnClickListener {
            detailExpanded = !detailExpanded
            binding.layoutDetail.visibility = if (detailExpanded) View.VISIBLE else View.GONE
            binding.iconDetailChevron.animate()
                .rotation(if (detailExpanded) 180f else 0f)
                .setDuration(200)
                .start()
        }
    }

    /** 连接方式选择：BLE / WiFi 扫描结果 / USB 有线 / 手动 IP */
    private fun showConnectDialog() {
        val entries = buildList {
            scannedDevices.forEach { d -> add(DeviceDialogEntry("BLE · ${d.name}", d, null)) }
            wifiDevices.forEach { c -> add(DeviceDialogEntry("WiFi · ${c.name} (${c.ipAddress})", null, c)) }
        }
        val items = entries.map { it.label }.toMutableList()
        items.add("USB 有线连接")
        items.add("手动输入 IP")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("连接相机")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which < entries.size -> {
                        val entry = entries[which]
                        binding.tvStatusMessage.text = "连接: ${entry.label}"
                        when {
                            entry.bleDevice != null -> viewModel.connectToDevice(entry.bleDevice.address)
                            entry.wifiDevice != null -> viewModel.connectToWifiCamera(entry.wifiDevice)
                        }
                    }
                    which == entries.size -> {
                        binding.tvStatusMessage.text = "检测 USB 相机..."
                        viewModel.connectUsb()
                    }
                    else -> showManualIpDialog()
                }
            }
            .setNeutralButton("重新扫描") { _, _ ->
                viewModel.startScan()
                viewModel.scanWifi()
                binding.tvStatusMessage.text = "正在扫描..."
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

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024f) else "$mb MB"

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

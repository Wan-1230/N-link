package com.nikonlink.app.device

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.animation.DecelerateInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.content.res.ColorStateList
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.MainActivity
import com.nikonlink.app.R
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionHintKind
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.device.wifi_sta.WifiCameraCandidate
import com.nikonlink.app.shared.data.PairedDevice
import com.nikonlink.app.databinding.ItemRecentDeviceBinding
import com.nikonlink.app.databinding.ItemWifiCandidateBinding
import com.nikonlink.app.databinding.FragmentDashboardBinding
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.camera.params.ShutterCountState
import com.nikonlink.app.shared.ui.pressEffect
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

    private var wifiDevices: List<WifiCameraCandidate> = emptyList()
    private var pairingDialog: AlertDialog? = null
    private var detailExpanded = false
    private var cameraInfoRequested = false
    private var currentMode = ConnectMode.WIFI_AP
    private var modeTabWidth = 0
    /** USB 链路激活标记：USB 模式下卡片展示 USB 信息而非 WiFi 频段 */
    private var usbActive = false

    private val modeTabsViews: List<android.widget.TextView>
        get() = listOf(binding.tabWifiAp, binding.tabWifiSta, binding.tabUsb)

    private val modePanels: List<View>
        get() = listOf(binding.panelWifiAp, binding.panelWifiSta, binding.panelUsb)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupObservers()
        setupInteractions()
        setupModeTabs()
        setupModeActions()
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
                binding.btnConnect.visibility = View.GONE
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

        // 相机信息：有真实数据才展示对应行，读不到时整行隐藏，不遗留横杠占位
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.cameraInfo.collect { info ->
                binding.rowBattery.visibility =
                    if (info.batteryLevel >= 0) View.VISIBLE else View.GONE
                if (info.batteryLevel >= 0) {
                    binding.tvBattery.text = "${info.batteryLevel}%"
                }

                binding.rowStorage.visibility =
                    if (info.storageTotalMb > 0) View.VISIBLE else View.GONE
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
                binding.rowShotsRemaining.visibility =
                    if (info.storageTotalMb > 0) View.VISIBLE else View.GONE

                binding.rowLens.visibility =
                    if (info.lensName.isNotBlank()) View.VISIBLE else View.GONE
                if (info.modelName.isNotBlank()) binding.tvCameraName.text = info.modelName
                if (info.lensName.isNotBlank()) binding.tvLens.text = info.lensName

                // 快门次数行：连接后常驻可点（未查询/失败/成功均可点击触发或刷新）。
                // 可行性：尼康无快门计数 PTP 属性 → 采样照片 MakerNotes 本地解析，失败走云端兜底
                val shutterRowVisible = info.shutterQueryState != ShutterCountState.NONE ||
                        info.modelName.isNotBlank()
                binding.rowShutterCount.visibility =
                    if (shutterRowVisible) View.VISIBLE else View.GONE
                binding.tvShutterCount.text = when (info.shutterQueryState) {
                    ShutterCountState.QUERYING -> "查询中…"
                    ShutterCountState.SUCCESS -> "${info.shutterCount} 次" +
                            if (info.shutterCountSource.isNotBlank()) "（${info.shutterCountSource}）" else ""
                    ShutterCountState.FAILED -> "查询失败，点击重试"
                    ShutterCountState.NONE -> "未查询 · 点击查询"
                }
                binding.tvShutterCount.isClickable = shutterRowVisible
                binding.tvShutterCount.setOnClickListener {
                    paramsViewModel.retryShutterCountQuery()
                }

                binding.rowFirmware.visibility =
                    if (info.firmwareVersion.isNotBlank()) View.VISIBLE else View.GONE
                if (info.firmwareVersion.isNotBlank()) {
                    binding.tvFirmware.text = info.firmwareVersion
                }
            }
        }

        // 折叠详情：蓝牙信号只在有效时显示，无信号整行隐藏
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.metrics.collect { metrics ->
                binding.rowBleSignal.visibility =
                    if (metrics.bleRssi != 0) View.VISIBLE else View.GONE
                if (metrics.bleRssi != 0) {
                    binding.tvBleSignal.text = "${metrics.bleRssi} dBm"
                }
                binding.rowWifiBand.visibility =
                    if (metrics.wifiFrequencyMhz > 0) View.VISIBLE else View.GONE
                if (metrics.wifiFrequencyMhz > 0) {
                    binding.tvWifiBand.text =
                        "${WifiManager.bandLabel(metrics.wifiFrequencyMhz)} (${metrics.wifiFrequencyMhz} MHz)"
                    binding.tvWifiBandCard.text =
                        "${WifiManager.bandLabel(metrics.wifiFrequencyMhz)} · ${metrics.wifiFrequencyMhz}MHz"
                } else if (!usbActive) {
                    // USB 链路激活时频段卡由 USB 分支维护，避免每 2s 的指标刷新覆盖
                    binding.tvWifiBandCard.text = "未连接"
                }
                binding.tvReconnectCount.text = "${metrics.reconnectCount} 次"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.wifiDeviceList.collect { devices ->
                wifiDevices = devices
                renderWifiCandidates(binding.apCandidateList, devices, ConnectMode.WIFI_AP)
                renderWifiCandidates(binding.staCandidateList, devices, ConnectMode.WIFI_STA)
                if (devices.isNotEmpty()) {
                    binding.tvStatusMessage.text = "发现 ${devices.size} 个 WiFi 相机"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isWifiScanning.collect { scanning ->
                binding.btnModeScan.isEnabled = !scanning
                binding.btnModeScan.text = if (scanning) "扫描中..." else "扫描相机"
                if (!scanning && wifiDevices.isEmpty()) {
                    binding.tvStatusMessage.text = "未发现 WiFi 相机"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentDevices.collect { devices ->
                renderRecentDevices(devices)
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
                        usbActive = true
                        // USB 链路下频段卡切换为 USB 状态信息（WiFi 频段无意义）
                        binding.ivWifiBandCard.setImageResource(R.drawable.ic_usb)
                        binding.tvWifiBandCard.text = "USB 已连接"
                        binding.tvConnectionStatus.text = "USB 已连接"
                        binding.tvUsbDevice.text = "USB 相机已连接，可直接传输照片"
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
                    UsbConnectionState.ERROR -> {
                        // 模块 6：细分失败原因，禁止统一报「未检测到 USB 相机」
                        binding.tvUsbDevice.text =
                            viewModel.usbErrorMessage.value ?: "USB 连接失败，正在重试…"
                    }

                    UsbConnectionState.PERMISSION_DENIED -> {
                        binding.tvUsbDevice.text =
                            viewModel.usbErrorMessage.value ?: "USB 权限被拒绝"
                    }

                    UsbConnectionState.REQUESTING_PERMISSION -> {
                        binding.tvUsbDevice.text = "正在请求 USB 访问权限…"
                    }

                    UsbConnectionState.CONNECTING -> {
                        binding.tvUsbDevice.text = "正在建立 USB 连接…"
                    }

                    UsbConnectionState.DISCONNECTED -> {
                        binding.tvUsbDevice.text = "未检测到 USB 相机"
                        // 切回 WiFi 链路：恢复频段卡，具体频段由指标刷新补齐
                        if (usbActive) {
                            usbActive = false
                            binding.ivWifiBandCard.setImageResource(R.drawable.ic_wifi_sta)
                            binding.tvWifiBandCard.text = "未连接"
                        }
                        if (viewModel.connectionState.value == ConnectionState.DISCONNECTED) {
                            binding.tvConnectionStatus.text = "未连接"
                            binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(requireContext(), R.color.on_dark_card_variant)
                            )
                            binding.btnConnect.visibility = View.GONE
                            binding.btnDisconnect.visibility = View.GONE
                        }
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbDeviceInfo.collect { info ->
                if (info != null) {
                    binding.tvCameraName.text = info.cameraModel
                    binding.tvUsbDevice.text = "${info.cameraModel} · 已连接 USB"
                }
            }
        }

        // 模块 6：分类错误文案变化时即时上屏（重试成功 → 清空恢复连接文案）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbErrorMessage.collect { message ->
                if (viewModel.usbState.value == UsbConnectionState.ERROR ||
                    viewModel.usbState.value == UsbConnectionState.PERMISSION_DENIED
                ) {
                    binding.tvUsbDevice.text = message ?: "USB 连接失败，正在重试…"
                }
            }
        }
    }

    private fun setupInteractions() {
        // 顶部设置入口 → Tab4
        binding.btnSettingsIcon.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainActivity.TAB_SETTINGS)
        }

        // 断开连接（顶部模式区负责发起连接）
        binding.btnDisconnect.pressEffect()
        binding.btnDisconnect.setOnClickListener {
            viewModel.disconnect()
            viewModel.disconnectUsb()
        }

        // 点击设备卡片快速重连
        binding.cardDevice.setOnClickListener {
            if (viewModel.connectionState.value == ConnectionState.DISCONNECTED) {
                connectForCurrentMode()
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
        // 当前 WiFi 频段卡片：展示当前链路频段（不可用时隐藏），点击跳设置页开启 5GHz 优先
        binding.cardWifiSpeed.pressEffect()
        binding.cardWifiSpeed.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainActivity.TAB_SETTINGS)
        }
        binding.cardQuickSettings.pressEffect()
        binding.cardQuickSettings.setOnClickListener {
            (activity as? MainActivity)?.switchToTab(MainActivity.TAB_SETTINGS)
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

    // ---------------- 连接模式 Tab ----------------

    private fun setupModeTabs() {
        binding.tabWifiAp.pressEffect()
        binding.tabWifiAp.setOnClickListener { selectMode(ConnectMode.WIFI_AP) }
        binding.tabWifiSta.pressEffect()
        binding.tabWifiSta.setOnClickListener { selectMode(ConnectMode.WIFI_STA) }
        binding.tabUsb.pressEffect()
        binding.tabUsb.setOnClickListener { selectMode(ConnectMode.USB) }

        binding.modeTabContainer.doOnLayout {
            modeTabWidth = binding.modeTabs.width / 3
            if (modeTabWidth > 0) {
                binding.modeTabIndicator.layoutParams = binding.modeTabIndicator.layoutParams.apply {
                    width = modeTabWidth
                }
                updateModeTabs(animate = false)
            }
        }
        updateModePanels()
        setupStaSubModes()
    }

    /**
     * WiFi STA 子模式（v1.0.2 拆分）：同一WiFi / 连接手机热点。
     * 两种模式的发现与路由策略不同（热点模式下相机在热点子网，NSD+ARP 兜底，
     * 连接时按相机 IP 子网匹配路由）；切换即持久化并更新教程文案。
     */
    private fun setupStaSubModes() {
        val chipSame = binding.chipStaSameWifi
        val chipHotspot = binding.chipStaHotspot
        chipSame.pressEffect()
        chipHotspot.pressEffect()

        fun render() {
            val hotspot = viewModel.settings.staSubMode ==
                com.nikonlink.app.shared.common.AppSettings.STA_MODE_PHONE_HOTSPOT
            chipSame.setBackgroundResource(
                if (!hotspot) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chipSame.setTextColor(
                ContextCompat.getColor(requireContext(), if (!hotspot) R.color.on_primary else R.color.text_primary)
            )
            chipHotspot.setBackgroundResource(
                if (hotspot) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chipHotspot.setTextColor(
                ContextCompat.getColor(requireContext(), if (hotspot) R.color.on_primary else R.color.text_primary)
            )
            binding.tvStaGuide.text = if (hotspot) {
                "1. 手机开启个人热点（相机需已记住该热点）\n2. 相机连接到手机热点\n3. 点「扫描相机」自动发现相机（NSD/ARP）\n4. 点「连接相机」完成 PTP/IP 配对"
            } else {
                "1. 相机与手机连接同一个路由器 / 局域网\n2. 点「扫描相机」自动发现相机\n3. 点「连接相机」完成 PTP/IP 配对"
            }
        }

        chipSame.setOnClickListener {
            viewModel.settings.staSubMode = com.nikonlink.app.shared.common.AppSettings.STA_MODE_SAME_WIFI
            render()
        }
        chipHotspot.setOnClickListener {
            viewModel.settings.staSubMode = com.nikonlink.app.shared.common.AppSettings.STA_MODE_PHONE_HOTSPOT
            render()
        }
        render()
    }

    private fun selectMode(mode: ConnectMode) {
        if (currentMode == mode) return
        currentMode = mode
        binding.scrollContent.smoothScrollTo(0, 0)
        updateModeTabs(animate = true)
        updateModePanels()
    }

    private fun updateModeTabs(animate: Boolean) {
        if (modeTabWidth > 0) {
            val targetX = currentMode.ordinal * modeTabWidth
            binding.modeTabIndicator.animate().cancel()
            if (animate) {
                binding.modeTabIndicator.animate()
                    .translationX(targetX.toFloat())
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                binding.modeTabIndicator.translationX = targetX.toFloat()
            }
        }
        modeTabsViews.forEachIndexed { index, tab ->
            val selected = index == currentMode.ordinal
            tab.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.on_primary else R.color.text_primary
                )
            )
            tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun updateModePanels() {
        modePanels.forEach { panel ->
            if (panel == panelFor(currentMode)) {
                panel.alpha = 0f
                panel.translationY = dpF(8f)
                panel.visibility = View.VISIBLE
                panel.animate().cancel()
                panel.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start()
            } else {
                panel.visibility = View.GONE
            }
        }
    }

    private fun panelFor(mode: ConnectMode): View {
        return when (mode) {
            ConnectMode.WIFI_AP -> binding.panelWifiAp
            ConnectMode.WIFI_STA -> binding.panelWifiSta
            ConnectMode.USB -> binding.panelUsb
        }
    }

    // ---------------- 扫描 / 连接 ----------------

    private fun setupModeActions() {
        binding.btnModeScan.pressEffect()
        binding.btnModeScan.setOnClickListener { scanForCurrentMode() }
        binding.btnModeConnect.pressEffect()
        binding.btnModeConnect.setOnClickListener { connectForCurrentMode() }
    }

    private fun scanForCurrentMode() {
        when (currentMode) {
            ConnectMode.WIFI_AP, ConnectMode.WIFI_STA -> {
                binding.tvStatusMessage.text = "正在扫描 WiFi 相机..."
                viewModel.scanWifi()
            }
            ConnectMode.USB -> {
                binding.tvStatusMessage.text = "正在检测 USB 相机..."
                viewModel.connectUsb()
            }
        }
    }

    private fun connectForCurrentMode() {
        when (currentMode) {
            ConnectMode.WIFI_AP, ConnectMode.WIFI_STA -> {
                val target = wifiDevices.firstOrNull()
                if (target != null) {
                    connectWifiCandidate(target)
                } else {
                    showManualIpDialog(currentMode)
                }
            }
            ConnectMode.USB -> {
                binding.tvStatusMessage.text = "正在建立 USB 通道..."
                viewModel.connectUsb()
            }
        }
    }

    private fun connectWifiCandidate(candidate: WifiCameraCandidate) {
        binding.tvStatusMessage.text = "连接: ${candidate.name} (${candidate.ipAddress})"
        viewModel.connectToWifiCamera(candidate)
    }

    private fun connectRecentDevice(device: PairedDevice) {
        val display = device.deviceName.ifBlank { "尼康相机" }
        binding.tvStatusMessage.text = "快速连接: $display"
        viewModel.connectToRecentDevice(device)
    }

    private fun renderWifiCandidates(
        container: LinearLayout,
        devices: List<WifiCameraCandidate>,
        mode: ConnectMode
    ) {
        container.removeAllViews()
        if (devices.isEmpty()) {
            container.addView(
                emptyHint(
                    if (mode == ConnectMode.WIFI_AP) {
                        "未发现相机，请先连接相机 WiFi 后再扫描"
                    } else {
                        // B4：失败原因可见化——不再笼统一句「未发现相机」。
                        // 统计来自 WifiScanner.ScanStats：探测了多少地址、mDNS/NSD 有无响应。
                        val stats = viewModel.wifiScanStats.value
                        if (stats == null) {
                            "未发现相机，请确认相机与手机在同一网络"
                        } else {
                            buildString {
                                append("未发现相机 · 已探测 ")
                                append(stats.probedHosts)
                                append(" 个地址（ARP 表 ")
                                append(stats.arpEntries)
                                append(" 条）均无响应")
                                if (stats.mdnsResponses == 0 && stats.nsdResponses == 0) {
                                    append("\nmDNS/NSD 均无响应：路由器可能过滤了组播，或开启了客户端隔离")
                                }
                                append("\n可点「连接」手动输入相机 IP（相机机身菜单可查）")
                            }
                        }
                    }
                )
            )
            return
        }
        devices.forEach { candidate ->
            val item = ItemWifiCandidateBinding.inflate(layoutInflater, container, false)
            item.tvCandidateName.text = candidate.name.ifBlank { "尼康相机" }
            item.tvCandidateInfo.text = "${candidate.ipAddress}:${candidate.port}"
            item.root.pressEffect()
            item.btnCandidateConnect.pressEffect()
            item.btnCandidateConnect.setOnClickListener { connectWifiCandidate(candidate) }
            container.addView(item.root)
        }
    }

    private fun renderRecentDevices(devices: List<PairedDevice>) {
        binding.recentDeviceList.removeAllViews()
        if (devices.isEmpty()) {
            binding.recentDeviceList.addView(emptyHint("暂无最近连接设备"))
            return
        }
        devices.forEach { device ->
            val item = ItemRecentDeviceBinding.inflate(
                layoutInflater,
                binding.recentDeviceList,
                false
            )
            item.tvRecentName.text = device.deviceName.ifBlank { "尼康相机" }
            item.tvRecentInfo.text = buildString {
                val endpoint = WifiEndpoint.parse(device.address)
                if (endpoint != null) {
                    append("WiFi · ").append(endpoint.host)
                } else {
                    append("BLE · ").append(device.address)
                }
                append(" · ").append(formatRecentTime(device.lastConnected))
            }
            item.root.pressEffect()
            item.btnRecentConnect.pressEffect()
            item.btnRecentConnect.setOnClickListener { connectRecentDevice(device) }
            binding.recentDeviceList.addView(item.root)
        }
    }

    private fun emptyHint(text: String): android.widget.TextView {
        return android.widget.TextView(requireContext()).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_tertiary))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        }
    }

    private fun formatRecentTime(time: Long): String {
        if (time <= 0) return "很久前"
        val diff = System.currentTimeMillis() - time
        return when {
            diff < 60_000 -> "刚刚"
            diff < 60 * 60_000 -> "${diff / 60_000} 分钟前"
            diff < 24 * 60 * 60_000 -> "${diff / (60 * 60_000)} 小时前"
            else -> "${diff / (24 * 60 * 60_000)} 天前"
        }
    }

    private fun showManualIpDialog(mode: ConnectMode) {
        val input = EditText(requireContext()).apply {
            hint = if (mode == ConnectMode.WIFI_AP) "192.168.1.1" else "192.168.1.1 或 192.168.1.1:15740"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(requireContext()).apply {
            setPadding(56, 28, 56, 8)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("手动连接 WiFi 相机")
            .setMessage(
                if (mode == ConnectMode.WIFI_AP) {
                    "请确认手机已连接相机发出的 WiFi，输入相机默认地址"
                } else {
                    // B4：补 IP 查看路径，降低 STA 用户找不到 IP 的门槛
                    "STA 模式下请输入相机在同一局域网中的 IP 地址\n" +
                        "（相机机身菜单「网络设置」可查看当前 IP，端口默认 15740 可省略）"
                }
            )
            .setView(container)
            .setPositiveButton("连接") { _, _ ->
                val endpoint = WifiEndpoint.parse(input.text?.toString())
                if (endpoint == null) {
                    binding.tvStatusMessage.text =
                        "IP 地址无效，请填写形如 192.168.1.1 的 IPv4 地址"
                    return@setPositiveButton
                }
                binding.tvStatusMessage.text = "连接 ${endpoint.display}"
                viewModel.connectToWifiCamera(
                    WifiCameraCandidate(endpoint.host, endpoint.port, "手动相机", "manual")
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024f) else "$mb MB"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dpF(value: Float): Float = value * resources.displayMetrics.density

    override fun onDestroyView() {
        super.onDestroyView()
        pairingDialog?.dismiss()
        pairingDialog = null
        _binding = null
    }
}

/**
 * 设备页顶部连接模式。
 */
private enum class ConnectMode {
    WIFI_AP,
    WIFI_STA,
    USB
}

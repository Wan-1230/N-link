package com.nikonlink.app.device

import com.nikonlink.app.shared.ui.NlFeedback
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
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
import com.nikonlink.app.shared.ui.glass.DepthLift
import com.nikonlink.app.shared.ui.glass.DockInset
import com.nikonlink.app.shared.ui.glass.GlassChrome
import com.nikonlink.app.shared.ui.glass.GlassInsetAware
import com.nikonlink.app.shared.ui.glass.GlassMotion
import com.nikonlink.app.shared.ui.glass.GlassTopBar
import com.nikonlink.app.shared.ui.glass.NlGlass
import com.nikonlink.app.shared.ui.glass.UiFlags
import com.nikonlink.app.shared.ui.glass.renderChipBackground
import com.nikonlink.app.MainActivity
import com.nikonlink.app.R
import com.nikonlink.app.device.model.CameraModelCatalog
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionHintKind
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.device.wifi_sta.WifiCameraCandidate
import com.nikonlink.app.shared.data.PairedDevice
import com.nikonlink.app.databinding.ItemRecentDeviceBinding
import com.nikonlink.app.databinding.ItemWifiCandidateBinding
import timber.log.Timber
import com.nikonlink.app.databinding.FragmentDashboardBinding
import com.nikonlink.app.device.ftp.FtpPushServer
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.camera.params.ShutterCountSource
import com.nikonlink.app.camera.params.ShutterCountState
import com.nikonlink.app.camera.params.ShutterFailReason
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Tab1 设备首页
 * 设备连接总览、相机基础信息、快捷功能入口、折叠详情
 * 交互：下拉刷新状态，点击设备卡片快速重连，入口带缩放反馈
 */
@AndroidEntryPoint
class DashboardFragment : Fragment(), GlassInsetAware {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    /** dock 悬浮时给滚动内容叠加的底部内衬 */
    private var dockInset: DockInset? = null

    /** 顶栏玻璃随滚动浮现 */
    private var topBarFx: GlassTopBar? = null
    private val viewModel: DashboardViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()

    private var wifiDevices: List<WifiCameraCandidate> = emptyList()
    private var pairingDialog: AlertDialog? = null
    private var detailExpanded = false
    private var cameraInfoRequested = false
    private var currentMode = ConnectMode.WIFI_AP
    private var modeTabWidth = 0

    /**
     * v2.6.3：STA 页当前的**连接架构**。
     *
     * 两种架构（PTP/IP 直连 / FTP 推送收图）共用同一个 STA 页签，靠顶部药丸分段控件切换，
     * 不新增页面、不新增路由。选择持久化在 [AppSettings.staArchitecture]。
     * AP / USB 两条链路完全不读这个字段。
     */
    private var staArch = AppSettings.STA_ARCH_PTP

    /** 首帧落位不平移（同相册页 tabIndicatorInitialized 的用意） */
    private var staArchInitialized = false
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
        setupDockFloat()
        UiFlags.observe(this) {
            applyDockSpace()
            styleTopBar()
        }
        setupObservers()
        setupInteractions()
        setupModeTabs()
        setupModeActions()
        setupStaArch()
        paramsViewModel.readAll()
    }

    // ---------------- v1.3.2：STA 扫描/连接所需的运行时权限 ----------------

    /** 用户答应权限后要继续执行的动作 */
    private var pendingStaAction: (() -> Unit)? = null

    private val staPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val action = pendingStaAction
        pendingStaAction = null
        val allGranted = granted.values.all { it }
        if (allGranted) {
            action?.invoke()
        } else {
            val msg = "缺少「定位 / 附近设备」权限：Android 12 起系统要求该权限才允许搜索 WiFi 相机"
            NlFeedback.show(requireContext(), msg, long = true)
            renderStatusLine()
            Timber.tag("Dashboard").w("STA permissions denied: " + granted.filterValues { !it }.keys)
        }
    }

    /**
     * v1.3.2（STA 修复）：Android 12 起 `ACCESS_FINE_LOCATION`、Android 13 起
     * `NEARBY_WIFI_DEVICES` 是 WiFi 相关 API 的前置条件。
     *
     * 旧实现把 `ACCESS_FINE_LOCATION` 放在 `SDK_INT < S` 的分支里申请 ——
     * 也就是说 Android 12 及以上**从来没有申请过**，`NEARBY_WIFI_DEVICES` 更是
     * 只声明不申请。缺少权限时系统会静默拒绝 WiFi 扫描/发现类调用，
     * 用户侧的表现就是"STA 完全搜不到相机"。这里在每次 STA 扫描/连接前补齐。
     */
    private fun missingStaPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return missing
    }

    /** 权限齐备则直接执行，否则先申请、授权后再执行 */
    private fun ensureStaPermissions(then: () -> Unit) {
        val missing = missingStaPermissions()
        if (missing.isEmpty()) {
            then()
            return
        }
        pendingStaAction = then
        staPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun setupObservers() {
        // 连接状态（灰度表达）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                val (text, colorRes, loading) = when (state) {
                    ConnectionState.FULLY_CONNECTED ->
                        Triple("已连接", R.color.status_connected, false)
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
                // FR-36：连上/断开都要重算相机全称——断开时参数值已被新会话复位，
                // 不重算就会把上一台相机的名字永久留在设备页。
                renderCameraName()
                binding.swipeRefresh.isRefreshing = false
                // 连接状态变化后统一刷新状态行（避免各处直接写 tvStatusMessage 造成互相覆盖）
                renderStatusLine()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statusMessage.collect { renderStatusLine() }
        }

        // STA 主机注册：进度/结果展示。长文案放卡片文本，不塞状态行（共享窄条规则）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hostReg.collect { state ->
                when (state) {
                    null -> {
                        binding.tvStaHostRegStatus.visibility = View.GONE
                        binding.btnStaHostRegister.isEnabled = true
                        // 结果清空后回到引导提示（若仍需要注册）
                        updateHostRegHint(
                            viewModel.staRegisterNeeded.value,
                            viewModel.staHostRegistered.value
                        )
                    }
                    is HostRegUiState.Running -> {
                        binding.tvStaHostRegStatus.visibility = View.VISIBLE
                        binding.tvStaHostRegStatus.text = state.progress
                        binding.btnStaHostRegister.isEnabled = false
                    }
                    is HostRegUiState.Success -> {
                        binding.tvStaHostRegStatus.visibility = View.VISIBLE
                        binding.tvStaHostRegStatus.text = state.message
                        binding.btnStaHostRegister.isEnabled = true
                    }
                    is HostRegUiState.Failure -> {
                        binding.tvStaHostRegStatus.visibility = View.VISIBLE
                        binding.tvStaHostRegStatus.text = state.message
                        binding.btnStaHostRegister.isEnabled = true
                    }
                }
            }
        }

        // 是否需要注册：true → 按钮切强调色并给出提示（相机拒绝过握手，或从未注册过）
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.staRegisterNeeded, viewModel.staHostRegistered) { needed, registered ->
                needed to registered
            }.collect { (needed, registered) ->
                renderStaHostRegisterButton(needed)
                updateHostRegHint(needed, registered)
            }
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
                if (info.modelName.isNotBlank()) renderCameraName()
                if (info.lensName.isNotBlank()) binding.tvLens.text = info.lensName

                // 快门次数：尼康无快门计数 PTP 属性，读数来自样张照片 MakerNote 的 0x00A7。
                // 来源与可信度必须呈现给用户 —— 「未校验」表示只过了数值合理性、没过恒等式；
                // 「云端解析」表示原片被交给第三方解析，见 PRD v2.4。
                val shutterRowVisible = info.shutterQueryState != ShutterCountState.NONE ||
                        info.modelName.isNotBlank()
                binding.rowShutterCount.visibility =
                    if (shutterRowVisible) View.VISIBLE else View.GONE
                binding.tvShutterCount.text = when (info.shutterQueryState) {
                    ShutterCountState.QUERYING -> getString(R.string.shutter_count_querying)
                    ShutterCountState.SUCCESS -> buildString {
                        append(getString(R.string.shutter_count_value, info.shutterCount))
                        // FR-10：样张之间对不上就报区间。快门计数不会倒退，
                        // 从两个数里挑一个装成精确值，比承认"这几张解得不一致"更坏。
                        info.shutterCountRange?.let {
                            append(getString(R.string.shutter_count_spread, it.first, it.last))
                        }
                        append(
                            if (info.shutterCountSource == ShutterCountSource.CLOUD) {
                                getString(R.string.shutter_count_source_cloud)
                            } else if (info.shutterVerified) {
                                getString(R.string.shutter_count_source_local)
                            } else {
                                getString(R.string.shutter_count_source_local_unverified)
                            }
                        )
                        // Z8/Z9 等机身的 0x00A7 含电子快门触发，与售后口径不一致时把仅机械值一并给出
                        if (info.shutterMechanicalCount in 0 until info.shutterCount) {
                            append(getString(R.string.shutter_count_mechanical, info.shutterMechanicalCount))
                        }
                    }
                    ShutterCountState.FAILED -> getString(
                        when (info.shutterFailReason) {
                            ShutterFailReason.NO_MEDIA -> R.string.shutter_count_failed_no_media
                            ShutterFailReason.PARSE_FAILED -> R.string.shutter_count_failed_parse
                            ShutterFailReason.CONSENT_REQUIRED ->
                                R.string.shutter_count_failed_consent
                            else -> R.string.shutter_count_failed_read
                        }
                    )
                    ShutterCountState.NONE -> getString(R.string.shutter_count_idle)
                }
                binding.tvShutterCount.isClickable = shutterRowVisible
                binding.tvShutterCount.setOnClickListener {
                    // 「本机解不出」是唯一值得点开的分支——它要问一次云端授权，不是简单重试
                    if (info.shutterFailReason == ShutterFailReason.CONSENT_REQUIRED) {
                        askShutterCloudConsent()
                    } else {
                        paramsViewModel.retryShutterCountQuery()
                    }
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
                // FR-37：STA 那一栏的行内「连接」按钮按所选架构分流——FTP 推送架构下手机侧
                // 没有可连的对象，点一下却发起 PTP/IP 直连，正是用户报的「切了没切」。
                renderWifiCandidates(
                    binding.staCandidateList,
                    devices,
                    ConnectMode.WIFI_STA,
                    staArch
                )
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
                // FR-36：配对记录是相机全称的第五顺位，记录到位后补一次渲染
                renderCameraName()
            }
        }

        // 配对引导弹窗（单一流程，不叠加）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionHint.collect { hint ->
                if (hint != null) {
                    val builder = NlGlass.dialog(requireContext())
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
                            ContextCompat.getColor(requireContext(), R.color.status_connected)
                        )
                        binding.btnConnect.visibility = View.GONE
                        binding.btnDisconnect.visibility = View.VISIBLE
                        if (!cameraInfoRequested) {
                            cameraInfoRequested = true
                            paramsViewModel.readAll()
                        }
                        renderStatusLine()
                    }
                    UsbConnectionState.ERROR -> {
                        // 模块 6：细分失败原因，禁止统一报「未检测到 USB 相机」
                        // 提示全文写进 USB 卡片（可换行、不截断）；状态行由 renderStatusLine 用短文案
                        binding.tvUsbDevice.text =
                            viewModel.usbErrorMessage.value ?: "USB 连接失败，正在重试…"
                        renderStatusLine()
                    }

                    UsbConnectionState.PERMISSION_DENIED -> {
                        binding.tvUsbDevice.text =
                            viewModel.usbErrorMessage.value ?: "USB 权限被拒绝"
                        renderStatusLine()
                    }

                    UsbConnectionState.REQUESTING_PERMISSION -> {
                        binding.tvUsbDevice.text = "正在请求 USB 访问权限…"
                        renderStatusLine()
                    }

                    UsbConnectionState.CONNECTING -> {
                        binding.tvUsbDevice.text = "正在建立 USB 连接…"
                        renderStatusLine()
                    }

                    UsbConnectionState.DISCONNECTED -> {
                        // USB 卡片（仅 USB 面板可见）保留完整指引；状态行用短文案，见 renderStatusLine
                        binding.tvUsbDevice.text =
                            viewModel.usbErrorMessage.value ?: "未检测到 USB 相机"
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
                        renderStatusLine()
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbDeviceInfo.collect { info ->
                if (info != null) {
                    // FR-36：相机全称统一走唯一出口（PID 表名/DeviceInfo 原文都在里面归一化）
                    renderCameraName()
                    val display = CameraModelCatalog.official(info.cameraModel)
                        ?: info.cameraModel
                    binding.tvUsbDevice.text = "$display · 已连接 USB"
                }
                renderStatusLine()
            }
        }

        // 模块 6：分类错误文案变化时即时上屏（重试成功 → 清空恢复连接文案）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbErrorMessage.collect { message ->
                when (viewModel.usbState.value) {
                    UsbConnectionState.ERROR,
                    UsbConnectionState.PERMISSION_DENIED,
                    // v1.3.0：DISCONNECTED 也要显示原因（例如"未检测到 USB 相机"），
                    // 但仅限 USB 场景 —— 否则 WiFi 连接页会出现 USB 专属文案（v1.3.0 反馈问题 1）
                    UsbConnectionState.DISCONNECTED -> {
                        if (isUsbMode()) {
                            binding.tvUsbDevice.text = message ?: "未检测到 USB 相机"
                        }
                        renderStatusLine()
                    }
                    else -> {}
                }
            }
        }
    }

    /** 当前是否处于「USB 有线连接」场景（用户停在 USB 页签，或 USB 链路已激活） */
    private fun isUsbMode(): Boolean = currentMode == ConnectMode.USB || usbActive

    /**
     * 注册按钮下方的引导提示。注册结果文本优先，不被引导覆盖。
     * 两种情形文案不同：从未注册（要教怎么注册）vs 已注册但相机仍拒绝（要教相机侧动作）。
     */
    private fun updateHostRegHint(needed: Boolean, registered: Boolean) {
        if (viewModel.hostReg.value != null) return
        val tv = binding.tvStaHostRegStatus
        when {
            !needed -> tv.visibility = View.GONE
            !registered -> {
                tv.visibility = View.VISIBLE
                tv.text = "相机没有记住本机 —— 需要先做一次主机注册（全程只需一次）：\n" +
                        "相机菜单进入「Wi-Fi连接（STA mode）」创建配置文件，" +
                        "停在「正在等待连接」画面；\n" +
                        "手机与相机同网后，点上方「STA 主机注册」，相机屏幕按 OK。\n" +
                        "注意：普通的「连接至智能设备」热点不接受注册（会回 0x201F）。"
            }
            else -> {
                tv.visibility = View.VISIBLE
                tv.text = "相机未接受本次连接：注册已经通过，多半是相机还没停在 STA 等待画面。\n" +
                        "请在相机菜单进入「WiFi 连接（STA mode）」，连上网络后停在等待画面，" +
                        "再点「扫描相机」→「连接相机」。"
            }
        }
    }

    /**
     * 注册按钮的视觉状态：需要注册时用主色强调（引导点击），已注册后回落到描边样式。
     */
    private fun renderStaHostRegisterButton(needed: Boolean) {
        val btn = binding.btnStaHostRegister
        if (needed) {
            btn.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_primary))
            btn.strokeWidth = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            btn.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            btn.strokeWidth = dpF(1f).toInt()
            btn.strokeColor =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.outline))
        }
    }

    /** FR-36：所有名称来源都落空时的兜底 —— 品牌名，与布局里的默认值同一个词。 */
    private val defaultCameraName = "Nikon"

    /**
     * FR-36：设备页「相机全称」的**唯一渲染出口**（与 [renderStatusLine] 同一条规矩）。
     *
     * 旧写法两处各写各的，两个症状都从这里来：
     * - 「显示的不是官方名称」：PTP 通道直接写机身自报串（`NIKON Z 50II` 这种全大写形态，
     *   见 `UsbPtpProtocol.parseDeviceInfoModel` 注释），USB 通道写 PID 映射表名，
     *   未知 PID 显示 `Nikon Camera (0x0FFF)`；
     * - 「要么不显示」：两处都只在拿到值时才写，GetDeviceInfo 还没回来（或压根不返回 Model）
     *   时界面就停在布局里写死的 `Nikon`（`fragment_dashboard.xml` tvApGuide 同级的 tvCameraName）。
     *
     * 取值优先级（越靠前越权威，识别不出机型就跳到下一个，**绝不猜**）：
     * ① 参数轮询解析的 Model（USB 与 PTP/IP 两条通道都会填，最完整）
     * ② PTP/IP 会话建立那次 `GetDeviceInfo` 记下的原文（比①早一整轮，连上即有）
     * ③ USB 的型号（DeviceInfo 回填，未回填时是 PID 表名）
     * ④ BLE 广播名里认出的机型
     * ⑤ 配对记录里存的机型
     * 全落空 → `Nikon`（品牌名，不再显示 IP / MAC / 十六进制 PID）
     */
    private fun renderCameraName() {
        if (_binding == null) return
        val usbModel = viewModel.usbDeviceInfo.value?.cameraModel
            ?.takeIf { viewModel.usbState.value == UsbConnectionState.CONNECTED }
        val name = CameraModelCatalog.official(paramsViewModel.cameraInfo.value.modelName)
            ?: CameraModelCatalog.official(viewModel.sessionCameraModel())
            ?: CameraModelCatalog.official(usbModel)
            ?: viewModel.discoveredCameraName()
            ?: viewModel.pairedCameraName()
            ?: defaultCameraName
        if (binding.tvCameraName.text != name) binding.tvCameraName.text = name
    }

    /**
     * 状态行（tvStatusMessage）的**唯一渲染出口**。
     *
     * 为什么必须收敛到一处：`tvStatusMessage` 宽 160dp、单行省略号，且 StateFlow 不会重发
     * 未变化的值。此前 USB 与无线的收集器各写各的，USB 的自动探测结果一旦后于无线状态写入，
     * 就会把 USB 专属文案永久留在 WiFi 连接页上（v1.3.0 反馈问题 1）。
     *
     * 规则：
     * - USB 已连上 → 显示「已连接（USB）：机型」（USB 优先，跨页签都成立）；
     * - 停留在 USB 页签时 → 显示 USB 过程/失败**短文案**（长指引只看 USB 卡片，避免被截断）；
     * - 其余一切情况 → 一律回落到无线状态机的文案（WiFi AP / STA / BLE / 未连接）。
     */
    private fun renderStatusLine() {
        val usb = viewModel.usbState.value
        // FR-36：状态行也用归一化后的机型（未识别时保留原文，不猜）
        val model = CameraModelCatalog.official(viewModel.usbDeviceInfo.value?.cameraModel)
            ?: viewModel.usbDeviceInfo.value?.cameraModel
        val text = when {
            usb == UsbConnectionState.CONNECTED ->
                if (model.isNullOrBlank()) "已连接（USB）" else "已连接（USB）：$model"

            isUsbMode() -> when (usb) {
                UsbConnectionState.REQUESTING_PERMISSION -> "正在请求 USB 权限…"
                UsbConnectionState.CONNECTING -> "正在建立 USB 通道…"
                UsbConnectionState.PERMISSION_DENIED -> "USB 权限被拒绝"
                UsbConnectionState.ERROR -> "USB 连接失败"
                // 短文案：完整指引（"…请用数据线连接相机并开机，再点连接"）留在 USB 卡片里
                UsbConnectionState.DISCONNECTED -> "未检测到 USB 相机"
                UsbConnectionState.CONNECTED -> "已连接（USB）"
            }

            else -> viewModel.statusMessage.value
        }
        binding.tvStatusMessage.text = text.ifBlank { "未连接" }
    }

    /**
     * 云端解析授权（PRD v2.4 §L4）：本机解析读不出时的唯一退路要把相机原片交给第三方
     * EXIF 接口，原片含机身序列号 / 镜头信息 / GPS，所以必须问一次、说清楚上传什么。
     * 同意后持久生效，不再打扰。
     */
    private fun askShutterCloudConsent() {
        NlGlass.dialog(requireContext())
            .setTitle(R.string.shutter_cloud_consent_title)
            .setMessage(R.string.shutter_cloud_consent_message)
            .setNegativeButton(R.string.shutter_cloud_consent_reject, null)
            .setPositiveButton(R.string.shutter_cloud_consent_accept) { _, _ ->
                paramsViewModel.grantShutterCloudConsentAndRetry()
            }
            .show()
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

        // STA 主机注册（ZDROP 式）：先引导用户在相机端进入「连接至 PC」向导，再执行注册
        binding.btnStaHostRegister.pressEffect()
        binding.btnStaHostRegister.setOnClickListener {
            NlGlass.dialog(requireContext())
                .setTitle("STA 主机注册")
                .setMessage(
                    "作用：把本机登记进相机的「信任主机」列表。\n" +
                        "尼康相机在 STA 模式下只接受已注册的主机，没注册过的连接会被相机直接拒绝。\n\n" +
                        "前提：相机必须停在主机配置向导的等待画面\n" +
                        "  · 相机菜单 →「Wi-Fi连接（STA mode）」创建配置文件" +
                        "（旧固件在「连接至 PC」→ 网络设定里）\n" +
                        "  · 连上网后相机停在「正在等待连接」，这时才受理注册\n" +
                        "  · 中途退出向导、或只开了普通的「连接至智能设备」热点，" +
                        "注册会失败并回 0x201F\n\n" +
                        "操作（全程只需一次）：\n" +
                        " ① 让相机连上本机热点（或与手机同一路由器），停在向导等待画面；" +
                        "不需要先连相机，点注册后本端会自动找到它\n" +
                        " ② 点「开始注册」，相机屏幕出现确认提示时按 OK，约 10 秒完成\n" +
                        " ③ 相机只会开自己热点的机型，也可先在「WiFi-AP」标签连上 AP 再回来注册\n\n" +
                        "注册成功后，日常用 STA 连接不需要再做这一步。"
                )
                .setPositiveButton("开始注册") { _, _ -> viewModel.registerStaHost() }
                .setNegativeButton("取消", null)
                .show()
        }
        // 结果卡片点击即清除（进行中的状态不可清除）
        binding.tvStaHostRegStatus.setOnClickListener { viewModel.clearHostReg() }

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
            chipSame.renderChipBackground(!hotspot)
            chipSame.setTextColor(
                ContextCompat.getColor(requireContext(), if (!hotspot) R.color.on_primary else R.color.text_primary)
            )
            chipHotspot.renderChipBackground(hotspot)
            chipHotspot.setTextColor(
                ContextCompat.getColor(requireContext(), if (hotspot) R.color.on_primary else R.color.text_primary)
            )
            binding.tvStaGuide.text = if (hotspot) {
                "首次使用先注册一次（之后不再需要）：\n" +
                    "  相机菜单 →「Wi-Fi连接（STA mode）」创建配置文件（旧固件在「连接至 PC」→ 网络设定里）。\n" +
                    "  向导连上网后会停在「正在等待连接」的画面 —— 注册必须在这一步做：\n" +
                    "  让手机与相机处于同一网络，回到本页点「STA 主机注册」，相机屏幕按 OK（约 10 秒）。\n" +
                    "  ⚠ 普通的「连接至智能设备」热点也能连上相机，但它不接受注册，点了报 0x201F。\n\n" +
                    "日常连接：\n" +
                    "  ① 手机开启个人热点\n" +
                    "  ② 相机菜单进入「WiFi 连接（STA mode）」，加入手机热点，停在等待画面\n" +
                    "  ③ 点「扫描相机」自动发现 → 点「连接相机」\n" +
                    "搜不到相机时：确认相机停在等待画面、热点没有设为隐藏、手机没开 VPN"
            } else {
                "首次使用先注册一次（之后不再需要）：\n" +
                    "  相机菜单 →「Wi-Fi连接（STA mode）」创建配置文件（旧固件在「连接至 PC」→ 网络设定里）。\n" +
                    "  向导连上网后会停在「正在等待连接」的画面 —— 注册必须在这一步做：\n" +
                    "  让手机与相机处于同一网络，回到本页点「STA 主机注册」，相机屏幕按 OK（约 10 秒）。\n" +
                    "  ⚠ 普通的「连接至智能设备」热点也能连上相机，但它不接受注册，点了报 0x201F。\n\n" +
                    "日常连接：\n" +
                    "  ① 手机与相机连接同一个路由器\n" +
                    "  ② 相机菜单进入「WiFi 连接（STA mode）」，选择该路由器，停在等待画面\n" +
                    "  ③ 点「扫描相机」自动发现 → 点「连接相机」\n" +
                    "搜不到相机时：确认手机没开 VPN、路由器没开 AP 隔离（两者都会挡掉设备发现）"
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

    // ─────────────── v2.6.3：STA 双架构（PTP/IP 直连 · FTP 推送收图）───────────────
    //
    // 需求要点与实现对应：
    //  · 入口统一 → 同一个 WiFi STA 页签，顶部药丸分段控件切架构，不新增页面/路由
    //  · 切换控件 → staArchPill（FrameLayout + 指示器 View + 等宽 TextView 行），
    //    原型与相册页 albumTabPill 一致：选中 = 黑底白字加粗 + 指示器滑动
    //  · 教程随动 → tvStaGuide（架构 A）与 tvFtpGuide（架构 B）各一份，切换即换
    //  · 状态隔离 → 架构 B 的状态全在 FtpPushServer 自己的 StateFlow 里；
    //    切走时停服务器 + 停扫描，不留"看不见却在跑"的中间态
    //  · 规范一致 → 复用 bg_chip / bg_chip_selected / GlassMotion.slidePill /
    //    NlButton.* 与 page_margin，不引入新框架

    private fun setupStaArch() {
        staArch = viewModel.settings.staArchitecture

        binding.tabStaArchPtp.pressEffect()
        binding.tabStaArchFtp.pressEffect()
        binding.tabStaArchPtp.setOnClickListener { selectStaArch(AppSettings.STA_ARCH_PTP) }
        binding.tabStaArchFtp.setOnClickListener { selectStaArch(AppSettings.STA_ARCH_FTP) }

        binding.btnFtpToggle.pressEffect()
        binding.btnFtpToggle.setOnClickListener {
            if (viewModel.ftpState.value is FtpPushServer.State.Listening) {
                viewModel.stopFtpServer()
            } else {
                viewModel.startFtpServer()
            }
            renderFtpUi()
        }

        // 两个流各自订阅、都只重绘 FTP 那一块；PTP 侧的状态由既有 observer 管，互不引用
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.ftpState.collect { renderFtpUi() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.ftpClients.collect { renderFtpUi() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.ftpReceived.collect { renderFtpUi() }
        }

        renderStaArch(animate = false)
    }

    private fun selectStaArch(arch: String) {
        if (staArch == arch) return
        val leavingFtp = staArch == AppSettings.STA_ARCH_FTP
        staArch = arch
        viewModel.settings.staArchitecture = arch

        if (arch == AppSettings.STA_ARCH_FTP) {
            // 进 FTP 架构：把架构 A 独有的在途状态收干净（扫描结果与轮询不属于这里）
            viewModel.stopScan()
        } else if (leavingFtp) {
            // 离开 FTP 架构：停服务器、释放端口。这是**显式**的用户动作，
            // 所以在途推送会被中断 —— 教程里写明了这一点，不做静默保留。
            viewModel.stopFtpServer()
        }
        renderStaArch(animate = true)
    }

    private fun renderStaArch(animate: Boolean) {
        if (_binding == null) return
        val isFtp = staArch == AppSettings.STA_ARCH_FTP

        // 指示器宽度 = 行宽 / 段数。Fragment 初始 hidden（GONE 不参与布局）时宽度为 0，
        // 所以做有限次重试（同相册页 applyIndicator 的处理）。
        val row = binding.staArchPill
        fun applyIndicator(retries: Int) {
            if (_binding == null) return
            val rowWidth = row.width - row.paddingLeft - row.paddingRight
            if (rowWidth <= 0) {
                if (retries > 0) {
                    binding.staArchIndicator.postDelayed({ applyIndicator(retries - 1) }, 64)
                }
                return
            }
            val segWidth = rowWidth / 2
            if (binding.staArchIndicator.layoutParams.width != segWidth) {
                binding.staArchIndicator.layoutParams.width = segWidth
                binding.staArchIndicator.requestLayout()
            }
            GlassMotion.slidePill(
                binding.staArchIndicator,
                (if (isFtp) 1 else 0) * segWidth.toFloat(),
                animated = staArchInitialized && animate,
            )
            staArchInitialized = true
        }
        applyIndicator(8)

        fun styleTab(tab: android.widget.TextView, selected: Boolean) {
            tab.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.on_primary else R.color.text_primary
                )
            )
            tab.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        styleTab(binding.tabStaArchPtp, !isFtp)
        styleTab(binding.tabStaArchFtp, isFtp)

        binding.layoutStaPtp.visibility = if (isFtp) View.GONE else View.VISIBLE
        binding.layoutStaFtp.visibility = if (isFtp) View.VISIBLE else View.GONE
        binding.tvFtpGuide.text = FTP_GUIDE
        applyActionRowVisibility()
        renderFtpUi()
    }

    /**
     * 架构 B 的状态渲染。数据全部来自 [FtpPushServer] 的单例状态，
     * 所以页面重建（旋转 / 切页回来）时能直接反映"服务器还在跑"的真实情况，
     * 不需要在 Fragment 里留副本。
     */
    private fun renderFtpUi() {
        if (_binding == null) return
        val ctx = requireContext()
        val clients = viewModel.ftpClients.value

        binding.tvFtpUser.text = FtpPushServer.USER
        binding.tvFtpPass.text = viewModel.ftpPassword

        when (val state = viewModel.ftpState.value) {
            is FtpPushServer.State.Listening -> {
                binding.tvFtpState.text =
                    if (clients > 0) "正在接收：$clients 个连接" else "正在监听，等待相机连上来"
                binding.tvFtpEndpoint.text = "${state.host} : ${state.port}"
                binding.btnFtpToggle.text = "停止 FTP 服务器"
                binding.tvFtpHint.visibility = View.VISIBLE
                binding.tvFtpHint.text =
                    "把上面的地址和端口填进相机的 FTP 配置，登录方式选「被动（PASV）」。"
            }

            is FtpPushServer.State.Failed -> {
                binding.tvFtpState.text = "启动失败：${state.message}"
                binding.tvFtpEndpoint.text = "—"
                binding.btnFtpToggle.text = "重试启动"
                binding.tvFtpHint.visibility = View.VISIBLE
                binding.tvFtpHint.text =
                    "端口被占用时重启应用再试；也确认没有别的 FTP 服务占着 ${FtpPushServer.DEFAULT_PORT}。"
            }

            FtpPushServer.State.Stopped -> {
                binding.tvFtpState.text = "未启动"
                val ips = viewModel.ftpCandidateAddresses()
                binding.tvFtpEndpoint.text = if (ips.isEmpty()) {
                    "未获取到局域网地址（手机先连上 WiFi 或开热点）"
                } else {
                    "相机侧填：${ips.first()} : ${FtpPushServer.DEFAULT_PORT}"
                }
                binding.btnFtpToggle.text = "启动 FTP 服务器"
                binding.tvFtpHint.visibility = View.GONE
            }
        }

        val list = viewModel.ftpReceived.value
        binding.tvFtpStats.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        binding.tvFtpStats.text =
            "已接收 ${list.size} 个文件 · ${viewModel.formatFtpBytes(list.sumOf { it.bytes })}"

        // 接收记录：代码建行，不新增 XML 布局文件（与预览页的做法一致）
        val container = binding.ftpLogList
        container.removeAllViews()
        list.take(6).forEach { item ->
            val ok = item.savedPath != null
            val tv = android.widget.TextView(ctx).apply {
                text = buildString {
                    append(viewModel.formatFtpTime(item.at)).append("  ")
                    append(item.name).append("  ")
                    append(viewModel.formatFtpBytes(item.bytes))
                    if (!ok) append("（保存失败）")
                }
                textSize = 12f
                setTextColor(
                    ContextCompat.getColor(ctx, if (ok) R.color.text_secondary else R.color.text_tertiary)
                )
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            container.addView(tv)
        }
    }

    /**
     * 架构 B 的教程。内容与 `FtpPushServer` 的真实行为逐条对应：
     * 端口取 [FtpPushServer.DEFAULT_PORT]（非 21，因为绑定 <1024 端口在 Android 上要 root），
     * 保存位置与 `AppSettings.savePath` 一致，架构切换会停服务器。
     */
    private val FTP_GUIDE: String
        get() = buildString {
            append("原理：相机主动把照片推给本机，全程不做主机注册 —— ")
            append("这是尼康官方菜单里的另一条路，绕开了 STA 模式对未注册主机的门控。\n\n")
            append("【相机侧】菜单 →「连接到 FTP 服务器」→ 网络设定 → 创建配置文件 → 连接向导\n")
            append("  · 服务器地址 / 端口 / 用户名 / 密码：按下方的值填，一个都不能错\n")
            append("  · 登录方式：被动（PASV）\n")
            append("  · 连接方式：与手机同一路由器，或相机开热点、手机连上去\n\n")
            append("【手机侧】点「启动 FTP 服务器」，保持本页开着即可。\n")
            // FR-37：架构选择现在真的管住连接行为了，必须在教程里写明白，否则用户会以为 App 卡了
            append("  · 选了本架构后，App 不再在后台自动用 PTP/IP 连相机（机身只有一个 PTP 客户端槽，" +
                "敲门会打断推送）；要做监看 / 相册，点「连接相机」临时直连即可\n")
            append("  · 收到的照片按「设置 → 传输设置 → 默认保存路径」存进 ")
            append("DCIM/N-Link 或 Download/N-Link，和相册页下载的照片在同一处\n")
            append("  · 点「PTP/IP 直连」标签会自动停止服务器（在途推送会中断）\n\n")
            append("【收不到照片】① 相机里的地址 / 端口 / 密码与本页是否完全一致；")
            append("② 手机不要开 VPN（会让相机连不上本机）；③ 看下方「接收记录」有没有动静")
        }

    private fun selectMode(mode: ConnectMode) {
        if (currentMode == mode) return
        currentMode = mode
        binding.scrollContent.smoothScrollTo(0, 0)
        updateModeTabs(animate = true)
        updateModePanels()
        // 页签切换会改变"是否 USB 场景"的判定 → 立即按新场景重渲染状态行，
        // 避免离开 USB 页后仍残留 USB 专属文案（v1.3.0 反馈问题 1）
        renderStatusLine()
    }

    private fun updateModeTabs(animate: Boolean) {
        if (modeTabWidth > 0) {
            val targetX = currentMode.ordinal * modeTabWidth
            GlassMotion.slidePill(binding.modeTabIndicator, targetX.toFloat(), animate)
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
        applyActionRowVisibility()
        // v2.6.3：STA 面板初始是 GONE，GONE 不参与布局 → 那一刻 staArchPill.width == 0，
        // 指示器量不出段宽（首帧只落一个 0 宽的胶囊）。所以**每次进入 STA 页签**都重算一次，
        // 而不是只在 onViewCreated 里算一次 —— 后者会让指示器一直停在全宽/零宽。
        if (currentMode == ConnectMode.WIFI_STA) renderStaArch(animate = false)
    }

    /**
     * 扫描 / 连接按钮行的显隐（v2.6.3）。
     *
     * 只有「STA + FTP 推送收图」这一种组合要隐藏：FTP 架构下相机是**主动推送方**，
     * 手机侧没有可发现、可连接的对象，留着两个按钮只会让人以为还要点。
     * 判据里显式带上 `currentMode`，所以：
     *  · AP / USB 两个页签**恒为 VISIBLE**（硬约束：这两条链路的界面不受影响）
     *  · 从 STA 的 FTP 架构切到别的页签，一定恢复
     */
    private fun applyActionRowVisibility() {
        if (_binding == null) return
        val hide = currentMode == ConnectMode.WIFI_STA && staArch == AppSettings.STA_ARCH_FTP
        binding.actionContainer.visibility = if (hide) View.GONE else View.VISIBLE
        applyActionEmphasis()
    }

    /**
     * FR-35②：AP 页签把「扫描相机」降成次按钮。
     *
     * 免扫描（FR-34①）之后扫描只是「多台相机里挑一台」的可选工具，但它在界面上与
     * 「连接相机」等宽、同样描边，用户按老习惯仍然先点它 —— 这是「一键连接没落地」
     * 的另一半成因。这里只改**主次表达**（文案 + 宽度权重），不动任何行为：
     * STA 页签扫描仍是必经步骤，维持等宽与原文案。
     */
    private fun applyActionEmphasis() {
        if (_binding == null) return
        val ap = currentMode == ConnectMode.WIFI_AP
        binding.btnModeScan.text = if (ap) "扫描（可选）" else "扫描相机"
        (binding.btnModeConnect.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = if (ap) 1.6f else 1f
            binding.btnModeConnect.layoutParams = it
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
                ensureStaPermissions {
                    binding.tvStatusMessage.text = "正在扫描 WiFi 相机..."
                    viewModel.scanWifi()
                }
            }
            ConnectMode.USB -> {
                binding.tvStatusMessage.text = "正在检测 USB 相机..."
                viewModel.connectUsb()
                // 兜底收口：若探测结果与上一次完全相同（StateFlow 不会重发），
                // 1.5s 后按状态重渲染一次，避免"正在检测…"永久留在状态行
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1500)
                    if (_binding != null) renderStatusLine()
                }
            }
        }
    }

    private fun connectForCurrentMode() {
        when (currentMode) {
            ConnectMode.WIFI_AP, ConnectMode.WIFI_STA -> ensureStaPermissions {
                val target = wifiDevices.firstOrNull()
                if (target != null) {
                    connectWifiCandidate(target)
                } else if (currentMode == ConnectMode.WIFI_AP && viewModel.apOneTapEnabled()) {
                    // FR-34①：AP 模式免扫描一键连接（ZDROP 同构）
                    apOneTapConnect()
                } else {
                    showManualIpDialog(currentMode)
                }
            }
            ConnectMode.USB -> {
                binding.tvStatusMessage.text = "正在建立 USB 通道..."
                viewModel.connectUsb()
                // 同 scanForCurrentMode：状态未变化时 StateFlow 不会重发，这里兜底重渲染
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(1500)
                    if (_binding != null) renderStatusLine()
                }
            }
        }
    }

    private fun connectWifiCandidate(candidate: WifiCameraCandidate) {
        // FR-37：与上面同一条规则，覆盖「连接相机」大按钮这条入口（STA+FTP 时按钮虽被隐藏，
        // 但候选行、快捷入口都可能走到这里）
        if (currentMode == ConnectMode.WIFI_STA && staArch == AppSettings.STA_ARCH_FTP) {
            binding.tvStatusMessage.text =
                "FTP 推送架构：手机只接收，不直连。请启动下方 FTP 服务器并在相机里填同一地址"
            return
        }
        binding.tvStatusMessage.text = "连接: ${candidate.name} (${candidate.ipAddress})"
        viewModel.connectToWifiCamera(candidate)
    }

    /**
     * FR-34① + FR-35：AP 免扫描一键连接，三条路按「用户还要点几下」排序。
     *
     * ① 已在相机热点（SSID 命中 NIKON 规则）→ 直接以兜底地址起轮次，learnFirst 学真网关；
     * ② 本会话有 BLE 下发的 WiFi 凭证 → specifier 免面板一键连（FR-35④，系统只弹一次
     *    「允许连接该网络」），失败自动补拉 ③；
     * ③ 其余 → 拉起系统 WLAN 面板让用户点热点，回连命中后由 ConnectionManager 的观察者
     *    （或下面 [onResume] 的兜底）自动起轮次。
     *
     * 面板走 AOSP 的 `Settings.Panel.ACTION_WIFI`（ZDROP dex `1ca886` 同款）：这是各家 ROM
     * 必须实现的标准化入口，绕开 OEM 全设置页深链失效；但**渲染者仍是 ROM 自己**，
     * 所以 [launchWifiPanel] 把实际命中的路径记进日志。
     */
    private fun apOneTapConnect() {
        if (viewModel.onCameraAp()) {
            binding.tvStatusMessage.text = "正在连接相机热点网关…"
            viewModel.connectApDirect()
            return
        }
        val started = viewModel.connectApWithCredential {
            // specifier 失败/超时（相机没广播、密码失效、用户点了拒绝）：回前台补拉面板，不白点
            if (_binding != null) launchApWifiPanel()
        }
        if (started) {
            binding.tvStatusMessage.text = "正在请求连接相机热点，请在系统弹窗点「允许」…"
        } else {
            launchApWifiPanel()
        }
    }

    /** 拉起系统 WLAN 入口并登记武装状态；两条 Intent 都拉不起来才退回手动 IP 旧入口。 */
    private fun launchApWifiPanel() {
        val path = launchWifiPanel()
        if (path == "none") {
            showManualIpDialog(ConnectMode.WIFI_AP)
            return
        }
        viewModel.armApPanel(path)
        binding.tvStatusMessage.text =
            "请在弹出面板里点相机热点（NIKON_…），连上后会自动开始连接"
    }

    /**
     * FR-35①：拉起 WLAN 入口，返回实际命中的路径 —— `panel`（AOSP 标准面板）/
     * `settings`（OEM 全设置页）/ `none`（都拉不起来）。
     *
     * 为什么要记：「vivo OriginOS、OPPO ColorOS 到底有没有实现 panel action」离线无法核实
     * （AGP 里查不到 ROM 的实现），而它决定了「绕过定制页」这句承诺是否兑现。
     * 静默回落到全设置页是真机最容易踩、又最容易误判成「App 没做」的一条路。
     */
    private fun launchWifiPanel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Settings.Panel.ACTION_WIFI 的字符串值；不直接用常量是为了 API<29 分支同表
            val panelOk = runCatching {
                startActivity(
                    Intent("android.settings.panel.action.WIFI")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            if (panelOk) return "panel"
        }
        val settingsOk = runCatching {
            startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        return if (settingsOk) "settings" else "none"
    }

    override fun onResume() {
        super.onResume()
        // FR-34①：从 WLAN 面板回到 App 的兜底 —— 用户在面板里点热点时
        // networkAvailable 可能早于本页可见，或 SSID 当时还没就绪，这里补一次判定。
        if (viewModel.apPanelArmed()) viewModel.tryStartArmedApConnect()
    }

    private fun connectRecentDevice(device: PairedDevice) {
        val display = device.deviceName.ifBlank { "尼康相机" }
        binding.tvStatusMessage.text = "快速连接: $display"
        viewModel.connectToRecentDevice(device)
    }

    /**
     * 扫描候选列表。[arch] 只在 STA 那一栏传（AP 栏用默认值）：
     * FR-37 之后行内「连接」按钮按所选架构分流，FTP 推送架构下不再发起 PTP/IP 直连。
     */
    private fun renderWifiCandidates(
        container: LinearLayout,
        devices: List<WifiCameraCandidate>,
        mode: ConnectMode,
        arch: String = AppSettings.STA_ARCH_PTP
    ) {
        container.removeAllViews()
        if (devices.isEmpty()) {
            // v1.3.2（STA 反馈修复）：三重信息，用户不必猜
            // ① 明确错误（未连 WiFi / WiFi 没拿到 IP / 扫描异常）
            // ② 自我定位（本机网段 + 当前 SSID + ARP 可读性 + 探测结论分布）
            // ③ 可操作建议（手动输入 IP）
            val explicitError = viewModel.wifiScanError.value
            val stats = viewModel.wifiScanStats.value
            container.addView(
                emptyHint(
                    when {
                        explicitError != null -> explicitError
                        // FR-35②：AP 链路早已免扫描（FR-34①），这句旧提示会把用户引回
                        // 「先点扫描」的老路，是「一键连接看起来没落地」的直接来源之一。
                        mode == ConnectMode.WIFI_AP ->
                            "热点模式不需要扫描：直接点「连接相机」，" +
                                "App 会自动弹出系统 Wi-Fi 面板或直接连上相机热点"
                        stats == null -> "未发现相机，请确认相机与手机在同一网络"
                        else -> buildString {
                            append("未发现相机 · 已探测 ")
                            append(stats.probedHosts)
                            append(" 个地址（ARP 表 ")
                            append(stats.arpEntries)
                            append(" 条）均无响应")
                            if (stats.mdnsResponses == 0 && stats.nsdResponses == 0) {
                                append("\nmDNS/NSD 均无响应：路由器可能过滤了组播，或开启了客户端隔离")
                            }
                            // FIX-1c（RC-5）：没有任何网卡接受组播组时，
                            // mDNS 这一整条路径在旧版是被静默跳过的 —— 必须说出来。
                            if (stats.mdnsInterfacesJoined == 0) {
                                append("\nmDNS 组播未能在任何网卡上建立（热点模式下常见），已仅用网段扫描")
                            }
                            if (!stats.wifiReady) {
                                append("\n本机 WiFi 未取得 IP 地址，网段扫描无法进行")
                            } else {
                                append("\n本机网段 ")
                                append(stats.localSubnets.ifBlank { "未知" })
                                if (stats.ssid.isNotBlank()) {
                                    append("（WiFi: ")
                                    append(stats.ssid)
                                    append("）")
                                }
                            }
                            // FIX-1（RC-0）：把「系统看不见热点、但内核网卡看得见」
                            // 这一事实摆出来 —— 这是热点场景判 no_wifi_network 的根因。
                            if (stats.kernelSubnets.isNotBlank() &&
                                stats.kernelSubnets != stats.localSubnets
                            ) {
                                append("\n含手机热点等本地网卡网段 ")
                                append(stats.kernelSubnets)
                                if (stats.hotspotTopology) {
                                    append("（当前为热点拓扑）")
                                }
                            }
                            if (stats.gateReason != null) {
                                append("\n扫描被提前收口：")
                                append(
                                    when (stats.gateReason) {
                                        "no_wifi_network" -> "未检测到可用网络"
                                        "wifi_no_ipv4" -> "网络未分配 IP 地址"
                                        else -> stats.gateReason
                                    }
                                )
                            }
                            if (!stats.arpReadable) {
                                append("\nARP 表不可读（Android 10+ 系统限制，属正常）")
                            }
                            append("\n探测结果：握手成功 ")
                            append(stats.probeOk)
                            append(" · 仅端口开放 ")
                            append(stats.probeTcpOpenNoPtp)
                            append(" · 无响应 ")
                            append(stats.probeTimeout)
                            append("\n可点「连接」手动输入相机 IP（相机机身菜单可查）")
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
            item.btnCandidateConnect.setOnClickListener {
                // FR-37：STA + FTP 推送架构下相机是**主动推送方**，手机侧没有可连的对象。
                // 旧代码这里恒走 PTP/IP 直连，等于把用户刚选的架构当场作废（本次用户报障的成因）。
                if (mode == ConnectMode.WIFI_STA && arch == AppSettings.STA_ARCH_FTP) {
                    binding.tvStatusMessage.text =
                        "FTP 推送架构：手机只接收，不直连。请启动下方 FTP 服务器并在相机里填同一地址"
                    return@setOnClickListener
                }
                connectWifiCandidate(candidate)
            }
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
            // FR-36：配对记录新写的是官方全称（`Nikon Z 50II`）；旧记录那一列存的是
            // 当年误传的 IP，归一化不成立就退回 deviceName（多为扫描名或「尼康相机」）。
            item.tvRecentName.text = CameraModelCatalog.official(device.cameraModel)
                ?: device.deviceName.ifBlank { "尼康相机" }
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
        NlGlass.dialog(requireContext())
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

    /**
     * dock 悬浮进内容区之后，本页要自己做两件事：
     *  ① 给滚动内容叠加底部内衬，否则最后一屏卡片会被 dock 盖住；
     *  ② 滚动时驱动 dock 的背景纹理重采 —— 内容才会真的"从玻璃底下划过"，
     *     否则 dock 糊着一张进页时的快照，悬浮感当场失效。
     * clipToPadding=false 是"内容能滚到玻璃底下"的前提，也是看得见真透景的原因。
     */
    private fun setupDockFloat() {
        dockInset = DockInset(binding.scrollContent)
        applyDockSpace()
        styleTopBar()
        binding.scrollContent.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            topBarFx?.onScroll(scrollY)
            // 把这一帧的滚动增量交给采集器：采样窗口跟着平移，顶栏玻璃才真的跟随滚动
            (activity as? MainActivity)?.dockBackdrop?.requestRefresh(scrollY - oldScrollY)
        }
    }

    /**
     * 顶栏：干净统一底色，两种外观一致 —— 几何与底色都在 `NlTopChrome/NlTopBar/NlTopDivider`
     * style 里，这里只挂标题让位。
     *
     * 原来这块是随滚动浮现的玻璃底片，但它背后就是本页的滚动区：采样慢一帧带来的错位、
     * 闪烁、切页残留全部来自它，收益（白底上透出白页）又是零，所以整条撤掉。
     */
    private fun styleTopBar() {
        topBarFx = GlassTopBar(binding.topChrome, binding.tvTitle)
        topBarFx?.onScroll(binding.scrollContent.scrollY)
    }

    /** 顶栏是覆盖层，滚动区要让出它的高度（玻璃/经典都一样） */
    private fun applyDockSpace() {
        if (_binding == null) return
        dockInset?.applyPadding((activity as? MainActivity)?.dockSpace() ?: 0)
        binding.scrollContent.clipToPadding = false
        GlassChrome.apply(binding.topChrome, binding.scrollContent)
        DepthLift.apply(binding.root, requireContext())
    }

    override fun onDockSpaceChanged(dockSpace: Int) = applyDockSpace()

    override fun onDestroyView() {
        UiFlags.unobserve(this)
        dockInset = null
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

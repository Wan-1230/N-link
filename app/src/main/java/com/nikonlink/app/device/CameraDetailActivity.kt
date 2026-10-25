package com.nikonlink.app.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.MainActivity
import com.nikonlink.app.R
import com.nikonlink.app.camera.location.CameraLocationStatus
import com.nikonlink.app.camera.location.CameraLocationSyncManager
import com.nikonlink.app.camera.params.CameraInfo
import com.nikonlink.app.camera.params.CameraParameterManager
import com.nikonlink.app.camera.params.ShutterCountState
import com.nikonlink.app.databinding.ActivityCameraDetailBinding
import com.nikonlink.app.device.ap.ApConnectionService
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.model.CameraModelCatalog
import com.nikonlink.app.device.model.ConnectionMetrics
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.model.FirmwareCatalog
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.NlFeedback
import com.nikonlink.app.shared.ui.glass.NlGlass
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 相机详情页：把散落在设备页/参数轮询里的相机身份、状态与能力收在一处。
 *
 * 参数选取原则：
 * - 只展示**能从当前链路确认**的值，读不到显示「—」，不猜；
 * - 序列号只露后四位（支持排查/保修询问，又不把完整机身标识复制到剪贴板）；
 * - 操作只做低风险项：刷新、固件清单比对、位置立即同步、复制诊断、跳官方下载页；
 * - **不提供固件刷写**——N-Link 不接管相机升级流程。
 */
@AndroidEntryPoint
class CameraDetailActivity : AppCompatActivity() {

    @Inject
    lateinit var params: CameraParameterManager

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var locationSync: CameraLocationSyncManager

    @Inject
    lateinit var ptpSession: PtpSessionManager

    @Inject
    lateinit var usbPtpManager: UsbPtpManager

    @Inject
    lateinit var apConnection: ApConnectionService

    @Inject
    lateinit var settings: AppSettings

    private lateinit var binding: ActivityCameraDetailBinding
    private var info = CameraInfo()
    private var metrics = ConnectionMetrics()
    private var connectionState = ConnectionState.DISCONNECTED
    private var locationStatus: CameraLocationStatus = CameraLocationStatus.Off

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { refreshInfo() }
        binding.btnCheckFirmware.setOnClickListener { checkFirmware() }
        binding.btnOfficialFirmware.setOnClickListener { openOfficialDownloadCenter() }
        binding.btnCopyInfo.setOnClickListener { copyCameraInfo() }
        binding.btnSyncLocation.setOnClickListener { syncLocationNow() }

        observe()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置/设置页返回时，位置开关与权限可能已变
        if (::binding.isInitialized) render()
    }

    private fun observe() {
        lifecycleScope.launch {
            params.cameraInfo.collect {
                info = it
                render()
            }
        }
        lifecycleScope.launch {
            connectionManager.connectionMetrics.collect {
                metrics = it
                render()
            }
        }
        lifecycleScope.launch {
            connectionManager.connectionState.collect {
                connectionState = it
                render()
            }
        }
        lifecycleScope.launch {
            locationSync.status.collect {
                locationStatus = it
                renderLocation()
            }
        }
    }

    private fun render() {
        val connected = isCameraConnected()
        binding.tvName.text = displayName()
        binding.tvStatus.text = when {
            usbPtpManager.isConnected() -> "已连接 · USB 有线"
            apConnection.isConnected() -> "已连接 · Wi-Fi 直连"
            ptpSession.isConnected() && ptpSession.isStaSession -> "已连接 · Wi-Fi STA"
            ptpSession.isConnected() -> "已连接 · Wi-Fi"
            connectionState == ConnectionState.FULLY_CONNECTED -> "已连接"
            connectionState == ConnectionState.CONNECTING ||
                connectionState == ConnectionState.WIFI_UPGRADING -> "正在连接"
            connectionState == ConnectionState.BLE_CONNECTED -> "蓝牙已连接，Wi-Fi 未建立"
            connectionState == ConnectionState.ERROR_WAITING_RETRY -> "连接异常，等待重试"
            else -> "未连接"
        }

        binding.tvModel.text = info.modelName.ifBlank {
            connectionManager.sessionCameraModel() ?: "—"
        }
        binding.tvFirmware.text = info.firmwareVersion.ifBlank { "—" }
        binding.tvSerial.text = maskSerial(info.serialNumber)
        binding.tvLens.text = info.lensName.ifBlank { "—" }
        binding.tvShutter.text = shutterText()
        binding.tvBattery.text = if (info.batteryLevel >= 0) "${info.batteryLevel}%" else "—"
        binding.tvStorage.text = storageText()

        binding.tvTransport.text = when {
            usbPtpManager.isConnected() -> "USB 有线"
            apConnection.isConnected() -> "Wi-Fi 直连（相机热点）"
            ptpSession.isConnected() && ptpSession.isStaSession -> "Wi-Fi（相机连路由器）"
            ptpSession.isConnected() -> "Wi-Fi（PTP/IP）"
            metrics.activeChannels.isNotEmpty() -> metrics.activeChannels.joinToString(" + ") {
                when (it.name) {
                    "WIFI" -> "Wi-Fi"
                    "BLE" -> "蓝牙"
                    "USB" -> "USB"
                    else -> it.name
                }
            }
            else -> "—"
        }
        binding.tvFrequency.text = if (metrics.wifiFrequencyMhz > 0) {
            "${bandLabel(metrics.wifiFrequencyMhz)} · ${metrics.wifiFrequencyMhz} MHz"
        } else "—"
        binding.tvBle.text = if (metrics.bleRssi != 0) "${metrics.bleRssi} dBm" else "—"
        binding.tvDuration.text = formatDuration(metrics.connectionDuration)
        binding.tvReconnect.text = "${metrics.reconnectCount} 次"

        val firmware = info.firmwareVersion
        binding.tvFirmwareNote.text = buildString {
            append(if (firmware.isBlank()) "未读到相机固件版本。" else "当前固件：$firmware")
            append('\n')
            append(FirmwareCatalog.NOTE)
        }
        // 固件读不到时仍允许点开检查：会明确告诉你"未读到版本"，比一颗亮着的禁用按钮更可解释
        binding.btnCheckFirmware.isEnabled = connected
        binding.btnSyncLocation.visibility =
            if (settings.cameraLocationEnabled) View.VISIBLE else View.GONE
        binding.btnSyncLocation.isEnabled = connected

        renderCapabilities()
        renderLocation()
    }

    private fun renderLocation() {
        if (!settings.cameraLocationEnabled) {
            binding.tvLocationStatus.text = "未开启。可在「设置 → 位置与隐私」中开启。"
            return
        }
        binding.tvLocationStatus.text = when (val status = locationStatus) {
            CameraLocationStatus.Off -> "当前未开启"
            CameraLocationStatus.PermissionMissing -> "缺少定位权限"
            CameraLocationStatus.LocationServiceDisabled -> "系统定位未开启"
            CameraLocationStatus.PausedInBackground -> "仅在 App 使用期间同步"
            CameraLocationStatus.WaitingForCamera -> "等待相机连接"
            CameraLocationStatus.Locating -> "正在获取位置…"
            is CameraLocationStatus.Synced -> buildString {
                append("最近写入：")
                append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.atMillis)))
                if (status.accuracyMeters > 0) {
                    append(" · 精度约 ±${status.accuracyMeters.toInt()} m")
                }
            }
            is CameraLocationStatus.Failed ->
                "写入失败（${if (status.code == -1) "无响应" else String.format("0x%04X", status.code)}）"
            CameraLocationStatus.Unsupported -> "当前相机/连接方式不支持位置写入"
        }
    }

    private fun renderCapabilities() {
        val grid = binding.capabilityGrid
        grid.removeAllViews()
        grid.columnCount = 2
        val ops = ptpSession.lastSupportedOperations.takeIf { ptpSession.isConnected() }
        data class Capability(val label: String, val required: List<Int>)

        val capabilities = listOf(
            Capability("实时取景", listOf(0x9201, 0x9203)),
            Capability("触控对焦", listOf(0x9205, 0x90C1)),
            Capability("位置写入", listOf(PtpConstants.OP_NIKON_UPDATE_LOCATION)),
            Capability("短片录制", listOf(0x920A, 0x920B)),
            Capability("主机注册", listOf(0x952B, 0x935A)),
            Capability("存储访问", listOf(0x1004, 0x1007))
        )
        capabilities.forEach { capability ->
            val supported = ops?.let { set -> capability.required.all { it in set } }
            val chip = TextView(this).apply {
                text = when (supported) {
                    true -> "✓ ${capability.label}"
                    false -> "— ${capability.label}"
                    null -> "? ${capability.label}"
                }
                gravity = Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        this@CameraDetailActivity,
                        when (supported) {
                            true -> R.color.text_primary
                            else -> R.color.text_tertiary
                        }
                    )
                )
                textSize = 12f
                background = ContextCompat.getDrawable(
                    this@CameraDetailActivity,
                    R.drawable.bg_chip
                )
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(36)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(chip, lp)
        }
        binding.tvCapabilityNote.text = if (ops == null) {
            "当前连接没有读到 Wi-Fi 能力表（USB / AP 连接可能如此），能力显示为未知。"
        } else {
            "能力表来自本次 Wi-Fi 会话的 GetDeviceInfo。"
        }
    }

    private fun refreshInfo() {
        binding.btnRefresh.isEnabled = false
        lifecycleScope.launch {
            runCatching { params.refreshCameraInfoNow() }
                .onFailure { NlFeedback.show(this@CameraDetailActivity, "刷新失败：${it.message ?: "未知原因"}") }
            render()
            binding.btnRefresh.isEnabled = true
        }
    }

    private fun checkFirmware() {
        val model = info.modelName.ifBlank { connectionManager.sessionCameraModel() ?: "" }
        val result = FirmwareCatalog.check(model, info.firmwareVersion)
        val body = when (result) {
            is FirmwareCatalog.Result.UpdateAvailable ->
                "相机当前固件：${result.currentVersion}\n" +
                    "内置清单记录：${result.entry.latestVersion}（${result.entry.releasedOn}）\n\n" +
                    "清单显示有更新的版本。请到尼康官方下载中心核对更新说明与升级步骤。"
            is FirmwareCatalog.Result.NotOlderThanCatalog ->
                "相机当前固件：${result.currentVersion}\n" +
                    "内置清单记录：${result.entry.latestVersion}（${result.entry.releasedOn}）\n\n" +
                    "当前版本不早于清单记录。清单可能滞后，仍建议以官网为准。"
            is FirmwareCatalog.Result.UnknownModel ->
                "内置清单未收录该机型（${model.ifBlank { "未知" }}）。\n" +
                    "请到尼康官方下载中心按机型核对最新固件。"
            is FirmwareCatalog.Result.NoVersion ->
                "没有读到相机固件版本，无法比对。\n请先点右上角刷新，或直接到官方下载中心核对。"
        }
        NlGlass.dialog(this)
            .setTitle("检查固件更新")
            .setMessage("$body\n\n${FirmwareCatalog.NOTE}\n\nN-Link 不下载、不刷写固件。")
            .setPositiveButton("打开下载中心") { _, _ -> openOfficialDownloadCenter() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openOfficialDownloadCenter() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FirmwareCatalog.OFFICIAL_URL)))
        }.onFailure {
            NlFeedback.show(this, "没有可打开网页的应用")
        }
    }

    private fun syncLocationNow() {
        if (!settings.cameraLocationEnabled) {
            NlGlass.dialog(this)
                .setTitle("位置同步未开启")
                .setMessage("请先在「设置 → 位置与隐私」中开启「向相机发送位置信息」。")
                .setPositiveButton("去设置") { _, _ -> openSettingsTab() }
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        binding.btnSyncLocation.isEnabled = false
        lifecycleScope.launch {
            val result = locationSync.syncNow()
            renderLocation()
            binding.btnSyncLocation.isEnabled = isCameraConnected()
            when (result) {
                is CameraLocationStatus.Synced -> NlFeedback.show(this@CameraDetailActivity, "已把当前位置写入相机")
                CameraLocationStatus.WaitingForCamera ->
                    NlFeedback.show(this@CameraDetailActivity, "相机未连接，无法写入位置")
                CameraLocationStatus.PermissionMissing ->
                    showPermissionDialog()
                CameraLocationStatus.LocationServiceDisabled -> showLocationServiceDialog()
                CameraLocationStatus.Unsupported ->
                    NlFeedback.show(this@CameraDetailActivity, "当前相机/连接方式不支持位置写入", long = true)
                is CameraLocationStatus.Failed ->
                    NlFeedback.show(this@CameraDetailActivity, "写入失败（${result.code}）", long = true)
                else -> Unit
            }
        }
    }

    private fun copyCameraInfo() {
        val text = buildString {
            appendLine("N-Link 相机信息")
            appendLine("型号：${info.modelName.ifBlank { "—" }}")
            appendLine("固件：${info.firmwareVersion.ifBlank { "—" }}")
            appendLine("序列号：${maskSerial(info.serialNumber)}")
            appendLine("连接：${binding.tvTransport.text}")
            appendLine("频段：${binding.tvFrequency.text}")
            appendLine("蓝牙：${binding.tvBle.text}")
            appendLine("本次时长：${binding.tvDuration.text}")
            appendLine("重连：${binding.tvReconnect.text}")
            appendLine("电池：${binding.tvBattery.text}")
            appendLine("存储：${binding.tvStorage.text}")
            appendLine("镜头：${binding.tvLens.text}")
            appendLine("快门：${binding.tvShutter.text}")
            appendLine("位置同步：${binding.tvLocationStatus.text.toString().replace('\n', ' ')}")
        }.trimEnd()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("N-Link 相机信息", text))
        NlFeedback.show(this, "已复制相机信息（不含 IP / MAC）")
    }

    private fun showPermissionDialog() {
        NlGlass.dialog(this)
            .setTitle("缺少定位权限")
            .setMessage("位置写入需要定位权限。请到系统设置中授予权限后再试。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showLocationServiceDialog() {
        NlGlass.dialog(this)
            .setTitle("系统定位未开启")
            .setMessage("请先在系统设置中打开定位服务。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openSettingsTab() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_SETTINGS)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
    }

    private fun isCameraConnected(): Boolean =
        usbPtpManager.isConnected() || apConnection.isConnected() || ptpSession.isConnected() ||
            connectionState == ConnectionState.FULLY_CONNECTED ||
            metrics.activeChannels.isNotEmpty()

    private fun displayName(): String =
        CameraModelCatalog.official(info.modelName)
            ?: CameraModelCatalog.official(connectionManager.sessionCameraModel())
            ?: "Nikon"

    private fun shutterText(): String = when (info.shutterQueryState) {
        ShutterCountState.SUCCESS -> {
            val main = info.shutterCountRange
                ?.let { "${it.first}–${it.last}" }
                ?: info.shutterCount.toString()
            val verified = if (info.shutterVerified) "已验证" else "未验证"
            val mechanical = if (info.shutterMechanicalCount in 0 until info.shutterCount) {
                " · 机械 ${info.shutterMechanicalCount}"
            } else ""
            "$main（$verified$mechanical）"
        }
        ShutterCountState.QUERYING -> "查询中…"
        ShutterCountState.FAILED -> "未读到"
        ShutterCountState.NONE -> "—"
    }

    private fun storageText(): String {
        if (info.storageTotalMb <= 0) return "—"
        val free = formatMb(info.storageFreeMb)
        val total = formatMb(info.storageTotalMb)
        val card = info.storageDescription.takeIf { it.isNotBlank() }
        return buildString {
            append("$free 可用 / $total")
            if (card != null) append(" · $card")
        }
    }

    private fun formatMb(mb: Long): String =
        if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "—"
        val totalSeconds = ms / 1000
        if (totalSeconds < 60) return "$totalSeconds 秒"
        val minutes = totalSeconds / 60
        if (minutes < 60) return "$minutes 分 ${totalSeconds % 60} 秒"
        return "${minutes / 60} 小时 ${minutes % 60} 分"
    }

    private fun maskSerial(serial: String): String {
        if (serial.isBlank()) return "—"
        return if (serial.length <= 4) "****" else "****${serial.takeLast(4)}"
    }

    private fun bandLabel(freqMhz: Int): String = if (freqMhz >= 5000) "5 GHz" else "2.4 GHz"

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

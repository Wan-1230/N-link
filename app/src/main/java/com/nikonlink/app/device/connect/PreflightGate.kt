package com.nikonlink.app.device.connect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.nikonlink.app.shared.device.RomDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接前预检（PRD v2.2 §6.1 / G11）。
 *
 * **解决什么**：过去连接失败只有一句「未检测到相机」，而真正的原因往往是
 * 权限没给、系统位置总开关没开、手机被电池优化限制、vivo/小米的 OTG 开关没开、
 * 或者系统「避开不良网络」把没有网络的相机热点主动踢掉。这些都不该由用户猜。
 *
 * 每项检查给出：是否阻塞、人话说明、可直达的系统设置入口（拿不到入口时降级为纯文案）。
 * 预检**只读**，不改任何连接行为；阻塞项被拦下时会写进 [ConnFunnel] 的 PREFLIGHT 阶段。
 *
 * 逐 ROM 的差异（哪台机器有自启动开关、OTG 在哪个菜单、WLAN+ 叫什么名字）来自
 * [CompatRules]，即 `assets/compat/rom_rules.json`；表里没有该项、或闸门
 * [ConnFlags.COMPAT_RULES] 关闭时退回代码内的兜底文案，行为与 v2.2.0 一致。
 */
@Singleton
class PreflightGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val compatRules: CompatRules
) {
    companion object {
        const val CHANNEL_WIFI = "WIFI"
        const val CHANNEL_USB = "USB"
        const val CHANNEL_BLE = "BLE"

        // 引导条目 id，与 rom_rules.json 里 tricks[].id 一一对应
        private const val TRICK_AUTOSTART = "autostart"
        private const val TRICK_BATTERY = "battery"
        private const val TRICK_OTG = "otg"
        private const val TRICK_WLAN_PLUS = "wlan_plus"

        /** dontkillmyapp 口径 3 分以上才提醒；原生/近原生系统不该出现这行噪声 */
        private const val NAG_MIN_KILL_RATING = 3
    }

    data class Item(
        val reason: ConnFunnel.Reason,
        val label: String,
        val ok: Boolean,
        /** true = 不解决就必然连不上，应拦下这次连接而不是重试 */
        val blocking: Boolean,
        val settingsIntent: Intent?
    ) {
        fun surfaceText(): String =
            if (ok) label else "$label。${reason.hint ?: "请检查系统设置"}"
    }

    /** 全量检查（供诊断面板展示，包含已通过项）。 */
    fun checkAll(channel: String): List<Item> = buildList {
        add(wifiSwitchOn())
        if (channel != CHANNEL_USB) {
            add(nearbyPermission())
            add(locationSwitch())
            add(smartSwitchAvoid())
        }
        if (channel == CHANNEL_USB) add(usbBusReadable())
        if (channel == CHANNEL_BLE) add(bluetoothPermission())
        add(batteryExemption())
        autostartPolicy()?.let { add(it) }
        add(vpnNotice())
    }

    /** 该通道的第一个阻塞项；null 表示可以继续连接。 */
    fun firstBlocking(channel: String): Item? =
        checkAll(channel).firstOrNull { !it.ok && it.blocking }

    // ── 各项检查 ────────────────────────────────────────────────────────────

    private fun wifiSwitchOn(): Item {
        val enabled = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: true
        }.getOrDefault(true)
        return Item(
            reason = ConnFunnel.Reason.NO_WIFI_NETWORK,
            label = if (enabled) "WLAN 已开启" else "WLAN 处于关闭状态",
            ok = enabled,
            blocking = !enabled,
            settingsIntent = intent(Settings.ACTION_WIFI_SETTINGS)
        )
    }

    private fun nearbyPermission(): Item {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 12 及以下走定位权限路径，由 locationSwitch() 负责
            return Item(ConnFunnel.Reason.OK, "「附近的设备」权限：不适用（Android 12 及以下）", true, false, null)
        }
        val granted = context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
        return Item(
            reason = ConnFunnel.Reason.PERM_NEARBY_WIFI,
            label = if (granted) "「附近的设备」权限已授予" else "缺少「附近的设备」权限（扫描/连接相机热点必需）",
            ok = granted,
            blocking = !granted,
            settingsIntent = appDetailsIntent()
        )
    }

    private fun locationSwitch(): Item {
        val needsLocation = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
        if (!needsLocation) {
            return Item(ConnFunnel.Reason.OK, "系统位置服务：Android 13+ 扫描 WiFi 不再必需", true, false, null)
        }
        val on = runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }.getOrDefault(true)
        val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val ok = on && granted
        return Item(
            reason = if (!on) ConnFunnel.Reason.LOC_SWITCH_OFF else ConnFunnel.Reason.PERM_LOCATION,
            label = when {
                ok -> "定位权限与位置服务正常"
                !granted -> "缺少定位权限（Android 12 及以下扫描 WiFi 必需）"
                else -> "系统「位置信息」总开关未开（Android 12 及以下扫描 WiFi 必需）"
            },
            ok = ok,
            blocking = !ok,
            settingsIntent = intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        )
    }

    private fun smartSwitchAvoid(): Item {
        // 读系统全局设置不需要权限；读不到就当作没开（不误报）
        val avoid = runCatching {
            Settings.Global.getInt(context.contentResolver, "network_avoid_bad_wifi", 0) == 1
        }.getOrDefault(false)
        val perRom = compatRules.hint(TRICK_WLAN_PLUS)
        return Item(
            reason = ConnFunnel.Reason.AVOID_BAD_WIFI,
            label = if (!avoid) {
                "系统「避开不良网络」未开启"
            } else {
                "系统开启了「避开不良网络/智能切换」，相机热点没有网络，可能被自动断开" +
                    (perRom?.let { "｜$it" } ?: "（${RomDetector.family}）") +
                    compatRules.staleSuffix(TRICK_WLAN_PLUS)
            },
            ok = !avoid,
            blocking = false,
            settingsIntent = compatRules.intent(TRICK_WLAN_PLUS)
                ?: intent(Settings.ACTION_WIFI_SETTINGS)
        )
    }

    private fun usbBusReadable(): Item {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
        val empty = usbManager?.deviceList.isNullOrEmpty()
        // 「这台机器有没有 OTG 独立开关」决定空总线是硬阻断还是链路问题。
        // 表里只有小米/vivo/OPPO 系写了 otg 条目，与 RomDetector.otgHint() 的覆盖面一致，
        // 所以换成读表并不会让任何机型新挨一次阻断。
        val hint = compatRules.hint(TRICK_OTG) ?: RomDetector.otgHint()
        val ok = !(empty && hint != null)   // 总线为空、且该机型有 OTG 独立开关 → 大概率是开关没开
        return Item(
            reason = ConnFunnel.Reason.OTG_DISABLED,
            label = if (!ok) "USB 总线上没有任何设备：$hint${compatRules.staleSuffix(TRICK_OTG)}" else "USB 总线可读",
            ok = ok,
            blocking = !ok,
            settingsIntent = appDetailsIntent()
        )
    }

    private fun bluetoothPermission(): Item {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = needed.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        return Item(
            reason = ConnFunnel.Reason.PERM_NEARBY_WIFI,
            label = if (missing.isEmpty()) "蓝牙权限已授予" else "缺少蓝牙权限：${missing.map { it.substringAfterLast('.') }.joinToString()}",
            ok = missing.isEmpty(),
            blocking = missing.isNotEmpty(),
            settingsIntent = appDetailsIntent()
        )
    }

    private fun batteryExemption(): Item {
        val ignored = runCatching {
            context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
                ?: true
        }.getOrDefault(true)
        return Item(
            reason = ConnFunnel.Reason.BATT_RESTRICTED,
            label = if (ignored) "已豁免电池优化" else {
                "App 仍在电池优化名单内，息屏/后台时连接易被系统打断" +
                    compatRules.hint(TRICK_BATTERY)?.let { "｜$it" }.orEmpty() +
                    compatRules.staleSuffix(TRICK_BATTERY)
            },
            ok = ignored,
            blocking = false,
            settingsIntent = compatRules.intent(TRICK_BATTERY)
                ?: intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
    }

    /**
     * 自启动 / 后台白名单提醒（PRD §6.1 的「ROM 自启动/后台策略」一项）。
     *
     * 这一项**读不到状态**：系统没有公开 API 告诉你「自启动是否已允许」，§6.5 向导里
     * 要求的「已生效 ✓ 读回」在 OEM 设置页上根本做不到。所以它永远只是提醒
     * （ok=false + blocking=false，面板上显示为 `!` 而不是 `✗`），
     * 并且只在查杀确实严厉的 ROM 上出现 —— 原生系统不该多这一行。
     */
    private fun autostartPolicy(): Item? {
        if (compatRules.killRating < NAG_MIN_KILL_RATING) return null
        val text = compatRules.hint(TRICK_AUTOSTART) ?: return null
        return Item(
            reason = ConnFunnel.Reason.AUTOSTART_REMINDER,
            label = text + compatRules.staleSuffix(TRICK_AUTOSTART),
            ok = false,
            blocking = false,
            settingsIntent = compatRules.intent(TRICK_AUTOSTART)
        )
    }

    private fun vpnNotice(): Item {
        // 只用 ConnectivityManager 只读地看有没有 VPN transport，
        // 绝不用 VpnService.Builder.build()（那会真的去创建虚拟网卡）。
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn = runCatching {
            cm?.allNetworks?.any { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } ?: false
        }.getOrDefault(false)
        return Item(
            reason = ConnFunnel.Reason.VPN_ACTIVE,
            label = if (vpn) "检测到 VPN 通道，相机流量可能被劫持" else "无 VPN 干扰",
            ok = !vpn,
            blocking = false,
            settingsIntent = null
        )
    }

    private fun intent(action: String): Intent? =
        runCatching { Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.getOrNull()

    private fun appDetailsIntent(): Intent? = runCatching {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrNull().takeIf { it != null && context.packageManager.resolveActivity(it, 0) != null }
}

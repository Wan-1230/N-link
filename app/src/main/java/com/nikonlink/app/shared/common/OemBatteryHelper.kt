package com.nikonlink.app.shared.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber

/**
 * 厂商后台保活适配工具
 *
 * PRD 3.2 厂商适配:
 * - 小米: 自启动管理
 * - 华为: 后台保护
 * - OPPO: 后台冻结
 * - 引导用户手动设置，确保后台存活率 > 99%
 *
 * PRD 1.4: 官方相机连接客户端未针对厂商激进后台策略做特殊处理（断联根因之一）
 */
object OemBatteryHelper {

    private const val TAG = "OemBattery"

    /**
     * 获取当前设备厂商类型
     */
    fun getManufacturer(): Manufacturer {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> Manufacturer.XIAOMI
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> Manufacturer.HUAWEI
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> Manufacturer.OPPO
            manufacturer.contains("vivo") -> Manufacturer.VIVO
            manufacturer.contains("samsung") -> Manufacturer.SAMSUNG
            manufacturer.contains("oneplus") -> Manufacturer.ONEPLUS
            else -> Manufacturer.OTHER
        }
    }

    /**
     * 打开厂商自启动/后台管理设置页面
     * @return true 如果成功打开设置页面
     */
    fun openBatterySettings(context: Context): Boolean {
        val manufacturer = getManufacturer()
        Timber.tag(TAG).i("Opening battery settings for: $manufacturer")

        return try {
            val intent = getManufacturerIntent(manufacturer)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                // 通用方案：打开系统电池优化设置
                openGenericBatterySettings(context)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to open manufacturer settings, trying generic")
            try {
                openGenericBatterySettings(context)
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "All battery settings attempts failed")
                false
            }
        }
    }

    private fun getManufacturerIntent(manufacturer: Manufacturer): Intent? {
        return when (manufacturer) {
            Manufacturer.XIAOMI -> {
                // 小米自启动管理
                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                }
            }
            Manufacturer.HUAWEI -> {
                // 华为后台保护
                Intent().apply {
                    component = ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
            }
            Manufacturer.OPPO -> {
                // OPPO 后台冻结管理
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                }
            }
            Manufacturer.VIVO -> {
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                }
            }
            Manufacturer.SAMSUNG -> {
                Intent().apply {
                    component = ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.battery.ui.BatteryActivity"
                    )
                }
            }
            Manufacturer.ONEPLUS -> {
                Intent().apply {
                    component = ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                }
            }
            Manufacturer.OTHER -> null
        }
    }

    private fun openGenericBatterySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    /**
     * 获取厂商特定的用户引导提示文本
     */
    fun getGuidanceText(): String {
        return when (getManufacturer()) {
            Manufacturer.XIAOMI -> "小米设备：请开启「自启动」权限\n" +
                    "设置 → 应用管理 → N-Link → 自启动 → 开启\n" +
                    "设置 → 电池 → 应用省电策略 → N-Link → 无限制"
            Manufacturer.HUAWEI -> "华为设备：请开启「后台保护」\n" +
                    "设置 → 电池 → 启动管理 → N-Link → 手动管理 → 全部开启\n" +
                    "最近任务 → 下拉 N-Link → 点击锁定图标"
            Manufacturer.OPPO -> "OPPO设备：请关闭「后台冻结」\n" +
                    "设置 → 电池 → 应用耗电管理 → N-Link → 允许后台运行\n" +
                    "设置 → 权限隐私 → 自启动管理 → N-Link → 开启"
            Manufacturer.VIVO -> "vivo设备：请开启「后台弹出」\n" +
                    "设置 → 电池 → 后台高耗电 → N-Link → 开启\n" +
                    "i管家 → 应用管理 → 权限管理 → 自启动 → N-Link → 开启"
            Manufacturer.SAMSUNG -> "三星设备：请取消「后台限制」\n" +
                    "设置 → 电池 → 后台使用限制 → 将 N-Link 从列表中移除\n" +
                    "设置 → 电池 → 从不自动休眠的应用 → 添加 N-Link"
            Manufacturer.ONEPLUS -> "一加设备：请开启「自启动」\n" +
                    "设置 → 电池 → 应用省电管理 → N-Link → 不优化"
            Manufacturer.OTHER -> "请确保 N-Link 不受电池优化限制\n" +
                    "设置 → 电池 → 电池优化 → N-Link → 不优化"
        }
    }

    /**
     * 是否需要显示厂商引导
     */
    fun shouldShowGuidance(): Boolean {
        return getManufacturer() != Manufacturer.OTHER
    }
}

/**
 * 设备厂商枚举
 */
enum class Manufacturer(val displayName: String) {
    XIAOMI("小米"),
    HUAWEI("华为"),
    OPPO("OPPO"),
    VIVO("vivo"),
    SAMSUNG("三星"),
    ONEPLUS("一加"),
    OTHER("其他")
}

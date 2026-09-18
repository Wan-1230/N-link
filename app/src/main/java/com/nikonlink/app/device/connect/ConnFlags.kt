package com.nikonlink.app.device.connect

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v2.2 连接改造的运行时开关（回退闸门）。
 *
 * 每一项都可以单独关掉而不必回装旧包：设置页 →「连接实验开关」。
 * 关掉后走 v2.1.1 的原路径，用于「新逻辑在某些机型上反而更差」时快速定位。
 * 三级回退：① 这里的开关；② 逐项 git revert；③ dist/ 里的 v2.1.1-release.apk。
 */
@Singleton
class ConnFlags @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "nl_settings"

        /** AP：从已连网络的网关/DHCP 学出相机地址（替代硬编码 192.168.1.1 单点） */
        const val AP_GATEWAY = "conn22_ap_gateway"

        /** AP：不依赖 BLE 凭证也能连——手机已连相机热点时直接走网关学习 */
        const val AP_BLELESS = "conn22_ap_bleless"

        /** AP：requestNetwork 交给系统超时，并在释放后留最小间隔再重请求 */
        const val AP_SPECIFIER = "conn22_ap_specifier"

        /** STA：网段盲扫只做 TCP 连接，握手留给最终候选 */
        const val STA_TCP_ONLY = "conn22_sta_tcp_only"

        /** 传输：批量传输期间心跳不再判死链路 */
        const val TRANSFER_HEARTBEAT = "conn22_transfer_heartbeat"

        /** USB：detach 先进宽限窗，不立刻判死 */
        const val USB_GRACE = "conn22_usb_grace"

        /** USB：OTG 关闭 / 无设备时给可操作的指引（小米系 OTG 10 分钟自动关） */
        const val USB_OTG_HINT = "conn22_usb_otg_hint"

        /**
         * 总闸（设置页「v2.2 连接改进」开关）。关掉后所有新逻辑回到 v2.1.1 行为，
         * 不必回装旧包；单项 key 仍可用 `adb shell run-as` 改 prefs 做二分定位。
         */
        const val MASTER = "conn22_enabled"

        private val DEFAULTS = mapOf(
            AP_GATEWAY to true,
            AP_BLELESS to true,
            AP_SPECIFIER to true,
            STA_TCP_ONLY to true,
            TRANSFER_HEARTBEAT to true,
            USB_GRACE to true,
            USB_OTG_HINT to true,
        )
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun masterEnabled(): Boolean = prefs.getBoolean(MASTER, true)

    fun setMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(MASTER, enabled).apply()
    }

    fun isEnabled(key: String): Boolean =
        masterEnabled() && prefs.getBoolean(key, DEFAULTS[key] ?: true)

    fun setEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean(key, enabled).apply()
    }
}

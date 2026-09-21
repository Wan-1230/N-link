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

        /** 全局：一轮连接的重试预算（防 AOSP 把反复失败的热点列入禁用） */
        const val ATTEMPT_BUDGET = "conn22_attempt_budget"

        /** STA：网段盲扫只做 TCP 连接，握手留给最终候选 */
        const val STA_TCP_ONLY = "conn22_sta_tcp_only"

        /**
         * v2.5 FR-02：STA 侧网络动作一律带显式超时。
         *
         * 关掉 = 回到 v2.3.1 的行为：非配对模式下 event 通道 `soTimeout = 0`、
         * `requestNetwork` 不给系统超时。相机接了 TCP 却不回 InitEventAck 时，
         * 整条连接会永久挂在阻塞 read 上（阻塞读不是挂起点，generation 校验轮不到执行）。
         */
        const val STA_TIMEOUTS = "v25_sta_timeouts"

        /**
         * 连接前的可达性探测只做 TCP 建链，不再发 PTP/IP InitCommand。
         *
         * 尼康机身同时只接受一个 PTP/IP 客户端：我们自己发出的探测握手会占住这个槽，
         * 并在探测结束后留下半开会话，把紧随其后的真实连接挡在门外。
         * 关掉它回到 v2.1.1 的「探测即握手」行为。
         */
        const val PROBE_TCP_ONLY = "conn22_probe_tcp_only"

        /** 传输：批量传输期间心跳不再判死链路 */
        const val TRANSFER_HEARTBEAT = "conn22_transfer_heartbeat"

        /**
         * 传输：原图下载进行中让缩略图请求排队让路。
         *
         * PTP 命令通道是全局串行的（`PtpSessionManager.commandMutex`），4MB 原图分块
         * 与网格缩略图请求互相插队，批量下载时单张吞吐被拖垮。
         */
        const val TRANSFER_THUMB_YIELD = "conn22_thumb_yield"

        /** 连接前先跑环境预检（权限 / 位置开关 / OTG / VPN），硬阻断不进入重试 */
        const val PREFLIGHT = "conn22_preflight"

        /**
         * 预检文案与系统入口改由 `assets/compat/rom_rules.json` 驱动（PRD §6.2）。
         * 关掉后回到 `RomDetector` 里的代码内枚举，只剩一条小米文案。
         */
        const val COMPAT_RULES = "conn22_compat_rules"

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
            ATTEMPT_BUDGET to true,
            STA_TCP_ONLY to true,
            STA_TIMEOUTS to true,
            PROBE_TCP_ONLY to true,
            TRANSFER_HEARTBEAT to true,
            TRANSFER_THUMB_YIELD to true,
            PREFLIGHT to true,
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

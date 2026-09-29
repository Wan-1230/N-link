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
         * v2.5 FR-06：主机注册结果由**机身证据**决定，不是一次写入的布尔。
         *
         * 相机对握手回了 InitFail（= 明确不信任本机）时，把「已注册」从「声称」退回「未确认」，
         * 否则注册按钮从此永远亮着「已成功」，而用户下一次 STA 连接照样被拒。
         * 关掉 = 回到 v2.3.1 行为（ConfirmHost 回过一次 OK 就长期记为已注册）。
         */
        const val HOSTREG_VERIFY = "v25_hostreg_verify"

        /**
         * v2.5 FR-04：批量传输期间容忍 event 通道**静默**，不再拿读超时判死链路。
         *
         * 真机语义：相机在传大文件时会把固件注意力给命令通道，event 通道长时间一个字节
         * 不来是**正常**的（见 `PtpSessionManager` 心跳里 G7 那段注释）。旧实现在 event
         * 读取抛 `SocketTimeoutException` 时一律 `markLinkError("event_read_timeout")`，
         * 于是"下载一张 60MB 的 RAW"会自己把链路掐了——周期性掉线最像样的那条假设。
         *
         * **默认关**（核心区）。只放行"一个字节都没读到"的那种超时；
         * 包读到一半才超时属于流错位，开不开都要判死，这条不由本闸门决定。
         */
        const val EVENT_SILENCE_HOLD = "v25_event_silence_hold"

        // ── v2.6 连接链路可观测性与首连成功率（PRD v2.6 §五）─────────────────

        /**
         * FR-19：一次用户意图 = 一个 generation。
         *
         * v2.3.2 的幂等守卫只判 `connector.isActive`，而 `learnFirst` 分支要先跑
         * ~2.4s 的网关学习才碰到 connector，那段窗口里任何重入都放行；重入又用
         * `pairingJob = launch{...}` **覆盖而不取消**旧 job → 孤儿协程各自发起一代连接。
         * 真机日志：4.9s 内 22 条 `sta_try`，45 个连接样本里 36 个（80%）是 `superseded`。
         * 关掉 = 回到 v2.3.2 的守卫。
         */
        const val CONN_SERIALIZE = "v26_conn_serialize"

        /**
         * FR-20：绑网前必须确认这张网的链路地址覆盖相机 IP。
         *
         * v2.3.2 的三路选网在子网不匹配时都会"退而求其次"返回任意 WiFi，然后把
         * **整个进程**绑上去 —— 而 `in_subnet` 诊断只在拿不到 Network 的分支里打，
         * 绑错网时日志一片安静。真机日志：某台机器 6/6 次 `tcp_timeout`，
         * 一条 `sta_fallback` 都没有，socket 全部 1.3s 内秒失败。
         * 关掉 = 回到"任意 WiFi 也绑"。
         */
        const val BIND_SUBNET_CHECK = "v26_bind_subnet_check"

        /**
         * FR-21：一次连接对机身 15740 的探测次数收敛。
         *
         * 尼康机身同时只接受一个 PTP/IP 客户端，而我们一次"点连接"要发 20 次量级的
         * TCP 探测（网关 1 + 出厂兜底 4 + 每轮 preconnect 2 + 每轮失败后 1 + 真实 2，
         * ×3 轮）。代码自己的注释记着实测：探测留下的半开会话要 **~35s** 才被机身收掉，
         * 而 `PROBE_SETTLE_MS` 只有 400ms。真机日志：第一次探通，之后 4 分钟全 `probe=rejected`。
         * 关掉 = 回到 v2.3.2 的探测密度。
         */
        const val PROBE_BUDGET = "v26_probe_budget"

        /**
         * FR-22：`camera_unreachable` 提前收口要同时满足"已经等够了"。
         *
         * 真机日志：失败恒定 20.1~21.4s 收口（n=6），而同型链路成功要 26.4~181.3s（n=4）。
         * **成功所需时间 > 放弃预算** —— 这是"老版本能连、新版本连不上"最省假设的解释
         * （v1.3.1 既无 `UNREACHABLE_GIVE_UP` 也无 `RETRY_BUDGET`，跑满 10 次退避）。
         * 关掉 = 连续 3 次不可达立即收口，不看累计等待。
         */
        const val GIVEUP_FLOOR = "v26_giveup_floor"

        /**
         * FR-23：「类 WiFi 的本地接口」要排除蜂窝与 VPN。
         *
         * v2.3.2 的实现是 `localAddresses().isNotEmpty()`，于是 `ccmni4`（蜂窝）、
         * `vgate0`（VPN）都算数 → 手机 WiFi 没连上时也判为"有本地网络"→ 走默认路由
         * 把 socket 打进蜂窝黑洞。真机日志：15/15 次 `sta_fallback` 全部
         * `in_subnet=false`，`in_subnet=true` 出现 **0** 次。
         * 关掉 = 回到"任意非回环 IPv4 即算类 WiFi"（v2.0.1 修 RC-0 时的粗判据）。
         */
        const val IFACE_CLASSIFY = "v26_iface_classify"

        /**
         * FR-24：预检分「硬阻断」与「软告警」，软告警不受"已在相机热点上"豁免。
         *
         * v2.3.2 在 `isOnCameraAp()` 为真时**跳过全部预检**，包括 VPN 与「避开不良网络」
         * 两项 —— 而恰恰是在相机热点上，这两项才是致败因素（日志里那台机器就有 `vgate0`）。
         * 关掉 = 回到"热点上什么都不查"。
         */
        const val PREFLIGHT_SOFT = "v26_preflight_soft"

        /**
         * FR-26a：收到 `InitFail` 就把当前地址认定为真实相机 IP。
         *
         * 依据：`InitFail` 是**机身自己发出来的 PTP/IP 包**，能收到它就说明这个地址上
         * 确实有一台尼康相机在应答，只是不认本机这个客户端 —— 这比"TCP 端口开着"强得多。
         * 旧实现只在整条连接成功时才提升 `realCameraIp`，于是被拒的地址一律不记，
         * App 重启后回落到硬编码 192.168.1.1：真机日志 01:11:07→01:11:31 对
         * 192.168.1.1 / 192.168.0.1 / 192.168.3.1 / 10.0.0.1 各探一次全 TIMEOUT，
         * 再加一次 30s socket 超时，而相机其实一直在 10.184.219.14。
         *
         * 关掉 = 只有整条连接成功才记地址（v2.3.2 行为）。
         */
        const val IP_PROMOTE = "v26_ip_promote"

        /**
         * FR-26b：USB 机身**完全不应答**时跳过「残留会话恢复」。
         *
         * 恢复的前提是"机身在、只是上一次的会话没关干净"；不应答说明它压根没在处理 PTP，
         * 对它再发 CloseSession + DeviceReady 只是把两次读超时串起来。
         * 真机证据：25 次 `stale_session_recovery` 全部失败、0 次成功；
         * 历史上连成功的那几次 OpenSession 只用 5ms，失败的每次 16s 读超时，
         * 加上这一步把单次失败从 16s 拖到 31s（01:08:38→01:09:09 实测）。
         *
         * 关掉 = 无论有没有应答都做残留会话恢复（v1.3.0 行为）。
         */
        const val USB_SKIP_RECOVERY = "v26_usb_skip_recovery"

        /**
         * FR-27：USB 自动重连的退避要**跨轮次单调变长**。
         *
         * 关掉 = 每轮都从 1000ms 重新开始（v2.2~v2.3.2 的实际行为）。
         * 真机实测代价：273 秒内重连 45 次，每次都要 open 设备两遍并在机身 PTP
         * 会话还活着的时候 `releaseInterface` + `close()`。
         */
        const val USB_RECONNECT_RAMP = "v26_usb_reconnect_ramp"


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
            HOSTREG_VERIFY to true,
            EVENT_SILENCE_HOLD to false,
            PROBE_TCP_ONLY to true,
            TRANSFER_HEARTBEAT to true,
            TRANSFER_THUMB_YIELD to true,
            PREFLIGHT to true,
            USB_GRACE to true,
            USB_OTG_HINT to true,
            CONN_SERIALIZE to true,
            BIND_SUBNET_CHECK to true,
            PROBE_BUDGET to true,
            GIVEUP_FLOOR to true,
            IFACE_CLASSIFY to true,
            PREFLIGHT_SOFT to true,
            IP_PROMOTE to true,
            USB_SKIP_RECOVERY to true,
            USB_RECONNECT_RAMP to true,
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

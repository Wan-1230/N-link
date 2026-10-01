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
         * FR-28①：`claimInterface()` 的**返回值**必须当判据用。
         *
         * 本机 android.jar（compileSdk 35）实证：`public boolean claimInterface(UsbInterface, boolean)`
         * —— 它返回布尔、**不抛异常**。旧写法 `runCatching { claimInterface(...) }.onFailure { … }`
         * 结构上只能接住异常，claim 返回 false 时 `onFailure` 永不触发，于是带着一个没claim成功的
         * 接口往下走、照样发 OpenSession。表现与用户报的一模一样：
         * `usb_open ok=true` 之后「机身一个字节都不回」。
         *
         * 关掉 = 回到"返回值丢掉"的旧写法（v1.3.1~v2.6.0 的实际行为）。
         */
        const val USB_CLAIM_CHECK = "v28_usb_claim_check"
        /**
         * FR-28②：命令阶段的 bulkTransfer 要按**写全长度**判成败，不是只判 `< 0`。
         *
         * `bulkTransfer` 返回实际传输字节数：返回 0（超时未写入）或短写时，旧判据 `< 0`
         * 一律放过，于是"根本没送到相机"被记成"送出去了但机身不回"，接下来必然是
         * 5000ms 读超时 —— 这正是日志里那 30 多次 `usb_open → usb_session` 间隔 5.0~5.2s 的成因。
         *
         * 关掉 = 只把负值当失败（v2.6.0 行为）。
         */
        const val USB_WRITE_STRICT = "v28_usb_write_strict"

        /**
         * FR-28③：OpenSession 允许一次重试，从而走到既有的 clearHalt 恢复路径。
         *
         * `transact` 只在第 2 次尝试前调 `clearHaltBothEndpoints()`（:737），而 OpenSession
         * 原来 `retryOnTimeout=false` → `maxAttempts=1` → 失败路径**一次都不清 stall、不排空**。
         * 上一轮残留在管道里的容器会被本轮第一读捞到 → txid mismatch → 又是 null，
         * 于是"机身不回"会被自己的失步永久锁住。
         * OpenSession 无副作用（且 `SESSION_ALREADY_OPEN` 已被当作成功），重试是安全的。
         *
         * 关掉 = OpenSession 只发一次（v2.6.0 行为）。
         */
        const val USB_OPENSESSION_RETRY = "v28_usb_opensession_retry"

        /**
         * FR-28④：拆 USB 链路之前，先给机身发一次 CloseSession(0x1003)。
         *
         * 四款竞品的静态产物里**都有** CloseSession（ZDROP `sendCloseSession`、
         * 像素蛋糕那套 SDK 甚至有品牌专用的 `NikonCloseSessionAction` 与 `CloseSessionCommand`），
         * 而我们的正常拆链路路径（保活判死 → ERROR → 下一轮 `disconnect(silent=true)`）
         * 只做 `releaseInterface` + `connection.close()`，**一个字节都不告诉机身** ——
         * 于是机身认为会话还在自己手里，下一次 OpenSession 没人应答。
         *
         * 超时单独压到 1200ms 且结果不参与判定（这一步只是尽力而为）。
         * 关掉 = 拆链路不发 CloseSession（v2.6.0 行为）。
         */
        const val USB_CLOSE_SESSION_ON_TEARDOWN = "v28_usb_close_session"

        // ── v2.6.3 STA 注册链路加固（见 docs/STA注册失败-根因分析与优化方案.md §4）──
        //
        // 三条**默认关**：它们改的是"失败之后如何解释与如何少走弯路"，不解决注册本身
        // 成功与否。默认关 = 与 v2.6.2 逐位等价，真机验证过再逐个打开。

        /**
         * R1：按 InitFail **原因**决定怎么引导，而不是一律"需要注册"。
         *
         * 原实现把所有 InitFail 都当作"相机不认本机"→ 置 `staRegisterNeeded`。
         * 但 reason=1 是 `connection_in_use`（机身唯一的 PTP/IP 客户端槽还被上一代
         * 会话的尸体占着），正确动作是**等十几秒重试**，不是让用户去点注册 ——
         * 2026-09-30 那份日志里 15:50:24 的 `fail_reason=1` 正是这种，用户被引导去
         * 注册，而相机当时根本不在配置向导，注册必然被 0x201F 拒。
         *
         * 打开后：reason=1 → 只提示"相机正忙"；reason=2 → 才置需要注册；
         * reason=3 → 直接视为已注册。
         */
        const val HOSTREG_HINT_V2 = "v263_hostreg_hint_v2"

        /**
         * R2：注册前先用 `GetDeviceInfo` 的 `OperationsSupported` 检查相机**当前模式**
         * 支不支持主机注册（0x952B）。
         *
         * 尼康的 SnapBridge AP（连接至智能设备）与主机配置向导**都在 192.168.1.1
         * 提供 PTP/IP**，但只有向导接受 0x952B。原实现不看这个，直接盲发 → 拿到
         * 0x201F 之后只能给一句笼统的"请确认相机屏幕处于可接受连接的画面"。
         * 打开后：不支持就**不发**，直接给出"相机不在主机配置向导"的明确结论，
         * 并把 SupportedOperations 摘要写进 I/O 日志。
         */
        const val HOSTREG_PRECHECK = "v263_hostreg_precheck"

        /**
         * R4：STA 预连接探测会掉进**蜂窝黑洞**时直接判不可达，不再发 socket。
         *
         * 2026-09-30 日志：手机已离开相机热点（`ifaces=ap0+ccmni4`，无 192.168.1.x），
         * App 仍对 192.168.1.1 从 `/10.56.31.140`（ccmni4 蜂窝）发起连接，
         * 15:50:45 空转到 15:51:38（约 53s）才等到手机回连相机热点。
         * 蜂窝口上打私网地址是纯浪费 —— 它是黑洞，既不 RST 也不可达。
         *
         * **v2.6.6 FR-29⑤：判据换过，原来那版不能开。**
         * v2.6.3 用 `localInterfaces.subnetsContain(host)`，而 PRD §13.3 已用真机数据证明
         * 这个来源在本项目主力机（vivo V2509A）上是**假阴性**：`localAddresses()` 看不见
         * 实际承载相机流量的接口（只列出 `ap0` + `ccmni4`），同一次连接在 `in_subnet=false`
         * 的情况下于 12:39:56 成功。以它为据硬拦截 = 把能连上的场景判死（§13.3 撤销 S3 同理）。
         * 现改走 `ConnectivityManager`（FR-23 已确立为主判据；同一份日志里 `net_bind covers=true`
         * 判定正确）：只有"默认路由在蜂窝 **且** 没有任何 WiFi 覆盖相机地址"才算黑洞。
         * 见 `WifiNetworkMonitor.probeWouldUseCellular` / 纯函数 `cellularBlackhole`。
         */
        const val STA_SKIP_CELLULAR = "v263_sta_skip_cellular"

        /**
         * v2.6.4 **AP 空闲断链修复**（修复项，默认开）。
         *
         * 现象：AP 连上后静置 2~7 分钟，相机自己把 PTP/IP 会话关掉
         * （2026-10-01 日志：01:09:00 最后一次下载 → 01:15:27 `ping_write_failed`
         * + `event_read_error` → `network_lost`）。
         *
         * 原因：相机侧的会话空闲计时器看的是 **PTP 命令**活动，不是 PTP/IP 传输层的
         * Ping（type 13）。我们的保活为了"把命令通道完全留给业务"，只发 Ping，
         * 于是相机认为这个客户端一直在空转，到点就断。Ping 只能发现 TCP 半开，
         * 刷新不了相机的计时器 —— 两件事不能互相替代。
         *
         * 打开后：命令通道空闲超过 60s 且不在批量传输中，补一条最轻量的
         * `DeviceReady`，把相机的空闲计时器拨回去。**批量传输期间一律不发**
         * （那时命令通道本来就在忙，相机也不会判空闲）。
         * 关掉 = 回到 v2.6.3 的"只发 Ping"（会在数分钟后断链）。
         */
        const val SESSION_IDLE_REFRESH = "v264_session_idle_refresh"

        /**
         * FR-32：扫描强候选命中即早退（命中后 1.5s 宽限再收工）。
         *
         * 默认**关**：2026-10-02 坏包（FR-31 全量）真机回归后随 FR-31① 一起回退过一轮；
         * 重落地时先以闸门形式进包，真机证明「多相机列表不漏列」后再转默认开。
         * 注册发现路径（FR-30 自主注册）显式请求 earlyExit，同样受本闸控制。
         */
        const val STA_SCAN_EARLY_EXIT = "v32_scan_early_exit"


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
            USB_CLAIM_CHECK to true,
            USB_WRITE_STRICT to true,
            USB_OPENSESSION_RETRY to true,
            USB_CLOSE_SESSION_ON_TEARDOWN to true,
            // v2.6.3 STA 注册链路加固：三条默认关（与 v2.6.2 逐位等价，真机验证后再开）
            // v2.6.6 FR-29⑥：R1/R2 转默认开。理由不是"觉得该开了"，是三条：
            // ①「开发选项」面板是 debug-only（`SettingsFragment.setupLabFlags` 首行
            //    `if (!BuildConfig.DEBUG) return`）—— 默认关等于**正式包里永远不生效**，
            //    这两条的价值到 v2.6.5 为止一直是 0；
            // ② 两条都只影响"怎么解释失败"和"要不要盲发一次注册"，不改任何已跑通的建链路径：
            //    R1 是 InitFail 的 reason 分级（1=机身忙→等待重试，2=门控拒绝→才提示注册，
            //    3=已注册→直接置位），修的是"把 connection_in_use 误报成需要注册"；
            //    R2 是发 0x952B 前先看 GetDeviceInfo 的 OperationsSupported 里有没有它，
            //    没有就直接说"相机不在主机配置向导"，而**不是**等相机回 0x201F 再猜。
            //    这正是本轮真机故障（`prepare_fail code=0x201f` 10ms 秒拒）最对症的一条；
            // ③ R2 的解析失败语义是"不知道就不拦"（`lastSupportedOperations == null` 放行），
            //    所以解析不出来时行为与关闸门一致，不会误伤。
            HOSTREG_HINT_V2 to true,
            HOSTREG_PRECHECK to true,
            // R4 仍默认关：判据已在 FR-29⑤ 换成 ConnectivityManager，但**真机数据还没站它这边**。
            // PRD §13.3 那次成功连接发生时，默认路由确实在蜂窝（socket 从 /10.56.31.140 出），
            // 也没有任何 WiFi 覆盖 192.168.1.1 —— 按新判据同样会被拦，而它实际连上了。
            // 唯一能分清"新判据对不对"的办法是双记一轮：见 FR-29⑦ 的 `sta_probe_ctx`。
            STA_SKIP_CELLULAR to false,
            // AP 空闲断链修复：默认开（这是修复不是实验，但保留开关便于现场二分定位）
            SESSION_IDLE_REFRESH to true,
            // FR-32：扫描早退默认关（坏包回归回退项，真机再定默认值）
            STA_SCAN_EARLY_EXIT to false,
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

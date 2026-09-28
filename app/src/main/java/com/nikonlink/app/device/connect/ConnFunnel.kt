package com.nikonlink.app.device.connect

import com.nikonlink.app.BuildConfig
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.device.RomDetector
import com.nikonlink.app.shared.metrics.BaselineMetrics
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一连接漏斗（PRD v2.2 §7.1 / G12）。
 *
 * **解决什么**：此前 WiFi、USB、BLE 各有一套错误文案，且都不带「死在哪一步」的信息，
 * 于是「这台手机上 AP 成功率到底多少」「是连不上热点还是热点连上了但相机不回」
 * 这类问题只能靠猜。漏斗把一次连接拆成固定阶段，每次尝试都产出
 * `(阶段, 原因码, 耗时, 通道, 机型, ROM)` —— 用户能看懂、工单能定位、我们能算基线。
 *
 * 只存内存（最近 12 次尝试），不落盘不上报；导出时随日志一起给出。
 */
@Singleton
class ConnFunnel @Inject constructor(
    private val eventLogger: AppEventLogger,
    private val baselineMetrics: BaselineMetrics
) {
    companion object {
        private const val HISTORY_LIMIT = 12

        /**
         * FR-01 口径里要单独给百分位的阶段（文档 §1）；其余阶段只进总耗时。
         *
         * v2.6 FR-18g 加入 `ROUTE`。它不是"多一个指标"，而是**修正 TCP 的定义**：
         * `DISCOVER` 卡打在 `beginWifiPairing` 入口，而 DISCOVER→TCP 之间其实还夹着
         * 选网（`resolveNetwork`，上限 4s）与连接前探活（`waitUntilReachable`，上限 3s）。
         * 于是真机基线里 `TCP p95=178927ms` 根本不是建链耗时 —— 原始时间戳量出来同一次
         * 尝试的建链只有 576ms（日志2 gen=49）。没有 `ROUTE` 这个分界卡，
         * "慢在建链"和"慢在选网/探活"就永远分不开，也就永远不知道该优化哪个。
         */
        private val MEASURED_STAGES = setOf(
            Stage.DISCOVER, Stage.ROUTE, Stage.TCP, Stage.HANDSHAKE, Stage.FIRST_COMMAND
        )
    }

    @Volatile
    private var current: Attempt? = null

        /**
         * 阶段划分（AP/STA/USB 共用同一条主轴，USB 自然跳过 JOIN/ADDRESS/ROUTE）。
         */
        enum class Stage(val label: String) {
            INTENT("用户发起连接"),
            PREFLIGHT("环境预检"),
            JOIN_REQUEST("请求加入相机热点"),
            JOINED("已加入网络"),
            ADDRESS("确定相机地址"),
            ROUTE("绑定网络路由"),
            DISCOVER("发现相机"),
            TCP("TCP:15740 建链"),
            HANDSHAKE("PTP 握手"),
            FIRST_COMMAND("首条命令"),
            READY("可用"),
            RECONNECT("自动重连")
        }

        /**
         * 一次连接尝试的**发起者**（FR-18c）。
         *
         * 真机日志里出现过 4.9 秒内 22 条 `sta_try`、45 个样本 80% 记 `superseded`
         * 的风暴，但静态读代码只能排除"UI 点击"和 15 分钟一轮的 HealthWorker，
         * 无法唯一锁定触发者 —— 因为 `conn_attempt` 这行不带来源。
         * 风暴的**放大机制**已经查清（守卫盲区 + `pairingJob` 覆盖不取消），
         * 剩下"谁在按按钮"这一半必须有数据。
         */
        enum class Caller(val code: String) {
            USER_TAP("user_tap"),
            RECONNECT_TRIGGER("reconnect_trigger"),
            HEALTH_WORKER("health_worker"),
            RESTORE_PAIRED("restore_paired"),
            WIFI_STATE("wifi_state"),
            PTP_SESSION("ptp_session"),
            UNKNOWN("unknown")
        }

        /**
         * 原因码。**只增不改**：码值一旦发布就是日志里的稳定契约，文案可以改。
         * 这里收敛的是原本散在 WifiDirectConnector / USB / PTP InitFail 三处的字符串。
         */
        enum class Reason(val code: String, val message: String, val hint: String?) {
            OK("ok", "正常", null),

            // ── 预检 ──
            PERM_NEARBY_WIFI("perm_nearby_wifi", "缺少「附近的设备」权限", "在系统弹窗中允许该权限"),
            PERM_LOCATION("perm_location", "缺少定位权限（Android 12 及以下扫描 WiFi 必需）", "授予定位权限"),
            LOC_SWITCH_OFF("loc_switch_off", "系统「位置信息」总开关未开", "下拉快捷面板打开位置信息"),
            BATT_RESTRICTED("batt_restricted", "App 处于电池优化名单，后台易被系统回收", "在电池设置里设为「无限制」"),
            AUTOSTART_REMINDER("autostart_reminder", "该机型 ROM 有自启动/后台白名单策略，息屏长传输可能被系统冻结", null),
            OTG_DISABLED("otg_disabled", "USB 总线上没有任何设备", RomDetector.otgHint()),
            AVOID_BAD_WIFI("avoid_bad_wifi", "系统开启了「避开不良网络 / 智能切换」，会主动断开没有网络的相机热点", "关闭 WLAN+ / 智能网络切换"),
            VPN_ACTIVE("vpn_active", "存在 VPN 通道，相机流量可能被劫持", "连接期间关闭 VPN"),

            // ── 加入热点 ──
            USER_DECLINED_JOIN("user_declined_join", "连接热点的授权弹窗被取消", "再点一次连接并选择「允许」"),
            AP_NOT_FOUND("ap_not_found", "没搜到相机发出的热点", "确认相机热点已开启（菜单 → 网络 → 接入点/热点）"),
            JOIN_TIMEOUT("join_timeout", "手机加入相机热点超时", "在系统 WiFi 里先手动连一次该热点"),
            JOIN_UNAVAILABLE("join_unavailable", "系统拒绝建立到相机热点的连接", "在系统 WiFi 设置里「忽略」该网络后重连"),

            // ── 地址与路由 ──
            NO_WIFI_NETWORK("no_wifi_network", "拿不到可用的 WiFi 网络", "确认手机已连上相机热点或同一 WiFi"),
            GATEWAY_UNSTABLE("gateway_unstable", "相机地址在恢复过程中发生了变化", "等待几秒后重试"),
            NOT_ON_CAMERA_AP("not_on_camera_ap", "手机当前不在相机网络里", "到系统 WiFi 连接相机热点后返回"),
            BIND_FAILED("bind_failed", "网络绑定失败（默认路由不在 WiFi 上）", "关闭移动数据或 VPN 后重试"),

            // ── 发现与建链 ──
            DISCOVER_TIMEOUT("discover_timeout", "在网段里没有找到相机", "确认相机已点亮并停留在连接页面"),
            CAMERA_SLEEPING("camera_sleeping", "端口开着但相机没回握手（多半在休眠）", "按快门或按一下电源键唤醒相机后重试"),
            ROUTER_ISOLATION("router_isolation", "疑似路由器开启了 AP/客户端隔离，设备之间不可达", "关闭路由器「客户端隔离」，或改用相机热点/USB"),
            TCP_REFUSED("tcp_refused", "相机端口拒绝连接", "退出相机菜单里的连接页面后重新进入"),
            TCP_TIMEOUT("tcp_timeout", "连接相机端口超时", "确认手机与相机在同一网段"),

            // ── PTP 会话 ──
            PTP_INIT_REJECTED("ptp_init_rejected", "相机拒绝了本机的会话请求", "在相机上完成主机注册/按 OK 确认"),
            EVENT_ACK_TIMEOUT(
                "event_ack_timeout",
                "相机接了连接但对事件通道一言不发",
                "让相机停留在连接/配对页面；仍不行就关开一次相机电源"
            ),
            PTP_BUSY_OTHER_CLIENT("ptp_busy", "相机已被其它客户端占用（机身同时只允许一个）", "关闭 SnapBridge 或另一台手机上的连接"),
            PTP_HANDSHAKE_FAILED("ptp_handshake_failed", "PTP 握手未完成", "保持相机停留在配对/连接页面后重试"),
            SESSION_DROP("session_drop", "会话中途断开", "等待自动重连，或手动重连"),

            // ── USB ──
            NO_USB_INTERFACE("no_usb_interface", "相机未以 PTP/StillImage 模式枚举", "在相机菜单把 USB 连接方式设为 PTP/Mass"),
            CLAIM_FAILED("claim_failed", "USB 接口被其它程序占用", "关闭图库/文件管理器等应用后重试"),
            USB_PERMISSION_DENIED("usb_permission_denied", "USB 访问授权被拒绝", "重新插拔并在弹窗中选择「允许」"),
            USB_CABLE_BROWNOUT("usb_cable_brownout", "USB 反复插拔（线材或供电不稳）", "换数据线，或给相机接外接电源"),

            // ── 收尾 ──
            BUDGET_EXHAUSTED("budget_exhausted", "本轮重试次数已达上限，继续重试会被系统拉黑该网络", "在系统 WiFi 里「忽略」相机热点后重新连接"),
            SUPERSEDED("superseded", "这一轮还没走完就被新的连接请求打断", "无需处理；日志里频繁出现说明连接被重复触发"),
            UNKNOWN("unknown", "连接失败", null)
        }

        data class Step(val stage: Stage, val atMs: Long, val reason: Reason? = null, val detail: String = "")

        data class Attempt(
            val channel: String,
            val startedAt: Long,
            val steps: MutableList<Step> = mutableListOf(),
            var finishedAt: Long = 0L,
            /**
             * FR-18c：这一轮是谁发起的。`superseded` 刷屏但说不出触发者，
             * 就永远分不清"用户狂点"与"程序自触发"——两者的修法完全相反。
             */
            var caller: String = Caller.UNKNOWN.code,
            /** FR-18h：本轮对机身端口发起的 TCP 探测次数（不含真实连接的 command/event 两条 socket）。 */
            var probes: Int = 0
        ) {
            val lastStage: Stage get() = steps.lastOrNull()?.stage ?: Stage.INTENT
            val durationMs: Long get() = (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt

            /** 一行可读结论，用于状态栏与日志。 */
            fun summary(): String {
                val last = steps.lastOrNull()
                val reasonText = last?.reason?.takeIf { it != Reason.OK }?.let { " · ${it.code}" }.orEmpty()
                return String.format(
                    "%s%s（%.1fs）",
                    last?.stage?.label ?: "未开始", reasonText, durationMs / 1000.0
                )
            }
        }


    private val _history = MutableStateFlow<List<Attempt>>(emptyList())

    /**
     * 报障时要能分清用户装的是哪个包。
     *
     * ⚠ 不要再手动拼 `-debug`：`app/build.gradle.kts` 的 debug buildType 已经有
     * `versionNameSuffix = "-debug"`，`VERSION_NAME` 本身就带后缀。这里再拼一次，
     * 真机日志里就成了 `av=2.3.2-debug-debug`（v2.6 首份验证日志即此）。
     * 同一份日志里 `update_check current=2.3.2-debug` 用的是 `BuildConfig.VERSION_NAME`
     * 原值，两处一对就能看出多出来的是谁。
     */
    private val appVersion: String = BuildConfig.VERSION_NAME

    /** 最近的连接尝试（含每次停在哪个阶段、原因码、耗时）。 */
    val history: StateFlow<List<Attempt>> = _history.asStateFlow()

    fun begin(channel: String, mode: String = "", caller: String = Caller.UNKNOWN.code) {
        // 上一轮还没收口（例如自动重连直接发起了新尝试）先记下来，别把失败丢了
        if (current != null) finish()
        val attempt = Attempt(
            channel = channel + if (mode.isEmpty()) "" else "/$mode",
            startedAt = System.currentTimeMillis(),
            caller = caller
        )
        attempt.steps += Step(Stage.INTENT, attempt.startedAt, Reason.OK, "机型 ${Build.MODEL} / ROM ${RomDetector.family}")
        current = attempt
        eventLogger.event(
            "conn_attempt",
            "channel" to attempt.channel,
            "caller" to caller,
            // FR-18f：报障日志此前判断不出是什么手机，机型归因做不了。
            // 只带 Build 的公开字段，与 §四 FR-18 的脱敏边界一致（不带序列号/标识符）。
            "model" to Build.MODEL,
            "rom" to RomDetector.family,
            "sdk" to Build.VERSION.SDK_INT,
            "av" to appVersion
        )
    }

    /**
     * FR-18h：记一次对机身 15740 的 TCP 探测（连接前探活、网关筛探、失败后归类都算）。
     *
     * 尼康机身只有一个 PTP/IP 客户端槽，**每一次探测都在占它**：代码自己记的实测
     * 是半开会话要 ~35s 才被机身收掉（`WifiDirectConnector` 的 `isCameraPortOpen` 注释），
     * 而我们的让路间隔只给了 400ms。不把这个次数打出来，"探测反而把相机挡在门外"
     * 这条假设就永远是推测。
     */
    fun recordProbe() {
        current?.probes = (current?.probes ?: 0) + 1
    }

    /** 推进到某阶段；[reason] 非 OK 表示停在了这一步。 */
    fun stage(stage: Stage, reason: Reason = Reason.OK, detail: String = "") {
        val attempt = current ?: return
        if (attempt.steps.lastOrNull()?.stage == stage && attempt.steps.last().reason == reason) return
        attempt.steps += Step(stage, System.currentTimeMillis(), reason, detail)
        if (reason != Reason.OK) {
            eventLogger.event(
                "conn_fail",
                "stage" to stage.name, "reason" to reason.code,
                "channel" to attempt.channel, "ms" to attempt.durationMs,
                "detail" to detail
            )
        } else {
            eventLogger.event("conn_stage", "stage" to stage.name, "channel" to attempt.channel)
        }
        if (stage == Stage.READY) finish()
    }

    /** 记一次失败并收口（默认停在当前阶段）。 */
    fun fail(reason: Reason, detail: String = "", at: Stage? = null) {
        val attempt = current ?: return
        stage(at ?: attempt.lastStage, reason, detail)
        finish()
    }

    fun finish() {
        val attempt = current ?: return
        current = null
        attempt.finishedAt = System.currentTimeMillis()
        // 收口时给一个**诚实**的结论：既没走到 READY、又没记过任何失败 = 这一轮是被
        // 后来的连接请求顶掉的。旧实现直接取最后一步的原因码，于是日志里满是
        // `conn_result stage=INTENT reason=ok` —— 看起来像成功，实际什么都没发生，
        // 拿日志排查「为什么前两次连不上」时会被彻底带偏。
        val lastStep = attempt.steps.lastOrNull()
        val outcome = when {
            lastStep == null -> Reason.UNKNOWN
            lastStep.stage == Stage.READY -> Reason.OK
            lastStep.reason == Reason.OK -> Reason.SUPERSEDED
            else -> lastStep.reason ?: Reason.UNKNOWN
        }
        if (outcome == Reason.SUPERSEDED) {
            attempt.steps += Step(lastStep?.stage ?: Stage.INTENT, attempt.finishedAt, outcome)
        }
        _history.value = (listOf(attempt) + _history.value).take(HISTORY_LIMIT)
        eventLogger.event(
            "conn_result",
            "channel" to attempt.channel,
            "stage" to (lastStep?.stage ?: Stage.INTENT).name,
            "ms" to attempt.durationMs,
            "reason" to outcome.code,
            "caller" to attempt.caller,
            "probes" to attempt.probes,
            // FR-18g 的诚实标记：一个被测量阶段在一次尝试里出现两次以上，
            // 说明这条尝试跨了多个连接循环，它的**分阶段耗时不可信**（总耗时仍可信）。
            // 日志2 的 `TCP p95=178927ms` 就是这么来的 —— 那台机器 4.9 秒内开了 22 代连接，
            // 20 个 DISCOVER 卡挤在同一条 attempt 里，TCP 的"上一步"根本不是本次的 DISCOVER。
            // 不改 stageGaps 的算法（它对线性序列是对的，且已有单测钉住语义），
            // 而是把被污染的样本标出来，让读日志的人不去信它。
            "revisits" to revisitCount(attempt),
            "rom" to RomDetector.family
        )
        baselineMetrics.recordConn(
            BaselineMetrics.ConnSample(
                channel = attempt.channel,
                outcome = outcome.code,
                totalMs = attempt.durationMs,
                stageMs = stageGaps(attempt),
                probes = attempt.probes,
                revisits = revisitCount(attempt),
                device = "${Build.MODEL}/${RomDetector.family}"
            )
        )
    }

    /**
     * 被测量阶段里被**重访**的次数（同一阶段出现 > 1 次的那些）。
     *
     * 0 = 这条样本的阶段序列是线性的，分阶段耗时可信；> 0 = 只信总耗时。
     */
    internal fun revisitCount(attempt: Attempt): Int =
        attempt.steps.map { it.stage }.filter { it in MEASURED_STAGES }
            .groupingBy { it }.eachCount().count { it.value > 1 }

    /** 相邻阶段的时间差即该阶段自身耗时；INTENT 之后第一段是预检+发起的合计。 */
    internal fun stageGaps(attempt: Attempt): Map<String, Long> {
        val gaps = LinkedHashMap<String, Long>()
        for (i in 1 until attempt.steps.size) {
            val stage = attempt.steps[i].stage
            if (stage in MEASURED_STAGES) {
                val gap = attempt.steps[i].atMs - attempt.steps[i - 1].atMs
                // 同一阶段可能被重访（重试、顶号），保留最后一次，避免把等待摊进耗时里
                gaps[stage.name] = gap
            }
        }
        return gaps
    }

    /** 进行中的尝试属于哪个通道（无进行中的尝试时 null）。 */
    fun currentChannel(): String? = current?.channel

    /** 是否有该通道前缀的进行中尝试。 */
    fun hasOngoing(channelPrefix: String): Boolean =
        current?.channel?.startsWith(channelPrefix) == true

    /** 供状态栏/诊断面板展示：最近一次尝试 + 历史摘要。 */
    fun lastSummary(): String? = _history.value.firstOrNull()?.summary()

    fun render(): String {
        val list = _history.value
        if (list.isEmpty()) return "本次启动还没有连接记录"
        return list.joinToString("\n\n") { attempt ->
            val head = "【${attempt.channel}】${attempt.summary()}"
            head + "\n" + attempt.steps.joinToString("\n") { step ->
                val gap = step.atMs - attempt.startedAt
                val tag = step.reason?.takeIf { it != Reason.OK }?.let { " ✗ ${it.code}" } ?: ""
                "  +%.1fs %s%s".format(gap / 1000.0, step.stage.label, tag)
            }
        }
    }
}

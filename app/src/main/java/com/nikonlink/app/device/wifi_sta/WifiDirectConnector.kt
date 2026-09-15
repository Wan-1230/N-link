package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.model.ConnectionEvent
import com.nikonlink.app.device.connect.ConnectionStateMachine
import com.nikonlink.app.device.ptp.PtpIpProbe
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.shared.common.AppEventLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 直连（STA/AP）唯一连接入口。
 *
 * ## 解决的根因
 *
 * - **RC-1 并发风暴**：旧 `ConnectionManager.connectToWifiCamera` 在 `dispatch(StartConnect)`
 *   之后才给 `pairingJob` 赋值，而 dispatch 会同步触发 `observeReconnectTrigger` 的
 *   `state.collect`，此刻 `pairingJob` 还是旧值（null）→ 递归再进一次 → 内层 job 被
 *   外层覆盖成孤儿、永不可取消。日志铁证：每条 `pair_start` 成对出现，间隔 1~20ms。
 *   现在所有连接都走本类，`activeJob` 槽位同步登记，旧循环要么被取消、要么因
 *   [generation] 令牌过期而自行退出。
 * - **RC-2 三循环互拆**：pairing / recoverWifiSession / 状态机重连三条独立循环各自
 *   `closeSession()`，相机侧必然回 `InitFail(type=5)`（尼康只接受一对通道）。
 *   现在全项目只有这一条连接循环。
 * - **RC-7 过期结果写状态**：每个协程持有自己的 [generation]，每个 await 点后校验，
 *   过期立即 `return`，不会把旧尝试的失败写进新连接的状态。
 * - **RC-8 节奏**：指数退避 `[1s,2s,4s,8s,15s…]` 共 10 次（总窗口约 90s，与旧
 *   PAIRING_TIMEOUT_MS 一致），相机停在"按 OK 确认"画面时不再被 10s 超时 + 3s 间隔拖死。
 * - **RC-4/RC-5**：网络句柄通过 [WifiNetworkMonitor] 每次尝试重新解析、
 *   连接生命周期内持有 WifiLock。
 *
 * `ConnectionManager` 对外签名零变更：它只把三条循环的内部实现换成对 [connect] 的调用。
 */
@Singleton
class WifiDirectConnector @Inject constructor(
    private val ptpSession: PtpSessionManager,
    private val wifiManager: WifiManager,
    private val networkMonitor: WifiNetworkMonitor,
    private val networkRequester: StaNetworkRequester,
    private val localInterfaces: LocalNetworkInterfaceResolver,
    private val stateMachine: ConnectionStateMachine,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "WifiDirect"
        private const val MAX_ATTEMPTS = 10
        /** RC-8: 指数退避，10 次总等待 ≈ 90s（对齐旧配对超时窗口） */
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 15000, 15000, 15000, 15000, 15000)

        /**
         * RC-4: 每次尝试等待 WiFi 网络就绪的上限。
         *
         * FIX-2：旧值 8000ms 会与 [StaNetworkRequester.acquire] 的 8000ms **串行**叠加，
         * 真机日志里每轮尝试耗时恒定 16.2s（= 8000 + 8000 + 余量），用户全程没有任何反馈。
         * 现在三条解析路径**并行**竞争（见 [resolveNetwork]），本值收敛到 4s，
         * 最坏耗时 ≈ 4s 而非 16.2s。
         */
        private const val AWAIT_NETWORK_MS = 4000L

        /** FIX-2：并行解析期间给 UI 推进度提示的间隔 */
        private const val PROGRESS_TICK_MS = 1500L

        /**
         * FIX-5：连接前等待相机起监听的总窗口（ZDROP `refreshing discovery before connect`）。
         *
         * 相机在 STA 下不会立刻监听 15740，需要先应用 host profile，实测有数秒延迟。
         */
        private const val PRECONNECT_WAIT_MS = 3000L

        /** FIX-5：单次探活超时（比扫描阶段长，覆盖相机唤醒时延） */
        private const val PRECONNECT_PROBE_TIMEOUT_MS = 1200L

        /** FIX-5：前几轮才做连接前等待（后续轮次已经给过机会，不必每轮都等） */
        private const val PRECONNECT_MAX_ROUNDS = 3
    }

    enum class Mode { PAIRING, RESUME }

    private val mutex = Mutex()
    private var scope: CoroutineScope? = null

    /** RC-7: 尝试令牌。每次 [connect] 递增，旧协程在任何 await 点发现过期即退。 */
    @Volatile private var generation = 0

    /** 当前活跃的连接循环。同步登记/取消，是 RC-1 修复的核心槽位。 */
    @Volatile private var activeJob: Job? = null

    /**
     * 会话是否已建立（连接成功后置 true）。
     *
     * STA 根因之一：旧实现在连接循环的 `finally` 里无条件
     * `bindProcessTo(null)`，导致**刚连上就把进程级绑网解掉**——
     * 后续 PTP 数据通道重新走系统默认路由（双卡机上多半是蜂窝网），
     * 表现为"连接成功但立刻断流/指令无响应"。成功路径必须保持绑网，
     * 直到 [cancelActive] / [stop] 才对称解绑。
     */
    @Volatile private var sessionBound = false

    /** 是否有连接循环在跑（含配对与恢复）。上层用它做幂等守卫。 */
    val isActive: Boolean get() = activeJob?.isActive == true

    fun start(scope: CoroutineScope) {
        this.scope = scope
        networkMonitor.start()
        // 申请到的网络被系统回收时留痕：这是 STA 断链的第一现场，
        // 心跳/健康检查的后续判定都以此为时间锚点
        scope.launch(Dispatchers.IO) {
            networkRequester.networkLost.collect { network ->
                Timber.tag(TAG).w("STA network lost: $network")
                eventLogger.event("sta_net_lost", "network" to network.toString())
            }
        }
    }

    fun stop() {
        networkMonitor.stop()
        generation++
        activeJob?.cancel()
        activeJob = null
        teardownSession()
    }

    /**
     * 归还会话级资源：解绑进程级网络、归还会话锁、释放 requestNetwork 句柄。
     * 与成功路径的持有动作严格对称，且幂等。
     */
    private fun teardownSession() {
        if (!sessionBound) return
        sessionBound = false
        networkMonitor.releaseRetained()
        networkMonitor.bindProcessTo(null)
        networkRequester.release()
        Timber.tag(TAG).i("STA session resources released")
    }

    /**
     * 发起一次 WiFi 直连。非挂起：同步取消旧循环并登记新循环，
     * 返回的 Job 可交给上层做 pairingJob 守卫。
     *
     * 回调语义（保持旧 UI 行为）：
     * - [onWaitingCameraOk]：相机端等待按 OK 时给出提示（仅 PAIRING）；
     * - [onRetry]：单次尝试失败进入退避时更新提示；
     * - [onSuccess]：连接成功（状态机 WifiConnected 由本类 dispatch）；
     * - [onFail]：全部尝试用尽（ErrorOccurred 由本类 dispatch）。
     *   参数是**机器可读的失败原因**（`no_wifi_network` / `camera_unreachable` /
     *   `ptp_handshake_failed` / `connect_failed`），由调用方翻译成给用户看的文案 ——
     *   v1.3.2 STA 反馈修复：旧版只回调空参，UI 只能笼统说"连接失败"。
     */
    fun connect(
        endpoint: WifiEndpoint,
        mode: Mode,
        onWaitingCameraOk: (() -> Unit)? = null,
        onRetry: (() -> Unit)? = null,
        onSuccess: () -> Unit = {},
        onFail: (String) -> Unit = {}
    ): Job {
        val gen = ++generation
        // RC-1: 同步作废旧循环（旧 run 会在下一个校验点因 gen 过期退出），
        // 再占住 activeJob 槽位 —— 后续任何 observeReconnectTrigger 再进入
        // 都会看到 isActive == true，不再产生第二条循环。
        activeJob?.cancel()
        activeJob = null
        // 新连接前把上一次会话的网络句柄/锁/绑网清干净，避免叠加持有
        teardownSession()

        val myScope = scope
        if (myScope == null) {
            eventLogger.event("sta_fail", "reason" to "not_started", "host" to endpoint.host)
            return Job().apply { complete() }
        }

        val job = myScope.launch(Dispatchers.IO) {
            // RC-2: 起新循环前把旧会话拆干净，杜绝两条循环互相 closeSession
            mutex.withLock { runCatching { ptpSession.closeSession() } }
            run(endpoint, gen, mode, onWaitingCameraOk, onRetry, onSuccess, onFail)
        }
        activeJob = job
        return job
    }

    /** 取消当前连接循环（用户断开/停止时调用）。 */
    fun cancelActive() {
        generation++
        activeJob?.cancel()
        activeJob = null
        teardownSession()
    }

    private suspend fun run(
        endpoint: WifiEndpoint,
        gen: Int,
        mode: Mode,
        onWaitingCameraOk: (() -> Unit)?,
        onRetry: (() -> Unit)?,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit
    ) {
        val pairing = (mode == Mode.PAIRING)
        var attempt = 0
        var lastErr: String? = null
        // RC-5: 连接生命周期内持有 WifiLock/MulticastLock，finally 保证释放
        networkMonitor.acquireLocks()
        try {
            eventLogger.event(
                "sta_try",
                "gen" to gen,
                "mode" to (if (pairing) "pair" else "resume"),
                "host" to endpoint.host,
                "port" to endpoint.port
            )

            while (attempt < MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                if (gen != generation) return                    // RC-7: 过期令牌立即退
                attempt++

                // RC-4: 每次尝试都等待并重新解析 Network，不用循环外缓存的旧句柄；
                // v1.0.2: 按相机 IP 的子网匹配选网（ZDROP 同款）——手机热点模式下
                // 相机在热点子网而非上游 WiFi 子网，"任意 WiFi 网络"会绑错路由
                // FIX-2：三条路径**并行**竞争，不再串行叠加（旧版 8s + 8s = 16.2s 才出结果）
                // 并行等待期间通过 onRetry 推进度文案，避免长时间静止无反馈
                val network = resolveNetwork(endpoint.host) { progress ->
                    onRetry?.invoke()
                    eventLogger.event("sta_progress", "gen" to gen, "hint" to progress)
                }
                if (network == null) {
                    lastErr = "no_wifi_network"
                    // v1.3.2 STA 反馈修复：手机压根没有 WiFi 网络时立刻收口。
                    // 旧版会走满 10 次退避（≈90s），用户面对"正在连接…"却永远等不到结果。
                    //
                    // FIX-1（RC-0）：判据必须用 [LocalNetworkInterfaceResolver.hasLocalWifiLikeInterface]，
                    // **不能**只看 ConnectivityManager —— 手机开热点时热点接口属 TETHERING，
                    // 不在 allNetworks 里，用 allNetworks 判断会得到"没有 WiFi"的假阴性，
                    // 于是明明开着热点、相机就连在上面，却被判 no_wifi_network 直接收口。
                    // 真机日志铁证：9 轮尝试全是 reason=no_wifi_network attempt=1。
                    if (!localInterfaces.hasLocalWifiLikeInterface()) {
                        eventLogger.event(
                            "sta_fail", "gen" to gen, "reason" to "no_wifi_network",
                            "attempt" to attempt, "host" to endpoint.host
                        )
                        stateMachine.dispatch(
                            ConnectionEvent.ErrorOccurred(
                                "手机未连接 WiFi：请先连上相机所在的同一个 WiFi / 热点后重试",
                                recoverable = true
                            )
                        )
                        onFail("no_wifi_network")
                        return
                    }
                    // 有本地接口但拿不到 Network 句柄：网络确实存在，只是系统没给我们
                    // 可直接绑定的句柄（热点场景典型）。不该判"没有 WiFi"，
                    // 退避后再试 —— 也可能相机还没入网。
                    eventLogger.event(
                        "sta_retry", "gen" to gen, "reason" to "no_network_handle",
                        "attempt" to attempt, "host" to endpoint.host
                    )
                    delay(BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)])
                    continue
                }
                // ZDROP 同款：进程级绑定到 WiFi 网络，杜绝双卡手机蜂窝默认路由
                // 抢走 PTP/IP 通道（socket 级绑定无法覆盖所有创建点）
                networkMonitor.bindProcessTo(network)

                // ── FIX-5：连接前刷新发现（对齐 ZDROP "refreshing discovery before connect"）──
                // ZDROP 在每次真正 connect 之前都会先刷一次对端可达性，原因是：
                // 相机（尤其 STA 下）会**延迟几秒才在 15740 上监听** —— 它需要先完成
                // host profile 的应用/注册（我逆向到的
                // `STA connect deferred while camera applies host profile remaining=`）。
                // 直接 connect 会在相机还没起监听时超时，白白吃掉一次尝试。
                //
                // 这里先做一次轻量 TCP 探活，给相机最多 [PRECONNECT_WAIT_MS] 的窗口；
                // 探活结果**不作为硬门禁**（避免把"休眠中但可唤醒"的相机挡掉），
                // 只用于：① 抢出几秒等待时间；② 让日志/UI 能区分"没起监听"与"握手失败"。
                if (attempt <= PRECONNECT_MAX_ROUNDS) {
                    val reachable = waitUntilReachable(network, endpoint) { hint ->
                        onRetry?.invoke()
                        eventLogger.event("sta_progress", "gen" to gen, "hint" to hint)
                    }
                    eventLogger.event(
                        "sta_preconnect", "gen" to gen, "attempt" to attempt,
                        "reachable" to reachable, "host" to endpoint.host
                    )
                }

                val ok = ptpSession.connect(
                    endpoint.host,
                    endpoint.port,
                    pairingMode = pairing,
                    network = network
                ) {
                    onWaitingCameraOk?.invoke()
                }

                if (gen != generation) return                    // RC-7: 二次校验
                if (ok) {
                    // 会话级持有：进程级绑网 + WifiLock 在整个监看/传输期间保持，
                    // 不再由本循环的 finally 解掉（见 [sessionBound] 的说明）
                    sessionBound = true
                    networkMonitor.retainLocks()
                    eventLogger.event(
                        "sta_ok",
                        "gen" to gen, "attempt" to attempt,
                        "host" to endpoint.host, "port" to endpoint.port
                    )
                    stateMachine.dispatch(ConnectionEvent.WifiConnected)
                    onSuccess()
                    return
                }

                // 连接前可达性刷新（对齐 ZDROP "refreshing discovery before connect"）：
                // 直接打一次 TCP 15740，把"相机不在这个地址/没醒"和"PTP 握手失败"分开，
                // 让用户拿到的原因是有信息量的，而不是统一的"连接失败"。
                lastErr = if (!probeReachable(network, endpoint)) {
                    "camera_unreachable"
                } else {
                    "ptp_handshake_failed"
                }
                onRetry?.invoke()
                Timber.tag(TAG).w("WiFi connect attempt $attempt failed (${endpoint.display}), backing off")
                delay(BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)])   // RC-8
            }

            eventLogger.event(
                "sta_fail",
                "gen" to gen, "attempts" to attempt,
                "err" to (lastErr ?: "unknown"),
                "host" to endpoint.host
            )
            stateMachine.dispatch(
                ConnectionEvent.ErrorOccurred(
                    describeStaFailure(lastErr, endpoint),
                    recoverable = true
                )
            )
            onFail(lastErr ?: "connect_failed")
        } catch (e: CancellationException) {
            eventLogger.event("sta_cancel", "gen" to gen, "attempt" to attempt)
            throw e
        } finally {
            // 归还"连接尝试"这一次持锁（会话持有另算，引用计数保证不误放）
            networkMonitor.releaseLocks()
            // 仅在**未连上**时恢复默认路由并释放网络申请；
            // 连上后必须保持，否则数据通道会被系统默认路由抢走（STA 断流根因）
            if (!sessionBound) {
                networkMonitor.bindProcessTo(null)
                networkRequester.release()
            }
            // 任何异常出口都保证会话被清干净，不残留半开 socket
            if (gen == generation && !ptpSession.isConnected()) {
                runCatching { ptpSession.closeSession() }
            }
        }
    }
    /**
     * FIX-2：并行解析可用的 Network 句柄。
     *
     * 旧实现是串行的 `?:` 链：
     * ```
     * networkRequester.acquire(8000) ?: networkMonitor.awaitWifiNetworkFor(8000) ?: wifiManager.bindToActiveWifi()
     * ```
     * 三条路径依次等待，最坏 8000 + 8000 = 16.2s（真机日志实测恒定值），
     * 期间用户看不到任何进度。
     *
     * 现在三条路径同时发起，**用 channel 收第一个非 null 结果**（真正的"谁快用谁"，
     * 而不是按固定顺序 await —— 否则最慢的那条仍会把整体拖满）。
     * 每 [PROGRESS_TICK_MS] 回调一次 [onProgress]，让 UI 能显示"正在准备网络…"
     * 而不是长时间静止 —— 对齐 ZDROP 的 `LocalRouteReadiness` 进度语义。
     *
     * ## 为什么用 channel 而不是 `select`
     *
     * `select { }` 在 Kotlin 协程里对 `Deferred` 的 `onAwait` 分支处理较为繁琐，
     * 且这里还要额外接进度心跳，用 [Channel] 把"首个成功结果"投递出来最直白。
     *
     * ## 关于取消
     *
     * [StaNetworkRequester.acquire] 内部的 `finally` 保证：只要它没把网络交出去
     * （`held == null`）就会释放 callback，所以被取消是安全的，不会泄漏。
     * 其它两条是无副作用的只读查询，取消无成本。
     */
    private suspend fun resolveNetwork(
        host: String,
        onProgress: ((String) -> Unit)? = null
    ): android.net.Network? {
        var resolved: android.net.Network? = null
        return coroutineScope {
            // 首个非 null 结果通过它回传（conflated：后来者无人接也无所谓）
            val winner = Channel<android.net.Network>(Channel.CONFLATED)

            val requester = async(Dispatchers.IO) {
                networkRequester.acquire(host, AWAIT_NETWORK_MS)?.let { winner.trySend(it) }
            }
            val monitor = async(Dispatchers.IO) {
                networkMonitor.awaitWifiNetworkFor(host, AWAIT_NETWORK_MS)?.let { winner.trySend(it) }
            }
            val fallback = async(Dispatchers.IO) {
                // 兜底路径：既有 AP 通道的绑网实现，通常很快返回或立即失败
                runCatching { wifiManager.bindToActiveWifi() }.getOrNull()?.let { winner.trySend(it) }
            }

            // 进度心跳：并行等待期间保持用户感知
            val ticker = launch(Dispatchers.IO) {
                var waited = 0L
                while (waited < AWAIT_NETWORK_MS) {
                    delay(PROGRESS_TICK_MS)
                    waited += PROGRESS_TICK_MS
                    onProgress?.invoke("正在准备网络（${waited / 1000}s）…")
                }
            }

            // 只等到整体上限；期间谁先给出结果就用谁
            resolved = withTimeoutOrNull(AWAIT_NETWORK_MS + 500L) {
                for (network in winner) return@withTimeoutOrNull network
                null
            }

            ticker.cancel()
            // 未被选中的分支取消掉，避免占着网络请求不做事的协程继续跑
            listOf(requester, monitor, fallback).forEach { if (it.isActive) it.cancel() }
            resolved
        }.also { network ->
            if (network != null) {
                Timber.tag(TAG).i("network resolved for host=$host: $network")
            } else {
                Timber.tag(TAG).w("network resolution failed for host=$host")
            }
        }
    }

    /**
     * FIX-5：等待相机在 [PRECONNECT_WAIT_MS] 内起 PTP/IP 监听。
     *
     * 对齐 ZDROP `refreshing discovery before connect`。相机在 STA 模式下不会立刻
     * 监听 15740 —— 它要先应用 host profile（逆向到的
     * `STA connect deferred while camera applies host profile remaining=`）。
     * 这段时间直接 connect 必然超时。
     *
     * 语义刻意做成**软等待**：
     * - 一旦探活成功立即返回 true（不浪费剩余窗口）；
     * - 窗口用完仍未探通则返回 false，但**不阻断**后续 connect ——
     *   相机可能"端口未起但稍后握手能成"，也可能探活本身被路由干扰，
     *   硬门禁会把这些情况误杀（这正是旧版"休眠相机永远连不上"的教训）。
     *
     * @return true = 窗口内已确认可达
     */
    private suspend fun waitUntilReachable(
        network: android.net.Network?,
        endpoint: WifiEndpoint,
        onProgress: ((String) -> Unit)? = null
    ): Boolean {
        val deadline = System.currentTimeMillis() + PRECONNECT_WAIT_MS
        var waited = 0L
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                PtpIpProbe.probe(
                    endpoint,
                    timeoutMs = PRECONNECT_PROBE_TIMEOUT_MS,
                    network = network
                )
            }.getOrDefault(false)
            if (ok) return true
            delay(400)
            waited += 400 + PRECONNECT_PROBE_TIMEOUT_MS
            if (waited < PRECONNECT_WAIT_MS) {
                onProgress?.invoke("正在等待相机响应（${waited / 1000}s）…")
            }
        }
        return false
    }

    // ---------------- v1.3.2 STA 诊断与反馈辅助 ----------------

    /**
     * 手机当前是否存在「类 WiFi 的本地接口」—— 含手机自身热点。
     *
     * FIX-1（RC-0）：**不要**退回成只看 `ConnectivityManager.allNetworks`。
     * 热点接口属 TETHERING，不经 ConnectivityService，在那里恒不可见；
     * 只有内核网卡枚举（[LocalNetworkInterfaceResolver]）能看到它。
     */
    private fun hasAnyWifiNetwork(): Boolean = localInterfaces.hasLocalWifiLikeInterface()

    /**
     * 连接前可达性刷新（对齐 ZDROP `refreshing discovery before connect`）。
     *
     * 直接用 2.5s 超时打一次 TCP 15740 + PTP/IP Init：能连上说明相机在线，
     * 之前失败就是握手层问题（配对码/会话占用）；连不上说明地址已失效或相机休眠，
     * 这时重试 10 次也没有意义 —— 给出"不可达"的明确原因，用户可以去唤醒相机或
     * 重新扫描。用比扫描阶段的 900ms 更长的超时，覆盖相机从休眠唤醒的时延。
     */
    private suspend fun probeReachable(network: android.net.Network?, endpoint: WifiEndpoint): Boolean =
        runCatching { PtpIpProbe.probe(endpoint, timeoutMs = 2500L, network = network) }
            .getOrDefault(false)

    /** 机器可读原因 → 用户可读文案（STA 场景的失败分类） */
    private fun describeStaFailure(reason: String?, endpoint: WifiEndpoint): String = when (reason) {
        "no_wifi_network" -> "手机未连接 WiFi：请先连上相机所在的同一个 WiFi / 热点后重试"
        "camera_unreachable" ->
            "相机不可达（${endpoint.host}）：请确认相机已开机、处于「连接智能设备」状态，" +
                "且与本机在同一网络；地址可能已变化，建议重新扫描"
        "ptp_handshake_failed" ->
            "相机在线但 PTP/IP 握手失败：请在相机上完成配对确认（按 OK），或断开后重新连接"
        "invalid_endpoint" -> "IP 地址无效，请填写形如 192.168.1.1 的 IPv4 地址"
        else -> "WiFi 相机连接失败：请确认相机与手机在同一网络后重试"
    }
}

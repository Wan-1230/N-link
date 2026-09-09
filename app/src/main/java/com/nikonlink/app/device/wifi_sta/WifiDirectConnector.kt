package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.model.ConnectionEvent
import com.nikonlink.app.device.connect.ConnectionStateMachine
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.shared.common.AppEventLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val stateMachine: ConnectionStateMachine,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "WifiDirect"
        private const val MAX_ATTEMPTS = 10
        /** RC-8: 指数退避，10 次总等待 ≈ 90s（对齐旧配对超时窗口） */
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000, 15000, 15000, 15000, 15000, 15000)
        /** RC-4: 每次尝试等待 WiFi 网络就绪的上限 */
        private const val AWAIT_NETWORK_MS = 8000L
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
     */
    fun connect(
        endpoint: WifiEndpoint,
        mode: Mode,
        onWaitingCameraOk: (() -> Unit)? = null,
        onRetry: (() -> Unit)? = null,
        onSuccess: () -> Unit = {},
        onFail: () -> Unit = {}
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
        onFail: () -> Unit
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
                // ZDROP 同款：先向系统申请一个能到达相机的 WiFi 网络并持有
                // （无 Internet 的相机网/热点网不会被系统回收），拿不到再依次
                // 回退到既有监听与 AP 通道。绑定前再按相机 IP 子网匹配一次，
                // 手机热点模式下"任意 WiFi 网络"会绑到上游 WiFi 而连不上相机。
                val network = networkRequester.acquire(endpoint.host, AWAIT_NETWORK_MS)
                    ?: networkMonitor.awaitWifiNetworkFor(endpoint.host, AWAIT_NETWORK_MS)
                    ?: wifiManager.bindToActiveWifi()
                if (network == null) {
                    lastErr = "no_wifi_network"
                    delay(BACKOFF_MS[(attempt - 1).coerceAtMost(BACKOFF_MS.size - 1)])
                    continue
                }
                // ZDROP 同款：进程级绑定到 WiFi 网络，杜绝双卡手机蜂窝默认路由
                // 抢走 PTP/IP 通道（socket 级绑定无法覆盖所有创建点）
                networkMonitor.bindProcessTo(network)

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

                lastErr = "connect_failed"
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
                ConnectionEvent.ErrorOccurred("WiFi 相机连接失败", recoverable = true)
            )
            onFail()
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
}

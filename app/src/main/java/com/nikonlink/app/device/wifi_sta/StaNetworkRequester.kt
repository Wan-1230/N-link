package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.device.connect.StaTimeoutPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * STA 网络请求器（对标 ZDROP `t1/s` 的网络申请链路）。
 *
 * ## 为什么要主动 `requestNetwork`
 *
 * STA 场景（相机与手机同时连路由器）与 AP 场景（手机连相机热点）有一个共同点：
 * **这张 WiFi 网通常没有 Internet**。Android 的"网络优选"逻辑会因为
 * `NET_CAPABILITY_INTERNET` 缺失而不把它设为默认网络，甚至在双卡机上直接把
 * 默认路由交给蜂窝网。此时：
 *
 * - 遍历 `allNetworks` 能拿到句柄，但不保证它还"活着"——系统随时可能因为没有
 *   上行流量而把它回收，表现为连上后几十秒掉线；
 * - 组播（mDNS 5353）与 PTP/IP（15740）的路由可能被蜂窝默认路由抢走。
 *
 * `ConnectivityManager.requestNetwork()` 是系统提供的"我要用这张网"的显式声明：
 * 注册期间系统会维持该网络不被回收，并通过 [NetworkCallback] 把可用/丢失事件
 * 推给我们 —— ZDROP 正是靠它把 STA 链路跑稳的。
 *
 * ## 与 [WifiNetworkMonitor] 的分工
 *
 * - [WifiNetworkMonitor]：被动监听 + 锁持有 + 进程级绑网；
 * - 本类：主动申请并**长期持有**一个网络句柄（连接会话期间不释放），
 *   丢失时通过 [networkLost] 上抛，让上层第一时间进入恢复流程而不是等心跳超时。
 *
 * 仅申请不绑网：进程级绑网由调用方决定（扫描/连接各有各的时机）。
 */
@Singleton
class StaNetworkRequester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connFlags: ConnFlags,
    private val eventLogger: com.nikonlink.app.shared.common.AppEventLogger
) {
    companion object {
        private const val TAG = "StaNetReq"
        private const val POLL_INTERVAL_MS = 150L
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /** 已申请到的网络丢失事件（extraBuffer=4，避免回调线程被慢消费者阻塞）。 */
    private val _networkLost = MutableSharedFlow<Network>(extraBufferCapacity = 4)
    val networkLost: SharedFlow<Network> = _networkLost.asSharedFlow()

    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 回调期间出现过的 WiFi 网络（含已丢失的，用于超时兜底）。 */
    private val available = LinkedHashSet<Network>()

    @Volatile
    private var held: Network? = null

    /** 当前持有的网络（未申请/已释放为 null）。 */
    fun heldNetwork(): Network? = held

    /**
     * 申请一个能到达 [host] 的 WiFi 网络。
     *
     * 选网策略（两级）：
     * 1. 快路径：已有 WiFi 网络里挑**链路地址与相机 IP 同网段**的那个
     *    （ZDROP `t1/q` 同款判定）。手机开热点时相机在热点子网、上游 WiFi 在
     *    另一个子网，按"任意 WiFi 网络"选会绑错路由，所以必须按子网匹配；
     * 2. 慢路径：向系统 `requestNetwork` 并等待回调，期间持续按子网匹配。
     *
     * 两条路径都拿不到**精确匹配**时：
     *  - FR-20 开（默认）→ 返回 null。"总比没有强"是错觉：拿到句柄就意味着上层会
     *    `bindProcessToNetwork`，把一个到不了相机的网段变成全进程的路由，
     *    失败形态从"能重试"变成"每条 socket 都秒断"。返回 null 反而更好 ——
     *    上层有默认路由回落这条正路，并且会把网段判据打进日志。
     *  - FR-20 关 → 退回 v2.3.2 行为，返回任意 WiFi 网络。
     *
     * @param host 相机 IP，用于子网匹配；为 null 时只做"任意 WiFi 网络"。
     * @return 申请到的网络；超时或（FR-20 开时）无子网匹配则返回 null。
     */
    suspend fun acquire(host: String?, timeoutMs: Long = 6000L): Network? {
        val target = host?.let { ipToInt(it) }

        // ── FIX-3 关键：快路径命中前必须先释放上一次的 callback ──────────────
        // 旧实现在这里直接 `return network`，**从不注册 callback 却把 held 设上**：
        // ① 上一轮若注册过 callback，这次不释放 → 每轮泄漏一个未注册回调；
        // ② `held` 与"是否有回调在注册中"语义不一致，release() 清不干净。
        // 真机日志铁证：netId 递增 127→129→130→131，且 network=131 一次性连发
        // 6 条 sta_net_lost —— 就是回调堆积后集中触发。
        // 现在：任何出口都先清掉旧 callback，保证「held 有值 ⇔ 语义明确」。
        releaseCallbackOnly()

        val localOnly = connFlags.isEnabled(ConnFlags.STA_LOCAL_ONLY_REQUEST)
        val diag = connFlags.isEnabled(ConnFlags.STA_LINK_DIAG)

        // ── FR-33 C1：请求去掉 NET_CAPABILITY_INTERNET（local-only）────────────────
        // 相机热点没有互联网，验证失败后系统会剥掉它的 INTERNET capability ——
        // 默认请求（含该 capability）永远匹配不上它，就**不构成持有**；
        // ConnectivityService 随后按「非默认网络且无 app 请求」回收这张网：
        // wlan0 被踢 → 相机感知客户端丢失 → 显示「无法连接」并关热点。
        // 竞品 ZDROP/影犀/ZRelay 的 dex 方法级反编译全部是 removeCapability(12)
        // （12 = NET_CAPABILITY_INTERNET，见分析文档 §8）。
        // ── FR-33 C2：注册提前到快路径判定之前 ─────────────────────────────────────
        // 旧实现 AP 模式走 findMatchingWifi 命中即 return，会话期间零 NetworkRequest
        // 在册（上面 FIX-3 的 releaseCallbackOnly 先跑、之后不再注册）——正是"零持有"
        // 的另一半。现在命中后 callback/请求保持在册直到 release()。
        // 不变式不破：held ⇔ 回调在册（超时/落选出口仍走 finally 的 releaseCallbackOnly）。
        // 匹配集合变为**超集**（含无互联网网），但子网过滤 covers() 与 FR-20 的
        // "不匹配就返回 null" 判据原样保留 —— 绑哪张网的决策语义不变，关闸即回旧行为。
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .apply {
                if (localOnly) removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .build()
        synchronized(lock) { available.clear() }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) { available.add(network) }
                Timber.tag(TAG).i("network available: $network")
                if (diag) {
                    val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }.getOrNull()
                    eventLogger.event(
                        "net_cb", "phase" to "available",
                        "validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        "internet" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    )
                }
            }

            override fun onLost(network: Network) {
                synchronized(lock) { available.remove(network) }
                Timber.tag(TAG).w("network lost: $network")
                if (held == network) held = null
                if (diag) eventLogger.event("net_cb", "phase" to "lost")
                _networkLost.tryEmit(network)
            }

            override fun onUnavailable() {
                Timber.tag(TAG).w("requestNetwork unavailable (no wifi candidate)")
                if (diag) eventLogger.event("net_cb", "phase" to "unavailable")
            }
        }

        var registered = false
        try {
            // FR-02：与 AP 侧同一手法（`requestNetwork(request, callback, timeout)`）——
            // 让系统侧也有一个截止点，别只靠我们自己的轮询兜底。
            // 截止点只管"等不到网络"这一件事；一旦 onAvailable，请求继续在册（=持有）。
            val systemDeadline = StaTimeoutPolicy.systemRequestDeadline(
                gateOn = connFlags.isEnabled(ConnFlags.STA_TIMEOUTS),
                timeoutMs = timeoutMs
            )
            if (systemDeadline != null) {
                connectivityManager.requestNetwork(request, cb, systemDeadline)
            } else {
                connectivityManager.requestNetwork(request, cb)
            }
            registered = true
        } catch (e: Exception) {
            // FIX-3：注册失败必须保证 cb 不会被"半个注册"地留在系统里。
            // requestNetwork 抛异常（如超过每 UID 100 个未释放回调的上限）时
            // 该请求并未生效，直接放弃引用即可，但不能写进 callback 字段。
            Timber.tag(TAG).w(e, "requestNetwork failed, falling back to existing wifi")
        }
        callback = if (registered) cb else null
        // FR-33 C3：诊断 —— 请求以什么 capability 在册，是本轮真机要回答的第一问。
        if (diag) {
            eventLogger.event(
                "net_req",
                "caps" to if (localOnly) "local-only" else "internet",
                "registered" to registered,
                "timeout" to timeoutMs
            )
        }

        // 快路径：已经连着的 WiFi 里找同网段的（C2 起：此时回调已在册，命中即构成持有）
        findMatchingWifi(target)?.let { network ->
            Timber.tag(TAG).i("reuse existing wifi network $network for host=$host")
            held = network
            return network
        }

        var fallback: Network? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val snapshot = synchronized(lock) { available.toList() }
                for (network in snapshot) {
                    if (covers(network, target)) {
                        Timber.tag(TAG).i("acquired wifi network $network (subnet match host=$host)")
                        held = network
                        return network
                    }
                    if (fallback == null) fallback = network
                }
                // 未注册回调（不支持/异常）时继续用快路径轮询，不必空转
                if (!registered) {
                    findMatchingWifi(target)?.let { network ->
                        held = network
                        return network
                    }
                }
                delay(POLL_INTERVAL_MS)
            }

            // FR-20：曾经这里把"看见过的任意 WiFi"当兜底返回。
            // 一旦返回，上层就会 `bindProcessToNetwork(it)` 把**整个进程**绑到一张
            // 根本到不了相机 IP 的网上 —— 而绑错网时 `network != null`，
            // 连 `in_subnet` 那条诊断都不会打（它只在 network == null 分支里）。
            // 真机日志：某台机器 6/6 次 `tcp_timeout`、一条 `sta_fallback` 都没有、
            // socket 全部 1.3s 内秒失败 —— 那就是绑错网后的 EHOSTUNREACH 形态。
            // 现在宁可返回 null，让上层走默认路由回落并**把网段判据打进日志**。
            if (fallback != null && connFlags.isEnabled(ConnFlags.BIND_SUBNET_CHECK)) {
                Timber.tag(TAG).w(
                    "saw wifi $fallback but its link addresses do not cover host=$host — 不绑，交默认路由回落"
                )
                return null
            }
            if (fallback != null) {
                Timber.tag(TAG).w("no subnet-matched wifi, fallback to $fallback (host=$host)")
                held = fallback
                return fallback
            }
        } finally {
            // FIX-3：只要本次没把网络"交出去"（返回给调用方长期持有），
            // 就必须把 callback 释放掉 —— 旧实现在超时/异常出口漏了 release()，
            // 是回调堆积的第二条路径。
            //
            // v2.6.6 注：**取消窗口不在这里修**。曾考虑"finally 里发现协程已取消就无条件释放"，
            // 但 `held = fallback; return fallback` 与取消观测之间无法区分
            // "调用方拿到了这个网络" 和 "调用方因超时把结果丢了" —— 前者若被注销 callback，
            // `networkLost` 就再也收不到，等于打断 STA 的断链检测（那是跑通的路）。
            // 正确位置在**知道自己丢了结果**的调用方：`WifiDirectConnector` 的竞争落选分支。
            if (held == null) releaseCallbackOnly()
        }
        Timber.tag(TAG).w("acquire wifi network timeout (host=$host, ${timeoutMs}ms)")
        return null
    }

    /** 释放申请到的网络（断开连接/扫描结束时调用，务必成对）。 */
    fun release() {
        releaseCallbackOnly()
        held = null
    }

    /**
     * FIX-3：只注销 callback、清空 available，**不动 [held]**。
     *
     * 拆出这个方法是因为两个场景语义不同：
     * - [release]：整段会话结束，held 与 callback 一起清；
     * - [acquire] 重入/超时出口：只清 callback，avoid 误伤刚交出去的 held。
     */
    private fun releaseCallbackOnly() {
        val cb = synchronized(lock) {
            val c = callback
            callback = null
            available.clear()
            c
        }
        cb?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
                .onFailure { e -> Timber.tag(TAG).w(e, "unregisterNetworkCallback failed") }
        }
    }

    /** 当前已有的 WiFi 网络中，链路地址与 [target] 同网段的那个。 */
    private fun findMatchingWifi(target: Int?): Network? {
        val networks = runCatching { connectivityManager.allNetworks }.getOrNull() ?: return null
        var any: Network? = null
        for (network in networks) {
            if (!isWifi(network)) continue
            if (covers(network, target)) return network
            if (any == null) any = network
        }
        return if (target == null) any else null
    }

    private fun isWifi(network: Network): Boolean {
        val caps = runCatching { connectivityManager.getNetworkCapabilities(network) }
            .getOrNull() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** [network] 的链路地址是否覆盖 [target]（同网段判定）。target 为 null 时直接通过。 */
    private fun covers(network: Network, target: Int?): Boolean {
        if (target == null) return true
        val properties = runCatching { connectivityManager.getLinkProperties(network) }
            .getOrNull() ?: return false
        return properties.linkAddresses.any { address ->
            val inet = address.address as? Inet4Address ?: return@any false
            val base = ipToInt(inet.hostAddress ?: return@any false) ?: return@any false
            val mask = prefixMask(address.prefixLength)
            (base and mask) == (target and mask)
        }
    }

    private fun prefixMask(prefixLength: Int): Int {
        if (prefixLength <= 0) return 0
        if (prefixLength >= 32) return -1
        return (0xFFFFFFFF.toInt() shl (32 - prefixLength))
    }

    private fun ipToInt(host: String): Int? {
        val parts = host.trim().split(".")
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }
}

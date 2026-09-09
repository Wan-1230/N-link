package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    @ApplicationContext private val context: Context
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
     * 两条路径都拿不到精确匹配时，退而返回任意 WiFi 网络（总比没有强，
     * 上层还有 PTP/IP Init 握手做最终确认）。
     *
     * @param host 相机 IP，用于子网匹配；为 null 时只做"任意 WiFi 网络"。
     * @return 申请到的网络；超时返回 null。
     */
    suspend fun acquire(host: String?, timeoutMs: Long = 6000L): Network? {
        val target = host?.let { ipToInt(it) }

        // 快路径：已经连着的 WiFi 里找同网段的
        findMatchingWifi(target)?.let { network ->
            Timber.tag(TAG).i("reuse existing wifi network $network for host=$host")
            held = network
            return network
        }

        synchronized(lock) { available.clear() }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(lock) { available.add(network) }
                Timber.tag(TAG).i("network available: $network")
            }

            override fun onLost(network: Network) {
                synchronized(lock) { available.remove(network) }
                Timber.tag(TAG).w("network lost: $network")
                if (held == network) held = null
                _networkLost.tryEmit(network)
            }

            override fun onUnavailable() {
                Timber.tag(TAG).w("requestNetwork unavailable (no wifi candidate)")
            }
        }

        var registered = false
        try {
            connectivityManager.requestNetwork(request, cb)
            registered = true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "requestNetwork failed, falling back to existing wifi")
        }
        callback = if (registered) cb else null

        val deadline = System.currentTimeMillis() + timeoutMs
        var fallback: Network? = null
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

        if (fallback != null) {
            Timber.tag(TAG).w("no subnet-matched wifi, fallback to $fallback (host=$host)")
            held = fallback
            return fallback
        }
        Timber.tag(TAG).w("acquire wifi network timeout (host=$host, ${timeoutMs}ms)")
        return null
    }

    /** 释放申请到的网络（断开连接/扫描结束时调用，务必成对）。 */
    fun release() {
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
        held = null
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

package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 网络监听 + 锁持有（RC-4 / RC-5）。
 *
 * ## 解决的根因
 *
 * - **RC-4 stale Network**：旧实现循环外一次性取 `Network` 句柄，WiFi 断连重连后
 *   句柄已失效仍复用（日志 L97 `network_lost` → L98 `network_restored` → 5 次
 *   `ok=false`）。[awaitWifiNetwork] 让每次连接尝试都拿到**当前**就绪的网络，
 *   网络未就绪就等待而不是拿 null 硬连。
 * - **RC-5 无 WifiLock**：全项目此前零 `WifiLock`（三方 APK 全部持有），息屏后
 *   系统 2m22s 就回收 WiFi。[acquireLocks] 在连接生命周期内持有
 *   `WIFI_MODE_FULL_HIGH_PERF`（三方共识），断开即释放，`release()` 由调用方
 *   放在 finally。
 *
 * `AndroidManifest` 已声明 `CHANGE_WIFI_STATE` + `WAKE_LOCK`，无需改清单。
 */
@Singleton
class WifiNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val TAG = "WifiNetMon"
        private const val POLL_INTERVAL_MS = 200L
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var callbackRegistered = false

    /** WifiLock / MulticastLock 持有引用计数（0 表示未持有）。 */
    private var lockRefs = 0

    private val _currentNetwork = MutableStateFlow<Network?>(null)
    val currentNetwork: StateFlow<Network?> = _currentNetwork.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _currentNetwork.value = network
            Timber.tag(TAG).i("WiFi network available: $network")
        }

        override fun onLost(network: Network) {
            if (_currentNetwork.value == network) {
                _currentNetwork.value = null
                Timber.tag(TAG).w("WiFi network lost: $network")
            }
        }
    }

    /** 注册 WiFi 网络回调（幂等）。 */
    fun start() {
        if (callbackRegistered) return
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            callbackRegistered = true
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to register WiFi network callback")
        }
    }

    fun stop() {
        if (callbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            callbackRegistered = false
        }
        // 停止是终态：忽略引用计数强制归还，避免会话异常退出后锁残留
        synchronized(this) { lockRefs = 0 }
        doReleaseLocks()
        _currentNetwork.value = null
    }

    /**
     * 把整个进程绑定到 [network]（null = 恢复系统默认路由）。
     *
     * ZDROP 的 STA 链路即采用进程级绑定（bindProcessToNetwork）：相机 AP/STA 网络
     * 无 Internet 能力时，部分机型的默认路由仍指向蜂窝网，仅靠逐 socket bindSocket
     * 覆盖不全（mDNS、NSD、探测 socket、三方库自建的 socket 都可能漏绑）。
     * 进程级绑定一次性解决所有 socket 的路由问题；连接/扫描结束务必传 null 恢复，
     * 否则应用流量会一直走无 Internet 的相机网络。
     */
    fun bindProcessTo(network: Network?): Boolean = runCatching {
        connectivityManager.bindProcessToNetwork(network)
    }.onFailure {
        Timber.tag(TAG).w(it, "bindProcessToNetwork failed (network=$network)")
    }.getOrDefault(false)

    /**
     * 等待 WiFi 网络就绪并返回当前 [Network]；超时返回 null。
     * RC-4：调用方每次连接尝试都应重新调用，绝不缓存旧句柄。
     */
    suspend fun awaitWifiNetwork(timeoutMs: Long): Network? {
        _currentNetwork.value?.let { return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            _currentNetwork.value?.let { return it }
            // StateFlow 可能在注册前网络就已就绪，兜底直查一次
            val direct = queryCurrentWifiNetwork()
            if (direct != null) {
                _currentNetwork.value = direct
                return direct
            }
        }
        return null
    }

    /**
     * 等待一个**子网覆盖 [host]** 的 WiFi 网络并返回（ZDROP 同款路由匹配）。
     *
     * 双卡手机/开热点场景下 `awaitWifiNetwork` 返回的"任意 WiFi 网络"可能不是
     * 通向相机的那张网（如手机自己开热点时，相机在热点子网而非上游 WiFi 子网），
     * 逐 socket 绑定也救不了路由。这里按 ZDROP t1.q 的做法：遍历网络的
     * LinkProperties，选"链路地址与相机 IP 同网段"的那个网络。
     * 找不到精确匹配时回退任意 WiFi 网络；超时返回 null。
     */
    suspend fun awaitWifiNetworkFor(host: String, timeoutMs: Long): Network? {
        val target = hostToInt(host) ?: return awaitWifiNetwork(timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var anyWifi: Network? = null
        while (System.currentTimeMillis() < deadline) {
            for (network in connectivityManager.allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) continue
                val properties = connectivityManager.getLinkProperties(network) ?: continue
                val coversHost = properties.linkAddresses.any { address ->
                    val inet = address.address as? java.net.Inet4Address ?: return@any false
                    val base = inetToInt(inet) ?: return@any false
                    val mask = if (address.prefixLength <= 0) 0
                    else (0xFFFFFFFFL shl (32 - address.prefixLength)).toInt()
                    (base and mask) == (target and mask)
                }
                if (coversHost) return network
                if (anyWifi == null) anyWifi = network
            }
            _currentNetwork.value?.let { if (anyWifi == null) anyWifi = it }
            if (anyWifi != null && System.currentTimeMillis() + 1000 >= deadline) break
            delay(POLL_INTERVAL_MS)
        }
        return anyWifi
    }

    private fun hostToInt(host: String): Int? {
        val parts = host.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it < 0 || it > 255 }) return null
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun inetToInt(inet: java.net.Inet4Address): Int? {
        val b = inet.address
        if (b.size != 4) return null
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    /**
     * 连接生命周期内持有 WifiLock + MulticastLock，支持**会话级重复持有**。
     *
     * RC-5：`WIFI_MODE_FULL_HIGH_PERF` 是 ZRelay/影犀/ZDROP 三方共识；
     * v1.1.0：改为引用计数。连接成功后由 [retainLocks] 追加一次持有，
     * 连接循环的 finally 里 [releaseLocks] 只减不释放 —— 否则会话还活着但锁
     * 已经归还，息屏 2 分钟后系统照样回收 WiFi（三方 APK 都是会话级持锁）。
     */
    @Synchronized
    fun acquireLocks() {
        acquireLocksInternal()
    }

    @Synchronized
    private fun acquireLocksInternal() {
        lockRefs++
        if (lockRefs > 1) return
        runCatching {
            if (wifiLock == null) {
                @Suppress("DEPRECATION")
                val lock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "N-LinkStaConnect"
                )
                lock.setReferenceCounted(false)
                lock.acquire()
                wifiLock = lock
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to acquire WifiLock") }

        runCatching {
            if (multicastLock == null) {
                val lock = wifiManager.createMulticastLock("N-LinkStaConnect")
                lock.setReferenceCounted(false)
                lock.acquire()
                multicastLock = lock
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to acquire MulticastLock") }
    }

    /** 断开时释放（调用方放 finally，绝不残留）。引用计数归零才真正归还。 */
    @Synchronized
    fun releaseLocks() {
        if (lockRefs == 0) return
        lockRefs--
        if (lockRefs > 0) {
            Timber.tag(TAG).d("locks still held (refs=$lockRefs), session in progress")
            return
        }
        doReleaseLocks()
    }

    /**
     * 会话级持锁：连接成功后调用，保证整个监看/传输过程中 WiFi 不被系统回收。
     * 语义与 [acquireLocks] 完全一致（引用计数 +1），单独命名只是为了让
     * 调用点表达"这是会话级持有，不是连接尝试级"的意图。
     */
    @Synchronized
    fun retainLocks() {
        acquireLocksInternal()
    }

    /** 归还 [retainLocks] 追加的那一次持有。 */
    @Synchronized
    fun releaseRetained() {
        releaseLocks()
    }

    @Synchronized
    private fun doReleaseLocks() {
        runCatching { wifiLock?.release() }
        wifiLock = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun queryCurrentWifiNetwork(): Network? {
        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}

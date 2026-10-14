package com.camerakit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import javax.net.SocketFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 相机网络绑定（基准实现 实证语义，网络链路层三件套）：
 * 1. `requestNetwork(TRANSPORT_WIFI, removeCapability(INTERNET))` 请求相机网络；
 * 2. **`bindProcessToNetwork(network)` 把进程钉在相机网络上**——这是防 Android 因
 *    「该 Wi-Fi 无互联网」在会话中途拆网/切蜂窝的关键（真机日志实证：事件通道被
 *    Software caused connection abort、恢复时 Binding socket EPERM/ENONET，
 *    全部是系统拆网所致；基准实现正是靠 bindProcessToNetwork 避免）；
 * 3. `network.socketFactory` 建 socket，流量强制走相机网络。
 * release 时先 `bindProcessToNetwork(null)` 解绑，避免进程被钉在死网络上。
 */
class NetworkBinder(
    context: Context,
    /** 相机 IP：requestNetwork 超时后按相机子网绑定本机网卡兜底（防双网手机错网口/EPERM）。 */
    private val targetHost: String? = null,
    private val log: (String) -> Unit = {},
) {
    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var currentNetwork: Network? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var processBound = false

    /**
     * 返回绑定相机网络的 SocketFactory；
     * 超时未发现独立相机网络时回退默认工厂（同网段/USB 场景）。
     */
    suspend fun cameraSocketFactory(timeoutMs: Long = 5_000): SocketFactory {
        val factory = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                // 匹配任意 Wi-Fi 网络（含无 internet 的相机 AP 与有 internet 的热点/路由器）。
                // 注意不能 removeCapability(INTERNET)：相机 AP 可能具备 internet 能力（实测日志
                // transportWifi=true validated=true internet=true），带该过滤会匹配不到而漂到蜂窝网络。
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) {
                            currentNetwork = network
                            // 核心（基准实现）：进程钉在相机网络上，阻止系统因
                            // 「相机 AP 无互联网」在会话中拆网/切蜂窝
                            processBound = runCatching {
                                cm.bindProcessToNetwork(network)
                            }.isSuccess
                            log("已绑定相机网络 netId=${network.networkHandle} processBound=$processBound")
                            cont.resume(network.socketFactory)
                        }
                    }

                    override fun onLosing(network: Network, maxMsToLive: Int) {
                        log("相机网络即将断开 netId=${network.networkHandle} maxMsToLive=${maxMsToLive}ms")
                    }

                    override fun onLost(network: Network) {
                        if (network == currentNetwork) {
                            currentNetwork = null
                            log("相机网络已断开 netId=${network.networkHandle}（系统拆网/相机关闭 Wi-Fi）")
                        }
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(subnetBoundFactory() ?: SocketFactory.getDefault())
                }
                cont.invokeOnCancellation { release() }
            }
        }
        return factory ?: run {
            // 兜底：按相机 IP 子网绑定本机网卡建链，避免默认路由从错误网卡发出
            // （真机日志实证：双网手机上默认路由会把连接从错误网卡发出、黑洞丢弃）
            val bound = subnetBoundFactory()
            if (bound != null) {
                log("未检测到独立相机网络（${timeoutMs}ms），改用相机子网网卡绑定建链")
                bound
            } else {
                log("未检测到独立相机网络（${timeoutMs}ms），使用默认网络")
                SocketFactory.getDefault()
            }
        }
    }

    /** 与相机同子网的本机地址（前缀取接口实测值，取不到按 /24 估计）；找不到返回 null。 */
    private fun findCameraSubnetLocal(): java.net.Inet4Address? {
        val targetHost = targetHost ?: return null
        val target = runCatching { java.net.InetAddress.getByName(targetHost) }.getOrNull()
            as? java.net.Inet4Address ?: return null
        val t = ipv4ToInt(target) ?: return null
        val self = runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { nif ->
                if (!nif.isUp || nif.isLoopback) return@flatMap emptyList()
                java.util.Collections.list(nif.inetAddresses)
                    .mapNotNull { it as? java.net.Inet4Address }
                    .filter { !(it.hostAddress ?: "").startsWith("169.254.") }
            }
        }.getOrDefault(emptyList())
        return self.firstOrNull { local ->
            val localInt = ipv4ToInt(local) ?: return@firstOrNull false
            val prefix = runCatching { nifPrefixLength(local) ?: 24 }.getOrDefault(24)
            val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
            (localInt and mask) == (t and mask)
        }
    }

    private fun nifPrefixLength(local: java.net.InetAddress): Int? {
        for (nif in java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            for (la in nif.interfaceAddresses ?: emptyList()) {
                if (la.address == local) return la.networkPrefixLength.toInt()
            }
        }
        return null
    }

    private fun ipv4ToInt(addr: java.net.InetAddress): Int? {
        val bytes = addr.address.takeIf { it.size == 4 } ?: return null
        return bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    }

    /** 绑定到相机子网网卡的 SocketFactory；找不到同子网网卡返回 null。 */
    private fun subnetBoundFactory(): SocketFactory? {
        val local = findCameraSubnetLocal() ?: return null
        return object : SocketFactory() {
            override fun createSocket(): java.net.Socket =
                java.net.Socket().apply { bind(java.net.InetSocketAddress(local, 0)) }

            override fun createSocket(host: String?, port: Int): java.net.Socket =
                createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

            override fun createSocket(
                host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int
            ): java.net.Socket = createSocket().apply {
                connect(java.net.InetSocketAddress(host, port))
            }

            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket =
                createSocket().apply { connect(java.net.InetSocketAddress(host, port)) }

            override fun createSocket(
                address: java.net.InetAddress?, port: Int,
                localAddress: java.net.InetAddress?, localPort: Int
            ): java.net.Socket = createSocket().apply {
                connect(java.net.InetSocketAddress(address, port))
            }
        }
    }

    fun release() {
        // 先解绑进程再注销回调（基准实现语义）：避免进程被钉在已死的相机网络上
        if (processBound) {
            processBound = false
            runCatching { cm.bindProcessToNetwork(null) }
        }
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        currentNetwork = null
    }
}

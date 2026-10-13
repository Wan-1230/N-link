package com.camerakit.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import javax.net.SocketFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 相机网络绑定（基准实现 实证：`requestNetwork(TRANSPORT_WIFI, removeCapability(INTERNET))`）。
 *
 * 相机热点通常没有 internet 能力，必须显式 removeCapability 才能请求到；
 * 拿到 Network 后用 `network.socketFactory` 建 socket，确保 PTP/IP 流量走相机所在网络。
 */
class NetworkBinder(
    context: Context,
    private val targetHost: String? = null,
    private val log: (String) -> Unit = {},
) {
    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile private var currentNetwork: Network? = null
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

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
                        // 子网匹配（基准实现语义）：只接受「相机 IP 所在子网」的网络。
                        // 双网手机上别的 Wi-Fi 也可能满足请求，绑错网口会被相机复位
                        if (targetHost != null &&
                            !matchesSubnet(targetHost, cm.getLinkProperties(network))
                        ) {
                            log("网络候选忽略（与相机 $targetHost 不同子网）netId=${network.networkHandle}")
                            return
                        }
                        if (cont.isActive) {
                            currentNetwork = network
                            log("已绑定相机网络 netId=${network.networkHandle}")
                            cont.resume(network.socketFactory)
                        }
                    }
                }
                callback = cb
                try {
                    cm.requestNetwork(request, cb)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(SocketFactory.getDefault())
                }
                cont.invokeOnCancellation { release() }
            }
        }
        return factory ?: run {
            log("未检测到独立相机网络（${timeoutMs}ms），使用默认网络")
            SocketFactory.getDefault()
        }
    }

    fun release() {
        callback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        currentNetwork = null
    }

    /** 相机 IP 与网络任一 IPv4 地址同子网（按前缀掩码比对，前缀钳制 0-32；解析不了放行）。 */
    private fun matchesSubnet(targetHost: String, lp: LinkProperties?): Boolean {
        if (lp == null) return false
        val target = runCatching { java.net.InetAddress.getByName(targetHost) }.getOrNull()
            as? java.net.Inet4Address ?: return true
        val t = ipv4ToInt(target) ?: return true
        for (la in lp.linkAddresses) {
            val addr = la.address as? java.net.Inet4Address ?: continue
            val prefix = la.prefixLength.coerceIn(0, 32)
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            val a = ipv4ToInt(addr) ?: continue
            if ((a and mask) == (t and mask)) return true
        }
        return false
    }

    private fun ipv4ToInt(addr: java.net.Inet4Address): Int? =
        addr.address.takeIf { it.size == 4 }
            ?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
}

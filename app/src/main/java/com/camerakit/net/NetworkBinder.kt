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
 * 相机网络绑定（基准实现 实证：`requestNetwork(TRANSPORT_WIFI, removeCapability(INTERNET))`）。
 *
 * 相机热点通常没有 internet 能力，必须显式 removeCapability 才能请求到；
 * 拿到 Network 后用 `network.socketFactory` 建 socket，确保 PTP/IP 流量走相机所在网络。
 */
class NetworkBinder(
    context: Context,
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
}

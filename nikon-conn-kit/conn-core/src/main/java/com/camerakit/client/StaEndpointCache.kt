package com.camerakit.client

import android.content.Context
import com.camerakit.api.CameraEndpoint
import com.camerakit.ptp.PtpConsts

/**
 * STA 端点与注册态持久缓存（基准实现语义）：
 * 记住上次连过的相机地址与「本机是否已注册为主机」——
 * 重连走缓存直连的快速路径；已注册时不再自动重做注册（入口分流）。
 */
internal class StaEndpointCache(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("camerakit_sta_cache", Context.MODE_PRIVATE)

    fun endpoint(): CameraEndpoint? {
        val host = prefs.getString("host", null) ?: return null
        val port = prefs.getInt("port", PtpConsts.DEFAULT_PTP_IP_PORT)
        return CameraEndpoint("Nikon camera", host, port, "cached")
    }

    fun saveEndpoint(ep: CameraEndpoint) {
        prefs.edit().putString("host", ep.host).putInt("port", ep.port).apply()
    }

    /** 注册成功后置 true（仅「移除保存的连接」才应回置 false）。 */
    fun isRegistered(): Boolean = prefs.getBoolean("registered", false)

    fun markRegistered(ep: CameraEndpoint) {
        prefs.edit()
            .putString("host", ep.host)
            .putInt("port", ep.port)
            .putBoolean("registered", true)
            .apply()
    }

    /**
     * 缓存地址有效性校验（基准实现 k() 语义）：须为可路由 IPv4（非 link-local）
     * 且与某本机网卡同子网，否则视为缓存失效——换了网络后旧地址直接作废。
     */
    fun isRoutableTo(host: String): Boolean {
        val target = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
            as? java.net.Inet4Address ?: return false
        val targetText = target.hostAddress ?: return false
        if (targetText.startsWith("169.254.")) return false
        val t = ipv4ToInt(target) ?: return false
        val self = runCatching {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { nif ->
                if (!nif.isUp || nif.isLoopback) return@flatMap emptyList()
                java.util.Collections.list(nif.inetAddresses)
                    .mapNotNull { it as? java.net.Inet4Address }
                    .filter { !(it.hostAddress ?: "").startsWith("169.254.") }
            }
        }.getOrDefault(emptyList())
        return self.any { local ->
            val localInt = ipv4ToInt(local) ?: return@any false
            val prefix = runCatching {
                nifPrefixLength(local) ?: guessPrefix(local)
            }.getOrDefault(guessPrefix(local))
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

    /** 取不到前缀长度时按 /24 保守估计（家庭路由 STA 场景的常态）。 */
    private fun guessPrefix(local: java.net.InetAddress): Int = 24

    private fun ipv4ToInt(addr: java.net.InetAddress): Int? {
        val bytes = addr.address.takeIf { it.size == 4 } ?: return null
        return bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
    }
}

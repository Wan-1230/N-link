package com.camerakit.discovery

import com.camerakit.api.CameraEndpoint
import com.camerakit.ptp.PtpConsts
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Wi-Fi AP 直连端点解析：相机热点网关固定 `192.168.1.1:15740`（基准实现 / 官方手册互证）。
 */
object ApEndpointResolver {

    fun defaultEndpoint(
        host: String = PtpConsts.DEFAULT_AP_HOST,
        port: Int = PtpConsts.DEFAULT_PTP_IP_PORT,
    ): CameraEndpoint = CameraEndpoint("Nikon Z Camera (AP)", host, port, "ap")

    /**
     * 路由就绪门（基准实现「AP route ready」策略）：
     * 轮询等待本地接口出现到相机网段的直连路由。禁止用一次性 TCP 探测打扰相机
     * （探测连接会干扰相机 PTP 服务，实测导致 ECONNREFUSED/复位）。
     */
    suspend fun awaitLocalRoute(host: String, totalTimeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (true) {
            if (isOnLocalSubnet(host)) return true
            if (System.currentTimeMillis() > deadline) return false
            kotlinx.coroutines.delay(500)
        }
    }

    /** 目标是否在本机某接口的直连子网内（如手机热点 192.168.1.x）。 */
    fun isOnLocalSubnet(host: String): Boolean {
        val target = toIntOrNull(host) ?: return false
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .any { addr ->
                    val mask = addrToSubnetMask(addr) ?: return@any false
                    (toIntOrNull(addr.hostAddress ?: "") ?: return@any false) and mask ==
                        target and mask
                }
        } catch (_: Exception) {
            false
        }
    }

    private fun addrToSubnetMask(addr: Inet4Address): Int? {
        // Android 不直接暴露前缀长度；按常见热点/路由网段做保守判断（/24 与 /16）
        val ip = toIntOrNull(addr.hostAddress ?: "") ?: return null
        return if ((ip ushr 24) == 192) 0xFFFFFF00.toInt() else 0xFFFF0000.toInt()
    }

    private fun toIntOrNull(dotted: String): Int? {
        val parts = dotted.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }
}

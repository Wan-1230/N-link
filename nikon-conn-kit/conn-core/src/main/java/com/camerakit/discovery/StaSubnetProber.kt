package com.camerakit.discovery

import com.camerakit.api.CameraEndpoint
import com.camerakit.ptp.PtpConsts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * STA 网段探测发现（mDNS 之外的兜底通道）。
 *
 * 真机实证：部分网络 / ROM 组合下 mDNS 组播收不到（服务发现启动正常却零命中），
 * 而相机的 PTP/IP 端口一定在线。这里对本机各 IPv4 所在 /24 网段并行探测
 * TCP 15740，命中即产出候选端点（service="probe"）。
 *
 * 只做「TCP 能否连上」这一件事，不发任何 PTP 报文；仅在 mDNS 无结果时兜底，
 * 不改变连接流程本身。
 */
class StaSubnetProber(
    private val log: (String) -> Unit = {},
) {
    private companion object {
        /** 并行探测上限：同时开的探测 socket 数，避免低端机瞬时 fd 压力 */
        const val CONCURRENCY = 48

        /** 单地址 TCP 探测超时：同一局域网内连得上就是亚百毫秒级，连不上 300ms 足够 */
        const val PROBE_TIMEOUT_MS = 300
    }

    /**
     * 在本机各 /24 网段内找第一个开放 PTP/IP 端口的地址。
     * 整体耗时约 ceil(254/48)×300ms ≈ 2s/网卡，命中即返回。
     */
    suspend fun findFirst(timeoutMs: Long = 5_000): CameraEndpoint? = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val self = selfIpv4()
        val targets = subnetTargets(self)
        if (targets.isEmpty()) {
            log("[发现] 网段探测：未取到本机 IPv4 网段，跳过")
            return@withContext null
        }
        log("[发现] 网段探测开始：${targets.size} 个地址（本机 ${self.joinToString()} 所在 /24）")
        val semaphore = Semaphore(CONCURRENCY)
        val hits = java.util.concurrent.ConcurrentLinkedQueue<CameraEndpoint>()
        val deadline = System.currentTimeMillis() + timeoutMs
        coroutineScope {
            targets.map { host ->
                async(Dispatchers.IO) {
                    if (hits.isNotEmpty() || System.currentTimeMillis() > deadline) return@async
                    semaphore.withPermit {
                        if (hits.isNotEmpty() || System.currentTimeMillis() > deadline) return@withPermit
                        if (probeTcp(host)) {
                            val ep = CameraEndpoint(
                                "Nikon camera", host, PtpConsts.DEFAULT_PTP_IP_PORT, "probe",
                            )
                            hits.add(ep)
                            log("[发现] 网段探测命中 $host:${PtpConsts.DEFAULT_PTP_IP_PORT}（${System.currentTimeMillis() - t0}ms）")
                        }
                    }
                }
            }.awaitAll()
        }
        val hit = hits.firstOrNull()
        if (hit == null) {
            log("[发现] 网段探测无命中（${System.currentTimeMillis() - t0}ms）")
        }
        hit
    }

    /** 只做 TCP 连接即断，不发 PTP 报文（探测不是会话）。 */
    private fun probeTcp(host: String): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, PtpConsts.DEFAULT_PTP_IP_PORT), PROBE_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    /** 单地址探测（缓存地址快速验证用）。 */
    suspend fun probeHost(host: String): Boolean = withContext(Dispatchers.IO) { probeTcp(host) }

    private fun selfIpv4(): List<String> = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { nif ->
            if (!nif.isUp || nif.isLoopback) return@flatMap emptyList()
            java.util.Collections.list(nif.inetAddresses)
                .mapNotNull { it as? java.net.Inet4Address }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() && !it.startsWith("169.254.") }
        }
    }.getOrDefault(emptyList())

    /** 各本机 IPv4 所在 /24 的全部主机地址（去掉网络/广播/本机自身）。 */
    private fun subnetTargets(self: List<String>): List<String> {
        val targets = LinkedHashSet<String>()
        self.forEach { ip ->
            val prefix = ip.substringBeforeLast('.')
            for (host in 1..254) {
                val candidate = "$prefix.$host"
                if (candidate != ip) targets.add(candidate)
            }
        }
        return targets.toList()
    }
}

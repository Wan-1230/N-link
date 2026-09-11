package com.nikonlink.app.device.ptp

import android.net.Network
import com.nikonlink.app.device.wifi.WifiEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * 轻量 PTP/IP Init 探测。
 *
 * STA 发现流程：仅 TCP 端口可连不代表相机已准备好，必须发送
 * InitCommand 并收到 InitResponse，才能把该 IP 判定为可用的尼康相机。
 *
 * v1.3.2（STA 修复）：探测结果**分级**而不是布尔值。原来只有 true/false，
 * 于是"TCP 连上了但相机休眠没回 PTP 握手"与"这个 IP 根本没人"被当成同一件事，
 * 整段扫描把它们一起丢掉 —— 这是 STA 搜不到相机的一个直接原因。
 * 现在把前者判为 [ProbeResult.TCP_OPEN_NO_PTP]（大概率就是相机，只是需要唤醒）。
 */
object PtpIpProbe {
    private const val TAG = "PtpIpProbe"

    /** 探测结论 */
    enum class ProbeResult {
        /** TCP 连通且 PTP/IP Init 握手成功 —— 确定是可用相机 */
        OK,

        /** TCP 端口开放但没等到 PTP 握手应答（相机休眠/忙/非 PTP 服务占用该端口） */
        TCP_OPEN_NO_PTP,

        /** 端口明确拒绝（有主机但没开 15740） */
        REFUSED,

        /** 连接超时 / 不可达（无主机或不在本网段） */
        TIMEOUT,

        /** 其它异常 */
        ERROR;

        /** 是否值得作为候选展示给用户 */
        val isCandidate: Boolean get() = this == OK || this == TCP_OPEN_NO_PTP
    }

    /** WifiEndpoint 便捷重载：地址已归一化，直接探测。 */
    suspend fun probe(
        endpoint: WifiEndpoint,
        timeoutMs: Long = 1200L,
        network: Network? = null
    ): Boolean = probeDetailed(endpoint.host, endpoint.port, timeoutMs, network) == ProbeResult.OK

    suspend fun probe(
        host: String,
        port: Int = PtpConstants.DEFAULT_PORT,
        timeoutMs: Long = 1200L,
        network: Network? = null
    ): Boolean = probeDetailed(host, port, timeoutMs, network) == ProbeResult.OK

    /**
     * 带结论的探测。
     *
     * 超时上限从旧版的 1500ms 放宽到 [MAX_TIMEOUT_MS]（6s）：尼康机身从 WiFi 休眠
     * 唤醒并完成 PTP 握手可能耗时数秒，旧上限导致"相机明明在同一网段却永远探不到"。
     * 网段盲扫仍用短超时（几百 ms）以控制总耗时，只有**确认候选**时才用长超时。
     */
    suspend fun probeDetailed(
        host: String,
        port: Int = PtpConstants.DEFAULT_PORT,
        timeoutMs: Long = 1200L,
        network: Network? = null
    ): ProbeResult = withContext(Dispatchers.IO) {
        val effective = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS).toInt()
        try {
            val socket = if (network != null) network.socketFactory.createSocket() else Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), effective)
                it.soTimeout = effective
                it.tcpNoDelay = true

                val output = it.getOutputStream()
                output.write(InitCommandPacket(clientName = "N-LinkProbe").toBytes())
                output.flush()

                val response = runCatching { PtpPacket.fromStream(it.getInputStream()) }.getOrNull()
                if (response is InitResponsePacket) ProbeResult.OK else ProbeResult.TCP_OPEN_NO_PTP
            }
        } catch (e: SocketTimeoutException) {
            // 连接阶段超时 → 不可达；握手阶段超时 → TCP 已通，属"端口开放但没回应"
            Timber.tag(TAG).v("PTP probe timeout for $host:$port: ${e.message}")
            ProbeResult.TIMEOUT
        } catch (e: PortUnreachableException) {
            ProbeResult.REFUSED
        } catch (e: IOException) {
            val msg = e.message?.lowercase().orEmpty()
            when {
                msg.contains("refused") -> ProbeResult.REFUSED
                msg.contains("route") || msg.contains("unreachable") -> ProbeResult.TIMEOUT
                msg.contains("timed out") || msg.contains("timeout") -> ProbeResult.TIMEOUT
                else -> ProbeResult.ERROR
            }
        } catch (e: NoRouteToHostException) {
            ProbeResult.TIMEOUT
        } catch (e: Exception) {
            Timber.tag(TAG).v("PTP init probe failed for $host:$port: ${e.message}")
            ProbeResult.ERROR
        }
    }

    private const val MIN_TIMEOUT_MS = 300L

    /**
     * 探测超时上限（v1.3.2 从 1.5s 放宽）。
     * 网段盲扫请显式传短超时；确认候选 / 连接前可达性检查才用长超时。
     */
    private const val MAX_TIMEOUT_MS = 6000L
}

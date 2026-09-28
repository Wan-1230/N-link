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

        /**
         * 路由不可达（ENETUNREACH / EHOSTUNREACH / NoRouteToHost）。
         *
         * 与 [TIMEOUT] 的区别有实际处置意义：TIMEOUT 是"包发出去了没人回"（相机休眠、
         * 被 AP 隔离、黑洞路由），NO_ROUTE 是"本机根本没有通往这个地址的路"
         * —— 手机压根不在相机那个网段上。后者重试一万次也不会好，
         * 该提示的是"换网/重新扫描"而不是"唤醒相机"。
         * PRD v2.6 FR-18b 新增；它不是 `ProbeResult` 老码值的一部分，
         * 只做进程内判据，不进 [com.nikonlink.app.device.connect.ConnFunnel.Reason] 那种
         * 对外稳定契约，所以加档不违反"只增不改"。
         */
        NO_ROUTE,

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

    /**
     * TCP-only 筛探的**结论**（PRD v2.6 FR-18b）。
     *
     * 旧的 `tcpConnectOnly` 返回 Boolean，于是"端口被拒（相机在、没起监听）"
     * "超时（黑洞）""路由不可达（根本不在同一个网段）"这三件完全不同的事
     * 在日志里都是同一句 `reachable=false` —— 用户日志排查时根本分不出该关 VPN
     * 还是该唤醒相机。本类型把档位连同原始异常身份一起带出来。
     *
     * @param open 端口是否连通（等价旧布尔值）
     * @param grade 档位：[ProbeResult.OK] / [ProbeResult.REFUSED] / [ProbeResult.TIMEOUT] /
     *              [ProbeResult.NO_ROUTE] / [ProbeResult.ERROR]
     * @param errno 异常类名 + message，供日志核对；探通时为 null
     */
    data class TcpScreen(val open: Boolean, val grade: ProbeResult, val errno: String?)

    /**
     * **只做 TCP 连接，不发 PTP/IP 握手**（PRD v2.2 §5.2 T-S3）。
     *
     * 网段盲扫期间对每个地址发 InitCommand 有两个代价：① 每个候选多花一个握手超时；
     * ② 尼康机身同时只允许一个 PTP/IP 客户端，握手会挤占/污染配对槽，反而让真正的
     * 目标连不上。因此扫描阶段用本函数筛出「端口开着的活主机」，只对最终候选做
     * [probeDetailed]。
     */
    suspend fun tcpConnectOnly(
        host: String,
        port: Int = PtpConstants.DEFAULT_PORT,
        timeoutMs: Long = 500L,
        network: Network? = null
    ): Boolean = tcpScreen(host, port, timeoutMs, network).open

    /**
     * [tcpConnectOnly] 的带档位版本（FR-18b）：连接流程要用它，
     * 这样 `sta_preconnect` / `ap_gateway` 才说得出**为什么**没探通。
     * 网段盲扫那种"只要知道开没开"的场合继续用布尔版本，别为了统一而把调用点搞复杂。
     */
    suspend fun tcpScreen(
        host: String,
        port: Int = PtpConstants.DEFAULT_PORT,
        timeoutMs: Long = 500L,
        network: Network? = null
    ): TcpScreen = withContext(Dispatchers.IO) {
        val effective = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS).toInt()
        try {
            val socket = if (network != null) network.socketFactory.createSocket() else Socket()
            socket.use {
                it.connect(InetSocketAddress(host, port), effective)
                TcpScreen(true, ProbeResult.OK, null)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).v("TCP-only screen miss for %s:%d: %s", host, port, e.message)
            TcpScreen(false, gradeOf(e), "${e.javaClass.simpleName}:${e.message}")
        }
    }

    /**
     * 异常 → 档位。抽成独立函数只为可测（FR-18b 的判据全在这一坨映射里，
     * 而它跑在真 socket 的 catch 分支里，没法用普通单测触发）。
     *
     * 消息里的 errno 字面量是 libsocket 抛上来的 `ErrnoException.getMessage()`，
     * Android 各版本措辞不一，所以两边都匹配。
     */
    internal fun gradeOf(e: Throwable): ProbeResult {
        val msg = e.message?.lowercase().orEmpty()
        return when {
            e is SocketTimeoutException -> ProbeResult.TIMEOUT
            e is PortUnreachableException -> ProbeResult.REFUSED
            e is NoRouteToHostException -> ProbeResult.NO_ROUTE
            msg.contains("refused") || msg.contains("econnrefused") -> ProbeResult.REFUSED
            msg.contains("unreachable") || msg.contains("route") ||
                msg.contains("enetrout") || msg.contains("ehostunreach") -> ProbeResult.NO_ROUTE
            msg.contains("timed out") || msg.contains("timeout") -> ProbeResult.TIMEOUT
            else -> ProbeResult.ERROR
        }
    }

    private const val MIN_TIMEOUT_MS = 300L

    /**
     * 探测超时上限（v1.3.2 从 1.5s 放宽）。
     * 网段盲扫请显式传短超时；确认候选 / 连接前可达性检查才用长超时。
     */
    private const val MAX_TIMEOUT_MS = 6000L
}

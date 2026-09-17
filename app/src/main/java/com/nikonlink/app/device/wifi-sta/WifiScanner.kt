package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nikonlink.app.device.ptp.PtpIpProbe
import com.nikonlink.app.device.wifi.WifiEndpoint
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 相机发现器
 *
 * 尼康相机的无线连接流程（实测确认）：
 * - STA 模式：相机与手机连接同一 WiFi，相机通过 UDP 5353 广播配置文件名称
 * - AP 模式：相机开启热点，手机连接热点后同样通过 UDP 5353 广播
 * 发现候选后通过 TCP 15740 探测 PTP/IP 端口，确认相机可连接。
 */
@Singleton
class WifiScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkRequester: StaNetworkRequester,
    private val localInterfaces: LocalNetworkInterfaceResolver
) {
    companion object {
        private const val TAG = "WifiScanner"
        private const val MDNS_PORT = 5353
        private const val PTP_PORT = 15740
        private const val MDNS_ADDRESS = "224.0.0.251"

        /**
         * B4（P1）：总超时 12s → 18s。
         * STA 失败的常见原因是路由器过滤组播 / 客户端隔离，多等 6 秒给
         * mDNS 重发与网段扫描更多机会；扫描全程在 IO 协程，不阻塞 UI。
         */
        private const val DEFAULT_SCAN_TIMEOUT_MS = 18_000L

        /** B4（P1）：mDNS 单次读超时 1s → 2.5s，配合周期重发 probe */
        private const val MDNS_SO_TIMEOUT_MS = 2_500L

        /** B4（P1）：每隔多久重发一次 mDNS probe（原来只在开头发一次） */
        private const val MDNS_PROBE_INTERVAL_MS = 4_000L
        private const val MAX_SCAN_CONCURRENCY = 32
        private const val GENERIC_NAME = "尼康相机"

        /**
         * v1.3.2：开始扫描前等待「WiFi 拿到 IPv4」的上限。
         *
         * 旧实现在拿不到本机 IPv4 时 `scanSubnet` 直接 return —— 网段扫描整条路径消失，
         * 而且没有任何提示。用户刚连上 WiFi（DHCP 还没完成）就点扫描，就会出现
         * "完全搜不到相机"。这里先等链路就绪，等不到也要给出明确原因。
         */
        private const val ROUTE_READY_WAIT_MS = 6_000L

        /** 网段盲扫的单主机探测超时（要控制 254 个地址的总耗时） */
        private const val SWEEP_PROBE_TIMEOUT_MS = 800L

        /** 候选确认 / 第二轮补扫的探测超时（覆盖相机从休眠唤醒的时延） */
        private const val CONFIRM_PROBE_TIMEOUT_MS = 2_500L

        /** RC-6：系统 NSD 服务类型（与自绘 mDNS 并行兜底） */
        private const val NSD_SERVICE_TYPE_PTP = "_ptp._tcp."
        private const val NSD_SERVICE_TYPE_NIKON = "_nikon._tcp."
    }

    /** B4：最近一次扫描的统计（失败原因可见化的数据源，UI 据此生成诊断文案） */
    data class ScanStats(
        /** 全部 TCP 15740 探测次数（网段 + ARP 表） */
        val probedHosts: Int,
        /** ARP 表里读到的条目数 */
        val arpEntries: Int,
        /** mDNS 收到的响应包数（0 = 组播大概率被过滤） */
        val mdnsResponses: Int,
        /** NSD 发现的服务数 */
        val nsdResponses: Int,
        val elapsedMs: Long,
        // ---------- v1.3.2：STA 诊断扩展（"为什么搜不到"要可回答） ----------
        /** WiFi 链路是否就绪：有 WiFi 网络且已拿到 IPv4 链路地址 */
        val wifiReady: Boolean = false,
        /** 本机在 WiFi 上的 IPv4/前缀，多网段用「, 」连接（如 192.168.1.23/24） */
        val localSubnets: String = "",
        /** 计划探测的主机数（网段 + 常见网关 + 热点段） */
        val plannedHosts: Int = 0,
        /** mDNS 查询包实际发送次数（0 = 连探针都没发出去） */
        val mdnsProbesSent: Int = 0,
        /**
         * RC-5：成功加入 mDNS 组播组的网卡数。
         * 0 = 本机没有任何网卡能收组播（热点场景下旧版必然如此并静默跳过整段 mDNS）。
         */
        val mdnsInterfacesJoined: Int = 0,
        /**
         * RC-0：内核网卡枚举出的本机网段（含热点接口），与 [localSubnets] 对照即可
         * 看出「ConnectivityManager 看不见热点」这一事实。
         */
        val kernelSubnets: String = "",
        /** RC-0：是否判定为热点拓扑（无 WiFi 客户端网络 + 有内核可见 IPv4 接口） */
        val hotspotTopology: Boolean = false,
        /** mDNS 路径异常（null = 正常） */
        val mdnsError: String? = null,
        /** NSD 路径异常（null = 正常） */
        val nsdError: String? = null,
        /** /proc/net/arp 是否可读（Android 10+ 常被系统 SELinux 限制） */
        val arpReadable: Boolean = false,
        /** 探测结论分布：PTP 握手成功 / 仅 TCP 通（相机休眠中）/ 超时 */
        val probeOk: Int = 0,
        val probeTcpOpenNoPtp: Int = 0,
        val probeTimeout: Int = 0,
        /** 提前收口的原因（null = 正常走完全部路径） */
        val gateReason: String? = null,
        /** 已连上的 WiFi 名称（若可读） */
        val ssid: String = ""
    )

    /** B4：每次 scan() 结束写入；UI 在候选为空时读取生成「为什么没找到」的提示 */
    private val _lastScanStats = kotlinx.coroutines.flow.MutableStateFlow<ScanStats?>(null)
    val lastScanStats: kotlinx.coroutines.flow.StateFlow<ScanStats?> = _lastScanStats

    /** v1.3.2 扫描过程计数器（诊断用，线程安全） */
    private class Diag {
        /** 本轮计划探测的主机数（诊断用） */
        val plannedHosts = java.util.concurrent.atomic.AtomicInteger(0)
        val probeOk = java.util.concurrent.atomic.AtomicInteger(0)
        val probeTcpOpen = java.util.concurrent.atomic.AtomicInteger(0)
        val probeTimeout = java.util.concurrent.atomic.AtomicInteger(0)
        val mdnsProbes = java.util.concurrent.atomic.AtomicInteger(0)
        /** RC-5：成功加入 mDNS 组播组的网卡数（0 = 组播收不到任何东西） */
        val mdnsInterfacesJoined = java.util.concurrent.atomic.AtomicInteger(0)
        /** RC-0：内核可见的本机网段（含热点接口），用于诊断"热点为什么看不见" */
        val kernelSubnets = java.util.concurrent.atomic.AtomicReference<String>("")
        val arpReadable = java.util.concurrent.atomic.AtomicBoolean(false)
        val mdnsError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val nsdError = java.util.concurrent.atomic.AtomicReference<String?>(null)
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    /**
     * 同时监听 mDNS 广播并扫描当前网段的 15740 端口。
     */
    suspend fun scan(
        timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS,
        network: Network? = null,
        hotspotMode: Boolean = false
    ): List<WifiCameraCandidate> {
        val results = ConcurrentHashMap.newKeySet<WifiCameraCandidate>()
        // 扫描期间进程级绑定到 WiFi 网络。双卡手机默认路由在蜂窝时，
        // 组播/NSD/网段探测 socket 的路由都可能被抢，仅靠逐 socket 绑定不可靠。
        // 调用方没给网络（STA 首次发现）时，先向系统申请一张 WiFi 网再扫，
        // 否则 mDNS 组播会发到蜂窝网口上，永远等不到相机响应。
        val monitor = runCatching {
            context.getSystemService(android.net.ConnectivityManager::class.java)
        }.getOrNull()
        val ownedNetwork = network == null
        val target = network ?: runCatching {
            networkRequester.acquire(null, 3000L)
        }.getOrNull()
        if (target != null) {
            runCatching { monitor?.bindProcessToNetwork(target) }
        }
        try {
            withContext(Dispatchers.IO) {
                // B4：探测/响应计数，供失败诊断文案使用
                val probed = java.util.concurrent.atomic.AtomicInteger(0)
                val mdnsSeen = java.util.concurrent.atomic.AtomicInteger(0)
                val nsdSeen = java.util.concurrent.atomic.AtomicInteger(0)
                val arpEntries = java.util.concurrent.atomic.AtomicInteger(0)
                val diag = Diag()
                val startAt = System.currentTimeMillis()

                // ── v1.3.2 路由就绪门控 ──────────────────────────────────────────
                // 拿不到本机 IPv4 时网段扫描整条路径会静默失效（旧版直接 return），
                // 用户看到的就是"完全搜不到相机"。这里最多等 ROUTE_READY_WAIT_MS，
                // 等不到也要把原因写进诊断（gateReason），让 UI 能说清"为什么"。
                var subnets = currentIpv4Addresses()
                if (subnets.isEmpty() && !hotspotMode) {
                    val routeDeadline = System.currentTimeMillis() + ROUTE_READY_WAIT_MS
                    while (System.currentTimeMillis() < routeDeadline && subnets.isEmpty()) {
                        delay(300)
                        subnets = currentIpv4Addresses()
                    }
                }

                // ── RC-0 修复：用内核网卡枚举补全网段（含手机自身热点接口）─────────
                // ConnectivityManager 看不到 Tethering 的热点接口，导致手机开热点时
                // subnets 为空 → 网段扫描只剩过时的 192.168.43.x 硬编码；
                // 同时上层还会误判 no_wifi_network 直接收口。
                // 这里改用 NetworkInterface.getNetworkInterfaces()把
                // 热点网段补回来，`subnets` 从此在热点场景下也是真实可用的。
                val kernelSubnets = runCatching { localInterfaces.subnets() }.getOrDefault(emptyList())
                diag.kernelSubnets.set(
                    kernelSubnets.joinToString(", ") { it.first + "/" + it.second }
                )
                if (kernelSubnets.isNotEmpty()) {
                    // 内核枚举到的网段优先级更高（含热点），且能覆盖 CM 未就绪的情况
                    val merged = LinkedHashSet<Pair<String, Int>>()
                    merged.addAll(subnets)
                    merged.addAll(kernelSubnets)
                    subnets = merged.toList()
                }

                val hotspotTopology = runCatching {
                    hotspotMode || localInterfaces.isHotspotTopology()
                }.getOrDefault(hotspotMode)
                val wifiReady = subnets.isNotEmpty()
                val gateReason = when {
                    wifiReady -> null                 // 有网段就能扫（无论它来自 CM 还是内核）
                    hotspotMode -> null
                    hotspotTopology -> null           // 热点拓扑：网卡可见即放行，不再误判
                    target == null -> "no_wifi_network"
                    else -> "wifi_no_ipv4"
                }
                val subnetText = subnets.joinToString(", ") { it.first + "/" + it.second }
                val ssid = runCatching { wifiManager.connectionInfo?.ssid?.trim('"') }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                    ?: ""

                if (gateReason == null) {
                    // 网络一律用 target（调用方给的，或本次主动申请到的），
                    // 不能再用可能为 null 的 network —— 否则 socket 会落到蜂窝网口
                    val mdnsJob = async { collectMdns(timeoutMs, results, target, mdnsSeen, diag) }
                    val subnetJob = async {
                        scanSubnet(timeoutMs, results, target, hotspotMode, probed, subnets, diag)
                    }
                    val nsdJob = async { collectNsd(timeoutMs, results, target, nsdSeen, diag) }
                    val arpJob = async {
                        collectArp(timeoutMs, results, target, probed, arpEntries, diag)
                    }
                    awaitAll(mdnsJob, subnetJob, nsdJob, arpJob)

                    // ── v1.3.2 第二轮补扫────────────────
                    // 第一轮用 800ms 短超时压缩总耗时；若一条路径都没出候选，
                    // 用 2.5s 长超时对网段再扫一遍 —— 覆盖"相机刚从休眠唤醒"的场景，
                    // 这时第一轮的短超时必然全部落空。
                    if (results.isEmpty() && subnets.isNotEmpty()) {
                        Timber.tag(TAG).i("No candidate in wave-1, running wave-2 (longer timeout)")
                        delay(600)
                        collectSlowSubnet(results, target, subnets, probed, diag)
                    }
                } else {
                    Timber.tag(TAG).w("Scan gated before discovery: " + gateReason)
                }

                _lastScanStats.value = ScanStats(
                    probedHosts = probed.get(),
                    arpEntries = arpEntries.get(),
                    mdnsResponses = mdnsSeen.get(),
                    nsdResponses = nsdSeen.get(),
                    elapsedMs = System.currentTimeMillis() - startAt,
                    wifiReady = wifiReady,
                    localSubnets = subnetText,
                    plannedHosts = diag.plannedHosts.get(),
                    mdnsProbesSent = diag.mdnsProbes.get(),
                    mdnsInterfacesJoined = diag.mdnsInterfacesJoined.get(),
                    kernelSubnets = diag.kernelSubnets.get(),
                    hotspotTopology = hotspotTopology,
                    mdnsError = diag.mdnsError.get(),
                    nsdError = diag.nsdError.get(),
                    arpReadable = diag.arpReadable.get(),
                    probeOk = diag.probeOk.get(),
                    probeTcpOpenNoPtp = diag.probeTcpOpen.get(),
                    probeTimeout = diag.probeTimeout.get(),
                    gateReason = gateReason,
                    ssid = ssid
                )
                val st = _lastScanStats.value!!
                Timber.tag(TAG).i(
                    "Scan done in " + st.elapsedMs + "ms: wifiReady=" + wifiReady +
                        " subnets=[" + subnetText + "] kernel=[" + st.kernelSubnets + "]" +
                        " hotspot=" + st.hotspotTopology + " ssid='" + ssid + "'" +
                        " gate=" + (gateReason ?: "-") + " planned=" + diag.plannedHosts.get() +
                        " probed=" + probed.get() + " arp=" + arpEntries.get() +
                        " readable=" + st.arpReadable + " mdns=" + mdnsSeen.get() +
                        " probesSent=" + st.mdnsProbesSent +
                        " mdnsIfaces=" + st.mdnsInterfacesJoined + " nsd=" + nsdSeen.get() +
                        " probeOk=" + st.probeOk + " tcpOnly=" + st.probeTcpOpenNoPtp +
                        " timeout=" + st.probeTimeout + " candidates=" + results.size
                )
            }
        } finally {
            // 恢复默认路由，避免应用流量滞留在无 Internet 的相机网络
            if (target != null) runCatching { monitor?.bindProcessToNetwork(null) }
            if (ownedNetwork) networkRequester.release()
        }
        // 任务2: 同一台相机会产生多个同名/异名条目（mDNS+网段扫描），按 IP 去重，
        // 优先保留相机自定义名称条目，隐藏通用占位名称的重复项
        return results
            .groupBy { it.ipAddress }
            .map { (_, cands) ->
                cands.firstOrNull { it.name != GENERIC_NAME } ?: cands.first()
            }
            .sortedBy { it.name }
    }

    private suspend fun collectMdns(
        timeoutMs: Long,
        results: MutableSet<WifiCameraCandidate>,
        network: Network?,
        mdnsSeen: java.util.concurrent.atomic.AtomicInteger,
        diag: Diag
    ) {
        val multicastLock = runCatching {
            wifiManager.createMulticastLock("N-LinkWifiScan")
        }.getOrNull()
        multicastLock?.setReferenceCounted(false)
        multicastLock?.acquire()

        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(MDNS_PORT).apply {
                reuseAddress = true
                soTimeout = MDNS_SO_TIMEOUT_MS.toInt()
            }
            // STA 模式下必须显式把组播 Socket 绑定到 WiFi 网络，
            // 否则默认路由可能被蜂窝网抢走，导致收不到相机的 mDNS 广播。
            network?.bindSocket(socket)
            // ── RC-5：绝不能用被废弃的单参 joinGroup ──────────────────────────
            // `MulticastSocket.joinGroup(InetAddress)` 会把组播组加入**所有**网卡，
            // 只要有一张网卡不支持组播就直接抛：
            //   ErrnoException: setsockopt failed: ENODEV (No such device)
            // 手机开热点时热点接口正是这种「无网关/不支持组播」的网卡，
            // 于是整段 mDNS 发现被跳过（旧版这行还没包 runCatching，异常直接
            // 冒泡到外层 catch）—— 这是"热点下搜不到相机"的独立丢失路径。
            // 参见 jmdns/jmdns#120 "JmDNS will crash when in Android AP mode"。
            // 修法：逐个网卡、用两参重载显式指定 interface；单张网卡失败不中断。
            val joined = joinMulticastOnAllInterfaces(socket)
            diag.mdnsInterfacesJoined.set(joined)
            if (joined == 0) {
                diag.mdnsError.compareAndSet(null, "no_multicast_capable_interface")
                Timber.tag(TAG).w("mDNS: no interface accepted the multicast group, skipping")
                return
            }
            sendMdnsProbe(socket)
            diag.mdnsProbes.incrementAndGet()

            val deadline = System.currentTimeMillis() + timeoutMs
            var lastProbeAt = System.currentTimeMillis()
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                // B4（P1）：周期重发 probe——路由器对 mDNS 查询的丢弃并不罕见，
                // 原来只发一次，丢了就一直干等到超时；也能覆盖「扫描开始后才入网」
                // 的相机（相机上电到开始广播有数秒延迟）。
                if (System.currentTimeMillis() - lastProbeAt >= MDNS_PROBE_INTERVAL_MS) {
                    runCatching { sendMdnsProbe(socket) }
                    diag.mdnsProbes.incrementAndGet()
                    lastProbeAt = System.currentTimeMillis()
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    mdnsSeen.incrementAndGet()
                    val sourceIp = packet.address?.hostAddress ?: continue
                    if (sourceIp.isLoopbackOrMulticast()) continue
                    val candidate = parseMdnsResponse(
                        buffer.copyOf(packet.length),
                        sourceIp
                    ) ?: continue
                    // v1.3.2：mDNS 主动播报 _ptp._tcp 的主机已高度可信，
                    // 确认超时放宽到 2.5s，并接受「TCP 通但 PTP 握手超时」
                    // （相机休眠中）—— 旧版只认握手成功，休眠相机直接被丢弃。
                    val verdict = PtpIpProbe.probeDetailed(
                        candidate.ip, candidate.port, CONFIRM_PROBE_TIMEOUT_MS, network
                    )
                    when (verdict) {
                        PtpIpProbe.ProbeResult.OK -> diag.probeOk.incrementAndGet()
                        PtpIpProbe.ProbeResult.TCP_OPEN_NO_PTP ->
                            diag.probeTcpOpen.incrementAndGet()
                        PtpIpProbe.ProbeResult.TIMEOUT -> diag.probeTimeout.incrementAndGet()
                        else -> {}
                    }
                    if (!verdict.isCandidate) continue
                    results.add(
                        WifiCameraCandidate(
                            candidate.ip,
                            candidate.port,
                            candidate.name ?: "尼康相机",
                            "WiFi"
                        )
                    )
                    Timber.tag(TAG).i("mDNS candidate: ${candidate.name} @ ${candidate.ip}:${candidate.port}")
                } catch (_: SocketTimeoutException) {
                    // 继续监听直到超时
                }
            }
        } catch (e: Exception) {
            diag.mdnsError.set(e.javaClass.simpleName + ": " + (e.message ?: ""))
            Timber.tag(TAG).w(e, "mDNS listener failed, subnet scan will still run")
        } finally {
            runCatching {
                // RC-5：必须与加入时同形（两参 + 指定 interface）。
                // 单参 leaveGroup 同样会遍历所有网卡并在热点接口上抛 ENODEV。
                val group = InetAddress.getByName(MDNS_ADDRESS)
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    runCatching {
                        socket?.leaveGroup(
                            InetSocketAddress(group, MDNS_PORT),
                            nif
                        )
                    }
                }
            }
            runCatching { socket?.close() }
            multicastLock?.release()
        }
    }

    /**
     * RC-5：逐个网卡把 [socket] 加入 mDNS 组播组，返回成功加入的网卡数。
     *
     * 只用**两参重载** `joinGroup(SocketAddress, NetworkInterface)`：
     * 单参版本会遍历全部网卡，任一网卡不支持组播就抛 `ENODEV` 并让整段发现失效
     * （热点接口必踩）。两参版本把失败局限在单张网卡内，其它网卡照常工作。
     *
     * @return 成功加入的网卡数量；0 表示本机没有任何网卡能收组播。
     */
    private fun joinMulticastOnAllInterfaces(socket: MulticastSocket): Int {
        val group = runCatching { InetAddress.getByName(MDNS_ADDRESS) }.getOrNull() ?: return 0
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return 0
        var joined = 0
        for (nif in interfaces) {
            val usable = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
            if (!usable) continue
            val ok = runCatching {
                socket.joinGroup(InetSocketAddress(group, MDNS_PORT), nif)
            }.isSuccess
            if (ok) {
                joined++
            } else {
                // 单张网卡失败是常态（热点/蜂窝/VPN 网卡多数不支持组播），
                // 不值得把整段发现判死，留痕即可。
                Timber.tag(TAG).d("mDNS: interface ${nif.name} rejected multicast group")
            }
        }
        return joined
    }

    private suspend fun scanSubnet(
        timeoutMs: Long,
        results: MutableSet<WifiCameraCandidate>,
        network: Network?,
        hotspotMode: Boolean = false,
        probed: java.util.concurrent.atomic.AtomicInteger,
        networks: List<Pair<String, Int>>,
        diag: Diag
    ) {
        if (networks.isEmpty()) {
            // v1.3.2：不再静默返回——网段缺失已在 scan() 的门控里等过并有 gateReason，
            // 这里只记录一次，避免"整条路径无声消失"。
            Timber.tag(TAG).w("scanSubnet skipped: no local IPv4 subnet")
            return
        }

        val subnetHosts = networks.flatMap { (ip, prefix) ->
            subnetHosts(ip, prefix).filterNot { it == ip }
        }.filter { WifiEndpoint.isProbeCandidate(it) }.distinct()
        // 相机 AP/STA 常见网关 IP，即使当前网段不同也做一次轻量探测。
        val knownGatewayHosts = listOf(
            "192.168.1.1", "192.168.0.1", "10.0.0.1", "192.168.42.1",
            "192.168.31.1", "192.168.2.1", "192.168.10.1", "192.168.50.1",
            "192.168.137.1", "172.16.0.1", "10.0.1.1"
        )
        // 热点模式：真正的主机列表已由调用方通过 `networks` 传入的本机网段覆盖
        // （RC-0 修复后，热点网段由 LocalNetworkInterfaceResolver 从内核网卡枚举得到，
        //  不再依赖下面这个兜底列表）。这里只保留最常见的 `192.168.43.x` 作为
        //  最后一道保险：部分老机型/老 ROM 仍固定使用该网段。
        val hotspotHosts = if (hotspotMode) {
            (1 until 255).map { "192.168.43.$it" }
        } else {
            emptyList()
        }
        val hosts = (subnetHosts + knownGatewayHosts + hotspotHosts)
            .filter { WifiEndpoint.isProbeCandidate(it) }.distinct()
        if (hosts.isEmpty()) return
        diag.plannedHosts.addAndGet(hosts.size)

        val semaphore = Semaphore(MAX_SCAN_CONCURRENCY)
        val deadline = System.currentTimeMillis() + timeoutMs
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (System.currentTimeMillis() >= deadline) return@async
                        probed.incrementAndGet()
                        when (PtpIpProbe.probeDetailed(host, PTP_PORT, SWEEP_PROBE_TIMEOUT_MS, network)) {
                            PtpIpProbe.ProbeResult.OK -> {
                                diag.probeOk.incrementAndGet()
                                results.add(WifiCameraCandidate(host, PTP_PORT, "尼康相机", "WiFi"))
                                Timber.tag(TAG).i("Port scan found camera at " + host)
                            }
                            PtpIpProbe.ProbeResult.TCP_OPEN_NO_PTP -> {
                                // 端口开放但没回 PTP 握手：大概率是休眠中的相机。
                                // 旧版直接丢弃，这是"相机在同一网段却搜不到"的直接原因之一。
                                diag.probeTcpOpen.incrementAndGet()
                                results.add(
                                    WifiCameraCandidate(host, PTP_PORT, "尼康相机(待唤醒)", "WiFi")
                                )
                                Timber.tag(TAG).i("Port open (no PTP yet) at " + host)
                            }
                            PtpIpProbe.ProbeResult.TIMEOUT -> diag.probeTimeout.incrementAndGet()
                            else -> {}
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * v1.3.2 第二轮补扫：第一轮（[SWEEP_PROBE_TIMEOUT_MS]）一条候选都没出时，
     * 用 [CONFIRM_PROBE_TIMEOUT_MS] 长超时对整个网段再扫一遍。
     *
     * 依据：尼康机身会进入 WiFi 休眠，从唤醒到完成 PTP 握手可能耗时 2~3 秒，
     * 第一轮的 800ms 短超时对它必然全部落空；而"TCP 通但没回握手"的地址
     * 在这一轮按"待唤醒"候选收下。
     * 总耗时可控：只在第一轮颗粒无收时才执行。
     */
    private suspend fun collectSlowSubnet(
        results: MutableSet<WifiCameraCandidate>,
        network: Network?,
        networks: List<Pair<String, Int>>,
        probed: java.util.concurrent.atomic.AtomicInteger,
        diag: Diag
    ) {
        val hosts = networks.flatMap { (ip, prefix) ->
            subnetHosts(ip, prefix).filterNot { it == ip }
        }.filter { WifiEndpoint.isProbeCandidate(it) }.distinct()
        if (hosts.isEmpty()) return
        diag.plannedHosts.addAndGet(hosts.size)
        val semaphore = Semaphore(MAX_SCAN_CONCURRENCY)
        val deadline = System.currentTimeMillis() + DEFAULT_SCAN_TIMEOUT_MS
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (System.currentTimeMillis() >= deadline) return@async
                        probed.incrementAndGet()
                        when (PtpIpProbe.probeDetailed(host, PTP_PORT, CONFIRM_PROBE_TIMEOUT_MS, network)) {
                            PtpIpProbe.ProbeResult.OK -> {
                                diag.probeOk.incrementAndGet()
                                results.add(WifiCameraCandidate(host, PTP_PORT, "尼康相机", "WiFi"))
                                Timber.tag(TAG).i("wave-2 found camera at " + host)
                            }
                            PtpIpProbe.ProbeResult.TCP_OPEN_NO_PTP -> {
                                diag.probeTcpOpen.incrementAndGet()
                                results.add(
                                    WifiCameraCandidate(host, PTP_PORT, "尼康相机(待唤醒)", "WiFi")
                                )
                            }
                            PtpIpProbe.ProbeResult.TIMEOUT -> diag.probeTimeout.incrementAndGet()
                            else -> {}
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * RC-6：NsdManager 系统服务发现，作为裸 mDNS Socket 的并行兜底。
     * 部分机型/路由器会过滤组播包，自绘 mDNS 收不到相机广播；系统 NSD 走
     * daemon 通道可以绕开这类过滤。发现结果仍需通过 PTP/IP Init Ack 确认。
     */
    private suspend fun collectNsd(
        timeoutMs: Long,
        results: MutableSet<WifiCameraCandidate>,
        network: Network?,
        nsdSeen: java.util.concurrent.atomic.AtomicInteger,
        diag: Diag
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Timber.tag(TAG).w("NsdManager unavailable, NSD discovery skipped")
            return
        }

        // "host:port" -> mDNS 服务名（ 直接拿 serviceName 当相机名展示，
        // 比统一显示"尼康相机"更容易在列表里认出目标机身）
        val discovered = ConcurrentHashMap<String, String>()
        val done = CompletableDeferred<Unit>()

        // v1.0.2 修复：NsdManager 同一时刻只允许一个 resolve 在途，旧版对两个服务
        // 类型的发现结果并发 resolveService，部分 resolve 静默失败 → 同一WiFi下
        // "搜索不到相机" 的主因之一。改为单飞队列串行 resolve。
        val resolveQueue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
        fun tryResolveNext() {
            if (!resolving.compareAndSet(false, true)) return
            val info = resolveQueue.poll() ?: run {
                resolving.set(false)
                return
            }
            runCatching {
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(p1: NsdServiceInfo?, errorCode: Int) {
                        resolving.set(false)
                        tryResolveNext()
                    }

                    override fun onServiceResolved(p1: NsdServiceInfo?) {
                        val host = p1?.host?.hostAddress
                        val port = p1?.port ?: 0
                        if (!host.isNullOrEmpty() && port > 0) {
                            // 值取服务名
                            discovered["$host:$port"] = cleanServiceName(p1.serviceName)
                            nsdSeen.incrementAndGet()
                        }
                        resolving.set(false)
                        tryResolveNext()
                    }
                })
            }.onFailure {
                resolving.set(false)
            }
        }

        fun discoveryListener() = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diag.nsdError.compareAndSet(null, "start_failed(" + errorCode + ") on " + serviceType)
                Timber.tag(TAG).w("NSD discovery start failed: $serviceType err=$errorCode")
                done.complete(Unit)
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveQueue.add(serviceInfo)
                tryResolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        val listeners = listOf(NSD_SERVICE_TYPE_PTP, NSD_SERVICE_TYPE_NIKON)
            .map { type -> type to discoveryListener() }
        try {
            listeners.forEach { (type, listener) ->
                runCatching {
                    nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            }
            // NSD 发现无天然结束信号，靠超时收口
            withTimeoutOrNull(timeoutMs) { done.await() }
        } finally {
            listeners.forEach { (_, listener) ->
                runCatching { nsdManager.stopServiceDiscovery(listener) }
            }
        }

        coroutineScope {
            discovered.map { (entry, serviceName) ->
                async(Dispatchers.IO) {
                    val endpoint = WifiEndpoint.parse("wifi:$entry")
                    if (endpoint != null &&
                        results.none { it.ipAddress == endpoint.host } &&
                        PtpIpProbe.probe(endpoint, timeoutMs = 1200L, network = network)
                    ) {
                        val name = serviceName.ifBlank { GENERIC_NAME }
                        results.add(
                            WifiCameraCandidate(endpoint.host, endpoint.port, name, "WiFi-nsd")
                        )
                        Timber.tag(TAG).i("NSD candidate: ${endpoint.host}:${endpoint.port} name=$name")
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * mDNS 服务名清洗：去掉尾部的 `._tcp` / `.local` 与空标签。
     * 尼康机身广播的服务名形如 `Z6III_12345678` / `Nikon Z Camera`，
     * 直接拿来当展示名即可。
     */
    private fun cleanServiceName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .removeSuffix("._tcp")
            .removeSuffix(".")
            .trim()
    }

    /**
     * ARP 表候选（v1.0.2 新增）：手机热点模式下本机热点接口多数不可枚举，
     * 无法确定真实网段做全段扫描；相机入网后必然出现在 ARP 表里，直接读表探测。
     * 同一WiFi 模式下也能兜底覆盖"路由器过滤组播导致 mDNS 全挂"的场景。
     */
    private suspend fun collectArp(
        timeoutMs: Long,
        results: MutableSet<WifiCameraCandidate>,
        network: Network?,
        probed: java.util.concurrent.atomic.AtomicInteger,
        arpEntries: java.util.concurrent.atomic.AtomicInteger,
        diag: Diag
    ) {
        // 给 ARP 表一点学习时间（相机刚入网时表里可能还没有它）
        delay(1500)
        val hosts = readArpHosts()
        diag.arpReadable.set(hosts != null)
        arpEntries.set(hosts?.size ?: 0)
        if (hosts.isNullOrEmpty()) return
        val semaphore = Semaphore(16)
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (results.any { it.ipAddress == host }) return@async
                        probed.incrementAndGet()
                        when (PtpIpProbe.probeDetailed(host, PTP_PORT, SWEEP_PROBE_TIMEOUT_MS, network)) {
                            PtpIpProbe.ProbeResult.OK -> {
                                diag.probeOk.incrementAndGet()
                                results.add(
                                    WifiCameraCandidate(host, PTP_PORT, GENERIC_NAME, "WiFi-arp")
                                )
                                Timber.tag(TAG).i("ARP candidate: " + host)
                            }
                            PtpIpProbe.ProbeResult.TCP_OPEN_NO_PTP -> {
                                diag.probeTcpOpen.incrementAndGet()
                                results.add(
                                    WifiCameraCandidate(host, PTP_PORT, "尼康相机(待唤醒)", "WiFi-arp")
                                )
                            }
                            PtpIpProbe.ProbeResult.TIMEOUT -> diag.probeTimeout.incrementAndGet()
                            else -> {}
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * 读 /proc/net/arp 中"已解析完成"(flags 含 0x2) 的 IPv4 条目。
     *
     * @return null = 文件不可读（Android 10+ 起 /proc/net/arp 对普通应用受限，
     *   SELinux 常直接拒绝），空列表 = 可读但表里没有有效条目。
     *   v1.3.2 起把这两种情况区分开，诊断里不再把"读不到"伪装成"表里是空的"。
     */
    private fun readArpHosts(): List<String>? {
        return runCatching {
            java.io.File("/proc/net/arp").readLines()
                .drop(1)
                .mapNotNull { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 6) return@mapNotNull null
                    val ip = cols[0]
                    val flags = cols[2].toIntOrNull(16) ?: return@mapNotNull null
                    if (flags and 0x2 == 0) return@mapNotNull null
                    if (!WifiEndpoint.isProbeCandidate(ip)) return@mapNotNull null
                    ip
                }
                .filterNot { it.startsWith("127.") }
                .distinct()
        }.getOrNull()
    }

    private fun parseMdnsResponse(data: ByteArray, sourceIp: String): MdnsCandidate? {
        if (data.size < 12) return null
        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN)
        val questionCount = buffer.short.toInt() and 0xFFFF
        val answerCount = buffer.short.toInt() and 0xFFFF
        buffer.short // NSCOUNT
        buffer.short // ARCOUNT

        var offset = 12
        repeat(questionCount) {
            val nameResult = readDnsName(data, offset) ?: return null
            offset = nameResult.second
            if (offset + 4 > data.size) return null
            offset += 4
        }

        var name: String? = null
        var resolvedIp: String? = null
        var port: Int? = null

        repeat(answerCount) {
            val nameResult = readDnsName(data, offset) ?: return@repeat
            if (name == null && nameResult.first != null) {
                name = nameResult.first
            }
            offset = nameResult.second
            if (offset + 10 > data.size) return@repeat

            val type = buffer.getShort(offset).toInt() and 0xFFFF
            buffer.getShort(offset + 2) // class
            buffer.getInt(offset + 4)   // ttl
            val rdLength = buffer.getShort(offset + 8).toInt() and 0xFFFF
            val rdataStart = offset + 10
            if (rdataStart + rdLength > data.size) return@repeat

            when (type) {
                1 -> { // A
                    if (rdLength == 4) {
                        resolvedIp = data[rdataStart].toInt().let { it and 0xFF }.toString() + "." +
                                (data[rdataStart + 1].toInt() and 0xFF) + "." +
                                (data[rdataStart + 2].toInt() and 0xFF) + "." +
                                (data[rdataStart + 3].toInt() and 0xFF)
                    }
                }
                12 -> { // PTR
                    val target = readDnsName(data, rdataStart)?.first
                    if (target != null) name = target
                }
                33 -> { // SRV
                    if (rdLength >= 6) {
                        port = buffer.getShort(rdataStart + 4).toInt() and 0xFFFF
                    }
                }
            }
            offset = rdataStart + rdLength
        }

        val targetIp = resolvedIp ?: sourceIp
        return MdnsCandidate(
            ip = targetIp,
            port = port ?: PTP_PORT,
            name = cleanMdnsName(name)
        )
    }

    private fun cleanMdnsName(name: String?): String? {
        if (name == null) return null
        val labels = name.split(".").filterNot {
            it.isBlank() || it.startsWith("_") || it.equals("local", ignoreCase = true)
        }
        return labels.ifEmpty { null }?.joinToString(".")
    }

    private fun readDnsName(data: ByteArray, start: Int): Pair<String?, Int>? {
        if (start >= data.size) return null
        val labels = mutableListOf<String>()
        var offset = start
        var jumped = false
        var guard = 0
        while (guard++ < 32) {
            if (offset >= data.size) return null
            val length = data[offset].toInt() and 0xFF
            when {
                length == 0 -> {
                    offset += 1
                    return labels.joinToString(".") to offset
                }
                (length and 0xC0) == 0xC0 -> {
                    if (offset + 1 >= data.size) return null
                    val pointer = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (!jumped) {
                        offset += 2
                        jumped = true
                    }
                    offset = pointer
                }
                else -> {
                    if (offset + 1 + length > data.size) return null
                    labels.add(String(data, offset + 1, length, Charsets.US_ASCII))
                    offset += 1 + length
                }
            }
        }
        return null
    }

    private fun currentIpv4Addresses(): List<Pair<String, Int>> {
        // 只扫描 WiFi transport，避免把蜂窝网段（如 ccmni3）误当成相机所在网段。
        return connectivityManager.allNetworks.mapNotNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return@mapNotNull null
            }
            val properties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            properties.linkAddresses.firstNotNullOfOrNull { address ->
                val inet = address.address as? Inet4Address ?: return@firstNotNullOfOrNull null
                inet.hostAddress to address.prefixLength
            }
        }
    }

    private fun subnetHosts(ip: String, prefixLength: Int): List<String> {
        val prefix = prefixLength.coerceIn(0, 32)
        val ipInt = ipToInt(ip) ?: return emptyList()
        val mask = if (prefix == 0) 0 else (0xFFFFFFFF.toInt() shl (32 - prefix))
        val networkBase = ipInt and mask
        if (prefix < 24) {
            // RC-6：宽网段不再放弃扫描，只探测前 1024 个地址控制耗时
            return (1..1024).map { intToIp(networkBase + it) }
        }
        return (1 until 255).map { intToIp(networkBase or it) }
    }

    private fun ipToInt(ip: String): Int? {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        return (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
    }

    private fun intToIp(value: Int): String {
        return "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}." +
                "${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }

    private fun sendMdnsProbe(socket: MulticastSocket) {
        runCatching {
            val queries = listOf(
                "_ptp._tcp.local",
                "_nikon._tcp.local",
                "_services._dns-sd._udp.local"
            )
            val group = InetAddress.getByName(MDNS_ADDRESS)
            queries.forEach { name ->
                val queryBytes = buildDnsQuery(name)
                val packet = DatagramPacket(
                    queryBytes,
                    queryBytes.size,
                    group,
                    MDNS_PORT
                )
                socket.send(packet)
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "mDNS probe failed")
        }
    }

    private fun buildDnsQuery(name: String): ByteArray {
        val parts = name.split(".").filter { it.isNotEmpty() }
        val nameBytes = ByteArrayOutputStream().apply {
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.US_ASCII)
                write(bytes.size)
                write(bytes)
            }
            write(0)
        }.toByteArray()

        val buffer = java.nio.ByteBuffer.allocate(12 + nameBytes.size + 4)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putShort(0)                    // transaction id
        buffer.putShort(0)                    // flags: standard query
        buffer.putShort(1)                    // QDCOUNT
        buffer.putShort(0)                    // ANCOUNT
        buffer.putShort(0)                    // NSCOUNT
        buffer.putShort(0)                    // ARCOUNT
        buffer.put(nameBytes)
        buffer.putShort(12)                   // PTR
        buffer.putShort(1)                    // IN
        return buffer.array()
    }

    private fun String.isLoopbackOrMulticast(): Boolean {
        return startsWith("127.") ||
                startsWith("0.") ||
                startsWith("224.") ||
                startsWith("255.") ||
                startsWith("169.254.")
    }
}

private data class MdnsCandidate(
    val ip: String,
    val port: Int,
    val name: String?
)

/**
 * WiFi 网络中发现到的相机候选
 */
data class WifiCameraCandidate(
    val ipAddress: String,
    val port: Int = 15740,
    val name: String,
    val source: String
)

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
import java.net.MulticastSocket
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
    private val networkRequester: StaNetworkRequester
) {
    companion object {
        private const val TAG = "WifiScanner"
        private const val MDNS_PORT = 5353
        private const val PTP_PORT = 15740
        private const val MDNS_ADDRESS = "224.0.0.251"
        private const val DEFAULT_SCAN_TIMEOUT_MS = 12000L
        private const val MAX_SCAN_CONCURRENCY = 32
        private const val GENERIC_NAME = "尼康相机"
        /** mDNS 查询重发间隔：兼顾"相机晚入网"与 UDP 丢包 */
        private const val MDNS_PROBE_INTERVAL_MS = 2000L

        /** RC-6：系统 NSD 服务类型（与自绘 mDNS 并行兜底） */
        private const val NSD_SERVICE_TYPE_PTP = "_ptp._tcp."
        private const val NSD_SERVICE_TYPE_NIKON = "_nikon._tcp."
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
        // ZDROP 同款：扫描期间进程级绑定到 WiFi 网络。双卡手机默认路由在蜂窝时，
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
                val mdnsJob = async { collectMdns(timeoutMs, results, target) }
                val subnetJob = async { scanSubnet(timeoutMs, results, target, hotspotMode) }
                val nsdJob = async { collectNsd(timeoutMs, results, target) }
                val arpJob = async { collectArp(timeoutMs, results, target) }
                awaitAll(mdnsJob, subnetJob, nsdJob, arpJob)
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
        network: Network?
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
                soTimeout = 1000
            }
            // STA 模式下必须显式把组播 Socket 绑定到 WiFi 网络，
            // 否则默认路由可能被蜂窝网抢走，导致收不到相机的 mDNS 广播。
            network?.bindSocket(socket)
            socket.joinGroup(InetAddress.getByName(MDNS_ADDRESS))
            sendMdnsProbe(socket)

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            // 只发一次查询会漏掉"扫描开始后才入网"的相机（相机上电到广播有数秒延迟），
            // 也扛不住单个 UDP 包丢失；按 2s 周期重发，直到扫描窗口结束。
            var lastProbeAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now - lastProbeAt >= MDNS_PROBE_INTERVAL_MS) {
                    sendMdnsProbe(socket)
                    lastProbeAt = now
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val sourceIp = packet.address?.hostAddress ?: continue
                    if (sourceIp.isLoopbackOrMulticast()) continue
                    val candidate = parseMdnsResponse(
                        buffer.copyOf(packet.length),
                        sourceIp
                    ) ?: continue
                    if (!PtpIpProbe.probe(candidate.ip, candidate.port, 1200L, network)) continue
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
            Timber.tag(TAG).w(e, "mDNS listener failed, subnet scan will still run")
        } finally {
            runCatching {
                socket?.leaveGroup(InetAddress.getByName(MDNS_ADDRESS))
            }
            runCatching { socket?.close() }
            multicastLock?.release()
        }
    }

    private suspend fun scanSubnet(
        timeoutMs: Long,
        results: MutableSet<WifiCameraCandidate>,
        network: Network?,
        hotspotMode: Boolean = false
    ) {
        val networks = currentIpv4Addresses()
        if (networks.isEmpty()) return

        val subnetHosts = networks.flatMap { (ip, prefix) ->
            subnetHosts(ip, prefix).filterNot { it == ip }
        }.filter { WifiEndpoint.isValidHost(it) }.distinct()
        // 相机 AP/STA 常见网关 IP，即使当前网段不同也做一次轻量探测。
        val knownGatewayHosts = listOf(
            "192.168.1.1", "192.168.0.1", "10.0.0.1", "192.168.42.1",
            "192.168.31.1", "192.168.2.1", "192.168.10.1", "192.168.50.1",
            "192.168.137.1", "172.16.0.1", "10.0.1.1"
        )
        // 热点模式：手机热点网段（现代 Android 随机化，旧版固定 192.168.43.x）。
        // 本机热点接口多数机型不在 allNetworks 里，无法枚举真实网段时用常见值兜底；
        // ARP 采集器负责覆盖真实网段（相机入网后必然出现在 ARP 表）。
        val hotspotHosts = if (hotspotMode) {
            (1 until 255).map { "192.168.43.$it" }
        } else {
            emptyList()
        }
        val hosts = (subnetHosts + knownGatewayHosts + hotspotHosts)
            .filter { WifiEndpoint.isValidHost(it) }.distinct()
        if (hosts.isEmpty()) return

        val semaphore = Semaphore(MAX_SCAN_CONCURRENCY)
        val deadline = System.currentTimeMillis() + timeoutMs
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (System.currentTimeMillis() >= deadline) return@async
                        if (PtpIpProbe.probe(host, PTP_PORT, 900L, network)) {
                            results.add(WifiCameraCandidate(host, PTP_PORT, "尼康相机", "WiFi"))
                            Timber.tag(TAG).i("Port scan found camera at $host")
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
        network: Network?
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Timber.tag(TAG).w("NsdManager unavailable, NSD discovery skipped")
            return
        }

        // "host:port" -> mDNS 服务名（ZDROP 直接拿 serviceName 当相机名展示，
        // 比统一显示"尼康相机"更容易在列表里认出目标机身）
        val discovered = ConcurrentHashMap<String, String>()
        val done = CompletableDeferred<Unit>()

        // v1.0.2 修复：NsdManager 同一时刻只允许一个 resolve 在途，旧版对两个服务
        // 类型的发现结果并发 resolveService，部分 resolve 静默失败 → 同一WiFi下
        // "搜索不到相机" 的主因之一。改为单飞队列串行 resolve（ZDROP 的 NSD 用法）。
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
                            discovered["$host:$port"] = cleanServiceName(p1.serviceName)
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
        network: Network?
    ) {
        // 给 ARP 表一点学习时间（相机刚入网时表里可能还没有它）
        delay(1500)
        val hosts = readArpHosts()
        if (hosts.isEmpty()) return
        val semaphore = Semaphore(16)
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (results.any { it.ipAddress == host }) return@async
                        if (PtpIpProbe.probe(host, PTP_PORT, 900L, network)) {
                            results.add(WifiCameraCandidate(host, PTP_PORT, GENERIC_NAME, "WiFi-arp"))
                            Timber.tag(TAG).i("ARP candidate: $host")
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    /** 读 /proc/net/arp 中"已解析完成"(flags 含 0x2) 的 IPv4 条目 */
    private fun readArpHosts(): List<String> {
        return runCatching {
            java.io.File("/proc/net/arp").readLines()
                .drop(1)
                .mapNotNull { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 6) return@mapNotNull null
                    val ip = cols[0]
                    val flags = cols[2].toIntOrNull(16) ?: return@mapNotNull null
                    if (flags and 0x2 == 0) return@mapNotNull null
                    if (!WifiEndpoint.isValidHost(ip)) return@mapNotNull null
                    ip
                }
                .filterNot { it.startsWith("127.") }
                .distinct()
        }.getOrDefault(emptyList())
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

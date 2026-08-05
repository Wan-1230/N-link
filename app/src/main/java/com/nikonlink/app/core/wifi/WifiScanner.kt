package com.nikonlink.app.core.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 相机发现器
 *
 * 尼康相机的无线连接流程（影控台/B 站教程实测）：
 * - STA 模式：相机与手机连接同一 WiFi，相机通过 UDP 5353 广播配置文件名称
 * - AP 模式：相机开启热点，手机连接热点后同样通过 UDP 5353 广播
 * 发现候选后通过 TCP 15740 探测 PTP/IP 端口，确认相机可连接。
 */
@Singleton
class WifiScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WifiScanner"
        private const val MDNS_PORT = 5353
        private const val PTP_PORT = 15740
        private const val MDNS_ADDRESS = "224.0.0.251"
        private const val DEFAULT_SCAN_TIMEOUT_MS = 12000L
        private const val TCP_PROBE_TIMEOUT_MS = 800
        private const val MAX_SCAN_CONCURRENCY = 32
        private const val GENERIC_NAME = "尼康相机"
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    /**
     * 同时监听 mDNS 广播并扫描当前网段的 15740 端口。
     */
    suspend fun scan(timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS): List<WifiCameraCandidate> {
        val results = ConcurrentHashMap.newKeySet<WifiCameraCandidate>()
        withContext(Dispatchers.IO) {
            val mdnsJob = async { collectMdns(timeoutMs, results) }
            val subnetJob = async { scanSubnet(timeoutMs, results) }
            awaitAll(mdnsJob, subnetJob)
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
        results: MutableSet<WifiCameraCandidate>
    ) {
        val multicastLock = runCatching {
            wifiManager.createMulticastLock("NikonLinkWifiScan")
        }.getOrNull()
        multicastLock?.setReferenceCounted(false)
        multicastLock?.acquire()

        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(MDNS_PORT).apply {
                reuseAddress = true
                soTimeout = 1000
            }
            socket.joinGroup(InetAddress.getByName(MDNS_ADDRESS))
            sendMdnsProbe(socket)

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val sourceIp = packet.address?.hostAddress ?: continue
                    if (sourceIp.isLoopbackOrMulticast()) continue
                    val candidate = parseMdnsResponse(
                        buffer.copyOf(packet.length),
                        sourceIp
                    ) ?: continue
                    if (!tcpProbe(candidate.ip, candidate.port)) continue
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
        results: MutableSet<WifiCameraCandidate>
    ) {
        val networks = currentIpv4Addresses()
        if (networks.isEmpty()) return

        val subnetHosts = networks.flatMap { (ip, prefix) ->
            subnetHosts(ip, prefix).filterNot { it == ip }
        }.distinct()
        // 相机 AP/STA 常见网关 IP，即使当前网段不同也做一次轻量探测。
        val knownGatewayHosts = listOf("192.168.1.1", "192.168.0.1", "10.0.0.1", "192.168.42.1")
        val hosts = (subnetHosts + knownGatewayHosts).distinct()
        if (hosts.isEmpty()) return

        val semaphore = Semaphore(MAX_SCAN_CONCURRENCY)
        val deadline = System.currentTimeMillis() + timeoutMs
        coroutineScope {
            hosts.map { host ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (System.currentTimeMillis() >= deadline) return@async
                        if (tcpProbe(host)) {
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

    private fun tcpProbe(host: String): Boolean {
        return tcpProbe(host, PTP_PORT)
    }

    private fun tcpProbe(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_PROBE_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
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
        if (prefix < 24) return emptyList() // 避免在大网段做全量扫描

        val ipInt = ipToInt(ip) ?: return emptyList()
        val mask = if (prefix == 0) 0 else (0xFFFFFFFF.toInt() shl (32 - prefix))
        val networkBase = ipInt and mask
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

    private fun parseDnsName(data: ByteArray): String? {
        if (data.size < 12) return null
        var offset = 12
        val labels = mutableListOf<String>()
        var guard = 0
        while (offset < data.size && guard++ < 32) {
            val length = data[offset].toInt() and 0xFF
            if (length == 0) break
            if ((length and 0xC0) == 0xC0) {
                if (offset + 1 >= data.size) break
                offset = ((length and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                continue
            }
            if (offset + 1 + length > data.size) break
            val label = String(data, offset + 1, length, Charsets.US_ASCII).trim()
            if (label.isNotEmpty()) labels.add(label)
            offset += 1 + length
        }
        if (labels.isEmpty()) return null
        return labels
            .filterNot { it.equals("local", ignoreCase = true) || it.startsWith("_") }
            .ifEmpty { labels }
            .joinToString(".")
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

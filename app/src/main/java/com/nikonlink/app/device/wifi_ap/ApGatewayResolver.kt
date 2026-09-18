package com.nikonlink.app.device.wifi_ap

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.wifi.WifiManager
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpIpProbe
import com.nikonlink.app.shared.common.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AP 模式下「相机在哪」的学习器（PRD v2.2 §5.1 T-A2）。
 *
 * 相机自己做热点时**自任网关**，所以手机加入该热点后，网关地址就是相机地址。
 * 这是 AP 通道唯一不依赖蓝牙凭证、也不依赖任何硬编码网段的判定依据 —— 旧实现
 * 只有 BLE 凭证与写死的 192.168.1.1 两条路，新固件把凭证 LsSec 加密后 AP 就整体不可用。
 *
 * 采样与规则借鉴行业通行做法（ZDROP 的 `AP gateway detected / stabilized /
 * ignored because it matches handset address`）：
 * - 加入后先等 [READINESS_MS] 再取，避免读到 DHCP 未完成时的中间态；
 * - 连采两次一致才认 [stable]=true（网关在恢复过程中会变）；
 * - 与手机自身链路地址相同的网关直接丢弃（部分 ROM 把本机地址报成网关）。
 */
@Singleton
class ApGatewayResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "ApGateway"

        /** 加入热点后到第一次取网关的等待（ZDROP: AP network readiness wait=1200ms） */
        private const val READINESS_MS = 1200L

        /** 两次采样间隔 */
        private const val RESAMPLE_MS = 700L

        /**
         * 尼康机身热点名：`NIKON_` + 8 位十六进制（ZDROP 同源规则），区域/固件变体
         * 见 `NIKON-WF…` / `Nikon WU…`。判定放宽到「以 NIKON 开头」，
         * 因为这里只用于「手机是不是已经连在相机网络上」的启发式，误判的代价只是多试一次。
         */
        fun looksLikeCameraAp(ssid: String?): Boolean {
            val clean = ssid?.trim()?.removeSurrounding("\"") ?: return false
            if (clean.isEmpty() || clean == "<unknown ssid>") return false
            return clean.startsWith("NIKON", ignoreCase = true) ||
                CAMERA_AP_SSID.matches(clean)
        }

        private val CAMERA_AP_SSID = Regex("(?i)^NIKON[ _\\-].{2,16}$")

        /** 厂商出厂默认候选（仅作为网关学习失败后的兜底，顺序即优先级） */
        val FACTORY_DEFAULT_HOSTS = listOf("192.168.1.1", "192.168.0.1", "192.168.3.1", "10.0.0.1")

        /**
         * 网关能不能当成相机地址。
         *
         * 排除项各有实测来由：`169.254` 是 DHCP 失败时系统自分配（链路根本不通）、
         * `.0`/`.255` 是网络号与广播、[ownAddresses] 里的地址是**手机自己**
         * （部分 ROM 在点对点热点上把本机地址报成网关，拿它去连必然空转）。
         */
        fun isCameraGateway(ip: String?, ownAddresses: Set<String>): Boolean {
            if (ip.isNullOrBlank() || ip == "0.0.0.0") return false
            if (ip.startsWith("169.254.") || ip.startsWith("224.") || ip.startsWith("240.")) return false
            if (ip.startsWith("127.")) return false
            if (ip.endsWith(".0") || ip.endsWith(".255")) return false
            return ip !in ownAddresses
        }

        /** DhcpInfo 的网关是 little-endian int，手工还原成点分十进制。 */
        fun intToIpLittleEndian(address: Int): String = intArrayOf(
            address and 0xFF,
            (address shr 8) and 0xFF,
            (address shr 16) and 0xFF,
            (address shr 24) and 0xFF
        ).joinToString(".")
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    data class Result(
        val host: String,
        val port: Int = PtpConstants.DEFAULT_PORT,
        /** true = 两次采样一致；false = 采样期间网关变了（链路可能仍在恢复） */
        val stable: Boolean,
        /** 结论来源：gateway | factory | none */
        val source: String,
    )

    /** 手机当前连着的 SSID（去掉引号；无权限或未连接时返回 null）。 */
    fun connectedSsid(): String? = runCatching {
        @Suppress("DEPRECATION")
        wifiManager.connectionInfo?.ssid
    }.getOrNull()

    fun isOnCameraAp(): Boolean = looksLikeCameraAp(connectedSsid())

    /**
     * 学习相机地址。[network] 为 specifier 建立的专属网络；为 null 时取当前 WiFi 网络。
     *
     * 返回的 host 已经过 TCP/PTP 可达性确认（[PtpIpProbe] 的 `OK` 或 `TCP_OPEN_NO_PTP`
     * 都算命中 —— 后者说明端口在、相机只是没回握手，通常是刚唤醒）。
     */
    suspend fun resolve(network: Network?, wifiNetwork: Network? = null): Result? {
        val target = network ?: wifiNetwork ?: runCatching {
            connectivityManager.allNetworks.firstOrNull { net ->
                connectivityManager.getNetworkCapabilities(net)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()

        delay(READINESS_MS)
        val first = gatewayOf(target)
        val second = if (first == null) null else {
            delay(RESAMPLE_MS)
            gatewayOf(target)
        }
        val host = second ?: first
        val stable = first != null && second != null && first == second

        if (host != null) {
            val probe = PtpIpProbe.probeDetailed(host, PtpConstants.DEFAULT_PORT, timeoutMs = 1500L, network = target)
            if (probe.isCandidate) {
                Timber.tag(TAG).i("gateway resolved host=$host stable=$stable probe=$probe")
                eventLogger.event(
                    "ap_gateway", "host" to host, "stable" to stable, "probe" to probe.name
                )
                return Result(host, stable = stable, source = "gateway")
            }
            Timber.tag(TAG).w("gateway host=$host not reachable as PTP (probe=$probe), trying factory defaults")
            eventLogger.event("ap_gateway", "host" to host, "stable" to stable, "probe" to "rejected")
        } else {
            Timber.tag(TAG).w("no gateway found on wifi network, trying factory defaults")
            eventLogger.event("ap_gateway", "host" to "none")
        }

        // 兜底：出厂默认地址逐个 TCP 快筛（不发握手，避免占用相机唯一的配对槽）
        for (candidate in FACTORY_DEFAULT_HOSTS) {
            if (PtpIpProbe.tcpConnectOnly(candidate, PtpConstants.DEFAULT_PORT, 600L, target)) {
                val deeper = PtpIpProbe.probeDetailed(candidate, timeoutMs = 1500L, network = target)
                if (deeper.isCandidate) {
                    Timber.tag(TAG).i("factory default hit host=$candidate")
                    eventLogger.event("ap_gateway", "host" to candidate, "stable" to stable, "probe" to "factory")
                    return Result(candidate, stable = stable, source = "factory")
                }
            }
        }
        return null
    }

    /**
     * 从链路属性里挑出网关：默认路由的 gateway 优先，其次任意「有网关」路由；
     * 排除手机自己的地址、链路本地/组播/回环，以及 0.0.0.0。
     */
    private fun gatewayOf(network: Network?): String? {
        val target = network ?: runCatching {
            connectivityManager.allNetworks.firstOrNull { net ->
                connectivityManager.getNetworkCapabilities(net)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrNull()
        val props: LinkProperties? = target?.let {
            runCatching { connectivityManager.getLinkProperties(it) }.getOrNull()
        }

        val own: Set<String> = props?.linkAddresses
            ?.mapNotNull { addr -> addr.address?.hostAddress }
            ?.toSet() ?: emptySet()

        val routes: List<RouteInfo> = props?.routes ?: emptyList()
        val gateways = routes
            .filter { it.hasGateway() }
            .sortedByDescending { runCatching { it.isDefaultRoute }.getOrDefault(false) }
            .mapNotNull { it.gateway?.hostAddress }
            .filter { isCameraGateway(it, own) }

        if (gateways.isNotEmpty()) return gateways.first()

        // 极老或残缺的 ROM 上路由表不可信，退回 DhcpInfo
        @Suppress("DEPRECATION")
        val dhcp = runCatching { wifiManager.dhcpInfo?.gateway ?: 0 }.getOrDefault(0)
        return if (dhcp != 0) intToIpLittleEndian(dhcp).takeIf { isCameraGateway(it, own) } else null
    }

    /** 便于日志：把该网络的链路地址列出来（诊断面板用）。 */
    fun describeNetwork(network: Network?): String {
        val props: LinkProperties? = network?.let { runCatching { connectivityManager.getLinkProperties(it) }.getOrNull() }
        val addrs: List<LinkAddress> = props?.linkAddresses ?: emptyList()
        val gw = props?.routes?.firstOrNull { it.hasGateway() }?.gateway?.hostAddress
        return "addrs=${addrs.mapNotNull { it.address?.hostAddress }} gw=$gw"
    }
}

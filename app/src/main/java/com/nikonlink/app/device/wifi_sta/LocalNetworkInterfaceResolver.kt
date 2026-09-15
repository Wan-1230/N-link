package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机网卡解析器 —— 补 `ConnectivityManager` 的盲区。
 *
 * ## 为什么必须有这个类（RC-0）
 *
 * Android 把「手机自己开的热点」接口交给 **Tethering 服务**管理，它**不经过
 * ConnectivityService**，因此：
 *
 * - 它**不会出现在 `ConnectivityManager.allNetworks`** 里；
 * - 它没有 `TRANSPORT_WIFI` 能力（那是"作为客户端连别人的 WiFi"的 transport）；
 * - `getLinkProperties()` 也拿不到它。
 *
 * 于是旧实现用"有没有 WiFi 网络"来判断链路是否就绪时，**手机开热点的场景恒为 false**
 * —— 直接判 `no_wifi_network` 收口。真机日志铁证：9 轮尝试每轮都
 * `sta_fail reason=no_wifi_network attempt=1`、耗时恒定 16.2s。
 *
 * **唯一能拿到手机自身热点接口的手段是 `NetworkInterface.getNetworkInterfaces()`**
 * —— 这正是 ZDROP 的做法（其 dex 中存在该方法调用，而我们全项目零使用）。
 *
 * ## 为什么不能靠接口名硬编码
 *
 * 热点接口名在各 ROM 上完全不统一：`ap0` / `swlan0` / `softap0` / `wlan1` / `ap1` …，
 * 甚至同一厂商不同机型都不同（参见 StackOverflow #74768317）。
 * 所以本类**只按性质筛选**：接口 up + 非回环 + 有非链路本地 IPv4。
 * 这样无论它叫什么名字都能被纳入，而且不会把蜂窝网段误当相机网段。
 *
 * @see WifiScanner 使用 [subnets] 补全网段列表
 * @see WifiDirectConnector 使用 [hasLocalWifiLikeInterface] 修正 `no_wifi_network` 误判
 */
@Singleton
class LocalNetworkInterfaceResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "LocalNetIface"
    }

    /** 一个本机 IPv4 链路地址（网卡名 + 地址 + 前缀长度）。 */
    data class LocalAddress(
        val interfaceName: String,
        val address: String,
        val prefixLength: Int
    ) {
        /** `ap0 192.168.43.1/24` 这样的诊断文本 */
        val display: String get() = "$interfaceName $address/$prefixLength"
    }

    /**
     * 本机全部「可用的 IPv4 链路地址」，**含热点接口**。
     *
     * 与 `ConnectivityManager` 的差别：后者只看得到系统承认的"网络"（蜂窝/WiFi客户端/VPN），
     * 看不到 Tethering 的热点接口；本方法走内核网卡列表，两者都能拿到。
     *
     * 过滤规则（只按性质，不按名字）：
     * - 接口已 up；
     * - 非回环；
     * - 有 IPv4 地址；
     * - **排除链路本地地址 `169.254.x.x`**（DHCP 失败时的自分配地址，路由不可用
     *   —— 对齐 ZDROP 的 `ignored link-local endpoint`）。
     *
     * @return 按网卡名排序；读不到网卡列表时返回空列表（不抛异常）。
     */
    fun localAddresses(): List<LocalAddress> {
        val result = mutableListOf<LocalAddress>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }
            .getOrNull() ?: run {
            Timber.tag(TAG).w("NetworkInterface.getNetworkInterfaces() unavailable")
            return emptyList()
        }
        for (nif in interfaces) {
            val ok = runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)
            if (!ok) continue
            val name = runCatching { nif.name }.getOrNull() ?: continue
            val addresses = runCatching { nif.inetAddresses }.getOrNull() ?: continue
            while (addresses.hasMoreElements()) {
                val inet = addresses.nextElement() as? Inet4Address ?: continue
                if (inet.isLoopbackAddress) continue
                if (inet.isLinkLocalAddress) continue          // 169.254.x.x
                val host = inet.hostAddress ?: continue
                val prefix = runCatching {
                    nif.interfaceAddresses
                        .firstOrNull { it.address == inet }
                        ?.networkPrefixLength
                        ?.toInt()
                }.getOrNull() ?: 24
                result.add(LocalAddress(name, host, prefix.coerceIn(0, 32)))
            }
        }
        return result.sortedBy { it.interfaceName }
    }

    /**
     * 本机 IPv4 网段列表（`地址 to 前缀`），**含热点接口** —— 供网段扫描使用。
     *
     * 与 [WifiScanner.currentIpv4Addresses] 的 `ConnectivityManager` 版本互为补充：
     * 那个负责"客户端 WiFi 网段"，本方法负责"热点网段 + 其它内核可见网段"。
     */
    fun subnets(): List<Pair<String, Int>> =
        localAddresses().map { it.address to it.prefixLength }.distinct()

    private fun ipToInt(host: String): Int? {
        val parts = host.trim().split(".")
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun prefixMask(prefixLength: Int): Int {
        if (prefixLength <= 0) return 0
        if (prefixLength >= 32) return -1
        return (0xFFFFFFFF.toInt() shl (32 - prefixLength))
    }

    /**
     * [host] 是否落在**本机某个已连接网段**之内。
     *
     * 这是判断"相机到底能不能从本机直达"的最终依据：
     * PTP/IP 是二层直连协议，相机必须与手机在**同一个直连网段**，
     * 跨网段/走网关一律不可达。若本方法返回 false，说明扫描给出的 IP
     * 与本机任何网卡都不在同网段 —— 再怎么重试也连不上，应该提示用户重新扫描。
     *
     * 注意前缀长度取自内核（[localAddresses]），蜂窝网口通常是 /32
     * （PPP 点对点），此时只有它自己算"同网段"，不会出现误判。
     *
     * @return true = host 在本机某个直连网段内（含网络地址/广播地址校验）
     */
    fun subnetsContain(host: String): Boolean {
        val target = ipToInt(host) ?: return false
        return localAddresses().any { local ->
            val base = ipToInt(local.address) ?: return@any false
            val mask = prefixMask(local.prefixLength)
            (base and mask) == (target and mask)
        }
    }

    /**
     * 手机上是否存在「类 WiFi 的本地接口」—— 用于区分
     * **"真的一个网都没有"** 与 **"有热点但 ConnectivityManager 看不见"**。
     *
     * [WifiDirectConnector] 在 `network == null` 时判 `no_wifi_network` 立即收口；
     * 但手机开热点时 `allNetworks` 里确实没有 WiFi，而这个热点接口是**真实可用**的
     * （相机就连在它上面）。若此时还判 `no_wifi_network`，就是 RC-0 那个误判。
     *
     * @return true = 存在至少一个非回环、有 IPv4 的接口（含热点）
     */
    fun hasLocalWifiLikeInterface(): Boolean = localAddresses().isNotEmpty()

    /**
     * 当前是否处于「热点拓扑」：系统侧看不到 WiFi 客户端网络，
     * 但本机存在内核可见的非蜂窝 IPv4 接口。
     *
     * 用于让扫描路径知道"该走默认路由 + 网卡枚举网段"，而**不是**收口放弃。
     */
    fun isHotspotTopology(): Boolean {
        if (!hasLocalWifiLikeInterface()) return false
        val hasClientWifi = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }.getOrDefault(false)
        return !hasClientWifi
    }
}

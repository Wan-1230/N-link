package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nikonlink.app.device.connect.ConnFlags
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
    @ApplicationContext private val context: Context,
    private val connFlags: ConnFlags
) {
    companion object {
        private const val TAG = "LocalNetIface"

        /**
         * FR-23：蜂窝与 VPN 接口的名字前缀**兜底表**。
         *
         * 它不是唯一判据 —— 主判据是 [nonWifiLikeInterfaceNames] 从 `ConnectivityManager`
         * 读到的 transport，本表只是叠加保险：当蜂窝网络已注册但链路属性还没就绪、
         * 或 ROM 干脆不给 VPN 派发 Network 对象时，名字仍能把黑洞出口拦下来。
         *
         * 出处（PRD v2.6 §十 要求每个数字/名单有来处）：
         *  - `ccmni` —— 真机日志实际观测到 `ccmni2 10.32.64.21/8`、`ccmni4 10.3.9.110/8`
         *    （MediaTek 平台蜂窝数据口，两份不同机器的日志里都出现过）
         *  - `vgate` —— 真机日志实际观测到 `vgate0 172.30.235.180/32`（/32 点对点，虚拟网关）
         *  - `rmnet` —— 高通平台蜂窝数据口的通用命名
         *  - `tun` / `tap` —— 通用 VPN 虚拟网卡命名
         *  - `ppp` —— 3GPPP 拨号与部分 VPN 的点对点命名（蜂窝/VPN 两侧都可能用到，两边都排）
         *  - `rndis` —— USB 共享网络（手机把蜂窝经 USB 分给电脑）。FR-34 真机实证：
         *    vivo V2509A 挂 PC 调试时 `rndis0 10.32.72.202/24` 被算成"类 WiFi"，
         *    wlan0 已掉时 `hasLocalWifiLikeInterface()` 仍为 true →
         *    defaultRouteFallback 误判"有本地网络"→ 3 条 30s socket 全从蜂窝
         *    /10.115.218.201 打进黑洞（2026-10-02 14:30:13→14:31:31，≈73s 空转）。
         *    相机永远不会出现在 USB 共享网络上，排除它不影响 RC-0 的热点拓扑
         *    （WiFi 热点接口是 `ap0`/`swlan0`/`wlan1`，不在此表）。
         *
         * **不排**的东西：`ap0` / `swlan0` / `softap0` / `wlan1` 等热点与第二 WiFi 接口
         * —— 它们正是"手机自己开热点、相机连上来"那条拓扑的唯一出口，误排就会退回
         * RC-0 那个"能发现但恒判 no_wifi_network"的老坑（见本类头注释）。
         */
        internal val NON_WIFI_PREFIXES = listOf("ccmni", "rmnet", "vgate", "tun", "tap", "ppp", "rndis")

        /**
         * 名字前缀判据（抽成纯函数只为可测）。
         *
         * 它是**兜底**，主判据是 transport —— 见 [isNonWifiName]。
         * 断言里钉住 `ap0` / `wlan0` / `swlan0` 不被误排，因为那正是
         * RC-0 那个"能发现但恒判 no_wifi_network"的坑会被重新踩回去的地方。
         */
        internal fun matchesNonWifiPrefix(name: String): Boolean =
            NON_WIFI_PREFIXES.any { name.startsWith(it) }
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
     * 本机 IPv4 网段列表（`地址 to 前缀`），**含热点接口、不含蜂窝与 VPN** —— 供网段扫描使用。
     *
     * 与 [WifiScanner.currentIpv4Addresses] 的 `ConnectivityManager` 版本互为补充：
     * 那个负责"客户端 WiFi 网段"，本方法负责"热点网段 + 其它内核可见网段"。
     *
     * FR-23：这里必须走 [wifiLikeAddresses] 而不是 [localAddresses] —— 蜂窝口常报 /8
     * （真机观测到 `ccmni4 10.3.9.110/8`），把它并进扫描网段等于对着一整段运营商
     * NAT 地址做盲扫，既扫不到二层直连的相机，又把探测预算烧光。
     */
    fun subnets(): List<Pair<String, Int>> =
        wifiLikeAddresses().map { it.address to it.prefixLength }.distinct()

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
     * 从 `ConnectivityManager` 动态取出「确定不是 WiFi」的接口名。
     *
     * 为什么以它为主判据：接口名在各 ROM 上完全不统一（本类头注释已警告过），
     * 而 transport 是系统给的性质声明，跨机型稳定。
     *
     * FR-23 关闭时返回空集，退化成"只按名字前缀判"以外的原行为（等价 v2.3.2）。
     */
    private fun transportExcludedNames(): Set<String> {
        if (!connFlags.isEnabled(ConnFlags.IFACE_CLASSIFY)) return emptySet()
        val cm = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            ?: return emptySet()
        val names = mutableSetOf<String>()
        for (network in runCatching { cm.allNetworks }.getOrNull() ?: return names) {
            val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: continue
            val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (!cellular && !vpn) continue
            runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()?.let { names.add(it) }
        }
        return names
    }

    /** 这个接口名是不是蜂窝 / VPN 出口。 */
    private fun isNonWifiName(name: String, transportExcluded: Set<String>): Boolean {
        if (name in transportExcluded) return true
        return connFlags.isEnabled(ConnFlags.IFACE_CLASSIFY) &&
            NON_WIFI_PREFIXES.any { name.startsWith(it) }
    }

    /**
     * 本机全部「类 WiFi 的可用 IPv4 链路地址」—— 蜂窝与 VPN 出口已剔除。
     *
     * [localAddresses] 是内核原始全量（含蜂窝），诊断文本要用它如实呈现；
     * 凡是**决策**用得到的地方（有没有本地网络、默认路由能不能走）都必须用本方法，
     * 否则就是 FR-23 修的那个"把蜂窝当 WiFi"的误判。
     */
    fun wifiLikeAddresses(): List<LocalAddress> {
        val excluded = transportExcludedNames()
        return localAddresses().filterNot { isNonWifiName(it.interfaceName, excluded) }
    }

    /**
     * 手机上是否存在「类 WiFi 的本地接口」—— 用于区分
     * **"真的一个网都没有"** 与 **"有热点但 ConnectivityManager 看不见"**。
     *
     * [WifiDirectConnector] 在 `network == null` 时判 `no_wifi_network` 立即收口；
     * 但手机开热点时 `allNetworks` 里确实没有 WiFi，而这个热点接口是**真实可用**的
     * （相机就连在它上面）。若此时还判 `no_wifi_network`，就是 RC-0 那个误判。
     *
     * FR-23：判据从「有任意非回环 IPv4」收紧为「有非蜂窝、非 VPN 的 IPv4」。
     * 旧实现把 `ccmni4`（蜂窝）与 `vgate0`（VPN）算成"类 WiFi"，于是 WiFi 没连上时
     * 仍判"有本地网络"→ 走默认路由把 socket 打进蜂窝黑洞，白烧掉整个重试预算。
     * 真机日志：15/15 次 `sta_fallback` 全部 `in_subnet=false`，`in_subnet=true` 出现 0 次。
     *
     * @return true = 存在至少一个非回环、有 IPv4、且不是蜂窝/VPN 的接口（含热点）
     */
    fun hasLocalWifiLikeInterface(): Boolean = wifiLikeAddresses().isNotEmpty()

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

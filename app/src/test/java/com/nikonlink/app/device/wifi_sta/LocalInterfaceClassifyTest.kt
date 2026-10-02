package com.nikonlink.app.device.wifi_sta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD v2.6 FR-23：哪些本机网卡**不能**当成「类 WiFi 的本地接口」。
 *
 * 旧判据是"有任意非回环 IPv4 接口"，于是蜂窝口 `ccmni4 10.3.9.110/8` 和
 * VPN 口 `vgate0 172.30.235.180/32` 都算数 —— 手机 WiFi 根本没连上时也判
 * "有本地网络"，然后走默认路由把 socket 打进蜂窝黑洞。
 * 真机日志：15/15 次 `sta_fallback` 全部 `in_subnet=false`，`in_subnet=true` 出现 0 次。
 *
 * 本测试只覆盖**名字前缀兜底**那一半（主判据是 `ConnectivityManager` 的 transport，
 * 需要真机）。它最容易被后来人顺手改错，所以把"不许误排"的名字钉死在这里。
 */
class LocalInterfaceClassifyTest {

    @Test
    fun `蜂窝与 VPN 口按名字排除`() {
        listOf(
            "ccmni2", "ccmni4", "rmnet0", "rmnet_data1", "vgate0", "tun0", "tap1", "ppp0",
            // FR-34：USB 共享网络。真机实证 rndis0 顶替 wlan0 让 defaultRouteFallback 误开，
            // 3 条 30s socket 全从蜂窝打进黑洞（2026-10-02 R5，≈73s 空转）
            "rndis0", "rndis1"
        ).forEach {
            assertTrue("$it 应当被排除", LocalNetworkInterfaceResolver.matchesNonWifiPrefix(it))
        }
    }

    /**
     * 这一条是 RC-0 的护栏。
     *
     * `ap0` / `swlan0` / `softap0` / `wlan1` 是"手机自己开热点、相机连上来"这种拓扑
     * 里唯一能看到相机网段的接口，而且它们**不经 ConnectivityService**、
     * 在 `allNetworks` 里恒不可见。一旦把它们排掉，就退回到当年那个
     * "能发现相机、却恒判 no_wifi_network、9 轮全空转"的误判。
     */
    @Test
    fun `热点与 WiFi 客户端接口绝不允许被误排`() {
        listOf("wlan0", "wlan1", "ap0", "ap1", "swlan0", "softap0", "p2p0", "bridge0").forEach {
            assertFalse("$it 不该被排除", LocalNetworkInterfaceResolver.matchesNonWifiPrefix(it))
        }
    }

    @Test
    fun `前缀表内容锁定`() {
        assertEquals(listOf("ccmni", "rmnet", "vgate", "tun", "tap", "ppp", "rndis"), NON_WIFI_PREFIXES)
    }

    private val NON_WIFI_PREFIXES get() = LocalNetworkInterfaceResolver.NON_WIFI_PREFIXES
}

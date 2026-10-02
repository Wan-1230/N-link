package com.nikonlink.app.device.wifi_sta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-34⑤：回连等待只允许作用于**私网地址**。
 *
 * 「wlan 不在位时 socket 必沉蜂窝黑洞」这个推断的前提是目的地为局域网地址
 * （相机热点/路由器 LAN）。公网或环回形态的地址走蜂窝是合法路径，误拦会把
 * 本来能连的场景判死 —— 与 FR-22 那次"预算比成功时间短"的教训同型，所以钉死。
 */
class WlanWaitRuleTest {

    @Test
    fun `私网段地址判为可等待`() {
        listOf(
            "192.168.1.1", "192.168.43.1", "10.0.0.1", "10.184.219.14",
            "172.16.0.1", "172.31.255.254"
        ).forEach {
            assertTrue("$it 应判私网", WifiDirectConnector.isPrivateLanIp(it))
        }
    }

    @Test
    fun `公网与畸形地址不拦`() {
        listOf(
            "172.15.0.1", "172.32.0.1",   // 172 私网区间之外
            "8.8.8.8", "100.64.0.1",      // 公网 / CGNAT
            "", "192.168.1", "a.b.c.d", "192.168.1.1.1", "256.1.1.1"
        ).forEach {
            assertFalse("$it 不该被拦", WifiDirectConnector.isPrivateLanIp(it))
        }
    }
}

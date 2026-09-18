package com.nikonlink.app.device.wifi_ap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2（PRD §5.1 T-A2/T-A3）AP 地址学习与热点识别的纯规则回归测试。
 *
 * 这里只覆盖不依赖 Android framework 判定的两件事：
 * 1. 「这个 SSID 是不是相机热点」——决定 AP 能不能不走蓝牙直接连；
 * 2. 「网关地址是不是可用的相机地址」——决定学习结果会不会把手机自己/广播地址
 *    当成相机（ZDROP 的 `AP gateway ignored because it matches handset address`）。
 * 实际的路由读取与 PTP 探测需要真机，列在 PRD §12 的 V-8。
 */
class ApGatewayResolverTest {

    @Test
    fun `尼康机身热点名被识别，路由器名不被误判`() {
        assertTrue(ApGatewayResolver.looksLikeCameraAp("NIKON_9431ABCD"))
        assertTrue(ApGatewayResolver.looksLikeCameraAp("nikon_12345678"))
        assertTrue(ApGatewayResolver.looksLikeCameraAp("\"NIKON_9431ABCD\""))
        assertTrue(ApGatewayResolver.looksLikeCameraAp("Nikon-WF2-8A12"))
        assertFalse(ApGatewayResolver.looksLikeCameraAp("TP-LINK_5G_88A1"))
        assertFalse(ApGatewayResolver.looksLikeCameraAp("小米15 Pro"))
        assertFalse(ApGatewayResolver.looksLikeCameraAp("<unknown ssid>"))
        assertFalse(ApGatewayResolver.looksLikeCameraAp(""))
        assertFalse(ApGatewayResolver.looksLikeCameraAp(null))
    }

    @Test
    fun `相机热点名提取不区分大小写且容忍分隔符差异`() {
        // 区域/固件变体：NIKON_xxxx、NIKON-xxxx、NIKON xxxx 都见过
        listOf("NIKON_1A2B3C4D", "NIKON-1A2B3C4D", "NIKON 1A2B3C4D", "Nikon_1a2b3c4d")
            .forEach { assertTrue("$it 应判为相机热点", ApGatewayResolver.looksLikeCameraAp(it)) }
    }

    @Test
    fun `出厂默认候选顺序把 Z 系常见的 1 段放最前`() {
        assertEquals("192.168.1.1", ApGatewayResolver.FACTORY_DEFAULT_HOSTS.first())
        assertEquals(4, ApGatewayResolver.FACTORY_DEFAULT_HOSTS.size)
    }

    @Test
    fun `网关判定的边界值与 DhcpInfo 还原`() {
        // DhcpInfo 的网关是 little-endian int：192.168.1.1 -> 0x0101A8C0
        assertEquals("192.168.1.1", ApGatewayResolver.intToIpLittleEndian(0x0101A8C0))
        assertEquals("192.168.43.1", ApGatewayResolver.intToIpLittleEndian(0x012BA8C0))
    }

    @Test
    fun `手机自身地址与系统自分配地址都不能当成相机`() {
        val own = setOf("192.168.1.100")
        assertFalse(ApGatewayResolver.isCameraGateway("192.168.1.100", own))   // 手机自己
        assertFalse(ApGatewayResolver.isCameraGateway("169.254.23.7", own))    // DHCP 失败自分配
        assertFalse(ApGatewayResolver.isCameraGateway("0.0.0.0", own))
        assertFalse(ApGatewayResolver.isCameraGateway("192.168.1.255", own))   // 广播
        assertFalse(ApGatewayResolver.isCameraGateway("192.168.1.0", own))     // 网络号
        assertFalse(ApGatewayResolver.isCameraGateway(null, own))
        assertTrue(ApGatewayResolver.isCameraGateway("192.168.1.1", own))
        assertTrue(ApGatewayResolver.isCameraGateway("10.0.0.1", own))
    }
}

package com.nikonlink.app.device.wifi_sta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.6 FR-29⑤：蜂窝黑洞的判定规则。
 *
 * 这条规则的存在理由是**一次真实误判**：v2.6.3 的 R4 用网卡枚举（`subnetsContain`）做硬拦截，
 * 而 PRD §13.3 用真机数据证明该来源在 vivo V2509A 上是假阴性 —— `localAddresses()` 看不见
 * 实际承载相机流量的接口，同一次连接在 `in_subnet=false` 的情况下照样成功。
 * 以假阴性为据做硬拦截，会把能连上的场景判死。
 *
 * 所以这里钉住的是"两个条件必须同时成立"：只满足一条都不许拦。
 */
class CellularBlackholeRuleTest {

    @Test
    fun `只有蜂窝默认路由且无 WiFi 覆盖相机地址时才算黑洞`() {
        assertTrue(
            "默认路由在蜂窝、且没有任何 WiFi 覆盖相机 → 这一探必然耗干 30s，该拦",
            cellularBlackhole(activeIsCellular = true, anyWifiCoversHost = false)
        )
    }

    @Test
    fun `有 WiFi 覆盖相机地址时绝不拦，哪怕默认路由报蜂窝`() {
        assertFalse(
            "手机热点 + 蜂窝并存：默认路由可能报蜂窝，但相机在热点子网里，探测能通" +
                "（真机 in_subnet=true 那轮一次就成）",
            cellularBlackhole(activeIsCellular = true, anyWifiCoversHost = true)
        )
    }

    @Test
    fun `默认路由不是蜂窝时绝不拦`() {
        assertFalse(
            "默认路由在 WiFi/VPN 上时失败会快速返回，不会像蜂窝那样耗干超时",
            cellularBlackhole(activeIsCellular = false, anyWifiCoversHost = false)
        )
        assertFalse(cellularBlackhole(activeIsCellular = false, anyWifiCoversHost = true))
    }
}

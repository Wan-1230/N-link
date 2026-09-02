package com.nikonlink.app.device.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RC-3 回归测试：相机地址解析 / 归一化。
 *
 * 核心场景来自真实日志 —— 用户输入 `192.168.031.065`，旧逻辑只判断「非空」
 * 就把原串丢给 `InetAddress`，结果连续 55 次 `phase=socket`、零次 `phase=init`，
 * 用户拿不到任何错误提示。
 *
 * 注意：前导零在 OpenJDK 21 实测按十进制解析（`031` → 31，不是八进制 25），
 * 所以这里断言的是「归一化后得到规范形式」，
 * 而不是「修好了某个八进制解析 bug」。真正防止的是非法输入静默重试。
 */
class WifiEndpointTest {

    @Test
    fun `前导零按八进制误读的地址被静默纠正`() {
        val endpoint = WifiEndpoint.parse("192.168.031.065")
        assertEquals("192.168.31.65", endpoint?.host)
        assertEquals(15740, endpoint?.port)
    }

    @Test
    fun `全零段与多前导零都能归一化`() {
        assertEquals("192.168.0.1", WifiEndpoint.parse("192.168.000.001")?.host)
        assertEquals("10.0.0.5", WifiEndpoint.parse("010.00.0.05")?.host)
    }

    @Test
    fun `存储格式 wifi 前缀与端口被正确解析`() {
        val endpoint = WifiEndpoint.parse("wifi:192.168.031.065:15740")
        assertEquals("192.168.31.65", endpoint?.host)
        assertEquals(15740, endpoint?.port)
        assertEquals("wifi:192.168.31.65:15740", endpoint?.address)
    }

    @Test
    fun `两端空白与大小写前缀容错`() {
        assertEquals("192.168.1.9", WifiEndpoint.parse("  192.168.1.9  ")?.host)
        assertEquals("192.168.1.9", WifiEndpoint.parse("WIFI:192.168.1.9")?.host)
    }

    @Test
    fun `方括号写法兼容`() {
        assertEquals("192.168.1.9", WifiEndpoint.parse("[192.168.1.9]:15740")?.host)
        assertEquals(15740, WifiEndpoint.parse("[192.168.1.9]:15740")?.port)
        assertEquals(15740, WifiEndpoint.parse("192.168.1.9:")?.port)
    }

    @Test
    fun `非默认端口保留`() {
        val endpoint = WifiEndpoint.parse("192.168.1.9:20000")
        assertEquals(20000, endpoint?.port)
        assertEquals("192.168.1.9:20000", endpoint?.display)
    }

    @Test
    fun `段数不对或超范围一律判非法`() {
        assertNull(WifiEndpoint.parse("192.168.1"))
        assertNull(WifiEndpoint.parse("192.168.1.1.1"))
        assertNull(WifiEndpoint.parse("192.168.1.256"))
        assertNull(WifiEndpoint.parse("192.168.1.1000"))
        assertNull(WifiEndpoint.parse("abc"))
        assertNull(WifiEndpoint.parse(""))
        assertNull(WifiEndpoint.parse("   "))
        assertNull(WifiEndpoint.parse(null))
        assertNull(WifiEndpoint.parse("192.168.1.-1"))
        assertNull(WifiEndpoint.parse("192.168..1"))
    }

    @Test
    fun `端口越界或非法一律判非法`() {
        assertNull(WifiEndpoint.parse("192.168.1.9:0"))
        assertNull(WifiEndpoint.parse("192.168.1.9:65536"))
        assertNull(WifiEndpoint.parse("192.168.1.9:abc"))
    }

    @Test
    fun `isValidHost 只认合法 IPv4`() {
        assertEquals(true, WifiEndpoint.isValidHost("192.168.031.065"))
        assertEquals(false, WifiEndpoint.isValidHost("192.168.1.256"))
        assertEquals(false, WifiEndpoint.isValidHost("nikon-camera.local"))
    }
}

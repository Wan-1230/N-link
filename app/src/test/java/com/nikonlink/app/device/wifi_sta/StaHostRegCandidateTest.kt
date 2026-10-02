package com.nikonlink.app.device.wifi_sta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.6.6 FR-30：未连接态自主注册的候选挑选规则。
 *
 * 注册的前提是相机 PTP/IP 服务正在受理事务（停在主机配置向导），
 * 而扫描结果里「待唤醒」条目是 TCP 通但 PTP 握手没应答的休眠相机 ——
 * 对它发 0x952B 必然超时，所以 awake 的候选必须排在前面；
 * 但只有休眠候选时也不能返回 null（用户可能刚把相机唤醒，扫描结论已过期），
 * 交给注册链路自己拿真实应答说话。
 */
class StaHostRegCandidateTest {

    @Test
    fun `优先挑 PTP 握手已应答的相机`() {
        val candidates = listOf(
            WifiCameraCandidate("10.0.0.9", 15740, "尼康相机(待唤醒)", "WiFi"),
            WifiCameraCandidate("10.0.0.7", 15740, "Z50II", "WiFi")
        )
        assertEquals("10.0.0.7", pickRegistrationCandidate(candidates)?.ipAddress)
    }

    @Test
    fun `只有待唤醒候选时兜底取第一个，不返回 null`() {
        val candidates = listOf(
            WifiCameraCandidate("10.0.0.9", 15740, "尼康相机(待唤醒)", "WiFi-arp")
        )
        assertEquals("10.0.0.9", pickRegistrationCandidate(candidates)?.ipAddress)
    }

    @Test
    fun `空候选返回 null 让上层给双路径引导`() {
        assertNull(pickRegistrationCandidate(emptyList()))
    }
}

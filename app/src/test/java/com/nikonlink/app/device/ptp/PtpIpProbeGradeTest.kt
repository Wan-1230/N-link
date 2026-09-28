package com.nikonlink.app.device.ptp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.SocketException

/**
 * PRD v2.6 FR-18b：探活失败到底该归到哪一档。
 *
 * 这条映射以前藏在真 socket 的 catch 分支里，导出日志只剩一个 `reachable=false`，
 * 于是"端口被拒""黑洞超时""本机根本不通"三件处置完全相反的事
 * （分别是：等机身 / 唤醒相机 / 换网络）在日志里长得一模一样。
 */
class PtpIpProbeGradeTest {

    @Test
    fun `连接超时归为 TIMEOUT`() {
        assertEquals(PtpIpProbe.ProbeResult.TIMEOUT, PtpIpProbe.gradeOf(SocketTimeoutException("connect timed out")))
    }

    @Test
    fun `端口拒绝归为 REFUSED`() {
        assertEquals(PtpIpProbe.ProbeResult.REFUSED, PtpIpProbe.gradeOf(PortUnreachableException()))
        assertEquals(
            PtpIpProbe.ProbeResult.REFUSED,
            PtpIpProbe.gradeOf(SocketException("connect failed: ECONNREFUSED (Connection refused)"))
        )
    }

    @Test
    fun `路由不可达单独一档，不与超时混在一起`() {
        assertEquals(PtpIpProbe.ProbeResult.NO_ROUTE, PtpIpProbe.gradeOf(NoRouteToHostException("no route to host")))
        assertEquals(
            PtpIpProbe.ProbeResult.NO_ROUTE,
            PtpIpProbe.gradeOf(SocketException("connect failed: EHOSTUNREACH (No route to host)"))
        )
        assertEquals(
            PtpIpProbe.ProbeResult.NO_ROUTE,
            PtpIpProbe.gradeOf(SocketException("connect failed: ENETUNREACH (Network is unreachable)"))
        )
    }

    @Test
    fun `无法识别的异常不猜，记 ERROR`() {
        assertEquals(PtpIpProbe.ProbeResult.ERROR, PtpIpProbe.gradeOf(IllegalStateException("boom")))
    }

    /**
     * errno 匹配必须**先于** timeout 匹配：
     * `EHOSTUNREACH (No route to host)` 这种消息里同时含 "route"，
     * 而 Android 的部分 ROM 会把超时也包装成带 "timeout" 字样的 IOException。
     * 顺序错了就会把"换网络"的场景误报成"再等等"。
     */
    @Test
    fun `带 route 字样的消息不会被 timeout 分支抢先吃掉`() {
        val e = SocketException("connect failed: EHOSTUNREACH (No route to host) timeout")
        assertEquals(PtpIpProbe.ProbeResult.NO_ROUTE, PtpIpProbe.gradeOf(e))
    }
}

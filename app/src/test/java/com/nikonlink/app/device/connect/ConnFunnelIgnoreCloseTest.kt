package com.nikonlink.app.device.connect

import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.metrics.BaselineMetrics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批次 C-N1（2026-10-12 vivo V2509A + Z 50Ⅱ AP 日志）：被拦下的连接请求必须收口。
 *
 * 现场是四条一模一样的空壳样本：
 * `conn_result channel=WIFI/AP-fallback stage=INTENT ms=59144 reason=superseded
 * caller=reconnect_trigger probes=0 revisits=0`（另三条 57770 / 5553 / 6652ms）。
 * 成因不是「连接被重复触发」，而是兜底地址冷却那次拦截：
 * `funnel.begin()` 已经把轮次打开，紧接着 `connect_ignored reason=fallback_cooldown`
 * 直接 `return`，没人给它收口 —— 于是它一直挂到用户的下一次点击才被顶掉，
 * 而 [ConnFunnel.finish] 对「没走到 READY 又没记过失败」的轮次一律判 superseded。
 * 25 个连接样本里 4 个（16%）是这种什么都没干过的空壳，
 * 分阶段耗时的 p50/p95 被它们带着走，排查时还得先分辨哪几条真的跑过。
 */
class ConnFunnelIgnoreCloseTest {

    private fun newFunnel(): ConnFunnel = ConnFunnel(
        eventLogger = mockk<AppEventLogger>(relaxed = true),
        baselineMetrics = BaselineMetrics(mockk<AppEventLogger>(relaxed = true))
    )

    /** 回归基线：开了轮次不收口，就会被下一轮顶成 superseded 空壳。 */
    @Test
    fun `不收口的轮次会被后来的 begin 顶成空壳`() {
        val funnel = newFunnel()
        funnel.begin("WIFI", "AP-fallback", ConnFunnel.Caller.RECONNECT_TRIGGER.code)
        // 冷却拦下 —— 旧实现在这里直接 return，什么都不补
        funnel.begin("WIFI", "AP-fallback", ConnFunnel.Caller.USER_TAP.code)

        val ghost = funnel.history.value.first()
        assertEquals(
            "没收口的轮次只能被推断成「被打断」",
            ConnFunnel.Reason.SUPERSEDED,
            ghost.steps.last().reason
        )
        assertEquals("空壳里一次探测都没有", 0, ghost.probes)
    }

    /** C-N1：拦下时收口 → 这一轮有了自己的码，且不再产生空壳。 */
    @Test
    fun `拦下时收口就不再留下空壳`() {
        val funnel = newFunnel()
        funnel.begin("WIFI", "AP-fallback", ConnFunnel.Caller.RECONNECT_TRIGGER.code)
        funnel.fail(ConnFunnel.Reason.COOLDOWN_SKIPPED, "waited_s=1")
        funnel.begin("WIFI", "AP-fallback", ConnFunnel.Caller.USER_TAP.code)

        val closed = funnel.history.value.first()
        assertEquals(
            "收口用的是本轮自己的原因码，不是被打断",
            ConnFunnel.Reason.COOLDOWN_SKIPPED,
            closed.steps.last().reason
        )
        // 第一条已 finished、current 被清空，所以第二次 begin 不会再回头给它补一份
        // superseded 历史 —— 这一条必须恰好只出现一次
        assertEquals("已收口的轮次不该被后来的 begin 顶第二次", 1, funnel.history.value.size)
        assertEquals(
            ConnFunnel.Caller.RECONNECT_TRIGGER.code,
            closed.caller
        )
    }

    /** 日志码一旦发布就是契约（只增不改）：钉住码值。 */
    @Test
    fun `新增原因码的码值固定`() {
        assertEquals("cooldown_skipped", ConnFunnel.Reason.COOLDOWN_SKIPPED.code)
        assertEquals(
            "拓扑类失败仍走原有码值",
            "no_wifi_network",
            ConnFunnel.Reason.NO_WIFI_NETWORK.code
        )
    }
}

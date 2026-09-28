package com.nikonlink.app.device.connect

import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.metrics.BaselineMetrics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PRD v2.6 FR-18g / FR-18h：漏斗口径。
 *
 * 修的是这个：`DISCOVER` 卡打在 `beginWifiPairing` 入口，而它到 `TCP` 之间还夹着
 * 选网（上限 4s）与连接前探活（上限 3s）。于是导出基线里的 `TCP p95=178927ms`
 * 根本不是建链耗时 —— 同一批日志用原始时间戳量，真实建链最短的一次只有 576ms。
 * 没有 `ROUTE` 这块分界卡，"慢在建链"和"慢在选网/探活"永远分不开。
 */
class ConnFunnelV26Test {

    private fun newFunnel(): ConnFunnel = ConnFunnel(
        eventLogger = mockk<AppEventLogger>(relaxed = true),
        baselineMetrics = BaselineMetrics(mockk<AppEventLogger>(relaxed = true))
    )

    /**
     * 关键断言：有了 ROUTE 之后，TCP 段只含 socket 时间。
     *
     * DISCOVER→ROUTE 之间是选网 + 探活（4400ms），ROUTE→TCP 之间才是建链（40ms）。
     * 旧口径会把这 4440ms 全记在 TCP 头上。
     */
    @Test
    fun `ROUTE 卡把选网与探活从 TCP 段里切出去`() {
        val funnel = newFunnel()
        funnel.begin("WiFi", "AP-fallback", ConnFunnel.Caller.USER_TAP.code)
        funnel.stage(ConnFunnel.Stage.DISCOVER, detail = "192.168.1.1")
        // 用绝对时间差构造：手工铺步骤比真等 4.4 秒可靠
        val attempt = ConnFunnel.Attempt(
            channel = "WiFi/AP-fallback",
            startedAt = 0L,
            steps = mutableListOf(
                ConnFunnel.Step(ConnFunnel.Stage.INTENT, 0L),
                ConnFunnel.Step(ConnFunnel.Stage.DISCOVER, 100L),
                ConnFunnel.Step(ConnFunnel.Stage.ROUTE, 4500L),
                ConnFunnel.Step(ConnFunnel.Stage.TCP, 4540L),
                ConnFunnel.Step(ConnFunnel.Stage.HANDSHAKE, 4549L),
                ConnFunnel.Step(ConnFunnel.Stage.FIRST_COMMAND, 4590L)
            )
        )
        assertEquals(
            mapOf(
                "DISCOVER" to 100L,
                "ROUTE" to 4400L,
                "TCP" to 40L,
                "HANDSHAKE" to 9L,
                "FIRST_COMMAND" to 41L
            ),
            funnel.stageGaps(attempt)
        )
    }

    /**
     * FR-18g 的诚实标记：阶段被重访说明这条 attempt 跨了多个连接循环，
     * 它的分阶段耗时不可信。不改 `stageGaps` 的算法（它对线性序列是对的，
     * 且已有 [ConnFunnelStageGapTest] 钉住语义），而是把脏样本**标出来**，
     * 让导出物的读者不去信它、也让指标层能把它剔出百分位计算。
     */
    @Test
    fun `线性序列 revisits 为 0，重访的记次数`() {
        val funnel = newFunnel()
        val linear = ConnFunnel.Attempt(
            channel = "WiFi/AP", startedAt = 0L,
            steps = mutableListOf(
                ConnFunnel.Step(ConnFunnel.Stage.INTENT, 0L),
                ConnFunnel.Step(ConnFunnel.Stage.DISCOVER, 10L),
                ConnFunnel.Step(ConnFunnel.Stage.TCP, 20L)
            )
        )
        assertEquals(0, funnel.revisitCount(linear))

        // 真机日志2 风暴段的形状：一代连接一个 DISCOVER 卡，全挤在同一条 attempt 里
        val stormy = ConnFunnel.Attempt(
            channel = "WiFi/AP", startedAt = 0L,
            steps = mutableListOf(
                ConnFunnel.Step(ConnFunnel.Stage.INTENT, 0L),
                ConnFunnel.Step(ConnFunnel.Stage.DISCOVER, 10L),
                ConnFunnel.Step(ConnFunnel.Stage.DISCOVER, 30L),
                ConnFunnel.Step(ConnFunnel.Stage.DISCOVER, 50L),
                ConnFunnel.Step(ConnFunnel.Stage.TCP, 90L)
            )
        )
        assertEquals(1, funnel.revisitCount(stormy))
    }

    /** 未被测量的阶段（PREFLIGHT / JOIN / READY）重访不该污染可信度判定。 */
    @Test
    fun `只统计口径内阶段的重访`() {
        val funnel = newFunnel()
        val attempt = ConnFunnel.Attempt(
            channel = "USB", startedAt = 0L,
            steps = mutableListOf(
                ConnFunnel.Step(ConnFunnel.Stage.INTENT, 0L),
                ConnFunnel.Step(ConnFunnel.Stage.PREFLIGHT, 5L),
                ConnFunnel.Step(ConnFunnel.Stage.PREFLIGHT, 9L),
                ConnFunnel.Step(ConnFunnel.Stage.TCP, 20L)
            )
        )
        assertEquals(0, funnel.revisitCount(attempt))
    }

    /** FR-18h：一次"点连接"对机身 15740 探了几次，必须能从日志里数出来。 */
    @Test
    fun `探测计数累加并随收口进入历史`() {
        val funnel = newFunnel()
        funnel.begin("WiFi", "AP-fallback")
        funnel.recordProbe()
        funnel.recordProbe()
        // 进行中的 attempt 不进 history（只有收口后的才进），所以要显式 finish
        funnel.finish()
        assertEquals(2, funnel.history.value.first().probes)
    }

    /** 顶号时上一轮的探测计数要跟着收口进历史，不能被新 attempt 抹掉。 */
    @Test
    fun `新一轮 begin 会先收口上一轮并保留其探测数`() {
        val funnel = newFunnel()
        funnel.begin("WiFi", "AP-fallback", ConnFunnel.Caller.RECONNECT_TRIGGER.code)
        repeat(3) { funnel.recordProbe() }
        funnel.begin("WiFi", "AP-fallback", ConnFunnel.Caller.USER_TAP.code)
        // 此刻 history 里只有被顶掉的那一条；第二条还在进行中
        assertEquals(1, funnel.history.value.size)
        val closed = funnel.history.value.first()
        assertEquals(3, closed.probes)
        assertEquals(ConnFunnel.Caller.RECONNECT_TRIGGER.code, closed.caller)
        // 既没走到 READY、又没记过任何失败 = 被后来的请求顶掉了，必须诚实记 superseded，
        // 不能像旧版那样留成 `reason=ok` 让人以为是成功
        assertEquals(ConnFunnel.Reason.SUPERSEDED, closed.steps.last().reason)
    }

    /** 没有进行中的 attempt 时记探测不该崩，也不该造出一条假样本。 */
    @Test
    fun `无进行中 attempt 时 recordProbe 静默`() {
        val funnel = newFunnel()
        funnel.recordProbe()
        assertEquals(emptyList<ConnFunnel.Attempt>(), funnel.history.value)
    }
}

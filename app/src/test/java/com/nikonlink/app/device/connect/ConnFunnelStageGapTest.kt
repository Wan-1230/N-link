package com.nikonlink.app.device.connect

import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.metrics.BaselineMetrics
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnFunnelStageGapTest {

    private val funnel = ConnFunnel(
        eventLogger = mockk<AppEventLogger>(relaxed = true),
        baselineMetrics = BaselineMetrics(mockk<AppEventLogger>(relaxed = true))
    )

    private fun attempt(vararg steps: Pair<ConnFunnel.Stage, Long>) =
        ConnFunnel.Attempt(
            channel = "WiFi/AP",
            startedAt = steps.first().second,
            steps = steps.map { ConnFunnel.Step(it.first, it.second) }.toMutableList()
        )

    @Test
    fun `只统计口径内的四个阶段`() {
        val gaps = funnel.stageGaps(
            attempt(
                ConnFunnel.Stage.INTENT to 0L,
                ConnFunnel.Stage.PREFLIGHT to 300L,
                ConnFunnel.Stage.DISCOVER to 900L,
                ConnFunnel.Stage.TCP to 1400L,
                ConnFunnel.Stage.READY to 2600L
            )
        )
        assertEquals(mapOf("DISCOVER" to 600L, "TCP" to 500L), gaps)
    }

    @Test
    fun `同一阶段被重访时取最后一次，不把等待摊进耗时`() {
        val gaps = funnel.stageGaps(
            attempt(
                ConnFunnel.Stage.INTENT to 0L,
                ConnFunnel.Stage.TCP to 400L,
                ConnFunnel.Stage.DISCOVER to 500L,
                ConnFunnel.Stage.TCP to 5400L,
                ConnFunnel.Stage.HANDSHAKE to 5700L
            )
        )
        assertEquals(mapOf("DISCOVER" to 100L, "TCP" to 4900L, "HANDSHAKE" to 300L), gaps)
    }

    @Test
    fun `没有步骤时返回空表而不是造数`() {
        val gaps = funnel.stageGaps(
            ConnFunnel.Attempt(channel = "USB", startedAt = 0L, steps = mutableListOf())
        )
        assertEquals(emptyMap<String, Long>(), gaps)
    }
}

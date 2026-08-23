package com.nikonlink.app.core.imaging.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RepairMath 单元测试（PRD 4.4 一键修复缺陷检测与修复策略）
 */
class RepairMathTest {

    private fun stats(
        meanLum: Float = 118f,
        meanR: Float = 118f,
        meanB: Float = 118f,
        p2: Float = 20f,
        p98: Float = 235f,
        shadowRatio: Float = 0f,
        highlightClip: Float = 0f
    ) = EnhanceMath.ImageStats(meanLum, meanR, 118f, meanB, p2, p98, shadowRatio, highlightClip)

    @Test
    fun `欠曝照片提亮并报告欠曝`() {
        val r = RepairMath.repair(stats(meanLum = 40f, shadowRatio = 0.3f))
        assertTrue("应报告欠曝", r.issues.contains("欠曝"))
        assertTrue("应提亮", r.params.brightness > 0)
        assertTrue("应提阴影", r.params.shadows > 0)
    }

    @Test
    fun `过曝照片压暗并报告过曝`() {
        val r = RepairMath.repair(stats(meanLum = 210f, highlightClip = 0.1f))
        assertTrue("应报告过曝", r.issues.contains("过曝"))
        assertTrue("应压暗", r.params.brightness < 0)
        assertTrue("应收高光", r.params.highlights < 0)
    }

    @Test
    fun `发灰照片拉对比并报告`() {
        val r = RepairMath.repair(stats(p2 = 90f, p98 = 160f))
        assertTrue("应报告发灰", r.issues.contains("画面发灰"))
        assertTrue("应拉对比", r.params.contrast > 0)
    }

    @Test
    fun `明显偏暖纠偏并向冷`() {
        val r = RepairMath.repair(stats(meanR = 160f, meanB = 100f))
        assertTrue("应报告偏暖", r.issues.contains("偏暖色"))
        assertTrue("应向冷校正", r.params.temperature < 0)
    }

    @Test
    fun `健康照片无缺陷且参数保守`() {
        val r = RepairMath.repair(stats())
        assertTrue(r.issues.contains("未检测到明显缺陷"))
        assertEquals(0, r.params.brightness)
        assertEquals(0, r.params.contrast)
        assertEquals(0, r.params.temperature)
    }

    @Test
    fun `修复幅度受限不失控`() {
        val r = RepairMath.repair(
            stats(meanLum = 5f, shadowRatio = 0.95f, meanR = 250f, meanB = 10f, p2 = 0f, p98 = 40f)
        )
        assertTrue(r.params.brightness in 0..60)
        assertTrue(r.params.temperature in -45..45)
        assertTrue(r.params.contrast in 0..55)
    }
}

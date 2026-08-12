package com.nikonlink.app.core.imaging.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SceneOptimizer 单元测试（PRD 4.2 人像/风光优化策略）
 */
class SceneOptimizerTest {

    private fun stats(
        meanLum: Float = 118f,
        p2: Float = 20f,
        p98: Float = 235f,
        shadowRatio: Float = 0f,
        highlightClip: Float = 0f
    ) = EnhanceMath.ImageStats(meanLum, 118f, 118f, 118f, p2, p98, shadowRatio, highlightClip)

    @Test
    fun `人像策略柔化对比且微暖`() {
        val r = SceneOptimizer.portrait(stats())
        assertTrue("对比应柔化", r.params.contrast <= 0)
        assertTrue("色温微暖", r.params.temperature > 0)
        assertTrue("低强度色彩增强", r.params.vibrance in 1..30)
        assertTrue("备注应说明全局策略", r.note.contains("全局人像"))
    }

    @Test
    fun `人像欠曝时提亮且不过度`() {
        val r = SceneOptimizer.portrait(stats(meanLum = 60f))
        assertTrue("应提亮", r.params.brightness > 0)
        assertTrue("提亮限幅", r.params.brightness <= 25)
    }

    @Test
    fun `人像高光溢出时回收`() {
        val r = SceneOptimizer.portrait(stats(highlightClip = 0.05f))
        assertTrue("应压高光", r.params.highlights < 0)
    }

    @Test
    fun `风光策略提升通透度与色彩`() {
        val r = SceneOptimizer.landscape(stats())
        assertTrue("清晰度应提升", r.params.clarity > 0)
        assertTrue("色彩增强应提升", r.params.vibrance > 0)
        assertTrue("轻冷调", r.params.temperature < 0)
    }

    @Test
    fun `风光暗部占比高时提阴影`() {
        val r = SceneOptimizer.landscape(stats(shadowRatio = 0.2f))
        assertTrue("应提阴影", r.params.shadows > 0)
        assertTrue("限幅", r.params.shadows <= 20)
    }

    @Test
    fun `风光动态范围宽时不加对比`() {
        val r = SceneOptimizer.landscape(stats(p2 = 0f, p98 = 255f))
        assertEquals(0, r.params.contrast)
    }

    @Test
    fun `极端输入参数受限`() {
        val extreme = stats(meanLum = 0f, shadowRatio = 1f, highlightClip = 1f, p2 = 0f, p98 = 10f)
        val p = SceneOptimizer.portrait(extreme).params
        val l = SceneOptimizer.landscape(extreme).params
        assertTrue(p.brightness in 0..25)
        assertTrue(p.shadows in 0..15)
        assertTrue(p.highlights in -30..0)
        assertTrue(l.shadows in 0..20)
        assertTrue(l.highlights in -35..0)
    }
}

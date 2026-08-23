package com.nikonlink.app.core.imaging.ai

import com.nikonlink.app.core.imaging.EditParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EnhanceMath 单元测试（PRD 4.1 一键增强的规则引擎数学）
 */
class EnhanceMathTest {

    private fun stats(
        meanLum: Float = 118f,
        meanR: Float = 118f,
        meanG: Float = 118f,
        meanB: Float = 118f,
        p2: Float = 20f,
        p98: Float = 235f,
        shadowRatio: Float = 0f,
        highlightClip: Float = 0f
    ) = EnhanceMath.ImageStats(meanLum, meanR, meanG, meanB, p2, p98, shadowRatio, highlightClip)

    @Test
    fun `均衡图像不产生调节`() {
        val p = EnhanceMath.suggest(stats())
        assertEquals(EditParams(), p)
    }

    @Test
    fun `欠曝图像提亮`() {
        val p = EnhanceMath.suggest(stats(meanLum = 60f))
        assertTrue("应提亮，实际 ${p.brightness}", p.brightness > 0)
    }

    @Test
    fun `过曝图像压暗`() {
        val p = EnhanceMath.suggest(stats(meanLum = 190f))
        assertTrue("应压暗，实际 ${p.brightness}", p.brightness < 0)
    }

    @Test
    fun `高光溢出时抑制提亮`() {
        val normal = EnhanceMath.suggest(stats(meanLum = 90f))
        val clipped = EnhanceMath.suggest(stats(meanLum = 90f, highlightClip = 0.05f))
        assertTrue(
            "溢出时提亮幅度应更小: ${clipped.brightness} < ${normal.brightness}",
            clipped.brightness < normal.brightness
        )
    }

    @Test
    fun `动态范围窄时增加对比度`() {
        val p = EnhanceMath.suggest(stats(p2 = 80f, p98 = 160f))
        assertTrue("应提升对比度", p.contrast > 0)
    }

    @Test
    fun `动态范围充足时不加对比度`() {
        val p = EnhanceMath.suggest(stats(p2 = 5f, p98 = 250f))
        assertEquals(0, p.contrast)
    }

    @Test
    fun `暖色偏向冷校正`() {
        val p = EnhanceMath.suggest(stats(meanR = 140f, meanB = 100f))
        assertTrue("暖色偏应得到负色温，实际 ${p.temperature}", p.temperature < 0)
    }

    @Test
    fun `冷色偏向暖校正`() {
        val p = EnhanceMath.suggest(stats(meanR = 100f, meanB = 140f))
        assertTrue("冷色偏应得到正色温，实际 ${p.temperature}", p.temperature > 0)
    }

    @Test
    fun `高光溢出压暗高光`() {
        val p = EnhanceMath.suggest(stats(highlightClip = 0.03f))
        assertTrue("应压高光，实际 ${p.highlights}", p.highlights < 0)
    }

    @Test
    fun `死黑占比高时提亮阴影`() {
        val p = EnhanceMath.suggest(stats(shadowRatio = 0.15f))
        assertTrue("应提阴影，实际 ${p.shadows}", p.shadows > 0)
    }

    @Test
    fun `所有输出在保守限幅内`() {
        // 极端输入也不失控（PRD 4.1 一键结果保守）
        val p = EnhanceMath.suggest(
            stats(meanLum = 5f, p2 = 0f, p98 = 30f, shadowRatio = 0.9f,
                meanR = 250f, meanB = 10f, highlightClip = 0.5f)
        )
        assertTrue(p.brightness in -45..45)
        assertTrue(p.contrast in 0..50)
        assertTrue(p.temperature in -35..35)
        assertTrue(p.highlights in -60..0)
        assertTrue(p.shadows in 0..50)
    }

    @Test
    fun `百分位计算正确`() {
        val hist = IntArray(256)
        hist[50] = 100
        hist[200] = 100
        assertEquals(50f, EnhanceMath.percentile(hist, 200, 0.25f), 0.01f)
        assertEquals(200f, EnhanceMath.percentile(hist, 200, 0.75f), 0.01f)
        assertEquals(0f, EnhanceMath.percentile(IntArray(256), 0, 0.5f), 0.01f)
    }

    @Test
    fun `强度缩放 0 等于原图 100 等于完整增强`() {
        val base = EditParams(brightness = 40, contrast = 20, temperature = -10, highlights = -30, shadows = 15)
        assertEquals(EditParams(), EnhanceMath.scale(base, 0))
        assertEquals(base, EnhanceMath.scale(base, 100))
        val half = EnhanceMath.scale(base, 50)
        assertEquals(20, half.brightness)
        assertEquals(10, half.contrast)
        assertEquals(-5, half.temperature)
    }
}

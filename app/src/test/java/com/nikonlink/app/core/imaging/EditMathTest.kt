package com.nikonlink.app.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditMath 单元测试（PRD 4.3 基础调节数学正确性）
 */
class EditMathTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    private fun channels(px: Int): Triple<Int, Int, Int> =
        Triple((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)

    @Test
    fun `默认参数上下文为 noOp`() {
        val ctx = EditMath.buildContext(EditParams())
        assertTrue(ctx.isNoOp)
    }

    @Test
    fun `亮度正向提升像素值`() {
        val ctx = EditMath.buildContext(EditParams(brightness = 50))
        val pixels = intArrayOf(argb(100, 100, 100))
        EditMath.applyContext(pixels, 1, ctx)
        val (r, g, b) = channels(pixels[0])
        // +50 → 偏移 +40: 100+40=140
        assertEquals(140, r)
        assertEquals(140, g)
        assertEquals(140, b)
    }

    @Test
    fun `亮度不截幅超出 255`() {
        val ctx = EditMath.buildContext(EditParams(brightness = 100))
        val pixels = intArrayOf(argb(250, 250, 250))
        EditMath.applyContext(pixels, 1, ctx)
        val (r, _, _) = channels(pixels[0])
        assertEquals(255, r)
    }

    @Test
    fun `对比度增强拉开明暗差距`() {
        val ctx = EditMath.buildContext(EditParams(contrast = 100))
        val pixels = intArrayOf(argb(180, 180, 180), argb(60, 60, 60))
        EditMath.applyContext(pixels, 2, ctx)
        val bright = channels(pixels[0]).first
        val dark = channels(pixels[1]).first
        // 对比度 +100: factor=1.8; 亮部 180→(52*1.8)+128=221.6; 暗部 60→(-68*1.8)+128=5.6
        assertTrue("亮部应提升", bright > 180)
        assertTrue("暗部应压暗", dark < 60)
    }

    @Test
    fun `色温正向偏暖红升蓝降`() {
        val ctx = EditMath.buildContext(EditParams(temperature = 100))
        val pixels = intArrayOf(argb(128, 128, 128))
        EditMath.applyContext(pixels, 1, ctx)
        val (r, _, b) = channels(pixels[0])
        assertEquals(173, r)  // 128 + 45
        assertEquals(83, b)   // 128 - 45
    }

    @Test
    fun `高光负值仅压暗亮部`() {
        val ctx = EditMath.buildContext(EditParams(highlights = -100))
        val pixels = intArrayOf(argb(220, 220, 220), argb(60, 60, 60))
        EditMath.applyContext(pixels, 2, ctx)
        val bright = channels(pixels[0]).first
        val dark = channels(pixels[1]).first
        assertTrue("亮部应被压暗", bright < 220)
        assertEquals("暗部不受影响", 60, dark)
    }

    @Test
    fun `阴影正值仅提亮暗部`() {
        val ctx = EditMath.buildContext(EditParams(shadows = 100))
        val pixels = intArrayOf(argb(220, 220, 220), argb(40, 40, 40))
        EditMath.applyContext(pixels, 2, ctx)
        val bright = channels(pixels[0]).first
        val dark = channels(pixels[1]).first
        assertEquals("亮部不受影响", 220, bright)
        assertTrue("暗部应被提亮", dark > 40)
    }

    @Test
    fun `alpha 通道保持不变`() {
        val ctx = EditMath.buildContext(EditParams(brightness = 100))
        val pixels = intArrayOf(0x80646464.toInt())
        EditMath.applyContext(pixels, 1, ctx)
        assertEquals(0x80, (pixels[0] ushr 24) and 0xFF)
    }

    @Test
    fun `calcInSampleSize 按长边降采样`() {
        assertEquals(1, EditMath.calcInSampleSize(1920, 1080, 1080))
        assertEquals(2, EditMath.calcInSampleSize(4000, 3000, 1080))
        assertEquals(4, EditMath.calcInSampleSize(8000, 6000, 1080))
        assertEquals(1, EditMath.calcInSampleSize(800, 600, 1080))
    }

    @Test
    fun `幂等性 相同输入结果一致`() {
        val ctx = EditMath.buildContext(EditParams(brightness = 30, contrast = 20, temperature = -10))
        val a = intArrayOf(argb(90, 130, 200))
        val b = intArrayOf(argb(90, 130, 200))
        EditMath.applyContext(a, 1, ctx)
        EditMath.applyContext(b, 1, ctx)
        assertEquals(a[0], b[0])
    }

    @Test
    fun `色彩增强 低饱和像素提升强于高饱和像素`() {
        // 低饱和（灰）像素 headroom 大，提升系数更高；已饱和像素几乎不变（PRD 4.3 保护肤色）
        val boostLowSat = EditMath.vibranceBoost(currentSaturation = 20, amountPct = 80)
        val boostHighSat = EditMath.vibranceBoost(currentSaturation = 240, amountPct = 80)
        assertTrue("低饱和提升应更强", boostLowSat > boostHighSat)
        assertTrue("高饱和几乎不变", boostHighSat < 1.08f)
        assertEquals("强度 0 不提升", 1f, EditMath.vibranceBoost(20, 0))
    }

    @Test
    fun `清晰度增益单调且受限`() {
        assertEquals(0f, EditMath.clarityGain(0))
        assertEquals(0f, EditMath.clarityGain(-10))
        assertTrue(EditMath.clarityGain(50) < EditMath.clarityGain(100))
        assertEquals(1.1f, EditMath.clarityGain(100), 0.001f)
    }

    @Test
    fun `新增参数默认值为零且 isDefault 覆盖`() {
        val p = EditParams()
        assertEquals(0, p.clarity)
        assertEquals(0, p.vibrance)
        assertEquals(0, p.denoise)
        assertTrue(p.isDefault)
        assertFalse(p.copy(clarity = 1).isDefault)
        assertFalse(p.copy(vibrance = 1).isDefault)
        assertFalse(p.copy(denoise = 1).isDefault)
    }

    @Test
    fun `降噪混合系数单调且封顶`() {
        assertEquals(0f, EditMath.denoiseBlend(0))
        assertTrue(EditMath.denoiseBlend(50) < EditMath.denoiseBlend(100))
        // 上限 0.85 保留纹理（PRD 4.3 避免塑料感）
        assertEquals(0.85f, EditMath.denoiseBlend(100), 0.001f)
        assertEquals(0.85f, EditMath.denoiseBlend(200), 0.001f)
    }

    @Test
    fun `降噪混合插值正确`() {
        assertEquals(100, EditMath.denoiseMix(100, 200, 0f))
        assertEquals(200, EditMath.denoiseMix(100, 200, 1f))
        assertEquals(150, EditMath.denoiseMix(100, 200, 0.5f))
    }
}

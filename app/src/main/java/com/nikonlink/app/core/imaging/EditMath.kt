package com.nikonlink.app.core.imaging

import kotlin.math.max
import kotlin.math.min

/**
 * 基础调节纯函数数学库（PRD 4.3 基础调节项）
 *
 * 纯 Kotlin 实现、无 Android 依赖，可被 JVM 单元测试直接覆盖。
 * 管线顺序: 亮度/对比度(LUT) → 色温 → 高光/阴影(按亮度加权)。
 * 日志来源: 渲染耗时由 EditEngine 统一打点，本类不做 IO。
 */
object EditMath {

    /** 对比度强度映射到乘法系数：-100 → 0.2，0 → 1.0，+100 → 1.8 */
    fun contrastFactor(contrast: Int): Float = 1f + contrast / 100f * 0.8f

    /** 亮度强度映射到加性偏移：±100 → ±80（保留余量防截幅） */
    fun brightnessOffset(brightness: Int): Float = brightness * 0.8f

    /** 色温强度映射到通道偏移：正=暖（R+/B-），±100 → ±45 */
    fun temperatureShift(temperature: Int): Float = temperature * 0.45f

    /**
     * 构建亮度/对比度组合 LUT（对 R/G/B 三通道一致）。
     * v' = (v - 128) * contrastFactor + 128 + brightnessOffset
     */
    fun buildBrightnessContrastLut(brightness: Int, contrast: Int): IntArray {
        val factor = contrastFactor(contrast)
        val offset = brightnessOffset(brightness)
        return IntArray(256) { v ->
            (((v - 128) * factor + 128 + offset)).toInt().coerceIn(0, 255)
        }
    }

    /**
     * 对像素数组应用完整基础调节管线（原地修改）。
     *
     * @param pixels ARGB 像素数组
     * @param count 有效像素数
     * @param lut 亮度/对比度 LUT（buildBrightnessContrastLut 产物）
     * @param tempShift 色温通道偏移（temperatureShift 产物），0 时跳过
     * @param highlights 高光强度 -100..100，0 时跳过高光分支
     * @param shadows 阴影强度 -100..100，0 时跳过阴影分支
     */
    fun applyToPixels(
        pixels: IntArray,
        count: Int,
        lut: IntArray,
        tempShift: Float,
        highlights: Int,
        shadows: Int
    ) {
        val hlGain = highlights / 100f * 0.5f
        val shGain = shadows / 100f * 0.5f
        val hasTone = highlights != 0 || shadows != 0
        val hasTemp = tempShift != 0f

        for (i in 0 until count) {
            val px = pixels[i]
            val a = px and 0xFF000000.toInt()
            var r = lut[(px shr 16) and 0xFF]
            var g = lut[(px shr 8) and 0xFF]
            var b = lut[px and 0xFF]

            if (hasTemp) {
                r = (r + tempShift).toInt().coerceIn(0, 255)
                b = (b - tempShift).toInt().coerceIn(0, 255)
            }

            if (hasTone) {
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                // 高光权重: 亮度高于中灰的部分；阴影权重: 低于中灰的部分
                val wh = max(0f, (lum - 128f) / 127f)
                val ws = max(0f, (128f - lum) / 128f)
                val gain = 1f + hlGain * wh + shGain * ws
                if (gain != 1f) {
                    r = (r * gain).toInt().coerceIn(0, 255)
                    g = (g * gain).toInt().coerceIn(0, 255)
                    b = (b * gain).toInt().coerceIn(0, 255)
                }
            }

            pixels[i] = a or (r shl 16) or (g shl 8) or b
        }
    }

    /** 按参数预计算一组渲染常量，供批量行带处理复用 */
    data class RenderContext(
        val lut: IntArray,
        val tempShift: Float,
        val highlights: Int,
        val shadows: Int
    ) {
        /** 参数全为默认时可直接跳过像素处理 */
        val isNoOp: Boolean
            get() = tempShift == 0f && highlights == 0 && shadows == 0 &&
                    lut.contentEquals(IDENTITY_LUT)

        companion object {
            val IDENTITY_LUT = IntArray(256) { it }
        }
    }

    fun buildContext(params: EditParams): RenderContext = RenderContext(
        lut = buildBrightnessContrastLut(params.brightness, params.contrast),
        tempShift = temperatureShift(params.temperature),
        highlights = params.highlights,
        shadows = params.shadows
    )

    fun applyContext(pixels: IntArray, count: Int, ctx: RenderContext) {
        if (ctx.isNoOp) return
        applyToPixels(pixels, count, ctx.lut, ctx.tempShift, ctx.highlights, ctx.shadows)
    }

    /** 计算 inSampleSize：2 的幂次，保证长边不超过 maxEdge */
    fun calcInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return 1
        var sample = 1
        while (longEdge / sample / 2 >= maxEdge) sample *= 2
        return min(sample, 64)
    }

    /**
     * 色彩增强（Vibrance）提升系数（PRD 4.3 自然饱和度优先）：
     * 当前饱和度越低的像素提升越强，已饱和像素（含肤色高饱和区）几乎不受影响。
     * @param currentSaturation 像素当前 max-min 饱和度 0-255
     * @return 饱和度乘法系数（≥1）
     */
    fun vibranceBoost(currentSaturation: Int, amountPct: Int): Float {
        if (amountPct <= 0) return 1f
        val headroom = 1f - currentSaturation.coerceIn(0, 255) / 255f
        return 1f + amountPct / 100f * 0.8f * headroom
    }

    /**
     * 清晰度（Clarity）细节增益系数（PRD 4.3 局部对比度）。
     * unsharp: newLum = lum + (lum - blur) * gain
     */
    fun clarityGain(amountPct: Int): Float =
        if (amountPct <= 0) 0f else amountPct / 100f * 1.1f

    /**
     * 降噪混合系数（PRD 4.3/8.5 传统算法兜底）：
     * 强度越高越接近模糊结果；上限 0.85 保留基础纹理，避免塑料感。
     */
    fun denoiseBlend(amountPct: Int): Float =
        if (amountPct <= 0) 0f else (amountPct / 100f * 0.85f).coerceIn(0f, 0.85f)

    /** 降噪单通道混合：out = orig + (blur - orig) * k */
    fun denoiseMix(orig: Int, blurred: Int, k: Float): Int =
        (orig + (blurred - orig) * k).toInt().coerceIn(0, 255)
}

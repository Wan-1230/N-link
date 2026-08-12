package com.nikonlink.app.core.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 滤镜定义与像素管线（PRD 4.5）
 *
 * 纯 Kotlin 实现、无 Android 依赖，可被 JVM 单元测试覆盖。
 * 滤镜以少量语义参数描述（饱和度/色温/对比/双色调），
 * 与基础调节管线复用同一像素循环风格，预览层与全分辨率导出结果一致（PRD 9.2）。
 */

/**
 * 滤镜参数定义。
 * @param saturation 饱和度乘数：0=黑白，1=原色，>1 增饱和
 * @param temperature 色温偏移（复用 EditMath 语义：正=暖）
 * @param contrastFactor 对比度乘法系数（1=不变）
 * @param brightnessOffset 亮度加性偏移
 * @param highlightTint 高光染色（R/G/B 偏移，胶片暖高光用）
 * @param shadowTint 阴影染色（青橙风格的青阴影等）
 */
data class FilterDef(
    val id: String,
    val name: String,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val contrastFactor: Float = 1f,
    val brightnessOffset: Float = 0f,
    val highlightTint: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    val shadowTint: Triple<Float, Float, Float> = Triple(0f, 0f, 0f)
)

/**
 * 内置滤镜库（PRD 4.5: 首版 10 款，含「原图」）。
 * 风格命名贴合摄影用户习惯；数值为初版调校，M3 盲测后可微调。
 */
object FilterLibrary {

    val ORIGINAL = FilterDef("original", "原图")

    val ALL: List<FilterDef> = listOf(
        ORIGINAL,
        FilterDef("bw", "黑白", saturation = 0f, contrastFactor = 1.08f),
        FilterDef(
            "film", "胶片",
            saturation = 0.92f, contrastFactor = 1.05f,
            highlightTint = Triple(6f, 3f, -4f), shadowTint = Triple(-5f, 2f, 6f)
        ),
        FilterDef(
            "japan", "日系",
            saturation = 0.85f, brightnessOffset = 8f, contrastFactor = 0.94f,
            temperature = 4f
        ),
        FilterDef(
            "teal_orange", "青橙",
            saturation = 1.1f, contrastFactor = 1.06f,
            highlightTint = Triple(8f, 3f, -6f), shadowTint = Triple(-8f, 2f, 8f)
        ),
        FilterDef(
            "retro", "复古",
            saturation = 0.8f, contrastFactor = 0.98f, temperature = 12f,
            highlightTint = Triple(7f, 4f, -6f)
        ),
        FilterDef("cool", "冷调", temperature = -14f, saturation = 1.02f),
        FilterDef("warm", "暖调", temperature = 14f, saturation = 1.02f),
        FilterDef("high_contrast", "高对比", contrastFactor = 1.25f, saturation = 1.05f),
        FilterDef("low_sat", "低饱和", saturation = 0.6f, contrastFactor = 1.02f)
    )

    fun byId(id: String): FilterDef? = ALL.firstOrNull { it.id == id }
}

/**
 * 滤镜像素管线。
 * 管线顺序: 亮度/对比度 → 色温 → 饱和度 → 高光/阴影染色。
 * 强度混合: 结果 = 原像素*(1-k) + 滤镜像素*k（k = strength/100，PRD 4.5 滤镜强度）。
 */
object FilterEngine {

    /**
     * 对像素数组应用滤镜（原地修改）。
     * @param strength 0-100；0 时直接返回
     */
    fun apply(pixels: IntArray, count: Int, filter: FilterDef, strength: Int = 100) {
        if (strength <= 0 || filter.id == FilterLibrary.ORIGINAL.id) return
        val k = min(strength, 100) / 100f
        val sat = filter.saturation
        val tempShift = filter.temperature * 0.45f
        val cf = filter.contrastFactor
        val bo = filter.brightnessOffset
        val (htR, htG, htB) = filter.highlightTint
        val (stR, stG, stB) = filter.shadowTint
        val hasTint = htR != 0f || htG != 0f || htB != 0f || stR != 0f || stG != 0f || stB != 0f

        for (i in 0 until count) {
            val px = pixels[i]
            val a = px and 0xFF000000.toInt()
            val srcR = (px shr 16) and 0xFF
            val srcG = (px shr 8) and 0xFF
            val srcB = px and 0xFF

            // 亮度/对比度
            var r = ((srcR - 128) * cf + 128 + bo)
            var g = ((srcG - 128) * cf + 128 + bo)
            var b = ((srcB - 128) * cf + 128 + bo)

            // 色温
            if (tempShift != 0f) {
                r += tempShift
                b -= tempShift
            }

            // 饱和度（Rec.601 亮度轴）
            if (sat != 1f) {
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                r = lum + (r - lum) * sat
                g = lum + (g - lum) * sat
                b = lum + (b - lum) * sat
            }

            // 高光/阴影染色
            if (hasTint) {
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val wh = max(0f, (lum - 128f) / 127f)
                val ws = max(0f, (128f - lum) / 128f)
                r += htR * wh + stR * ws
                g += htG * wh + stG * ws
                b += htB * wh + stB * ws
            }

            val fr = r.roundToInt().coerceIn(0, 255)
            val fg = g.roundToInt().coerceIn(0, 255)
            val fb = b.roundToInt().coerceIn(0, 255)

            // 强度混合
            val outR = (srcR + (fr - srcR) * k).roundToInt()
            val outG = (srcG + (fg - srcG) * k).roundToInt()
            val outB = (srcB + (fb - srcB) * k).roundToInt()

            pixels[i] = a or (outR shl 16) or (outG shl 8) or outB
        }
    }
}

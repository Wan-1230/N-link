package com.nikonlink.app.core.imaging.ai

import com.nikonlink.app.core.imaging.EditParams
import kotlin.math.roundToInt

/**
 * AI 自动增强的图像统计与参数决策（PRD 4.1）
 *
 * 纯 JVM 实现（不含 Android 依赖），可被单元测试覆盖。
 * 规则引擎为模型未就绪时的降级链实现（PRD 8.5）；模型就绪后由 AiEnhancer
 * 优先走 Image-Adaptive LUT，本类继续作为兜底。
 *
 * 决策依据（曝光/白平衡/对比度/高光阴影）参考经典自动色调算法：
 * 直方图百分位定位动态范围，灰世界法估计色偏。
 */
object EnhanceMath {

    /** 图像全局统计（由位图采样得到） */
    data class ImageStats(
        /** 平均亮度 0-255 */
        val meanLum: Float,
        /** 通道均值（灰世界白平衡用） */
        val meanR: Float,
        val meanG: Float,
        val meanB: Float,
        /** 亮度 2% / 98% 百分位（动态范围端点） */
        val p2: Float,
        val p98: Float,
        /** 深暗像素占比（lum < 16） */
        val shadowRatio: Float,
        /** 高光溢出占比（lum > 245） */
        val highlightClip: Float
    )

    /** 目标中灰：自动曝光把平均亮度拉向该值 */
    private const val TARGET_MID = 118f

    /**
     * 根据统计信息生成增强参数建议。
     * 输出幅度刻意保守（各项限幅），保证「一键」结果不失控；
     * 强度滑杆在 UI 层按 0-100 比例缩放（PRD 4.1 强度可调）。
     */
    fun suggest(stats: ImageStats): EditParams {
        // 1) 曝光：向目标中灰收敛；高光已溢出时抑制提亮，避免加剧截幅
        var brightness = (TARGET_MID - stats.meanLum) * 0.9f
        if (stats.highlightClip > 0.02f && brightness > 0) {
            brightness *= 0.4f
        }
        brightness = brightness.coerceIn(-45f, 45f)

        // 2) 对比度：动态范围（p98-p2）偏窄时拉开，宽画幅不加
        val range = stats.p98 - stats.p2
        val contrast = if (range < 170f) {
            ((170f - range) * 0.35f).coerceIn(0f, 50f)
        } else 0f

        // 3) 白平衡：灰世界法，暖色偏(R>B)向冷校正，冷色偏向暖校正
        val cast = stats.meanR - stats.meanB
        val temperature = (-cast * 1.2f).coerceIn(-35f, 35f)

        // 4) 高光回收：溢出占比越大压得越狠（上限 -60）
        val highlights = if (stats.highlightClip > 0.005f) {
            (-stats.highlightClip * 1200f).coerceIn(-60f, 0f)
        } else 0f

        // 5) 阴影提升：死黑占比越大提得越多（上限 +50）
        val shadows = if (stats.shadowRatio > 0.05f) {
            (stats.shadowRatio * 400f).coerceIn(0f, 50f)
        } else 0f

        return EditParams(
            brightness = brightness.roundToInt(),
            contrast = contrast.roundToInt(),
            temperature = temperature.roundToInt(),
            highlights = highlights.roundToInt(),
            shadows = shadows.roundToInt()
        )
    }

    /**
     * 在亮度直方图上取百分位值（线性扫描，256 桶）。
     * @param p 0.0-1.0
     */
    fun percentile(lumHistogram: IntArray, totalPixels: Long, p: Float): Float {
        if (totalPixels <= 0) return 0f
        val target = (totalPixels * p.coerceIn(0f, 1f)).toLong().coerceAtLeast(1L)
        var acc = 0L
        for (v in lumHistogram.indices) {
            acc += lumHistogram[v]
            if (acc >= target) return v.toFloat()
        }
        return 255f
    }

    /** 按强度 0-100 缩放参数（0=原图，100=完整增强，PRD 4.1） */
    fun scale(params: EditParams, strengthPct: Int): EditParams {
        if (strengthPct >= 100) return params
        if (strengthPct <= 0) return EditParams()
        val k = strengthPct / 100f
        return EditParams(
            brightness = (params.brightness * k).roundToInt(),
            contrast = (params.contrast * k).roundToInt(),
            temperature = (params.temperature * k).roundToInt(),
            highlights = (params.highlights * k).roundToInt(),
            shadows = (params.shadows * k).roundToInt()
        )
    }
}

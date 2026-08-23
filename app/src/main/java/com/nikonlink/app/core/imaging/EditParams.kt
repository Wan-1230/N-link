package com.nikonlink.app.core.imaging

/**
 * 修图参数集（PRD 4.8 参数化编辑）
 *
 * 所有效果以参数描述，预览层实时合成，导出时才对全分辨率执行一次完整管线。
 * M1 阶段仅含基础调节五项（PRD 4.3），AI 增强/场景/滤镜参数在 M2/M3 扩展。
 */
data class EditParams(
    /** 亮度 -100..100，0 为原图 */
    val brightness: Int = 0,
    /** 对比度 -100..100，0 为原图 */
    val contrast: Int = 0,
    /** 色温 -100..100，负=偏冷（蓝），正=偏暖（黄） */
    val temperature: Int = 0,
    /** 高光 -100..100，负=压暗高光，正=提亮高光 */
    val highlights: Int = 0,
    /** 阴影 -100..100，负=压暗阴影，正=提亮阴影 */
    val shadows: Int = 0,
    /** 清晰度（Clarity 式局部对比度，PRD 4.3）0-100，默认 0 */
    val clarity: Int = 0,
    /** 色彩增强（自然饱和度优先，保护肤色，PRD 4.3）0-100，默认 0 */
    val vibrance: Int = 0,
    /** 降噪（模型档为 NAFNet，未就绪时传统算法兜底，PRD 4.3/8.5）0-100，默认 0 */
    val denoise: Int = 0
) {
    val isDefault: Boolean
        get() = brightness == 0 && contrast == 0 && temperature == 0 &&
                highlights == 0 && shadows == 0 && clarity == 0 && vibrance == 0 &&
                denoise == 0

    companion object {
        const val RANGE_MIN = -100
        const val RANGE_MAX = 100
        const val RANGE_MIN_POS = 0

        fun clamp(value: Int): Int = value.coerceIn(RANGE_MIN, RANGE_MAX)

        fun clampPositive(value: Int): Int = value.coerceIn(RANGE_MIN_POS, RANGE_MAX)
    }
}

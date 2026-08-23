package com.nikonlink.app.core.imaging.ai

import com.nikonlink.app.core.imaging.EditParams

/**
 * 场景优化策略（PRD 4.2: 人像优化 / 风光优化）
 *
 * 纯 JVM 实现、可被单元测试覆盖。
 * 人脸分区模型（SCRFD/BiSeNet）发布前，按 PRD 8.5 降级链采用全局近似策略，
 * 并在 note 中如实告知用户（PRD 4.2: 未检测到人脸时允许应用全局策略）。
 * 模型就绪后由 AiEnhancer 优先走人脸分区渲染。
 */
object SceneOptimizer {

    data class SceneResult(
        val params: EditParams,
        val note: String
    )

    /**
     * 人像优化（全局近似）：轻提亮 + 柔和对比 + 微暖 + 低强度色彩增强，
     * 避免磨皮感与肤色过饱和（PRD 4.2 画质向定位）。
     */
    fun portrait(stats: EnhanceMath.ImageStats): SceneResult {
        // 面部曝光补偿近似：欠曝时轻提亮，过曝不动
        val brightness = ((115f - stats.meanLum) * 0.5f).coerceIn(0f, 25f)
        // 死黑多时轻提阴影，保留立体感
        val shadows = (stats.shadowRatio * 200f).coerceIn(0f, 15f)
        // 高光溢出时回收，保护面部高光
        val highlights = if (stats.highlightClip > 0.01f) {
            (-stats.highlightClip * 800f).coerceIn(-30f, 0f)
        } else 0f

        return SceneResult(
            params = EditParams(
                brightness = brightness.toInt(),
                contrast = -6,          // 柔化对比，降低皮肤瑕疵观感
                temperature = 4,        // 微暖肤色
                highlights = highlights.toInt(),
                shadows = shadows.toInt(),
                clarity = 6,            // 轻度发丝/眼部清晰度
                vibrance = 16           // 低强度色彩增强，保护肤色
            ),
            note = "人脸检测模型未加载，已应用全局人像策略"
        )
    }

    /**
     * 风光优化：通透度（清晰度 + 对比）+ 色彩增强 + 暗部细节恢复（PRD 4.2）。
     */
    fun landscape(stats: EnhanceMath.ImageStats): SceneResult {
        // 暗部细节恢复：死黑占比越高提得越多
        val shadows = (stats.shadowRatio * 250f).coerceIn(0f, 20f)
        // 天空过曝回收
        val highlights = if (stats.highlightClip > 0.005f) {
            (-stats.highlightClip * 1000f).coerceIn(-35f, 0f)
        } else 0f
        // 动态范围宽时不加对比，避免截幅
        val range = stats.p98 - stats.p2
        val contrast = if (range < 180f) ((180f - range) * 0.15f).coerceIn(0f, 15f) else 0f

        return SceneResult(
            params = EditParams(
                contrast = contrast.toInt(),
                temperature = -3,       // 轻冷调提升通透感
                highlights = highlights.toInt(),
                shadows = shadows.toInt(),
                clarity = 24,           // 通透度核心
                vibrance = 28           // 天空/植被色彩增强
            ),
            note = "风光优化：通透度 + 暗部细节 + 色彩增强"
        )
    }
}

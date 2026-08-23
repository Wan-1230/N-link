package com.nikonlink.app.core.imaging.ai

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一键修复规则引擎（PRD 4.4）
 *
 * 与「AI 自动增强」的区别（PRD 4.4）：
 * - 自动增强面向「正常照片更好看」，幅度保守；
 * - 一键修复面向「有缺陷的照片先救回来」：欠曝/过曝拉回、灰蒙蒙去雾、白平衡纠偏，
 *   检测到的缺陷会显式告知用户。
 *
 * 纯 JVM 实现，可被单元测试覆盖。模型档（去雾/降噪专用模型）就绪前，
 * 本类即 PRD 8.5 降级链的传统算法实现。
 */
object RepairMath {

    data class RepairResult(
        val params: com.nikonlink.app.core.imaging.EditParams,
        val issues: List<String>
    )

    fun repair(stats: EnhanceMath.ImageStats): RepairResult {
        val issues = mutableListOf<String>()
        var brightness = 0f
        var contrast = 0f
        var temperature = 0f
        var highlights = 0f
        var shadows = 0f

        // 1) 曝光缺陷：欠曝提亮 + 提阴影；过曝压暗 + 收高光
        if (stats.meanLum < 70f) {
            issues += "欠曝"
            brightness = ((95f - stats.meanLum) * 1.1f).coerceIn(0f, 60f)
            shadows = (stats.shadowRatio * 300f).coerceIn(10f, 45f)
        } else if (stats.meanLum > 175f) {
            issues += "过曝"
            brightness = ((140f - stats.meanLum) * 1.1f).coerceIn(-60f, 0f)
            highlights = (-40f - stats.highlightClip * 800f).coerceIn(-70f, -20f)
        }

        // 2) 灰蒙蒙（去雾近似）：动态范围过窄且整体居中 → 拉对比 + 压黑位
        val range = stats.p98 - stats.p2
        if (range < 100f && stats.meanLum in 60f..200f) {
            issues += "画面发灰"
            contrast = ((100f - range) * 0.5f).coerceIn(20f, 55f)
            shadows = minOf(shadows, -10f)
        }

        // 3) 白平衡纠偏（灰世界估计，阈值高于自动增强，只救明显偏色）
        val cast = stats.meanR - stats.meanB
        if (abs(cast) > 25f) {
            issues += if (cast > 0) "偏暖色" else "偏冷色"
            temperature = (-cast * 1.2f).coerceIn(-45f, 45f)
        }

        if (issues.isEmpty()) {
            issues += "未检测到明显缺陷"
        }

        return RepairResult(
            params = com.nikonlink.app.core.imaging.EditParams(
                brightness = brightness.roundToInt(),
                contrast = contrast.roundToInt(),
                temperature = temperature.roundToInt(),
                highlights = highlights.roundToInt(),
                shadows = shadows.roundToInt()
            ),
            issues = issues
        )
    }
}

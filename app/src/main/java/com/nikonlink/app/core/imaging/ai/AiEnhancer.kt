package com.nikonlink.app.core.imaging.ai

import android.graphics.Bitmap
import com.nikonlink.app.core.imaging.EditParams
import com.nikonlink.app.core.imaging.model.ModelCapability
import com.nikonlink.app.core.imaging.model.ModelRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * AI 自动增强引擎（PRD 4.1 + 8.5 降级链）
 *
 * 策略路由：
 * 1. auto_enhance 模型（Image-Adaptive LUT）已就绪 → 走模型推理
 * 2. 模型未就绪/推理失败 → 规则引擎（EnhanceMath，传统 CV 算法）
 * 两条路径产出统一的 EnhanceSuggestion，UI 层无感知差异。
 *
 * 幂等性（PRD 4.1）：相同输入必得相同输出（规则引擎为确定性纯函数）。
 * 日志来源: AiEnhancer 标签输出策略选择与耗时。
 */
@Singleton
class AiEnhancer @Inject constructor(
    private val modelRegistry: ModelRegistry
) {

    companion object {
        private const val TAG = "AiEnhancer"
    }

    /** 增强结果来源（日志与后续埋点用，UI 不展示） */
    enum class EnhanceSource { MODEL, RULES }

    /** 一键策略类型（PRD 4.4: 自动增强与一键修复为不同策略集；PRD 4.2: 场景优化） */
    enum class EnhanceKind { ENHANCE, REPAIR, PORTRAIT, LANDSCAPE }

    data class EnhanceSuggestion(
        val params: EditParams,
        val source: EnhanceSource,
        val kind: EnhanceKind = EnhanceKind.ENHANCE,
        /** 一键修复检测到的缺陷描述（PRD 4.4 交互反馈） */
        val issues: List<String> = emptyList()
    )

    /**
     * 分析图像并给出增强参数建议。
     * @param bitmap 预览层降采样位图（≤1080p，分析耗时可控）
     */
    suspend fun analyze(bitmap: Bitmap): EnhanceSuggestion = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val stats = extractStats(bitmap)

        // 模型插槽：内置 LUT 模型就绪后优先（当前占位清单未发布，走降级链）
        val modelFile = modelRegistry.modelFile(ModelCapability.AUTO_ENHANCE)
        if (modelFile != null) {
            runCatching { analyzeWithModel(modelFile, bitmap) }
                .getOrNull()
                ?.let { suggestion ->
                    Timber.tag(TAG).i(
                        "Enhance via MODEL in ${System.currentTimeMillis() - start}ms"
                    )
                    return@withContext suggestion
                }
        }

        // 降级链：规则引擎
        val suggestion = EnhanceSuggestion(EnhanceMath.suggest(stats), EnhanceSource.RULES)
        Timber.tag(TAG).i("Enhance via RULES in ${System.currentTimeMillis() - start}ms: ${suggestion.params}")
        suggestion
    }

    /**
     * 一键修复（PRD 4.4）：检测欠曝/过曝/发灰/偏色等缺陷并给出修复参数。
     * 去雾/降噪专用模型就绪前，规则引擎即降级链实现。
     */
    suspend fun analyzeRepair(bitmap: Bitmap): EnhanceSuggestion = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val stats = extractStats(bitmap)
        val result = RepairMath.repair(stats)
        Timber.tag(TAG).i(
            "Repair via RULES in ${System.currentTimeMillis() - start}ms, " +
                "issues=${result.issues}, params=${result.params}"
        )
        EnhanceSuggestion(
            params = result.params,
            source = EnhanceSource.RULES,
            kind = EnhanceKind.REPAIR,
            issues = result.issues
        )
    }

    /**
     * 场景优化（PRD 4.2）：人像/风光策略。
     * 人脸分区模型未就绪时走全局近似策略（note 中如实告知）。
     */
    suspend fun analyzeScene(
        bitmap: Bitmap,
        kind: EnhanceKind
    ): EnhanceSuggestion = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val stats = extractStats(bitmap)
        val result = when (kind) {
            EnhanceKind.PORTRAIT -> SceneOptimizer.portrait(stats)
            EnhanceKind.LANDSCAPE -> SceneOptimizer.landscape(stats)
            else -> throw IllegalArgumentException("Unsupported scene kind: $kind")
        }
        Timber.tag(TAG).i(
            "Scene($kind) via RULES in ${System.currentTimeMillis() - start}ms, params=${result.params}"
        )
        EnhanceSuggestion(
            params = result.params,
            source = EnhanceSource.RULES,
            kind = kind,
            issues = listOf(result.note)
        )
    }

    /**
     * 模型推理路径（占位实现）。
     * Image-Adaptive LUT 模型的输入输出契约（输入缩略图 → 输出 3D LUT 系数）
     * 待模型训练/转换完成后在此接线；接线前返回 null 触发规则降级。
     */
    private fun analyzeWithModel(modelFile: java.io.File, bitmap: Bitmap): EnhanceSuggestion? {
        // TODO(PRD M2 后续): TfLiteRuntime.load(modelFile) → 推理 → LUT 系数转 EditParams
        Timber.tag(TAG).d("Model present but inference contract pending: ${modelFile.name}")
        return null
    }

    /** 位图统计采样：全像素单次遍历构建亮度直方图与通道均值 */
    private fun extractStats(bitmap: Bitmap): EnhanceMath.ImageStats {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val hist = IntArray(256)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var sumLum = 0L
        var shadowCount = 0L
        var clipCount = 0L

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val lum = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            hist[lum]++
            sumR += r
            sumG += g
            sumB += b
            sumLum += lum
            if (lum < 16) shadowCount++
            if (lum > 245) clipCount++
        }

        val total = pixels.size.toLong()
        return EnhanceMath.ImageStats(
            meanLum = sumLum.toFloat() / total,
            meanR = sumR.toFloat() / total,
            meanG = sumG.toFloat() / total,
            meanB = sumB.toFloat() / total,
            p2 = EnhanceMath.percentile(hist, total, 0.02f),
            p98 = EnhanceMath.percentile(hist, total, 0.98f),
            shadowRatio = shadowCount.toFloat() / total,
            highlightClip = clipCount.toFloat() / total
        )
    }
}

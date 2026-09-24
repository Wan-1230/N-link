package com.nikonlink.app.camera.liveview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 实时亮度直方图（优化项 4：遥控拍摄界面直方图开关）。
 *
 * 设计取舍：
 * - **只画亮度直方图**，不画 RGB 三通道：需求是「判断亮度分布」，单通道亮度图
 *   正是相机的标准做法；同时本项目 UI 是严格黑白体系，画彩色通道会破坏风格一致性。
 * - **降采样后再统计**：取景帧可能是 640×424（27 万像素），逐像素统计再叠加
 *   15fps 的帧率会明显占用主线程。这里先把帧缩到一张 96px 宽的复用位图，
 *   统计约 6500 个采样点，误差可忽略、耗时恒定在亚毫秒级。
 * - **关闭时零开销**：[setFrame] 在视图不可见时由调用方跳过；即便调用，
 *   也只做一次可见性判断即返回。
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 直方图柱数：256 级亮度按 4 级一柱合并，柱宽足够又不至于太碎 */
        private const val BIN_COUNT = 64

        /** 降采样后的统计宽度；高度按原始比例推，约 6500 个采样点 */
        private const val SAMPLE_WIDTH = 96
    }

    private val binCounts = IntArray(BIN_COUNT)
    private val binHeights = FloatArray(BIN_COUNT)

    /** 降采样取数交给 [FrameSampler]：与波形同一份缓存管理，不再各写一遍 */
    private val sampler = FrameSampler(SAMPLE_WIDTH)

    /** 归一化基准（上一帧的最大柱高），做平滑避免直方图抖动 */
    private var smoothedPeak = 0f

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = (0.60f * 255).toInt()
    }

    /** 主体柱体：白色半透明，与取景浮层（liveview_scrim + 白字）同一套视觉语言 */
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (0.85f * 255).toInt()
    }

    /** 25% / 50% / 75% 参考线：极淡，只提供左右分布的位置感 */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (0.22f * 255).toInt()
        strokeWidth = 1f
    }

    /** 左右溢出行（0 / 255 顶格）用不透明白区分：提示高光溢出与暗部死黑 */
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val panelRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = 6f
        panelRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(panelRect, radius, radius, panelPaint)

        // 三条竖向参考线
        for (i in 1..3) {
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }

        val gap = 1f
        val slot = w / BIN_COUNT
        val barWidth = max(1f, slot - gap)
        for (i in 0 until BIN_COUNT) {
            val ratio = binHeights[i]
            if (ratio <= 0f) continue
            val barH = max(1f, ratio * h)
            val left = i * slot + gap / 2f
            val top = h - barH
            // 首柱（暗部死黑）与末柱（高光溢出）顶格时用实心白强调
            val paint = if ((i == 0 || i == BIN_COUNT - 1) && ratio >= 0.98f) clipPaint else barPaint
            canvas.drawRect(left, top, left + barWidth, h, paint)
        }
    }

    /**
     * 用最新一帧刷新直方图。调用方需保证只在视图可见时调用（关闭时零开销）。
     */
    fun setFrame(bitmap: Bitmap) {
        val pixels = sampler.sample(bitmap) ?: return
        val total = sampler.sampledWidth * sampler.sampledHeight

        binCounts.fill(0)
        var peak = 0
        // 只遍历本帧的有效区：数组是复用的，比本帧大的部分是上一帧的残留像素
        for (i in 0 until total) {
            val c = pixels[i]
            // 亮度口径与伪彩共用 PseudoColorLut.lumaOf（Rec.601，同 JPEG 的 Y 分量）：
            // 两个工具各算各的迟早会出现"直方图说不曝、伪彩说曝"，那种矛盾没法解释也没法修
            val luma = PseudoColorLut.lumaOf(c)
            val bin = luma * BIN_COUNT / 256
            val idx = if (bin >= BIN_COUNT) BIN_COUNT - 1 else bin
            val v = ++binCounts[idx]
            if (v > peak) peak = v
        }

        // 峰值做一阶平滑：直方图会随帧抖动，直接取当帧峰值会导致柱体整体上下跳
        smoothedPeak = if (smoothedPeak <= 0f) peak.toFloat()
        else smoothedPeak * 0.7f + peak * 0.3f
        val denom = max(1f, smoothedPeak)
        for (i in 0 until BIN_COUNT) {
            binHeights[i] = (binCounts[i] / denom).coerceIn(0f, 1f)
        }
        invalidate()
    }

    /** 清空统计（停止监看 / 关闭开关时调用，避免残留上一帧的图形） */
    fun clear() {
        binCounts.fill(0)
        binHeights.fill(0f)
        smoothedPeak = 0f
        invalidate()
    }
}

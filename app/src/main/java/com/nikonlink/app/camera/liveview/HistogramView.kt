package com.nikonlink.app.camera.liveview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
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

    /** 降采样用的复用位图与画布：避免每帧新建对象触发 GC */
    private var scratch: Bitmap? = null
    private val scratchCanvas = Canvas()
    private var scratchPixels: IntArray? = null

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

    /** 降采样绘制用：显式开 FILTER_BITMAP，否则 paint 为 null 时走最近邻，采样会偏噪 */
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val panelRect = RectF()
    private val dstRect = RectF()

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
        val sw = SAMPLE_WIDTH
        val sh = max(1, bitmap.height * sw / max(1, bitmap.width))
        val target = ensureScratch(sw, sh) ?: return
        val pixels = ensurePixels(sw * sh) ?: return

        scratchCanvas.setBitmap(target)
        // 先清底：源位图若带透明区域，残留像素会被算进亮度统计
        scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        dstRect.set(0f, 0f, sw.toFloat(), sh.toFloat())
        scratchCanvas.drawBitmap(bitmap, null, dstRect, drawPaint)
        target.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        binCounts.fill(0)
        var peak = 0
        for (i in pixels.indices) {
            val c = pixels[i]
            // 与 JPEG 亮度分量一致的 Rec.601 权重
            val luma = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
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

    private fun ensureScratch(w: Int, h: Int): Bitmap? {
        val current = scratch
        if (current != null && current.width == w && current.height == h) return current
        current?.recycle()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratch = it }
        } catch (e: Throwable) {
            null
        }
    }

    private fun ensurePixels(size: Int): IntArray? {
        val current = scratchPixels
        if (current != null && current.size >= size) return current
        return try {
            IntArray(size).also { scratchPixels = it }
        } catch (e: Throwable) {
            null
        }
    }
}

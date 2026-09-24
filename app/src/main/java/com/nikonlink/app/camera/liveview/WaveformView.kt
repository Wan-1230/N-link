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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RGB 分量波形（v2.5 FR-13 第二件工具）。
 *
 * 竖轴 = 信号电平（0 在下、255 在上），横轴 = 画面的**列位置**，
 * 三个通道按加法叠色（R+G=黄、G+B=青、三者齐=白）。所以它回答的是直方图答不了的两问：
 * 亮的那一坨在画面哪一边，以及蓝色是不是单独溢了。
 *
 * 与 [HistogramView] 同一套约束：只在开关打开时被喂帧（关掉零开销）、复用位图与像素数组、
 * 面板视觉沿用黑白那套。唯一的例外是叠色本身——**分量**波形拆成灰阶就失去意义了。
 *
 * 采样量：横轴 128 列 × 每列约 85 行 ≈ 1.1 万像素，每像素三次累加。
 * 不是逐像素波形（那样一帧 27 万次 × 3），换来的是恒定亚毫秒级；
 * 波形要判的是"哪一片偏了"，不是纹理，这个取舍站得住。
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 横轴列数 = 采样宽度：一个采样像素正好落进一列 */
        private const val COLUMNS = 128

        /** 竖轴档数，与直方图的 64 柱同一口径，两个面板叠着看不打架 */
        private const val LEVEL_BINS = 64
    }

    private val sampler = FrameSampler(COLUMNS)
    private val waveform = RgbWaveform(COLUMNS, LEVEL_BINS)

    /** 128×64 的小图，绘制时按面板尺寸双线性放大 */
    private var film: Bitmap? = null
    private val filmPixels = IntArray(COLUMNS * LEVEL_BINS)

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = (0.60f * 255).toInt()
    }

    /** 位置参考线（竖）与中灰线（横）：极淡，只给左右/上下的位置感 */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = (0.22f * 255).toInt()
        strokeWidth = 1f
    }

    private val filmPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val panelRect = RectF()
    private val filmRect = RectF()

    /** 用最新一帧刷新。只在开关打开时由调用方喂帧。 */
    fun setFrame(source: Bitmap) {
        val pixels = sampler.sample(source) ?: return
        waveform.clear()
        waveform.accumulate(pixels, sampler.sampledWidth, sampler.sampledHeight)
        paintFilm()
        invalidate()
    }

    /** 清空（关闭开关 / 停止监看时调用），避免重新打开时闪出上一轮的图形 */
    fun clear() {
        waveform.clear()
        filmPixels.fill(0)
        invalidate()
    }

    /**
     * 把累加结果写进 128×64 的加法叠色小图。
     *
     * 每格存成"半透明色"而不是"不透明色"：alpha 取三通道里最强的那个，
     * RGB 按比例归一。这样叠加到底黑面板上的结果正好等于 (ar, ag, ab) 本来的加法色，
     * 而三通道都没有的格子仍然是透明的——不会被画成一块死黑。
     */
    private fun paintFilm() {
        val target = ensureFilm() ?: return
        val peak = max(1, waveform.peak)
        for (column in 0 until COLUMNS) {
            for (bin in 0 until LEVEL_BINS) {
                val ar = intensity(waveform.countOf(RgbWaveform.CHANNEL_RED, column, bin), peak)
                val ag = intensity(waveform.countOf(RgbWaveform.CHANNEL_GREEN, column, bin), peak)
                val ab = intensity(waveform.countOf(RgbWaveform.CHANNEL_BLUE, column, bin), peak)
                val strongest = max(ar, max(ag, ab))
                // 电平 0 在底部：位图行号从上往下数，所以按档号翻转
                val row = LEVEL_BINS - 1 - bin
                val index = row * COLUMNS + column
                if (strongest <= 0f) {
                    filmPixels[index] = 0
                    continue
                }
                val red = (ar / strongest * 255f).toInt().coerceIn(0, 255)
                val green = (ag / strongest * 255f).toInt().coerceIn(0, 255)
                val blue = (ab / strongest * 255f).toInt().coerceIn(0, 255)
                filmPixels[index] = ((strongest * 255f).toInt().coerceIn(1, 255) shl 24) or
                    (red shl 16) or (green shl 8) or blue
            }
        }
        target.setPixels(filmPixels, 0, COLUMNS, 0, 0, COLUMNS, LEVEL_BINS)
    }

    /**
     * 计数 → 不透明度。开方而不是线性：一列里只有零星几个像素落在暗档是常态，
     * 线性映射下它们全是透明，波形就会"看起来没有暗部"——那是假信息。
     */
    private fun intensity(count: Int, peak: Int): Float =
        if (count <= 0) 0f else min(1f, sqrt(count.toFloat() / peak))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        panelRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(panelRect, 6f, 6f, panelPaint)

        for (i in 1..3) {
            val x = w * i / 4f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        // 中灰那一条横线：判断"整体抬了一档还是只有一边"要看这条基线
        canvas.drawLine(0f, h / 2f, w, h / 2f, gridPaint)

        val source = film ?: return
        if (waveform.peak <= 0) return
        filmRect.set(0f, 0f, w, h)
        canvas.drawBitmap(source, null, filmRect, filmPaint)
    }

    private fun ensureFilm(): Bitmap? {
        film?.let { if (!it.isRecycled) return it }
        return try {
            Bitmap.createBitmap(COLUMNS, LEVEL_BINS, Bitmap.Config.ARGB_8888).also { film = it }
        } catch (e: Throwable) {
            null
        }
    }
}

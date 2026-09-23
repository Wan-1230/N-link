package com.nikonlink.app.camera.liveview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import kotlin.math.max

/**
 * 取景器辅助叠加层。
 * 网格线与水平线只绘制在相机画面实际显示区域内，横竖屏下不会跟随黑边拉伸。
 *
 * 伪彩色也画在这一层，而且**必须在网格线之下**：叠加层已经持有画面实际显示矩形
 * （[displayedRect]，含缩放后的 imageMatrix），另开一个 View 就得把这套矩阵再算一遍，
 * 缩放时两边一漂，颜色盖住的就不是同一块画面了。
 */
class ViewfinderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var imageView: ImageView? = null
    private var gridVisible = true
    private var levelVisible = false

    /** 伪彩：开关 + 上一帧算出来的小图。null 表示这一帧还没有可画的色块 */
    private var toneVisible = false
    private var toneSample: Bitmap? = null
    private var tonePixels: IntArray? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 150
        strokeWidth = dp(0.9f)
        style = Paint.Style.STROKE
    }

    /**
     * 伪彩盖在画面上但不吃掉了画面：结构感还要靠底下的实拍像素，
     * 所以是"半透上色"而不是"换一张图"。
     */
    private val tonePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        alpha = TONE_ALPHA
    }

    /** 降采样用的复用画布与目标矩形：每帧新建位图会被 GC 拖成掉帧 */
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val toneCanvas = Canvas()
    private val toneDst = RectF()

    fun setImageSource(source: ImageView?) {
        imageView = source
        invalidate()
    }

    fun setGridVisible(visible: Boolean) {
        gridVisible = visible
        invalidate()
    }

    fun setLevelVisible(visible: Boolean) {
        levelVisible = visible
        invalidate()
    }

    /**
     * 伪彩开关。关掉时把缓存的小图一起清掉：叠加层留着不清，关掉的下一帧仍会在
     * 画面上看到一层旧色块，那比"没有伪彩"更误导人。
     */
    fun setToneVisible(visible: Boolean) {
        if (toneVisible == visible) return
        toneVisible = visible
        if (!visible) {
            toneSample?.recycle()
            toneSample = null
        }
        invalidate()
    }

    /**
     * 用最新一帧刷新伪彩。调用方保证只在开关打开时调用（关掉时零开销，与直方图同一约定）。
     *
     * 先缩到 [TONE_SAMPLE_WIDTH] 宽再上色：监看帧 640×424 逐像素是 27 万次映射，
     * 而伪彩读的是**块**不是纹理，一万像素足够分档；缩放交给绘制时的双线性。
     */
    fun setToneFrame(source: Bitmap) {
        if (!toneVisible) return
        val sw = TONE_SAMPLE_WIDTH
        val sh = max(1, source.height * sw / max(1, source.width))
        val sample = ensureSample(sw, sh) ?: return
        val pixels = ensurePixels(sw * sh) ?: return

        toneCanvas.setBitmap(sample)
        // 先清底：源帧带透明区域时残留像素会被算进上一帧的颜色里
        toneCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        toneDst.set(0f, 0f, sw.toFloat(), sh.toFloat())
        toneCanvas.drawBitmap(source, null, toneDst, samplePaint)

        sample.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        val palette = PseudoColorLut.palette
        for (i in pixels.indices) {
            val c = pixels[i]
            // 保留源像素的 alpha：黑边区域不该被涂上"死黑档"的颜色
            val alpha = c and 0xFF000000.toInt()
            pixels[i] = alpha or (palette[PseudoColorLut.lumaOf(c) and 0xFF] and 0x00FFFFFF)
        }
        sample.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = displayedRect() ?: return
        // 伪彩先画：它是"给画面染色"，网格与水平仪是"在画面之上量东西"，顺序反了会被色块盖掉
        if (toneVisible) toneSample?.let { canvas.drawBitmap(it, null, rect, tonePaint) }
        if (gridVisible) drawThirdsGrid(canvas, rect)
        if (levelVisible) drawLevel(canvas, rect)
    }

    private fun displayedRect(): RectF? {
        val source = imageView
        val drawable = source?.drawable ?: return null
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) return null

        val imageRect = RectF(0f, 0f, intrinsicWidth.toFloat(), intrinsicHeight.toFloat())
        val matrix = Matrix(source.imageMatrix)
        matrix.mapRect(imageRect)
        return if (imageRect.width() > 0 && imageRect.height() > 0) imageRect else null
    }

    private fun drawThirdsGrid(canvas: Canvas, rect: RectF) {
        val thirdWidth = rect.width() / 3f
        val thirdHeight = rect.height() / 3f
        for (i in 1..2) {
            val x = rect.left + thirdWidth * i
            val y = rect.top + thirdHeight * i
            canvas.drawLine(x, rect.top, x, rect.bottom, linePaint)
            canvas.drawLine(rect.left, y, rect.right, y, linePaint)
        }
    }

    private fun drawLevel(canvas: Canvas, rect: RectF) {
        val centerY = rect.centerY()
        val half = rect.width() * 0.28f
        canvas.drawLine(rect.centerX() - half, centerY, rect.centerX() + half, centerY, linePaint)
        canvas.drawLine(rect.centerX(), centerY - dp(6f), rect.centerX(), centerY + dp(6f), linePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun ensureSample(w: Int, h: Int): Bitmap? {
        val current = toneSample
        if (current != null && current.width == w && current.height == h && !current.isRecycled) return current
        current?.recycle()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { toneSample = it }
        } catch (e: Throwable) {
            toneSample = null
            null
        }
    }

    private fun ensurePixels(size: Int): IntArray? {
        val current = tonePixels
        if (current != null && current.size >= size) return current
        return try {
            IntArray(size).also { tonePixels = it }
        } catch (e: Throwable) {
            null
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }

    companion object {
        /**
         * 半透上色的不透明度。再高就开始盖掉画面细节（伪彩的用处是"看出哪块偏了"，
         * 不是替掉取景），再低则两端分不出颜色。0.55 是在 640×424 的监看帧上取的。
         */
        private const val TONE_ALPHA = 140

        /** 上色采样宽度：640 宽降到 128 是 1/25 的像素量，分档边界肉眼不受影响 */
        private const val TONE_SAMPLE_WIDTH = 128
    }
}

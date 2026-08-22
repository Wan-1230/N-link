package com.nikonlink.app.camera.liveview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView

/**
 * 取景器辅助叠加层。
 * 网格线与水平线只绘制在相机画面实际显示区域内，横竖屏下不会跟随黑边拉伸。
 */
class ViewfinderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var imageView: ImageView? = null
    private var gridVisible = true
    private var levelVisible = false

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 150
        strokeWidth = dp(0.9f)
        style = Paint.Style.STROKE
    }

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = displayedRect() ?: return
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidate()
    }
}

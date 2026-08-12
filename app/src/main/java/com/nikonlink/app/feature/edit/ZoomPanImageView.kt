package com.nikonlink.app.feature.edit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 编辑器画布（PRD 5.3: 双指缩放 / 双击放大 / 长按对比原图）
 *
 * fitCenter 为基准矩阵，叠加缩放/平移矩阵；平移自动回弹约束在可视范围内。
 * 长按开始/结束通过回调暴露给 Activity 做「长按看原图」。
 */
class ZoomPanImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_SCALE_FACTOR = 6f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val displayMatrix = Matrix()
    private val drawRect = RectF()

    private var baseScale = 1f
    private var longPressActive = false

    /** 上一张图的固有尺寸：同尺寸换图（原图/效果图对比）时保持缩放位置（PRD 4.6） */
    private var prevIntrinsicW = 0
    private var prevIntrinsicH = 0

    /** 长按开始（显示原图） */
    var onLongPressStart: (() -> Unit)? = null

    /** 长按结束（恢复效果图） */
    var onLongPressEnd: (() -> Unit)? = null

    /**
     * 当前显示矩阵副本（PRD 4.6 缩放同步：分屏对比层复用同一矩阵，
     * 保证原图/效果图在相同缩放与平移位置下对比）
     */
    fun currentMatrix(): Matrix = Matrix(displayMatrix)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                var factor = detector.scaleFactor
                val newScale = (current * factor).coerceIn(1f, MAX_SCALE_FACTOR)
                factor = newScale / current
                suppMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                updateDisplayMatrix()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current > 1.01f) {
                    // 回到适配缩放
                    suppMatrix.reset()
                } else {
                    suppMatrix.postScale(DOUBLE_TAP_SCALE, DOUBLE_TAP_SCALE, e.x, e.y)
                }
                updateDisplayMatrix()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                longPressActive = true
                onLongPressStart?.invoke()
            }
        }
    )

    private var lastX = 0f
    private var lastY = 0f

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetMatrices()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrices()
    }

    private fun resetMatrices() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val dW = d.intrinsicWidth.toFloat()
        val dH = d.intrinsicHeight.toFloat()
        if (dW <= 0 || dH <= 0) return

        val fitScale = min(width / dW, height / dH)
        val dx = (width - dW * fitScale) / 2f
        val dy = (height - dH * fitScale) / 2f
        baseMatrix.reset()
        baseMatrix.postScale(fitScale, fitScale)
        baseMatrix.postTranslate(dx, dy)
        baseScale = fitScale
        // 同尺寸换图（长按对比原图）时保留当前缩放/平移；尺寸变化才重置
        if (prevIntrinsicW != dW.toInt() || prevIntrinsicH != dH.toInt()) {
            suppMatrix.reset()
        }
        prevIntrinsicW = dW.toInt()
        prevIntrinsicH = dH.toInt()
        updateDisplayMatrix()
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        suppMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun updateDisplayMatrix() {
        displayMatrix.set(baseMatrix)
        displayMatrix.postConcat(suppMatrix)
        imageMatrix = displayMatrix

        // 平移约束：图像不能拖出可视范围
        val d = drawable ?: return
        drawRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        displayMatrix.mapRect(drawRect)

        var dx = 0f
        var dy = 0f
        dx += if (drawRect.width() <= width) {
            (width - drawRect.width()) / 2f - drawRect.left
        } else {
            when {
                drawRect.left > 0 -> -drawRect.left
                drawRect.right < width -> width - drawRect.right
                else -> 0f
            }
        }
        dy += if (drawRect.height() <= height) {
            (height - drawRect.height()) / 2f - drawRect.top
        } else {
            when {
                drawRect.top > 0 -> -drawRect.top
                drawRect.bottom < height -> height - drawRect.bottom
                else -> 0f
            }
        }
        if (dx != 0f || dy != 0f) {
            suppMatrix.postTranslate(dx, dy)
            displayMatrix.set(baseMatrix)
            displayMatrix.postConcat(suppMatrix)
            imageMatrix = displayMatrix
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > 1 || abs(dy) > 1) {
                        suppMatrix.postTranslate(dx, dy)
                        updateDisplayMatrix()
                    }
                    lastX = event.x
                    lastY = event.y
                } else {
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (longPressActive) {
                    longPressActive = false
                    onLongPressEnd?.invoke()
                }
            }
        }
        return true
    }
}

package com.nikonlink.app.camera.liveview

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.min

/**
 * 监看画面缩放控制器（模块 1：双指缩放与网格联动重构）。
 *
 * 设计要点：
 * - **唯一变换源 = ImageView.imageMatrix**（scaleType=MATRIX）。旧实现把缩放做在 View 层
 *   scaleX/scaleY（中心锚点、无平移），网格层 ViewfinderOverlayView 读的是 imageMatrix
 *   （恒为单位阵），导致网格与放大后的画面错位；迁移到 imageMatrix 后网格、对焦坐标
 *   （FocusTapMapper）自动读取同一矩阵，像素级同步。
 * - 双指中点锚点缩放：ScaleGestureDetector 焦点在缩放前后保持不动（不跟手问题的根因
 *   是旧实现只有围绕视图中心的等比缩放）。
 * - 平移边界约束 + 回弹：放大后图像必须持续覆盖视口（无黑边外露）；手指越界松手后
 *   ValueAnimator 回弹到边界（180ms）。
 * - 双击复位 1.0（单指对焦点击改由 onSingleTapConfirmed 触发，与双击手势互不误触）。
 *
 * 矩阵构成：imageMatrix = Base(fitCenter) ∘ [缩放 z 绕视口锚点] ∘ [平移 t]
 * base 矩阵随每帧位图尺寸重算（相机流分辨率可能动态变化）。
 */
class LiveViewZoomController(
    private val imageView: ImageView,
    /** 单击确认（对焦点击）；双击被复位手势消费，不会误触发 */
    private val onSingleTapConfirmed: ((x: Float, y: Float) -> Unit)?
) {

    companion object {
        const val MIN_ZOOM = 1.0f
        const val MAX_ZOOM = 5.0f
        private const val RESET_DURATION_MS = 200L
        private const val EDGE_ANIM_MS = 180L
    }

    private var zoom = MIN_ZOOM
    private var panX = 0f
    private var panY = 0f

    /** 当前帧图像的 fitCenter 基准矩形（视口坐标） */
    private var baseRect = RectF()
    private var viewW = 0f
    private var viewH = 0f

    private var resetAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(
        imageView.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                resetAnimator?.cancel()
                val newZoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                applyZoomAround(newZoom, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        imageView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateReset()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapConfirmed?.invoke(e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // 双指捏合期间 ScaleGestureDetector 主导，跳过单指平移
                if (scaleDetector.isInProgress) return true
                if (zoom <= MIN_ZOOM) return true
                resetAnimator?.cancel()
                panX -= distanceX
                panY -= distanceY
                clampPan()
                applyMatrix()
                return true
            }
        }
    )

    init {
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val newW = r - l
            val newH = b - t
            if (newW != viewW.toInt() || newH != viewH.toInt()) {
                // 横竖屏切换 / 分屏尺寸变化：重算基准并收敛到合法区域
                viewW = newW.toFloat()
                viewH = newH.toFloat()
                recomputeBase()
                clampPan()
                applyMatrix()
            }
        }
    }

    /** 每帧位图更新后调用：分辨率变化时重算基准矩阵并保持视觉连续 */
    fun onFrameChanged() {
        if (imageView.drawable == null) return
        recomputeBase()
        clampPan()
        applyMatrix()
    }

    /** 手指事件入口；返回 true 表示已被手势系统消费 */
    fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            springBackIfNeeded()
        }
        return true
    }

    /** 菜单「画面放大 / 缩小」入口：围绕视口中心缩放一档 */
    fun zoomBy(factor: Float) {
        resetAnimator?.cancel()
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        applyZoomAround(newZoom, viewW / 2f, viewH / 2f)
        applyMatrix()
    }

    /** 菜单「重置缩放」/ 双击复位 */
    fun reset() = animateReset()

    fun currentZoom(): Float = zoom

    // ---------------- 内部实现 ----------------

    private fun recomputeBase() {
        val drawable = imageView.drawable ?: return
        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        if (iw <= 0 || ih <= 0 || viewW <= 0 || viewH <= 0) return
        val matrix = Matrix()
        val scale = min(viewW / iw, viewH / ih)
        matrix.setScale(scale, scale)
        matrix.postTranslate((viewW - iw * scale) / 2f, (viewH - ih * scale) / 2f)
        baseRect = RectF(0f, 0f, iw, ih).also { matrix.mapRect(it) }
    }

    /**
     * 以屏幕锚点 (fx, fy) 为不动点缩放到 newZoom：
     * 新 pan = 锚点 - (锚点 - 视口中心) / 旧zoom * 新zoom（pan 以视口中心为原点）。
     */
    private fun applyZoomAround(newZoom: Float, fx: Float, fy: Float) {
        val cx = viewW / 2f
        val cy = viewH / 2f
        val ratio = newZoom / zoom
        panX = fx - cx - (fx - cx - panX) * ratio
        panY = fy - cy - (fy - cy - panY) * ratio
        zoom = newZoom
        clampPan()
    }

    /** 平移边界：放大后的图像矩形必须覆盖视口（z=1 时强制定回 fitCenter） */
    private fun clampPan() {
        if (baseRect.isEmpty) return
        if (zoom <= MIN_ZOOM + 0.001f) {
            panX = 0f
            panY = 0f
            return
        }
        val cx = viewW / 2f
        val cy = viewH / 2f
        val halfW = baseRect.width() * zoom / 2f
        val halfH = baseRect.height() * zoom / 2f
        // 图像中心 = 视口中心 + pan；覆盖视口 ⇔ |pan| ≤ (放大后的半边长 − 视口半边长)
        val maxPanX = (halfW - cx).coerceAtLeast(0f)
        val maxPanY = (halfH - cy).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    private fun applyMatrix() {
        val drawable = imageView.drawable ?: return
        val iw = drawable.intrinsicWidth.toFloat()
        val ih = drawable.intrinsicHeight.toFloat()
        if (iw <= 0 || ih <= 0 || baseRect.isEmpty) {
            imageView.invalidate()
            return
        }
        val baseScale = if (iw > 0) baseRect.width() / iw else 1f
        val matrix = Matrix()
        matrix.setScale(baseScale, baseScale)
        matrix.postTranslate(baseRect.left, baseRect.top)
        // 合成最终变换：绕视口中心缩放 zoom，再平移 pan
        matrix.postScale(zoom, zoom, viewW / 2f, viewH / 2f)
        matrix.postTranslate(panX, panY)
        imageView.imageMatrix = matrix
        imageView.invalidate()
    }

    /** 越界回弹：pan 已在 clampPan 内约束，这里处理缩放被钳位后的平滑收敛 */
    private fun springBackIfNeeded() {
        if (zoom < MIN_ZOOM + 0.001f && (panX != 0f || panY != 0f)) {
            animateReset()
        }
    }

    private fun animateReset() {
        resetAnimator?.cancel()
        val fromZoom = zoom
        val fromX = panX
        val fromY = panY
        resetAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RESET_DURATION_MS
            addUpdateListener { animator ->
                val t = animator.animatedFraction
                zoom = fromZoom + (MIN_ZOOM - fromZoom) * t
                panX = fromX * (1 - t)
                panY = fromY * (1 - t)
                applyMatrix()
            }
            start()
        }
    }
}

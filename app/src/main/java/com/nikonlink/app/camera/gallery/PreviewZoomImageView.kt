package com.nikonlink.app.camera.gallery

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 大图浏览页的可缩放视图（v2.6.4，v2.6.5 重写定位与手感）。
 *
 * ## 为什么需要「基准矩阵」
 *
 * v2.6.4 的第一版**只做了一件事**：把 `scaleType` 设成 `MATRIX`，然后用一个**单位矩阵**
 * 起步。`MATRIX` 的含义是"完全按你给的矩阵画"，单位矩阵 = 原始像素尺寸、原点在左上角 ——
 * 于是照片既不居中也不自适应，缩略图大小的图缩在左上角，大图则被右下裁掉。
 * 这正是「点击照片进入放大浏览时照片显示在左上角」的成因。
 *
 * 现在分两层：
 * - [baseMatrix]：图片 → 视图的**适配矩阵**（contain 语义，等比缩放到能完整放进视图并居中），
 *   在 `onSizeChanged` / `setImageDrawable` 时重算。它承担"居中 + 自适应屏幕尺寸"。
 * - 用户手势只在这之上叠 **缩放 [scale] + 平移 [dx]dy**，且**围绕视图中心**缩放
 *   （`postScale(scale, scale, cx, cy)`），所以任何倍率下图形天然保持居中，
 *   平移量只需按越界多少夹紧即可，不必反解矩阵。
 *
 * ## 手感相关的三个点（对应「双指缩放体验不佳」）
 * 1. **单帧倍率夹紧**：`ScaleGestureDetector` 在手指刚并拢/抖动时会给出很夸张的瞬时
 *    `scaleFactor`，直接乘上去会"跳一下"。这里把单帧因子夹在 [MIN_FRAME, MAX_FRAME]，
 *    手指抖一下最多推进 18%，长距离捏合仍然连续跟手。
 * 2. **平移要过 touchSlop 才启动**：否则双指刚落下时的轻微位移会被当成拖动，
 *    画面在缩放前先晃一下。
 * 3. **抬起一指不跳**：`ACTION_POINTER_UP` 时把锚点重置到剩下那根手指的位置，
 *    而不是继续用两指的中点 —— 否则会看到画面"弹"一下。
 *
 * ## 与 ViewPager2 共存
 * 放大态通过 [onZoomChanged] 通知外部关掉 ViewPager2 的横向滑动，未放大时把事件让给翻页。
 */
class PreviewZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** 是否处于放大态（供外部关掉 ViewPager2 的横向滑动） */
    var onZoomChanged: ((zoomed: Boolean) -> Unit)? = null

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        /** 双击放大的目标倍率 */
        const val DOUBLE_TAP_SCALE = 2.5f
        /** 单帧缩放因子的上下限（抑制手指抖动引起的大跳） */
        const val MIN_FRAME_FACTOR = 0.82f
        const val MAX_FRAME_FACTOR = 1.18f
        const val ZOOM_EPS = 0.001f
    }

    /** 图片 → 视图的适配矩阵（contain + 居中） */
    private var baseMatrix: Matrix? = null

    /** 适配后（scale = 1）图片在视图中的矩形，用于算平移边界 */
    private val fitRect = RectF()

    private val matrix = Matrix()
    private val savedMatrix = Matrix()

    /** 用户缩放倍率，1 = 适配尺寸 */
    private var scale = MIN_SCALE

    /** 相对居中位置的平移量（视图坐标系 px） */
    private var dx = 0f
    private var dy = 0f

    /** 单指平移锚点 */
    private val start = PointF()
    private var panning = false
    private var lastPointerCount = 0
    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                panning = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val raw = detector.scaleFactor
                if (raw.isNaN() || raw <= 0f) return true
                val factor = raw.coerceIn(MIN_FRAME_FACTOR, MAX_FRAME_FACTOR)
                val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (next == scale) return true

                // 以捏合焦点为不动点缩放：k 为本次倍率变化比
                val k = next / scale
                val cx = width / 2f
                val cy = height / 2f
                dx = detector.focusX - k * (detector.focusX - dx - cx) - cx
                dy = detector.focusY - k * (detector.focusY - dy - cy) - cy
                scale = next
                rebuild()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // 越界松手回到合法倍率：低于 1x 直接回中，高于上限压回上限
                if (scale < MIN_SCALE) {
                    scale = MIN_SCALE
                    dx = 0f
                    dy = 0f
                    rebuild()
                } else if (scale > MAX_SCALE) {
                    scale = MAX_SCALE
                    rebuild()
                }
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isZoomed) {
                    scale = MIN_SCALE
                    dx = 0f
                    dy = 0f
                } else {
                    val next = DOUBLE_TAP_SCALE
                    val k = next / scale
                    val cx = width / 2f
                    val cy = height / 2f
                    dx = e.x - k * (e.x - dx - cx) - cx
                    dy = e.y - k * (e.y - dy - cy) - cy
                    scale = next
                }
                rebuild()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                performClick()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        // 换图（翻页）必须回到未缩放态并重算适配矩阵，否则上一张的缩放/平移会带到下一张
        scale = MIN_SCALE
        dx = 0f
        dy = 0f
        recomputeBase()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化（旋转 / 分屏 / 首次布局）必须重算适配矩阵：
        // 这一步就是"自适应屏幕尺寸"的实现点
        recomputeBase()
    }

    /**
     * 重算 [baseMatrix]：把图片等比缩放到**完整放进视图**（contain）并居中。
     *
     * 用 `min` 而不是 `max`：宁可留黑边也不要裁掉照片内容 —— 浏览页的首要职责是看全。
     * 尺寸取 `intrinsicWidth/Height`（相机图已解码为 Bitmap，二者即有值）。
     */
    private fun recomputeBase() {
        val d = drawable
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (d == null || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0 || vw <= 0f || vh <= 0f) {
            baseMatrix = null
            return
        }
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        val fit = min(vw / iw, vh / ih)
        val w = iw * fit
        val h = ih * fit
        val left = (vw - w) / 2f
        val top = (vh - h) / 2f
        fitRect.set(left, top, left + w, top + h)

        baseMatrix = Matrix().apply {
            setScale(fit, fit)
            postTranslate(left, top)
        }
        rebuild()
    }

    /** 用当前 (scale, dx, dy) 重建展示矩阵，并把平移夹在有效范围内 */
    private fun rebuild() {
        val base = baseMatrix ?: run {
            imageMatrix = matrix.apply { reset() }
            return
        }
        val contentW = fitRect.width() * scale
        val contentH = fitRect.height() * scale
        val maxDx = max(0f, (contentW - width) / 2f)
        val maxDy = max(0f, (contentH - height) / 2f)
        dx = dx.coerceIn(-maxDx, maxDx)
        dy = dy.coerceIn(-maxDy, maxDy)

        matrix.set(base)
        // 围绕视图中心缩放 → 任意倍率下图形自动保持居中，平移只需处理越界部分
        matrix.postScale(scale, scale, width / 2f, height / 2f)
        matrix.postTranslate(dx, dy)
        imageMatrix = matrix

        onZoomChanged?.invoke(scale > MIN_SCALE + ZOOM_EPS)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix)
                start.set(event.x, event.y)
                panning = false
                lastPointerCount = 1
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二指落下 → 交给缩放，立刻停止平移，避免"缩放前先晃一下"
                panning = false
                lastPointerCount = event.pointerCount
            }

            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress) return true
                if (event.pointerCount != 1) return true
                if (!isZoomed) return true          // 未放大 → 让 ViewPager2 翻页
                if (!panning) {
                    val moved = max(
                        kotlin.math.abs(event.x - start.x),
                        kotlin.math.abs(event.y - start.y),
                    )
                    if (moved <= touchSlop) return true   // 没过 slop 不当拖动
                    panning = true
                    start.set(event.x, event.y)
                    return true
                }
                dx += event.x - start.x
                dy += event.y - start.y
                start.set(event.x, event.y)
                rebuild()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一指：把锚点挪到剩下那根手指，避免画面弹一下
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    start.set(event.getX(remaining), event.getY(remaining))
                }
                panning = false
                lastPointerCount = event.pointerCount - 1
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                panning = false
                lastPointerCount = 0
                if (scale < MIN_SCALE) {
                    scale = MIN_SCALE
                    dx = 0f
                    dy = 0f
                    rebuild()
                }
            }
        }
        return true
    }

    /** 回到适配尺寸并居中 */
    fun resetZoom() {
        scale = MIN_SCALE
        dx = 0f
        dy = 0f
        rebuild()
    }

    /** 供外部（返回键）判断：已放大时先复位而不是直接退出页面 */
    val isZoomed: Boolean
        get() = scale > MIN_SCALE + ZOOM_EPS
}

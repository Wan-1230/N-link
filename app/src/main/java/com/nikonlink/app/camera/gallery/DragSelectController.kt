package com.nikonlink.app.camera.gallery

import android.graphics.RectF
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * 模块 4.4 长按滑动多选控制器（交互对齐安卓系统相册）。
 *
 * 交互语义：
 * - 长按某张照片进入多选并选中它（长按检测由 PhotoGridAdapter 完成，回调到 Fragment 后
 *   调 [onDragStart] 激活本控制器）；
 * - 手指不抬起继续滑动：滑动轨迹经过的照片**滑过即选**；**反向滑回已选中的照片时自动取消**；
 * - 滑到列表边缘自动滚动并续选（滚动后按最后触点持续命中）；
 * - 与普通上下滚动 / 点击单选的手势优先级：本控制器未激活时完全透传
 *   （onInterceptTouchEvent 恒 false，RecyclerView 滚动与 item 点击不受影响）；
   激活后（长按 + 移动）才拦截事件流，RecyclerView 自身滚动让位于自动滚动续选。
 *
 * 区间算法：维护 lastPosition；每次命中新位置 pos，
 * pos > last → 区间 (last, pos] 逐项**选中**；pos < last → 区间 [pos, last) 逐项**取消**。
 * 与锚点方向无关，纯按移动方向判定，天然满足「滑过即选、回滑即取消」。
 */
class DragSelectController(
    private val recyclerView: RecyclerView,
    /** 位置 → 相机文件；日期标题行返回 null（不参与选择，仅推进 lastPosition） */
    private val itemAt: (position: Int) -> CameraFile?,
    /** 区间选择回调（一次命中批量一次回调，选中态由 ViewModel 单源维护） */
    private val onRange: (handles: List<Int>, select: Boolean) -> Unit
) : RecyclerView.OnItemTouchListener {

    companion object {
        /** 距边缘该距离内触发自动滚动 */
        private val EDGE_ZONE = 24f * 3f
        /** 自动滚动步长（px/帧） */
        private const val AUTO_SCROLL_STEP = 14
        private const val AUTO_SCROLL_INTERVAL_MS = 16L
    }

    private var active = false
    private var lastPosition = RecyclerView.NO_POSITION
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val hitRect = RectF()
    private var autoScrollRun: Runnable? = null
    private var autoScrollStep = 0

    /** 长按命中后调用：激活拖动多选并记录起点（起点 item 已由调用方选中） */
    fun onDragStart(position: Int) {
        active = true
        lastPosition = position
    }

    fun isActive(): Boolean = active

    private fun endDrag() {
        active = false
        lastPosition = RecyclerView.NO_POSITION
        stopAutoScroll()
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = e.x
                lastTouchY = e.y
                hitTestAndSelect()
                updateAutoScroll()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit

    /** 命中检测 + 方向判定 + 区间选择 */
    private fun hitTestAndSelect() {
        val pos = positionUnder(lastTouchX, lastTouchY)
        if (pos == RecyclerView.NO_POSITION || pos == lastPosition) return
        val from = min(lastPosition, pos)
        val to = max(lastPosition, pos)
        val select = pos > lastPosition
        val handles = ArrayList<Int>()
        for (p in from..to) {
            if (p == lastPosition) continue
            itemAt(p)?.let { handles.add(it.handle) }
        }
        lastPosition = pos
        if (handles.isNotEmpty()) onRange(handles, select)
    }

    private fun positionUnder(x: Float, y: Float): Int {
        hitRect.set(x - 24f, y - 24f, x + 24f, y + 24f)
        val child = recyclerView.findChildViewUnder(x, y) ?: return RecyclerView.NO_POSITION
        val pos = recyclerView.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION
        // 标题行：仅推进 lastPosition（调用方判定 itemAt null），返回位置本身
        return pos
    }

    // ---------------- 边缘自动滚动 ----------------

    private fun updateAutoScroll() {
        val height = recyclerView.height
        val step = when {
            lastTouchY < EDGE_ZONE -> -AUTO_SCROLL_STEP
            lastTouchY > height - EDGE_ZONE -> AUTO_SCROLL_STEP
            else -> 0
        }
        if (step == 0) {
            stopAutoScroll()
            return
        }
        if (autoScrollStep == step && autoScrollRun != null) return
        autoScrollStep = step
        stopAutoScroll()
        val runner = object : Runnable {
            override fun run() {
                if (!active || autoScrollStep == 0) return
                recyclerView.scrollBy(0, autoScrollStep)
                // 滚动后按最后触点继续命中，实现「边缘滚动续选」
                hitTestAndSelect()
                recyclerView.postDelayed(this, AUTO_SCROLL_INTERVAL_MS)
            }
        }
        autoScrollRun = runner
        recyclerView.postDelayed(runner, AUTO_SCROLL_INTERVAL_MS)
    }

    private fun stopAutoScroll() {
        autoScrollRun?.let { recyclerView.removeCallbacks(it) }
        autoScrollRun = null
        autoScrollStep = 0
    }
}

package com.nikonlink.app.shared.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nikonlink.app.R
import kotlin.math.roundToInt

/**
 * 水平刻度尺（飓风相机参考图 018 的档位选择范式）。
 * 拖动滚动、松手吸附最近刻度并回调；选中项 = 最靠近控件中心的那一格。
 * 只负责「选哪一格」，档位真源与下发逻辑仍在 CameraParamsViewModel。
 */
class TickRulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 松手/点选吸附后回调被选中的下标 */
    var onSelected: ((Int) -> Unit)? = null

    private var labels: List<String> = emptyList()
    private var offset = 0f
    private var dragStartX = 0f
    private var dragStartOffset = 0f
    private var dragging = false

    private val spacing = dp(58f)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val accent = ContextCompat.getColor(context, R.color.lv_accent)
    private val dim = ContextCompat.getColor(context, R.color.lv_text_dim)
    // 未选中刻度色提前取好：onDraw 里每个可见刻度都调一次 getColor 会做重复资源查找，
    // 拖动时是每帧 N 次，白白拖慢跟手度
    private val plain = ContextCompat.getColor(context, R.color.white)

    fun setItems(labels: List<String>, selectedIndex: Int) {
        this.labels = labels
        if (labels.isEmpty()) {
            offset = 0f
        } else {
            offset = selectedIndex.coerceIn(0, labels.lastIndex) * spacing
        }
        invalidate()
    }

    /** 状态流回读写回：只移动光标不触发回调；拖动中不打断用户 */
    fun setSelectedIndex(index: Int) {
        if (labels.isEmpty() || dragging) return
        val target = index.coerceIn(0, labels.lastIndex) * spacing
        ValueAnimator.ofFloat(offset, target).apply {
            duration = 140
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun selectedIndex(): Int =
        if (labels.isEmpty()) 0 else (offset / spacing).roundToInt().coerceIn(0, labels.lastIndex)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.size < 2) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartX = event.x
                dragStartOffset = offset
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                offset = (dragStartOffset - (event.x - dragStartX))
                    .coerceIn(0f, labels.lastIndex * spacing)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val idx = selectedIndex()
                setSelectedIndex(idx)
                if (event.actionMasked == MotionEvent.ACTION_UP) onSelected?.invoke(idx)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (labels.isEmpty()) return
        val cx = width / 2f
        val baseY = height - dp(12f)
        val sel = selectedIndex()
        for (i in labels.indices) {
            val x = cx + i * spacing - offset
            if (x < -spacing || x > width + spacing) continue
            val isSel = i == sel
            val tickH = if (isSel) dp(26f) else dp(14f)
            tickPaint.color = if (isSel) accent else plain
            tickPaint.alpha = if (isSel) 255 else 190
            tickPaint.strokeWidth = if (isSel) dp(3f) else dp(1.6f)
            canvas.drawLine(x, baseY, x, baseY - tickH, tickPaint)
            labelPaint.color = if (isSel) accent else dim
            labelPaint.textSize = if (isSel) dp(15f) else dp(12f)
            labelPaint.isFakeBoldText = isSel
            canvas.drawText(labels[i], x, baseY - dp(34f), labelPaint)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

package com.nikonlink.app.shared.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.nikonlink.app.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 弧形跟焦轮（飓风相机参考图 012）。
 *
 * 轮身只记录 0..1 的**相对轮位**：拖动时把角度增量换算成 MF 相对驱动指令
 * （0x9204，direction + speed）往外发。机身没有绝对焦点位置回读，
 * 所以读数框展示的是轮位而不是"相机对焦距离"——不造假数据。
 */
class ArcDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 相对驱动回调：direction 1=远端 -1=近端，speed 1-3 */
    var onDrive: ((direction: Int, speed: Int) -> Unit)? = null

    /** 轮位变化回调（0..1），供读数框显示 */
    var onWheelPosition: ((Float) -> Unit)? = null

    /** 非 MF 模式时由控制器置 false：轮子置灰且不发包 */
    var driveEnabled: Boolean = true
        set(value) {
            field = value
            alpha = if (value) 1f else 0.4f
        }

    private var position = 0.5f
    private var lastDriveMs = 0L

    private val startDeg = -126f
    private val endDeg = -54f
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!driveEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val deg = angleOf(event.x, event.y).coerceIn(startDeg, endDeg)
                val newPos = (deg - startDeg) / (endDeg - startDeg)
                val delta = newPos - position
                if (delta != 0f) {
                    maybeDrive(delta)
                    position = newPos
                    onWheelPosition?.invoke(position)
                    invalidate()
                }
            }
        }
        return true
    }

    /** 节流 + 按角度增量分档速度，避免一次拖动刷爆 PTP 命令通道 */
    private fun maybeDrive(delta: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDriveMs < 110L) return
        lastDriveMs = now
        val speed = when {
            delta >= 0.02f || delta <= -0.02f -> 3
            delta >= 0.008f || delta <= -0.008f -> 2
            else -> 1
        }
        onDrive?.invoke(if (delta > 0) 1 else -1, speed)
    }

    private fun geometry(): Triple<Float, Float, Float> {
        val r = height * 1.35f
        val cx = width / 2f
        val cy = height + r * 0.55f
        return Triple(cx, cy, r)
    }

    private fun angleOf(x: Float, y: Float): Float {
        val (cx, cy, _) = geometry()
        return Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
    }

    private fun point(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(deg.toDouble())
        return (cx + cos(rad) * r).toFloat() to (cy + sin(rad) * r).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val (cx, cy, r) = geometry()
        val accent = ContextCompat.getColor(context, R.color.lv_accent)

        arcPaint.color = ContextCompat.getColor(context, R.color.white)
        arcPaint.alpha = 40
        arcPaint.strokeWidth = dp(1f)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, startDeg, endDeg - startDeg, true, arcPaint)

        val steps = 36
        for (i in 0..steps) {
            val pos = i.toFloat() / steps
            val deg = startDeg + (endDeg - startDeg) * pos
            val major = i % 4 == 0
            val len = if (major) dp(15f) else dp(8f)
            val (x1, y1) = point(cx, cy, r, deg)
            val (x2, y2) = point(cx, cy, r - len, deg)
            tickPaint.color = ContextCompat.getColor(context, R.color.white)
            tickPaint.alpha = if (major) 200 else 120
            tickPaint.strokeWidth = if (major) dp(1.8f) else dp(1.2f)
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
            if (major) {
                val (lx, ly) = point(cx, cy, r - dp(30f), deg)
                canvas.save()
                canvas.translate(lx, ly)
                canvas.rotate(deg + 90f)
                labelPaint.color = ContextCompat.getColor(context, R.color.lv_text_dim)
                labelPaint.textSize = dp(10f)
                canvas.drawText(String.format("%.1f", pos * 1.2f), 0f, 0f, labelPaint)
                canvas.restore()
            }
        }

        val deg = startDeg + (endDeg - startDeg) * position
        val (px1, py1) = point(cx, cy, r + dp(4f), deg)
        val (px2, py2) = point(cx, cy, r - dp(20f), deg)
        pointerPaint.color = accent
        pointerPaint.strokeWidth = dp(3.4f)
        canvas.drawLine(px1, py1, px2, py2, pointerPaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

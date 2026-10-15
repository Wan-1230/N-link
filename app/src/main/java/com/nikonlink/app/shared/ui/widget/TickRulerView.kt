package com.nikonlink.app.shared.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.nikonlink.app.R
import com.nikonlink.app.shared.ui.InertiaMath
import com.nikonlink.app.shared.ui.Motion
import com.nikonlink.app.shared.ui.glass.UiFlags
import kotlin.math.abs

/**
 * 水平刻度尺（飓风相机参考图 018 的档位选择范式）。
 * 拖动滚动、松手吸附最近刻度并回调；选中项 = 最靠近控件中心的那一格。
 * 只负责「选哪一格」，档位真源与下发逻辑仍在 CameraParamsViewModel。
 *
 * ## 惯性甩动 —— 四拍动效的第 2 拍（family: physics / inertia）
 *
 * 改动前本控件**没有任何惯性**：ACTION_UP 立即吸附到最近一格，
 * 甩动和轻点的手感完全一样，读作「一格一格的按钮条」而不是「可以拨的转盘」。
 *
 * 依据视频解析 05「INERTIA WHEEL」逐帧实测：
 * - 小时列数字变化的间隔是 100 / 100 / 100 / 100 / 140 / 160 / 170 / 200 / 270 / 300 / 430 ms
 *   —— **单调递增**，报告称这是「惯性衰减 + 逐格吸附」的硬证据，不是匀速滚动
 * - 末端还有橡皮筋回弹（78.60→79.43 第二轮）
 * - 减速比达 4.3 倍（100→430ms）
 *
 * ### 实现选择：解析投影 + 减速插值器，而不是逐帧物理循环
 *
 * 报告建议的落地写法是 `while (|v| > 0.02) { pos += v; v *= 0.94 }` 这样的逐帧循环。
 * 本实现不那样做，理由是一条几何事实：
 *
 * **刻度是等距的。任何单调减速的位移，经过等距刻度时，间隔必然单调递增。**
 *
 * 所以只要「投影出终点 + 用 DecelerateInterpolator 走过去」，
 * 就自动得到实测那条 100→430ms 曲线，不需要自己跑物理循环、不需要 Choreographer、
 * 不需要处理低帧率下的步长补偿。少一个每帧回调，也少一处与监看渲染链抢主线程的地方
 * （那条链固定 15fps，见 docs/ADR-001 §3.3）。
 *
 * 投影公式取报告实测的摩擦系数 0.94/帧 @60fps：
 *   总位移 = v0 · dt · (1 + f + f² + …) = v0 · (1/60) / (1 − 0.94) = v0 / 3.6
 *
 * ### 未实现的部分（诚实登记）
 *
 * 报告提到「每格吸附时有一次轻微的重新对齐（不是滑过去就完事）」。
 * 本实现只在**终点**吸附一次，不做逐格微吸附——那需要每帧检测越界并打断插值，
 * 与上面的解析投影方案互斥。逐格微吸附在无验证通道的前提下风险大于收益，故不做。
 *
 * ## 静止版终态（motion.md:22-25 强制）
 *
 * [UiFlags.motionEnabled] 为 false 时：不做甩动投影、不做补间，
 * ACTION_UP 直接把 offset 设为最近刻度的精确位置并回调。
 * 拖动跟手部分**保留**——那是直接操纵（direct manipulation），不是动效；
 * 关掉动画不该让控件变得拖不动。
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

    private var velocityTracker: VelocityTracker? = null
    private var settleAnimator: ValueAnimator? = null

    /** 甩动距离上限（格数）。不设上限的话一次快甩能滚完整条档位表，
     *  用户得盯着看它滚完，比不甩更慢。6 格约等于一次「有意的快速拨动」。 */
    private val maxFlingTicks = 6

    /** 越界拖动阻力系数。橡皮筋手感：越界部分只走手指位移的 35% */
    private val overscrollResistance = 0.35f

    /** 判定为甩动的最小速度（dp/s）。低于它按轻点处理，直接吸附 */
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private fun maxOffset() = labels.lastIndex * spacing

    fun setItems(labels: List<String>, selectedIndex: Int) {
        this.labels = labels
        settleAnimator?.cancel()
        offset = if (labels.isEmpty()) 0f
        else selectedIndex.coerceIn(0, labels.lastIndex) * spacing
        invalidate()
    }

    /** 状态流回读写回：只移动光标不触发回调；拖动中不打断用户 */
    fun setSelectedIndex(index: Int) {
        if (labels.isEmpty() || dragging) return
        val target = index.coerceIn(0, labels.lastIndex) * spacing
        animateOffsetTo(target, Motion.FAST, fireCallback = false)
    }

    fun selectedIndex(): Int = InertiaMath.snapIndex(offset, spacing, labels.size)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.size < 2) return false

        // 甩动投影期间用户再次按下：立即停掉补间，交还控制权。
        // 不做这一步的话动画会和手指打架，读作「拖不动」。
        settleAnimator?.cancel()
        settleAnimator = null

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragStartX = event.x
                dragStartOffset = offset
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                offset = withResistance(dragStartOffset - (event.x - dragStartX))
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)

                val isUp = event.actionMasked == MotionEvent.ACTION_UP
                val max = maxOffset()

                // 越界了 → 橡皮筋回弹到边界，不触发选中回调
                // （回弹是「你没越过去」的反馈，不是一次选择）
                if (offset < 0f || offset > max) {
                    val clamped = offset.coerceIn(0f, max)
                    animateOffsetTo(clamped, Motion.BASE, fireCallback = false)
                    releaseTracker()
                    return true
                }

                if (!isUp || !UiFlags.motionEnabled(context)) {
                    // CANCEL，或动效已关闭 → 静止版：直接落到最近刻度
                    val idx = selectedIndex()
                    offset = idx * spacing
                    invalidate()
                    if (isUp) onSelected?.invoke(idx)
                    releaseTracker()
                    return true
                }

                // 计算甩动投影终点
                val velocity = computeVelocity()
                releaseTracker()
                val projected = if (abs(velocity) < minFlingVelocity) {
                    // 速度不足 → 就是轻点/慢拖，吸附到最近一格
                    selectedIndex() * spacing
                } else {
                    // 解析投影（v0/3.6，摩擦 0.94/帧 @60fps），并限制最多甩 6 格。
                    // 投影后仍要吸附到整格，否则会停在两格之间。
                    val limit = maxFlingTicks * spacing
                    val travel = InertiaMath.projectTravel(velocity).coerceIn(-limit, limit)
                    InertiaMath.snapIndex(offset - travel, spacing, labels.size) * spacing
                }

                val flung = abs(projected - offset) > spacing * 0.75f
                animateOffsetTo(
                    target = projected,
                    // 甩动走 SLOW、普通吸附走 FAST：两档都来自 Motion token，
                    // 不引入第 5 档时长（项目军规「全 App 只允许 4 档」）
                    duration = if (flung) Motion.SLOW else Motion.FAST,
                    fireCallback = true,
                )
            }
        }
        return true
    }

    /** 越界部分按 [overscrollResistance] 衰减，界内 1:1 跟手 */
    private fun withResistance(raw: Float): Float {
        val max = maxOffset()
        return when {
            raw < 0f -> raw * overscrollResistance
            raw > max -> max + (raw - max) * overscrollResistance
            else -> raw
        }
    }

    private fun computeVelocity(): Float {
        val vt = velocityTracker ?: return 0f
        vt.computeCurrentVelocity(1000)
        // 手指向左滑（x 减小）→ offset 增大 → 内容向左走，故速度取负
        return -vt.xVelocity
    }

    private fun releaseTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 走到 [target] 并在结束时按 [fireCallback] 决定是否上报选中。
     *
     * 用 [DecelerateInterpolator]：这是「步进间隔单调递增」的唯一来源——
     * 等距刻度 + 单调减速 = 逐格耗时递增（见类注释的几何论证）。
     * 换成 Linear 会退化成匀速滚动，视频实测那条 100→430ms 曲线就没了。
     */
    private fun animateOffsetTo(target: Float, duration: Long, fireCallback: Boolean) {
        settleAnimator?.cancel()
        if (abs(target - offset) < 0.5f) {
            offset = target
            invalidate()
            if (fireCallback) onSelected?.invoke(selectedIndex())
            return
        }
        settleAnimator = ValueAnimator.ofFloat(offset, target).apply {
            this.duration = duration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 精确对齐到整格，避免浮点累积误差让游标停在两格之间
                    offset = target
                    invalidate()
                    if (fireCallback) onSelected?.invoke(selectedIndex())
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    // 被打断不上报：打断意味着用户又按下来了，选择权交还给手指
                }
            })
            start()
        }
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

            // 景深梯度（视频解析 §四.6「滚轮用透明度梯度表达景深：
            // 选中行高亮，上下行按距离逐级渐隐」）。
            // 改动前是二值的（选中 255 / 其余一律 190），所有非选中项一样亮，
            // 读作「一排并列按钮」；按距离衰减后才读作「一个有中心的转盘」。
            val dist = abs(i - sel)
            val tickH = if (isSel) dp(26f) else dp(14f)
            val fade = if (isSel) 255 else (200 - 42 * dist).coerceIn(64, 200)

            tickPaint.color = if (isSel) accent else plain
            tickPaint.alpha = fade
            tickPaint.strokeWidth = if (isSel) dp(3f) else dp(1.6f)
            canvas.drawLine(x, baseY, x, baseY - tickH, tickPaint)

            labelPaint.color = if (isSel) accent else dim
            labelPaint.alpha = fade
            labelPaint.textSize = if (isSel) dp(15f) else dp(12f)
            labelPaint.isFakeBoldText = isSel
            canvas.drawText(labels[i], x, baseY - dp(34f), labelPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        settleAnimator?.cancel()
        settleAnimator = null
        releaseTracker()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}

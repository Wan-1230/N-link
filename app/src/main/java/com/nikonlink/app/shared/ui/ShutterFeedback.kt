package com.nikonlink.app.shared.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import com.nikonlink.app.shared.ui.glass.UiFlags

/**
 * 快门分段反馈 —— 四拍动效的第 1 拍（family: state transition）。
 *
 * ## 为什么是三段而不是一段
 *
 * 视频解析《SIX TOUCHES IN ONE APP》01「DONE BURST」逐帧实测：一次「完成」被拆成
 * 按压收缩(267ms) → 保持(400ms) → 塌圆(367ms) → 画勾(800ms) → 粒子迸发(643ms) → 消散(267ms)。
 * 报告结论是**「Squeeze → Tick → Burst 三段缺一不可」**：单看每段都不大，
 * 但每段都有独立反馈，于是「从点击到完成」被拆成多个可感知的确认点。
 *
 * 快门是全 App 后果最重的一次点击（错过一个镜头就没了），所以它最需要这种分段确认。
 * 改动前 `LiveViewFragment` 的快门只有白闪一段——按下去到画面闪白之间是空的，
 * 用户不知道「我按到了没有」。
 *
 * 本实现保留三段结构，替换其中两段的具体形态：
 *   ① 按压收缩 scale 1.00 → 0.90（实测视频 −9.6%，取 −10%）
 *   ② 松手过冲回弹 0.90 → 1.06 → 1.00（spring，不是 ease-out）
 *   ③ 落定后回调触发白闪 + 下发拍摄
 * 塌圆与画勾不适用——那是「任务完成」按钮的语义，快门是瞬时动作不是状态终结。
 * 粒子迸发**刻意不做**：motion.md:138 明写 state transition 是唯一属于 product
 * register 的动效族，粒子属 particle/field 族，在本 App 的 register 下被禁。
 *
 * ## 与 [PressEffect] 的分工
 *
 * `PressEffect` 是全局通用按压反馈：scale 0.95、单段、[Motion.INSTANT]。
 * 0.95 对普通控件是对的——所有按钮都缩到 0.90 会读作「整个界面软塌塌」。
 * 快门是唯一需要更重手感的控件，所以单独走这里，scale 0.90 + 过冲回弹。
 * 两者不要叠加使用在同一个 View 上（会互相抢 animate）。
 *
 * ## 静止版终态（motion.md:22-25 强制）
 *
 * 系统「移除动画」或 App 动效开关关闭时（[UiFlags.pressMotionEnabled] 为 false）：
 * scale 直设 1.0、跳过全部补间、立即执行 onSettled 回调。
 * 注意这里是**立即执行回调而不是不执行**——白闪与拍摄下发是功能不是装饰，
 * 关掉动效不能把快门本身关掉。
 */
object ShutterFeedback {

    /** 按压收缩到的比例。视频实测宽 125→113px = −9.6%，取整 −10% */
    private const val SQUEEZE = 0.90f

    /** 回弹过冲峰值。视频 04 实测过冲幅度达目标位移的 150%，
     *  但那是卡片位移；快门是 scale，过冲过大读作「果冻」，取 1.06（+6%）。 */
    private const val OVERSHOOT = 1.06f

    /**
     * 附加到快门 View：接管 ACTION_DOWN 的收缩，不接管 UP/CANCEL
     * （那两个由 [release] 在业务侧显式触发，因为「落定」要接拍摄下发）。
     *
     * 返回 false 继续分发，不吞事件——与 [PressEffect.apply] 同一约定。
     */
    fun onPressDown(view: View) {
        if (!view.isEnabled) return
        view.animate().cancel()
        if (UiFlags.pressMotionEnabled(view.context)) {
            view.animate()
                .scaleX(SQUEEZE).scaleY(SQUEEZE)
                .setInterpolator(DecelerateInterpolator())
                .setDuration(Motion.FAST)
                .start()
        } else {
            view.scaleX = SQUEEZE
            view.scaleY = SQUEEZE
        }
    }

    /**
     * 松手：过冲回弹到静止，落定后执行 [onSettled]（白闪 + 拍摄下发）。
     *
     * 两阶段而不是一阶段带过冲插值器：`OvershootInterpolator` 的过冲量是
     * **动画区间的百分比**，0.90→1.00 区间只有 0.10，即便 tension 拉满峰值也只到 ~1.01，
     * 肉眼读不出回弹。要精确落在 1.06 必须显式分两段。
     *
     * [onSettled] 保证在任何路径下都会被调用恰好一次——包括动效关闭、
     * View 已 detach、动画被打断。快门不能因为动效出问题就不拍。
     */
    fun release(view: View, onSettled: () -> Unit) {
        view.animate().cancel()

        if (!UiFlags.pressMotionEnabled(view.context)) {
            // 静止版：直落终态，回调照跑
            view.scaleX = 1f
            view.scaleY = 1f
            onSettled()
            return
        }

        var settled = false
        val fireOnce = {
            if (!settled) {
                settled = true
                onSettled()
            }
        }

        val up = ObjectAnimator.ofFloat(view, View.SCALE_X, SQUEEZE, OVERSHOOT).apply {
            duration = Motion.FAST
            interpolator = DecelerateInterpolator()
        }
        val upY = ObjectAnimator.ofFloat(view, View.SCALE_Y, SQUEEZE, OVERSHOOT).apply {
            duration = Motion.FAST
            interpolator = DecelerateInterpolator()
        }
        val down = ObjectAnimator.ofFloat(view, View.SCALE_X, OVERSHOOT, 1f).apply {
            duration = Motion.INSTANT
            interpolator = AccelerateDecelerateInterpolator()
        }
        val downY = ObjectAnimator.ofFloat(view, View.SCALE_Y, OVERSHOOT, 1f).apply {
            duration = Motion.INSTANT
            interpolator = AccelerateDecelerateInterpolator()
        }

        AnimatorSet().apply {
            play(up).with(upY)
            play(down).with(downY).after(up)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = fireOnce()
                override fun onAnimationCancel(animation: Animator) {
                    // 被打断也要归位并放行：卡在 0.90 的快门是功能性 bug
                    view.scaleX = 1f
                    view.scaleY = 1f
                    fireOnce()
                }
            })
            start()
        }
    }

    /**
     * 复位（页面离开 / 手势被父容器拦截时调用）。
     * 不做动画，直设终值——复位本身不该被看见。
     */
    fun reset(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }
}

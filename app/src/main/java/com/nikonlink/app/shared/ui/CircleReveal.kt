package com.nikonlink.app.shared.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.PathInterpolator
import com.nikonlink.app.shared.ui.glass.UiFlags

/**
 * 触点圆形扩散转场 —— 四拍动效的第 3 拍（family: state transition）。
 *
 * ## 依据
 *
 * 视频解析 06「CIRCLE REVEAL」是**全片数据最完整的一条**（等效半径逐帧换算）：
 * - 正向扩散 733ms，半径 88.6→307.8px，峰值速度 29.7px/帧出现在**第 3 帧**，
 *   之后单调衰减 → 标准 ease-out
 * - 前 133ms 完成 47% 半径，最后 200ms 只推进 5% → **收尾极缓**，所以「铺满整屏」这一下很轻
 * - 反向收回 667ms，中段近似匀速（−11～−14px/帧持续 12 帧），首尾慢 → 接近 ease-in-out
 * - 原点在**底部**（分带起始时刻自下而上 500ms 内扫过），即从被点的那个页签长出来
 *
 * 报告列出的 ★★★ 结论之一是「**正反向用不同缓动**，且去程慢回程快」。
 * 这与 03 叠卡「展开慢收起快」是同一个设计取向。
 *
 * ## 时长为什么不是 733/667
 *
 * 报告明确警告：宣传视频的动画**被刻意放慢以便观看**，绝对毫秒值只能当「慢速演示档」，
 * 落到真实 App 建议压缩到 60–75%（733→440~550，667→400~500）。
 * 但项目军规是「全 App 动画时长只允许 [Motion] 的 4 档」，
 * 440~550 落在 SLOW(380) 与下一档之间，加第 5 档会破坏单一时长出口。
 *
 * 取 **去程 [Motion.SLOW](380) / 回程 [Motion.BASE](220)**，理由两条：
 * 1. 保住视频真正可复用的部分——**相对关系**（回程比去程快、两条不同曲线）。
 *    380/220 = 1.73，比视频的 733/667 = 1.10 差距更大，但方向一致。
 * 2. 与 Material 的转场口径一致：退出比进入快，因为用户已经决定要走，
 *    让他等是纯损耗。视频那个 1.10 的微小差距在真机上其实读不出来。
 *
 * ## 为什么用圆心是触点
 *
 * 视频的核心不是「圆形」这个形状，是**「从你点的地方长出来」**——
 * 它把「我点了哪里」和「新页面从哪来」连成同一个动作，转场本身就是位置反馈。
 * 所以调用方必须把真实触点坐标传进来，不要用屏幕中心兜底。
 * 拿不到触点时（如键盘/无障碍触发的切换）应改用普通淡入，而不是伪造一个圆心。
 *
 * ## 静止版终态（motion.md:22-25 强制）
 *
 * [UiFlags.motionEnabled] 为 false 时直接设 visibility，不跑补间。
 * 注意用的是 `motionEnabled` 而非 `pressMotionEnabled`：
 * 页面转场属于「界面动效」而非「按压反馈」，玻璃关掉时也应一并关掉。
 */
object CircleReveal {

    /** 去程：强 ease-out。cubic-bezier(.16,1,.3,1) —— anti-cheap.md 点名的
     *  「power1.out 或 linear 做揭示太快＝廉价」的正解，也是视频实测的
     *  「第 3 帧达峰、之后单调衰减」形状。 */
    private val EASE_OUT_STRONG = PathInterpolator(0.16f, 1f, 0.3f, 1f)

    /** 回程：ease-in-out。对应视频实测「中段近似匀速、首尾慢」。 */
    private val EASE_IN_OUT = PathInterpolator(0.65f, 0f, 0.35f, 1f)

    /**
     * 覆盖到整个 View 所需的半径 —— 从 [cx],[cy] 到**最远那个角**的距离。
     * 用 max(cx, w-cx) 而不是直接算四角取最大，等价且省两次 hypot。
     * 这条公式在非 9:16 屏幕上依然成立（视频解析 §五指出这是该转场的优点）。
     */
    fun radiusFor(view: View, cx: Int, cy: Int): Float =
        InertiaMath.revealRadius(view.width.toFloat(), view.height.toFloat(), cx.toFloat(), cy.toFloat())

    /**
     * 从触点扩散显示 [view]。
     *
     * @param cx cy 触点相对 [view] 的坐标（不是屏幕坐标，调用方需先换算）
     * @param onEnd 动画结束（或被跳过）后回调，保证恰好调用一次
     */
    fun reveal(view: View, cx: Int, cy: Int, onEnd: (() -> Unit)? = null) {
        run(view, cx, cy, entering = true, onEnd)
    }

    /** 原路收回到触点并隐藏 [view]。 */
    fun unreveal(view: View, cx: Int, cy: Int, onEnd: (() -> Unit)? = null) {
        run(view, cx, cy, entering = false, onEnd)
    }

    private fun run(
        view: View,
        cx: Int,
        cy: Int,
        entering: Boolean,
        onEnd: (() -> Unit)?,
    ) {
        var fired = false
        val finish = {
            if (!fired) {
                fired = true
                onEnd?.invoke()
            }
        }

        // View 还没测量过（width=0）时 createCircularReveal 会得到半径 0 的圆，
        // 整个转场看不见。此时直接落终态，不伪造动画。
        if (view.width == 0 || view.height == 0) {
            view.visibility = if (entering) View.VISIBLE else View.GONE
            finish()
            return
        }

        if (!UiFlags.motionEnabled(view.context)) {
            // 静止版：瞬时切换，无补间
            view.visibility = if (entering) View.VISIBLE else View.GONE
            finish()
            return
        }

        val r = radiusFor(view, cx, cy)
        val start = if (entering) 0f else r
        val end = if (entering) r else 0f

        if (entering) view.visibility = View.VISIBLE

        val anim = ViewAnimationUtils.createCircularReveal(view, cx, cy, start, end)
        anim.duration = if (entering) Motion.SLOW else Motion.BASE
        anim.interpolator = if (entering) EASE_OUT_STRONG else EASE_IN_OUT
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!entering) view.visibility = View.GONE
                finish()
            }

            override fun onAnimationCancel(animation: Animator) {
                // 被打断也要落到终态：半开的圆形裁剪会让页面卡在一片扇形里
                if (entering) {
                    view.visibility = View.VISIBLE
                } else {
                    view.visibility = View.GONE
                }
                finish()
            }
        })
        anim.start()
    }
}

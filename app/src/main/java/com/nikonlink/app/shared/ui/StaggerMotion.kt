package com.nikonlink.app.shared.ui

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import com.nikonlink.app.shared.ui.glass.UiFlags

/**
 * 错峰开合与入场编排 —— 四拍动效的第 4 拍（family: state transition）。
 *
 * ## 依据：错峰方向不对称（视频解析 03，逐带起始时刻实测）
 *
 * 这是报告里 ★★★ 的三条之一，也是数据最干净的一条：
 *
 * | | 顺序 | 每张间隔 | 整体 |
 * |---|---|---|---|
 * | 展开 | **自上而下** 带1 47.90 / 带2 48.17 / 带3 48.20 / 带4 48.40 | 80–100ms | 633ms |
 * | 收起 | **自下而上** 带4 49.30 / 带3 49.30 / 带2 49.33 / 带1 49.47 | 约 40ms | 600ms |
 *
 * 报告结论：**「展开要看得清，收起要利落」**——收起更快 + 错峰量只有展开的一半，
 * 所以叠回去显得干净不拖沓。展开的帧增量 65→53→41→33→24→18→13→10 严格递减，
 * 是典型 ease-out。
 *
 * 这个不对称是本实现的核心。**两个方向用同一个 stagger 值是最常见的错误**——
 * 那样收起会读作「展开的倒放」，拖沓感就回来了。
 *
 * ## 时长口径
 *
 * 视频绝对值按报告的「压缩到 60–75%」折算：展开每项 633×0.7≈443ms、
 * 收起 600×0.7≈420ms。但项目军规是时长只走 [Motion] 4 档，故：
 * - 每项动画时长：展开 [Motion.BASE](220)、收起 [Motion.FAST](150)
 *   ——收起项本身更短，配合更小的错峰量，双重保证「收起更利落」
 * - 错峰间隔不属于「动画时长」，是编排参数，单独定义为下方两个常量，
 *   取值直接来自实测（展开 90ms 落在实测 80–100ms 区间中值，收起 40ms 为实测值）
 *
 * ## 静止版终态（motion.md:22-25 强制）
 *
 * [UiFlags.motionEnabled] 为 false 时：所有子项直接落终态（展开=完全可见、
 * 收起=完全隐藏、入场=完全可见），不跑补间，回调照跑。
 * 注意收起路径必须真的把 View 设成 GONE/alpha 0，
 * 否则「关掉动效」会变成「面板关不上」这种功能性 bug。
 */
object StaggerMotion {

    /** 展开错峰间隔：实测 80–100ms/张，取中值 */
    private const val STAGGER_EXPAND = 90L

    /** 收起错峰间隔：实测约 40ms/张。刻意是展开的一半以下 */
    private const val STAGGER_COLLAPSE = 40L

    /** 入场错峰间隔：与被删的 EntranceMotion 保持同值，避免两种入场节奏并存 */
    private const val STAGGER_ENTER = 40L

    /** 位移幅度。8dp 是「能看见在动但读不出在移动」的上限，
     *  再大会读作布局跳动而不是入场。 */
    private val RISE_DP = 8f

    /** 入场重播抑制窗口。同一组 View 在此时间内重复触发直接落终态——
     *  旋屏、配置变更、Fragment 回栈都会重跑 onViewCreated，
     *  没有抑制的话用户转个屏就看一次入场动画，很快变成噪音。 */
    private const val REPLAY_SUPPRESS_MS = 1500L

    private val lastEnterAt = HashMap<Int, Long>()

    private fun risePx(view: View) = RISE_DP * view.resources.displayMetrics.density

    /**
     * 展开一组子项：自上而下错峰，每项 8dp 上升 + 淡入，ease-out。
     *
     * @param children 顺序即视觉顺序（第一个是最上面那个）
     */
    fun expand(children: List<View>) {
        if (children.isEmpty()) return
        val animated = UiFlags.motionEnabled(children[0].context)
        children.forEachIndexed { i, v ->
            v.visibility = View.VISIBLE
            if (!animated) {
                v.alpha = 1f
                v.translationY = 0f
                return@forEachIndexed
            }
            v.alpha = 0f
            v.translationY = risePx(v)
            v.animate().cancel()
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * STAGGER_EXPAND)
                .setDuration(Motion.BASE)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /**
     * 收起一组子项：**自下而上**错峰（与展开反向），每项下沉 + 淡出，ease-in。
     *
     * @param onEnd 全部收起后回调（用于隐藏容器），保证恰好调用一次
     */
    fun collapse(children: List<View>, onEnd: (() -> Unit)? = null) {
        if (children.isEmpty()) {
            onEnd?.invoke()
            return
        }
        val animated = UiFlags.motionEnabled(children[0].context)
        if (!animated) {
            // 静止版：直接落终态。必须真隐藏，否则面板关不上
            children.forEach {
                it.animate().cancel()
                it.alpha = 0f
                it.visibility = View.GONE
            }
            onEnd?.invoke()
            return
        }

        val lastIdx = children.size - 1
        val total = lastIdx * STAGGER_COLLAPSE + Motion.FAST
        var fired = false
        val fire = {
            if (!fired) {
                fired = true
                onEnd?.invoke()
            }
        }

        children.reversed().forEachIndexed { i, v ->
            v.animate().cancel()
            val a = v.animate()
                .alpha(0f)
                .translationY(risePx(v))
                .setStartDelay(i * STAGGER_COLLAPSE)
                .setDuration(Motion.FAST)
                .setInterpolator(AccelerateInterpolator())
            // 回调只能挂在**最后收尾**的那一项上（i == lastIdx，即原顺序里最上面那个）。
            // 挂在每一项上会由最先结束的（最下面那个）触发，容器会在其余项还在飞的时候就消失。
            if (i == lastIdx) {
                a.withEndAction {
                    v.visibility = View.GONE
                    fire()
                }
            } else {
                a.withEndAction { v.visibility = View.GONE }
            }
            a.start()
        }
        // 兜底：withEndAction 在动画被外部 cancel 时也会跑，但延迟队列可能被清空。
        // 万一回调没来，也要放行，否则调用方等不到 onEnd 会把容器永久留在展开态。
        children[0].postDelayed({
            if (!fired) {
                children.forEach { it.visibility = View.GONE }
                fire()
            }
        }, total + 80)
    }

    /**
     * 页面入场编排：子项依次 8dp 上升 + 淡入。
     *
     * 改动前只有 `RemoteFragment` 三处有入场编排，Dashboard / Transfer / Settings /
     * LiveView 都是「瞬间全部出现」。差别不是华丽与否——是**「渲染出来了」与「到场了」**
     * 之间的那一下（h5-mobile.md:469：四行代码，是这两者之间的大部分差别）。
     *
     * 带 [REPLAY_SUPPRESS_MS] 重播抑制，见常量注释。
     *
     * @param key 抑制窗口的标识，通常传 Fragment 的 hashCode 或页面名 hash
     */
    fun enter(key: Int, children: List<View>) {
        if (children.isEmpty()) return

        val now = System.currentTimeMillis()
        val last = lastEnterAt[key] ?: 0L
        val suppressed = now - last < REPLAY_SUPPRESS_MS
        lastEnterAt[key] = now

        val animated = !suppressed && UiFlags.motionEnabled(children[0].context)
        children.forEachIndexed { i, v ->
            v.visibility = View.VISIBLE
            if (!animated) {
                v.animate().cancel()
                v.alpha = 1f
                v.translationY = 0f
                return@forEachIndexed
            }
            v.alpha = 0f
            v.translationY = risePx(v)
            v.animate().cancel()
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(i * STAGGER_ENTER)
                .setDuration(Motion.BASE)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    /** 清理入场抑制记录（Fragment onDestroy 时调用，避免 Map 无界增长） */
    fun forget(key: Int) {
        lastEnterAt.remove(key)
    }
}

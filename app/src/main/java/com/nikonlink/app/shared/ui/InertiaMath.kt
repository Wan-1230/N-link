package com.nikonlink.app.shared.ui

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 动效相关的**纯数学**，从 View 里抽出来以便单元测试。
 *
 * 为什么单独一个文件：[CircleReveal] 与 [TickRulerView] 都继承/持有 android.view.View，
 * 逻辑埋在 View 里就只能靠真机验证。本机无可用真机与模拟器（见 docs/ADR-001 §4.3），
 * 因此把可判定的部分抽成纯函数，用 JVM 单测锁住——
 * 这是无设备条件下唯一能拿到的真实证据，而不是「看起来对」。
 */
object InertiaMath {

    /**
     * 每帧摩擦系数。取自视频解析 05 的实测建议值：
     * 报告给出的落地写法是 `v *= 0.94`，并指出它与实测的 4.3 倍减速吻合
     * （步进间隔 100ms → 430ms）。
     */
    const val FRICTION_PER_FRAME = 0.94f

    /** 参考帧率。投影公式的 dt 由它得出 */
    const val FRAME_HZ = 60f

    /**
     * 由初速度投影出惯性总位移（解析解，不跑逐帧循环）。
     *
     * 等比级数求和：总位移 = v0·dt·(1 + f + f² + …) = v0·dt / (1 − f)
     * 代入 dt = 1/60、f = 0.94 → v0 / 3.6
     *
     * 不逐帧积分的理由：等距刻度 + 任何单调减速位移，逐格耗时必然单调递增
     * （见 [decelerateTickFractions] 与其单测），所以只要终点正确、
     * 用减速插值器走过去，就自动得到实测那条曲线，不需要 Choreographer 每帧回调。
     * 少一个每帧回调，也少一处与监看渲染链（固定 15fps）抢主线程的地方。
     *
     * @param velocityPxPerSec 初速度，px/秒；符号即方向
     */
    fun projectTravel(
        velocityPxPerSec: Float,
        friction: Float = FRICTION_PER_FRAME,
        frameHz: Float = FRAME_HZ,
    ): Float {
        require(friction in 0f..0.999f) { "friction 必须在 (0,1) 内，否则级数发散：$friction" }
        require(frameHz > 0f) { "frameHz 必须为正：$frameHz" }
        val dt = 1f / frameHz
        return velocityPxPerSec * dt / (1f - friction)
    }

    /**
     * 把连续 offset 吸附到最近刻度下标，并钳到合法范围。
     * 越界返回端点而不是抛异常：甩动投影可能算出界外终点，钳位是期望行为。
     */
    fun snapIndex(offset: Float, spacing: Float, count: Int): Int {
        if (count <= 0 || spacing <= 0f) return 0
        return (offset / spacing).roundToInt().coerceIn(0, count - 1)
    }

    /**
     * 覆盖整个矩形所需的扩散半径 = 圆心到**最远那个角**的距离。
     *
     * 用 max(cx, w−cx) 而不是显式算四角取最大：等价，且省两次 hypot。
     * 这条公式在非 9:16 屏幕上同样成立（视频解析 §五指出这是该转场的优点）。
     */
    fun revealRadius(w: Float, h: Float, cx: Float, cy: Float): Float {
        if (w <= 0f || h <= 0f) return 0f
        val x = cx.coerceIn(0f, w)
        val y = cy.coerceIn(0f, h)
        return hypot(max(x, w - x), max(y, h - y))
    }

    /**
     * 匀减速位移经过 N 等距刻度时，**每格耗时占总时长的比例**（逐格，从第 1 格到第 N 格）。
     *
     * Android `DecelerateInterpolator`（factor=1）的位移函数是 p(t) = 1 − (1 − t)²。
     * 令第 k 格落在 p = k/N，解出 t：
     *   1 − (1−t)² = k/N  →  (1−t)² = 1 − k/N  →  t = 1 − √(1 − k/N)
     * 第 k 格的耗时 = t(k) − t(k−1) = √(1 − (k−1)/N) − √(1 − k/N)
     *
     * 这个序列**单调递增**，也就是视频解析 05 逐帧实测到的
     * 100/100/100/100/140/160/170/200/270/300/430 ms 那个形状。
     * 单测直接断言单调性与首末比，把「为什么用 DecelerateInterpolator 而不是 Linear」
     * 从注释里的说法变成可执行的事实。
     *
     * @return 长度 [ticks] 的数组，第 i 项是走过第 i+1 格所占总时长的比例
     */
    fun decelerateTickFractions(ticks: Int): FloatArray {
        require(ticks > 0) { "ticks 必须为正：$ticks" }
        val out = FloatArray(ticks)
        var prevT = 0f
        for (k in 1..ticks) {
            // √ 内可能因浮点误差变成极小负数，钳到 0
            val t = 1f - kotlin.math.sqrt(max(0f, 1f - k.toFloat() / ticks))
            out[k - 1] = t - prevT
            prevT = t
        }
        return out
    }
}

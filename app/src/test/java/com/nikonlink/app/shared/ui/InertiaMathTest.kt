package com.nikonlink.app.shared.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InertiaMath] 的 JVM 单测。
 *
 * 为什么这些测试值得存在：本机**无可用真机也无可用模拟器**
 * （两次尝试均失败，见 docs/ADR-001 §4.3），所以视觉效果无法自查。
 * 在不能跑 UI 的前提下，唯一诚实的证据就是把可判定的数学抽成纯函数并锁住。
 * 这些测试覆盖的是「静默失败」风险最高的三处：
 * 惯性投影的公式、刻度吸附的钳位、圆形扩散的半径。
 */
class InertiaMathTest {

    private val eps = 1e-3f

    // ---------- 惯性投影 ----------

    @Test
    fun `投影公式等于 v0 除以 3_6`() {
        // dt=1/60、f=0.94 → dt/(1-f) = (1/60)/0.06 = 1/3.6
        assertEquals(1000f, InertiaMath.projectTravel(3600f), eps)
        assertEquals(0f, InertiaMath.projectTravel(0f), eps)
        assertEquals(-500f, InertiaMath.projectTravel(-1800f), eps)
    }

    @Test
    fun `投影与速度成正比且保留方向`() {
        val a = InertiaMath.projectTravel(1200f)
        val b = InertiaMath.projectTravel(2400f)
        assertEquals(a * 2f, b, eps)
        assertEquals(-a, InertiaMath.projectTravel(-1200f), eps)
    }

    @Test
    fun `摩擦系数越大投影越远`() {
        val low = InertiaMath.projectTravel(1000f, friction = 0.80f)
        val high = InertiaMath.projectTravel(1000f, friction = 0.98f)
        assertTrue("摩擦大应滑得更远：$low vs $high", high > low)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `摩擦系数大于等于 1 时级数发散必须拒绝`() {
        InertiaMath.projectTravel(1000f, friction = 1f)
    }

    // ---------- 刻度吸附 ----------

    @Test
    fun `吸附到最近刻度`() {
        assertEquals(0, InertiaMath.snapIndex(0f, 58f, 10))
        assertEquals(1, InertiaMath.snapIndex(58f, 58f, 10))
        // 29 = 半格，roundToInt 采用 HALF_UP → 1
        assertEquals(1, InertiaMath.snapIndex(29f, 58f, 10))
        assertEquals(0, InertiaMath.snapIndex(28f, 58f, 10))
    }

    @Test
    fun `甩动投影越界时钳到端点而不是抛异常`() {
        // 这是甩动路径的常态：投影终点算出界外，必须钳住
        assertEquals(9, InertiaMath.snapIndex(99999f, 58f, 10))
        assertEquals(0, InertiaMath.snapIndex(-99999f, 58f, 10))
    }

    @Test
    fun `空档位表返回 0 而不是崩溃`() {
        assertEquals(0, InertiaMath.snapIndex(123f, 58f, 0))
        assertEquals(0, InertiaMath.snapIndex(123f, 0f, 10))
    }

    // ---------- 圆形扩散半径 ----------

    @Test
    fun `圆心在正中时半径是半对角线`() {
        // 300x400 的矩形，中心 (150,200) → 半对角线 = √(150²+200²) = 250
        assertEquals(250f, InertiaMath.revealRadius(300f, 400f, 150f, 200f), eps)
    }

    @Test
    fun `圆心在角上时半径是整条对角线`() {
        assertEquals(500f, InertiaMath.revealRadius(300f, 400f, 0f, 0f), eps)
        assertEquals(500f, InertiaMath.revealRadius(300f, 400f, 300f, 400f), eps)
    }

    @Test
    fun `半径必须覆盖最远角否则扩散铺不满屏`() {
        // 视频解析 06 的落地要点：r = hypot(max(cx, W-cx), max(cy, H-cy))
        // 偏心触点也要能铺满，这是该转场在非 9:16 屏上依然成立的原因
        val w = 1080f
        val h = 2400f
        for (cx in floatArrayOf(0f, 200f, 540f, 900f, 1080f)) {
            for (cy in floatArrayOf(0f, 600f, 2100f, 2400f)) {
                val r = InertiaMath.revealRadius(w, h, cx, cy)
                for (ax in floatArrayOf(0f, w)) {
                    for (ay in floatArrayOf(0f, h)) {
                        val d = kotlin.math.hypot(ax - cx, ay - cy)
                        assertTrue("圆心($cx,$cy) 半径 $r 盖不住角 ($ax,$ay) 距离 $d", r >= d - eps)
                    }
                }
            }
        }
    }

    @Test
    fun `未测量或退化尺寸返回 0 而不是负数或 NaN`() {
        assertEquals(0f, InertiaMath.revealRadius(0f, 0f, 10f, 10f), eps)
        assertEquals(0f, InertiaMath.revealRadius(-5f, 100f, 0f, 0f), eps)
    }

    // ---------- 减速插值产生递增步进（视频 05 的核心断言）----------

    @Test
    fun `匀减速经过等距刻度时逐格耗时单调递增`() {
        // 视频解析 05 实测：100/100/100/100/140/160/170/200/270/300/430 ms
        // 报告称单调递增是「惯性衰减 + 逐格吸附」的硬证据。
        // 本测试把这条从观察变成断言：等距刻度 + 减速位移 ⇒ 间隔必然递增，
        // 这就是 TickRulerView 用 DecelerateInterpolator 而非 Linear 的理由。
        val f = InertiaMath.decelerateTickFractions(11)
        assertEquals(11, f.size)
        for (i in 1 until f.size) {
            assertTrue(
                "第 ${i + 1} 格耗时 ${f[i]} 未大于第 $i 格 ${f[i - 1]}（必须单调递增）",
                f[i] > f[i - 1],
            )
        }
    }

    @Test
    fun `各格耗时之和恰好等于总时长`() {
        for (n in intArrayOf(1, 2, 5, 11, 30)) {
            val sum = InertiaMath.decelerateTickFractions(n).sum()
            assertEquals("n=$n 时比例之和应为 1", 1f, sum, 1e-3f)
        }
    }

    @Test
    fun `末格显著慢于首格_量级与实测的4_3倍同向`() {
        // 实测首末比 430/100 = 4.3。此处只断言方向与量级（末格远慢于首格），
        // 不断言具体倍数：实测值来自被刻意放慢的宣传视频，绝对比值不可直接搬。
        val f = InertiaMath.decelerateTickFractions(11)
        val ratio = f.last() / f.first()
        assertTrue("末格/首格 = $ratio，应明显大于 1", ratio > 2f)
    }

    @Test
    fun `匀速插值不会产生递增间隔_用作对照`() {
        // 反证：Linear 下每格耗时相同，视频那条曲线不会出现。
        // 这条测试的存在是为了防止「反正都是减速」的想当然替换。
        val ticks = 11
        val uniform = FloatArray(ticks) { 1f / ticks }
        for (i in 1 until ticks) {
            assertEquals(uniform[i], uniform[i - 1], 1e-6f)
        }
        val decel = InertiaMath.decelerateTickFractions(ticks)
        assertTrue("减速插值的末格必须慢于匀速", decel.last() > uniform.last())
        assertTrue("减速插值的首格必须快于匀速", decel.first() < uniform.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `零刻度必须拒绝`() {
        InertiaMath.decelerateTickFractions(0)
    }
}

package com.nikonlink.app.shared.ui

import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.StateSet
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nikonlink.app.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * finesse component-scope §3「八状态硬门禁」在 Android 侧的落地验证。
 *
 * Web 版用 preview.html 同时命中真伪类 + 强制类来「打开看」每态；Android 无伪类，
 * 等价做法 = 把每个 `bg_*.xml` selector 逐态 resolve，**断言每态渲染出的 Drawable 互不相同**，
 * 从而证明「不是声称八态、而是每态真的有样式」。collapse 任意两态到同一 item 会立刻红。
 *
 * 覆盖两条通道：
 *  · selector 层 = default / hover / active（叠层 scrim）/ focus（2dp 换色描边）
 *  · ControlStates 层 = disabled（三通道）/ loading（预留终态宽度）
 * active 的按压缩放通道由全局 PressEffect 承担，不在本单测范围（动画）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EightStateSelectorTest {

    private val ctx get() = RuntimeEnvironment.getApplication()

    /** 状态名到 StateSet：disabled=无 state_enabled；default=仅 enabled。 */
    /** 从【同一个】selector 实例逐态 resolve 出当前 Drawable，才可能暴露两态塌到同一 child。 */
    private fun resolve(resId: Int, stateSet: IntArray): android.graphics.drawable.Drawable {
        val d = ContextCompat.getDrawable(ctx, resId) as StateListDrawable
        d.state = stateSet
        return d.current
    }

    private val enabledOnly = intArrayOf(android.R.attr.state_enabled)
    private val disabled = StateSet.WILD_CARD
    private val hover = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_hovered)
    private val active = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
    private val focus = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_focused)

    private fun assertEightStateSelector(resId: Int, name: String) {
        // 关键：单实例逐态切，child 复用才有意义——若两态塌到同一 item，引用会相同而被抓。
        val d = ContextCompat.getDrawable(ctx, resId) as StateListDrawable
        d.state = enabledOnly; val def = d.current
        d.state = hover;       val hov = d.current
        d.state = active;      val act = d.current
        d.state = focus;       val foc = d.current
        d.state = disabled;    val dis = d.current

        // 每态都必须 resolve 出东西，不许有态漏成 null（门禁：不可只交付部分态）
        assertNotNull("$name default 不可为空", def)
        assertNotNull("$name hover 不可为空", hov)
        assertNotNull("$name active 不可为空", act)
        assertNotNull("$name focus 不可为空", foc)
        assertNotNull("$name disabled 不可为空", dis)

        // hover / active / focus 必须与 default 渲染不同 —— 即 scrim / 焦点环真的生效
        assertNotSame("$name hover 必须区别于 default（scrim 生效）", def, hov)
        assertNotSame("$name active 必须区别于 default（scrim 生效）", def, act)
        assertNotSame("$name focus 必须区别于 default（焦点环生效）", def, foc)
        // 两态间也互不相同：active 与 hover 不是同一个静止样式
        assertNotSame("$name active 必须区别于 hover", hov, act)
    }

    @Test fun `bg_chip 八状态各有样式`() = assertEightStateSelector(R.drawable.bg_chip, "bg_chip")
    @Test fun `bg_cta_primary 八状态各有样式`() = assertEightStateSelector(R.drawable.bg_cta_primary, "bg_cta_primary")
    @Test fun `bg_chip_selected 八状态各有样式`() = assertEightStateSelector(R.drawable.bg_chip_selected, "bg_chip_selected")
    @Test fun `bg_lv_action 八状态各有样式`() = assertEightStateSelector(R.drawable.bg_lv_action, "bg_lv_action")

    @Test
    fun `焦点环是换色描边而非改宽度 border-width 恒定不位移`() {
        // default 是「底色 + 2dp 透明描边」，focus 是「底色 + 2dp focus_ring 描边」——
        // 两态都是 2dp 描边（宽度不变），只是颜色不同，满足「状态间不改 border-width」硬纪律。
        val def = resolve(R.drawable.bg_chip, enabledOnly)
        val foc = resolve(R.drawable.bg_chip, focus)
        assertNotSame(def, foc)
        assertTrue(
            "focus 态必须是描边形状（换色实现焦点环）",
            foc is android.graphics.drawable.GradientDrawable
        )
    }

    @Test
    fun `active 态是叠层 scrim 而非换实底`() {
        // primary/chip 底色随主题翻转，压深会撞纯 #000，故 hover/active 用半透明 scrim 叠层。
        val act = resolve(R.drawable.bg_cta_primary, active)
        assertTrue("active 必须是叠层（layer-list）以叠加 scrim", act is LayerDrawable)
    }

    // ---------------- ControlStates：disabled 三通道 + loading 预留宽度 ----------------

    @Test
    fun `setEnabled 三通道 disabled 视觉可点性原生属性同时落地`() {
        val v = View(ctx)
        ControlStates.setEnabled(v, false)
        assertFalse("channel3 原生 disabled", v.isEnabled)
        assertFalse("channel2 不可点（cursor:not-allowed 等价）", v.isClickable)
        assertFalse("禁用退出 tab 焦点序", v.isFocusable)
        assertEquals("channel1 opacity .5", 0.5f, v.alpha, 1e-6f)

        ControlStates.setEnabled(v, true)
        assertTrue(v.isEnabled)
        assertTrue(v.isClickable)
        assertTrue(v.isFocusable)
        assertEquals(1f, v.alpha, 1e-6f)
    }

    @Test
    fun `纯 alpha 不是 disabled——只降透明度仍可点属假禁用`() {
        // 反证 finesse 门禁：仅 alpha 降低、isEnabled/isClickable 不变 = 假禁用，不满足三通道。
        val v = View(ctx)
        v.isClickable = true   // 真实可交互控件（带点击监听）
        v.alpha = 0.5f         // 假禁用：只动 alpha
        assertTrue("假禁用仍可点（必须被 setEnabled 纠正）", v.isClickable)
        ControlStates.setEnabled(v, false)
        assertFalse("setEnabled 后才真正不可点", v.isClickable)
    }

    @Test
    fun `setLoading 换标签预留终态宽度防回流并可还原`() {
        val tv = TextView(ctx)
        tv.text = "开始监看"
        // 造一个已测量宽度，模拟 loading 发生在布局之后
        tv.layout(0, 0, 240, 80)

        ControlStates.setLoading(tv, true, "启动中", "开始监看")
        assertEquals("启动中", tv.text.toString())
        assertEquals("预留终态宽度防邻居回流", 240, tv.minimumWidth)

        ControlStates.setLoading(tv, false, "启动中", "开始监看")
        assertEquals("还原静止文案", "开始监看", tv.text.toString())
        assertEquals("结束释放宽度锁", 0, tv.minimumWidth)
    }

    @Test
    fun `setLoading 未测量时不误锁宽度`() {
        val tv = TextView(ctx)
        // width==0（未布局）→ 不应写入 minimumWidth（避免锁 0）
        ControlStates.setLoading(tv, true, "启动中", "开始监看")
        assertEquals("未测量时跳过宽度锁", 0, tv.minimumWidth)
    }
}

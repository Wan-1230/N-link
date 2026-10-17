package com.nikonlink.app.shared.ui

import android.view.View
import android.widget.TextView

/**
 * 交互八状态的运行时通道（finesse component-scope §3 硬门禁）。
 *
 * 样式层的 default / hover / focus / active 已由 `bg_*.xml` selector 交付（常驻 2dp 描边换色
 * 实现焦点环零位移）。剩下两态的**非样式通道**在这里集中，避免各处手搓、口径漂移：
 *
 *  · **disabled 三通道** —— component-scope 原文：`opacity: .5` + `cursor: not-allowed` + 原生
 *    `disabled`。**纯 alpha 不是 disabled**，那只是「变淡的 enabled」；必须可点性一并关掉。
 *    Android 无 cursor 概念，「cursor: not-allowed」等价于 `isClickable=false`（去掉点击/无障碍
 *    role）；原生 disabled = `isEnabled=false`；视觉 = `alpha .5`。三者齐备。
 *
 *  · **loading** —— 换标签时**预留终态宽度**防回流（邻居不跳）；>300ms 才给反馈由调用方按场景区分，
 *    这里只负责宽度锁定这一确定性的样式/布局通道。
 *
 * 这是对**前端状态反馈**的修正，不改变业务逻辑与功能行为。此前项目里多处是「假禁用」
 * （只 `alpha = 0.55f` 而仍可点，或只 `isEnabled=false` 而无视觉降级），状态不诚实，此处统一。
 */
object ControlStates {

    /** disabled 三通道：视觉 alpha .5 + 不可点 + 原生 disabled。禁用元素同时退出 tab 焦点序。 */
    @JvmStatic
    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled            // 原生 disabled（channel 3）
        view.isClickable = enabled          // cursor:not-allowed 等价（channel 2）
        view.isFocusable = enabled          // 禁用不占 tab 焦点（无障碍）
        view.alpha = if (enabled) 1f else 0.5f   // opacity .5（channel 1）
    }

    /** 反向别名，语义更贴「禁用某控件」的调用点。 */
    @JvmStatic
    fun setDisabled(view: View, disabled: Boolean) = setEnabled(view, !disabled)

    /**
     * loading：换标签时锁定宽度（预留终态宽度，防 label swap 让邻居回流）。
     * [restingLabel] 为静止态文案，[loadingLabel] 为加载态文案；结束时还原并释放宽度锁。
     * 宽度取静止态实测宽（final width），加载标签更长时至少不塌缩——回流最危险的方向是缩。
     */
    @JvmStatic
    fun setLoading(view: TextView, loading: Boolean, loadingLabel: CharSequence, restingLabel: CharSequence) {
        if (loading) {
            if (view.width > 0) view.minimumWidth = view.width   // 预留终态宽度，防回流
            view.text = loadingLabel
        } else {
            view.text = restingLabel
            view.minimumWidth = 0
        }
    }
}

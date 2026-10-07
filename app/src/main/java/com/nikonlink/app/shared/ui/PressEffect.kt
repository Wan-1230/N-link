package com.nikonlink.app.shared.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import com.nikonlink.app.shared.ui.glass.UiFlags

/**
 * 全局点击反馈：按压缩放至 95% + 松开回弹，时长走 [Motion.INSTANT]。
 * 设计规范：所有可点击元素统一触感；禁用元素无反馈。
 *
 * 视修复 T1（2026-10-07）：此前两处不达标——
 * ① 时长用自有常量 100ms 绕过了 Motion token（`grep 'setDuration([0-9]'` 口径
 *    抓不到常量引用，属审计盲区）；
 * ② 不尊重动效无障碍：系统「移除动画」（animator_duration_scale=0）或 App
 *    动效开关关闭时，按压缩放照旧在跑。现在经 [UiFlags.pressMotionEnabled]
 *    判定（不含玻璃条件——玻璃关掉时按压反馈仍属反馈语义，应保留），
 *    关闭时按下不做动画、松开直设终值 1.0。
 */
object PressEffect {

    private const val SCALE = 0.95f

    /** 附加缩放反馈，不影响原 clickListener（返回 false 继续分发） */
    @SuppressLint("ClickableViewAccessibility")
    fun apply(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    if (v.isEnabled && UiFlags.pressMotionEnabled(v.context)) {
                        v.animate().cancel()
                        v.animate().scaleX(SCALE).scaleY(SCALE).setDuration(Motion.INSTANT).start()
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    if (UiFlags.pressMotionEnabled(v.context)) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(Motion.INSTANT).start()
                    } else {
                        // 动画被系统/用户关闭：直设终值，保证任何中途状态都会归位
                        v.scaleX = 1f
                        v.scaleY = 1f
                    }
                }
            }
            false
        }
    }

    /** 批量附加 */
    fun apply(vararg views: View) {
        views.forEach { apply(it) }
    }
}

/** 便捷扩展 */
fun View.pressEffect() = PressEffect.apply(this)

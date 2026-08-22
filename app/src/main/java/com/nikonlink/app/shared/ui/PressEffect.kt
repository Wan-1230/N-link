package com.nikonlink.app.shared.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

/**
 * 全局点击反馈：0.1s 缩放至 95% + 松开 0.1s 回弹。
 * 设计规范：所有可点击元素统一触感；禁用元素无反馈。
 */
object PressEffect {

    private const val SCALE = 0.95f
    private const val DURATION = 100L

    /** 附加缩放反馈，不影响原 clickListener（返回 false 继续分发） */
    @SuppressLint("ClickableViewAccessibility")
    fun apply(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    if (v.isEnabled) {
                        v.animate().cancel()
                        v.animate().scaleX(SCALE).scaleY(SCALE).setDuration(DURATION).start()
                    }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(DURATION).start()
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

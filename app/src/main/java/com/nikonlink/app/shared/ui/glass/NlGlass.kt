package com.nikonlink.app.shared.ui.glass

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R

/**
 * 弹窗与 BottomSheet 的玻璃入口（PRD §10.5 M1 —— 整个改版里性价比最高的一处）。
 *
 * Android 12+ 的 `FLAG_BLUR_BEHIND` 由**系统合成器**完成背景模糊：不需要自建纹理、
 * 不进帧预算、在 Android 11 及以下自动无效并静默退化。所以这里只做两件事：
 * 开窗口级模糊 + 把面板底换成半透明染色让模糊透出来。
 *
 * 接入方式是把 `MaterialAlertDialogBuilder(` 换成 `NlGlass.dialog(`、
 * `BottomSheetDialog(` 换成 `NlGlass.sheet(` —— 文案、按钮语义、取消行为零改动（AC-3）。
 */
object NlGlass {

    /** 弹窗背后的模糊半径（PRD §3.2 `glass_blur_l`） */
    private const val BLUR_BEHIND_DP = 40f

    /** 入场时长，对齐 PRD §6.3 的「玻璃面出现 250ms / 弹窗模糊建立 220ms」 */
    private const val ENTER_MS = 220L

    /** 弹窗背后的暗度 */
    private const val DIM = 0.32f

    /** Material 标准 emphasized 曲线（0.4,0,0.2,1） */
    private val EMPHASIZE = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /** 替代 `MaterialAlertDialogBuilder(context)` */
    fun dialog(context: Context): MaterialAlertDialogBuilder = GlassAlertDialogBuilder(
        context,
        // 关闭玻璃时必须用 v2.2 原主题，不能只是"看起来差不多"（AC-12）
        if (UiFlags.glassEnabled(context)) R.style.NlDialog_Glass else R.style.NlDialog,
    )

    /** 替代 `BottomSheetDialog(context)` */
    fun sheet(context: Context): BottomSheetDialog {
        val sheet = BottomSheetDialog(context)
        hookSheetPanel(sheet)
        return sheet
    }

    /**
     * 给已经拿到手的 Dialog 上玻璃（自定义 setView 的弹窗、或不便换构造入口的调用点）。
     */
    fun applyTo(dialog: Dialog): Dialog {
        dialog.setOnShowListener { applyBlurBehind(dialog.window, dialog.context) }
        return dialog
    }

    /**
     * 开窗口级模糊。
     *
     * 返回 false = 当前环境不支持（Android 11 及以下 / 省电模式 / 降低透明度 / 经典外观），
     * 此时保持原有不透明面板 —— 静默退化，不提示、不崩（PRD §9.2）。
     */
    /**
     * 开窗口级模糊 + 入场动画。
     *
     * 返回 false = 当前环境不支持窗口模糊（Android 11 及以下 / 省电 / 降低透明度 / 经典外观），
     * 此时保持原有不透明面板 —— 静默退化，不提示、不崩（PRD §9.2）。
     *
     * 为什么要自己写入场动画：MDC 自带的弹窗动画只动面板自身，
     * 而 `blurBehindRadius` 和 `dimAmount` 是**开局即满值**的，于是视觉上出现
     * "背景先糊了、面板后才飘进来"的错位（用户反馈的"不同步、生硬"）。
     * 这里关掉系统窗口动画，把模糊半径、暗度、面板 alpha/缩放挂在同一条
     * [ValueAnimator] 上，用同一条曲线一起推进（PRD §6.3「弹窗背后模糊建立」220ms）。
     */
    fun applyBlurBehind(window: Window?, context: Context): Boolean =
        applyBlurBehind(window, context, animatePanel = true)

    /**
     * @param animatePanel BottomSheet 自己会滑上来，再给它套一层 alpha/缩放就是双重动画；
     *   所以 sheet 只要模糊与暗度跟上来（animatePanel=false）。
     */
    private fun applyBlurBehind(
        window: Window?,
        context: Context,
        animatePanel: Boolean,
    ): Boolean {
        if (window == null) return false
        val blur = UiFlags.windowBlurAvailable(context)
        val dm = context.resources.displayMetrics
        val maxPx = (BLUR_BEHIND_DP * dm.density).toInt()

        if (UiFlags.glassEnabled(context)) {
            val anim = animatePanel && UiFlags.motionEnabled(context)
            if (anim) {
                // 关掉 MDC 自带转场，避免两条时间线打架
                window.setWindowAnimations(0)
            }
            val decor = window.decorView
            window.setDimAmount(0f)
            if (blur) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply { blurBehindRadius = 0 }
            }
            if (anim) {
                decor.alpha = 0f
                decor.scaleX = 0.94f
                decor.scaleY = 0.94f
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = ENTER_MS
                    interpolator = EMPHASIZE
                    addUpdateListener { a ->
                        val t = a.animatedValue as Float
                        rampBlur(window, blur, maxPx, t)
                        decor.alpha = t
                        val s = 0.94f + 0.06f * t
                        decor.scaleX = s
                        decor.scaleY = s
                    }
                    start()
                }
            } else {
                rampBlur(window, blur, maxPx, 1f)
                window.setDimAmount(DIM)
            }
        }
        return blur
    }

    private fun rampBlur(window: Window, blur: Boolean, maxPx: Int, t: Float) {
        if (blur) {
            runCatching {
                window.attributes = window.attributes.apply {
                    blurBehindRadius = (maxPx * t).toInt()
                }
            }
        }
        window.setDimAmount(DIM * t)
    }

    /**
     * BottomSheet 的面板底默认是 `?attr/colorSurface`（不透明），
     * 换成玻璃染色才透得出窗口级模糊。
     */
    private fun hookSheetPanel(dialog: BottomSheetDialog) {
        val context = dialog.context
        dialog.setOnShowListener {
            applyBlurBehind(dialog.window, context)
            val panel = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet,
            ) ?: return@setOnShowListener
            if (UiFlags.glassEnabled(context)) {
                val radius = context.resources.getDimension(R.dimen.glass_radius_l)
                panel.applyGlass { GlassTokens.floatingBar(it.context).copy(radiusPx = radius) }
            } else {
                panel.setBackgroundColor(ContextCompat.getColor(context, R.color.surface))
            }
        }
    }

    /**
     * 走 create() 挂钩，所以 `.show()` 与 `.create().show()` 两种用法都覆盖到。
     * MDC 1.12 没有 `MaterialAlertDialog` 类，create() 的返回类型就是 appcompat 的 AlertDialog。
     */
    private class GlassAlertDialogBuilder(
        context: Context,
        themeResId: Int,
    ) : MaterialAlertDialogBuilder(context, themeResId) {

        override fun create(): AlertDialog {
            val d = super.create()
            applyBlurBehind(d.window, d.context)
            return d
        }
    }
}

package com.nikonlink.app.shared.ui.glass

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.Window
import android.view.WindowManager
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
    fun applyBlurBehind(window: Window?, context: Context): Boolean {
        if (window == null || !UiFlags.windowBlurAvailable(context)) return false
        return try {
            val px = (BLUR_BEHIND_DP * context.resources.displayMetrics.density).toInt()
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = px }
            window.setBackgroundBlurRadius(px)
            true
        } catch (e: Exception) {
            false
        }
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

package com.nikonlink.app.shared.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.nikonlink.app.R
import com.nikonlink.app.shared.ui.Motion

/**
 * 视S2 内联状态条 —— 「图标位 + 标题 + 副文案 + 动作按钮」，替代"错误全靠 Toast"。
 *
 * 背景：审计发现拍摄页无加载态、错误全走 NlFeedback Toast（转瞬即逝、无重试入口）。
 * 本组件给失败一个**留在页面上的位置**：一行标题说清发生了什么，副文案给建议，
 * 右侧动作按钮（重试/去设置）即是出口。
 *
 * 颜色纪律（视觉规范 v2.6.5 §1.1「错误/成功提示继续用文字表达，不上色」）：
 * 全组件黑白灰——容器 surface + 1px outline，图标位 chip_unselected，动作按钮
 * primary/on_primary 胶囊；语气靠文案，不靠红黄绿。
 *
 * 用法（接入方在失败分支调 [bind]，成功/重试后调 [hide]）。
 */
class NlStateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconBox = TextView(context)
    private val titleView = TextView(context)
    private val descView = TextView(context)
    private val actionBtn = TextView(context)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(dimenRes: Int): Float =
        resources.getDimensionPixelSize(dimenRes) / resources.displayMetrics.scaledDensity

    private fun roundedBg(): GradientDrawable = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(12)
        setPadding(pad, pad, dp(8), pad)

        iconBox.apply {
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(36), dp(36)).also { it.marginEnd = dp(12) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_body))
        }
        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_caption))
        }
        descView.apply {
            visibility = GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_foot))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(2) }
        }
        textCol.addView(titleView)
        textCol.addView(descView)
        actionBtn.apply {
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).also { it.marginStart = dp(12) }
            setPadding(dp(14), 0, dp(14), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_foot))
        }
        addView(iconBox)
        addView(textCol)
        addView(actionBtn)
    }

    /**
     * 渲染状态条。actionText/onAction 成对给才有按钮；desc 可省。
     */
    fun bind(
        title: CharSequence,
        desc: CharSequence? = null,
        icon: CharSequence = "!",
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ) {
        iconBox.text = icon
        titleView.text = title
        if (desc.isNullOrBlank()) {
            descView.visibility = GONE
        } else {
            descView.text = desc
            descView.visibility = VISIBLE
        }
        if (actionText.isNullOrBlank() || onAction == null) {
            actionBtn.visibility = GONE
            actionBtn.setOnClickListener(null)
        } else {
            actionBtn.text = actionText
            actionBtn.visibility = VISIBLE
            actionBtn.setOnClickListener {
                it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(Motion.FAST)
                    .withEndAction {
                        it.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FAST).start()
                        onAction()
                    }.start()
            }
        }
        // 主题色 bind 时取：全部新建 drawable（初值本就为 null，不能复用强转），
        // 深浅切换后重新 bind 即生效
        background = roundedBg().apply {
            setColor(context.getColor(R.color.surface))
            setStroke(dp(1), context.getColor(R.color.outline))
        }
        iconBox.background = GradientDrawable().apply {
            setColor(context.getColor(R.color.chip_unselected))
            cornerRadius = dp(10).toFloat()
        }
        actionBtn.background = GradientDrawable().apply {
            setColor(context.getColor(R.color.primary))
            cornerRadius = dp(999).toFloat()
        }
        titleView.setTextColor(context.getColor(R.color.text_primary))
        descView.setTextColor(context.getColor(R.color.text_secondary))
        iconBox.setTextColor(context.getColor(R.color.text_primary))
        actionBtn.setTextColor(context.getColor(R.color.on_primary))
    }

    /** 收起状态条（重试成功/状态解除时调用）。 */
    fun hide() {
        visibility = GONE
    }

    /** 显示（bind 后调用；分开是为了让接入方能在布局里先占位）。 */
    fun show() {
        visibility = VISIBLE
    }
}

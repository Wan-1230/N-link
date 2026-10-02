package com.nikonlink.app.shared.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nikonlink.app.R

/**
 * 工具栏条目（飓风相机参考图 060/067）：图标 + 状态角标 + 标签竖排。
 * 角标用于 A（自动）/ ON（开启）/ 当前模式缩写；关闭态整体置灰。
 */
class ToolItemView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView = ImageView(context)
    private val badgeView = TextView(context)
    private val labelView = TextView(context)

    // 角标走中性白：强调色只留给「当前值/激活态」的读数控件（刻度尺/分段/跟焦轮），
    // 工具栏角标属于常驻装饰，上色会让底部整片发花
    private val labelOn = 0xE6FFFFFF.toInt()
    private val labelOff = 0x80FFFFFF.toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(6f), 0, dp(4f))

        val iconBox = FrameLayout(context)
        iconView.layoutParams = FrameLayout.LayoutParams(dp(25f), dp(25f)).apply {
            gravity = Gravity.CENTER
        }
        badgeView.textSize = 9f
        badgeView.setTypeface(badgeView.typeface, android.graphics.Typeface.BOLD)
        badgeView.setTextColor(labelOn)
        badgeView.visibility = GONE
        badgeView.layoutParams = FrameLayout.LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.END }
        iconBox.addView(iconView)
        iconBox.addView(badgeView)
        addView(iconBox, LayoutParams(dp(30f), dp(28f)))

        labelView.textSize = 11f
        labelView.setTextColor(labelOn)
        labelView.layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2f) }
        addView(labelView)

        val ta = context.obtainStyledAttributes(attrs, R.styleable.ToolItemView)
        val icon = ta.getResourceId(R.styleable.ToolItemView_toolIcon, 0)
        if (icon != 0) iconView.setImageResource(icon)
        labelView.text = ta.getString(R.styleable.ToolItemView_toolLabel).orEmpty()
        ta.recycle()
        iconView.setColorFilter(labelOn)
    }

    /**
     * @param badge 角标文本（A / ON / 模式缩写），null 或空串不显示
     * @param active 功能开启态：false 时图标与标签置灰
     */
    fun setState(badge: String?, active: Boolean) {
        if (badge.isNullOrBlank()) {
            badgeView.visibility = GONE
        } else {
            badgeView.text = badge
            badgeView.visibility = VISIBLE
        }
        val tint = if (active) labelOn else labelOff
        labelView.setTextColor(if (active) labelOn else labelOff)
        iconView.setColorFilter(tint)
        iconView.alpha = if (active) 1f else 0.6f
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
}

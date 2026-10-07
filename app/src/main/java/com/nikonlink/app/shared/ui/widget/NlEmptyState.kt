package com.nikonlink.app.shared.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nikonlink.app.R
import com.nikonlink.app.shared.ui.Motion

/**
 * 视S2 空态组件 —— 「图标容器 + 粗标题 + 副文案 + 行动按钮」，替代代码拼的一行灰字。
 *
 * 实机取证依据（2026-10-04）：成熟相册的空态
 * 是一个居中设计组件，而不是一句提示。三件套收口：
 * - iconImage：72dp 圆角方块容器（bg_skeleton 底）内的**矢量图**，缺省隐藏；
 * - title：font_title 粗体一级色，主信息；
 * - desc：font_caption 二级色引导语，可省；
 * - action：primary/on_primary 胶囊按钮（黑白反相，不占尼康黄强调色）。
 *
 * **只有矢量图一条通道**（v2.7）：此前还有一个 `icon: CharSequence` 的 emoji 通道，
 * 换完最后两处占位（📷 相机、🕐 时钟）后已零调用，遂整条拆掉 ——
 * 留着等于给"下次又塞个 emoji 进来"开门。空态图标统一走 res/drawable 矢量资源。
 *
 * 组件不持有业务语义；接入方（DashboardFragment.emptyHint、相册 layoutEmpty）负责给文案。
 * 颜色在 [bind] 时取，主题切换后重新 bind 即生效。
 */
class NlEmptyState @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconImage = ImageView(context)
    private val titleView = TextView(context)
    private val descView = TextView(context)
    private val actionBtn = TextView(context)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun sp(dimenRes: Int): Float =
        resources.getDimensionPixelSize(dimenRes) / resources.displayMetrics.scaledDensity

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(32), dp(24), dp(32))

        // 与相册空态（fragment_transfer 的 ivEmptyIcon）保持同一观感：
        // 72dp 圆角方块容器（规划 §2.2「图标容器」）+ 6dp 内衬，图标 fitCenter 居中。
        // 图标是纯装饰（信息由紧随其后的 title 承载），故对 TalkBack 屏蔽避免重复播报。
        iconImage.apply {
            visibility = GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setBackgroundResource(R.drawable.bg_skeleton)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LayoutParams(dp(72), dp(72)).also { it.bottomMargin = dp(16) }
        }
        titleView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_title))
        }
        descView.apply {
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(4) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_caption))
        }
        actionBtn.apply {
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).also { it.topMargin = dp(20) }
            setPadding(dp(20), 0, dp(20), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(R.dimen.font_caption))
        }
        addView(iconImage)
        addView(titleView)
        addView(descView)
        addView(actionBtn)
    }

    /** 行动按钮统一按压反馈（0.95 缩放，Motion.FAST，与 PressEffect 同参数） */
    private fun bindAction(onAction: () -> Unit) {
        actionBtn.setOnClickListener {
            it.animate().scaleX(0.95f).scaleY(0.95f).setDuration(Motion.FAST)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FAST).start()
                    onAction()
                }.start()
        }
    }

    /**
     * 渲染空态。[iconRes] 为 0 时隐藏图标容器（紧凑模式，供列表内嵌使用）；
     * actionText/onAction 成对给才有按钮。
     */
    fun bind(
        title: CharSequence,
        desc: CharSequence? = null,
        iconRes: Int = 0,
        actionText: CharSequence? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (iconRes != 0) {
            iconImage.setImageResource(iconRes)
            iconImage.visibility = VISIBLE
        } else {
            iconImage.visibility = GONE
        }
        titleView.text = title
        if (desc.isNullOrBlank()) {
            descView.visibility = GONE
        } else {
            descView.text = desc
            descView.visibility = VISIBLE
        }
        if (actionText.isNullOrBlank() || onAction == null) {
            actionBtn.visibility = GONE
        } else {
            actionBtn.text = actionText
            actionBtn.visibility = VISIBLE
            bindAction(onAction)
        }
        // 主题色在 bind 时取：每次新建 drawable（初值本就为 null，不能复用强转），
        // 深浅切换后重新 bind 即生效
        actionBtn.background = GradientDrawable().apply {
            setColor(context.getColor(R.color.primary))
            cornerRadius = dp(999).toFloat()
        }
        titleView.setTextColor(context.getColor(R.color.text_primary))
        descView.setTextColor(context.getColor(R.color.text_secondary))
        actionBtn.setTextColor(context.getColor(R.color.on_primary))
    }
}

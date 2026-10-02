package com.nikonlink.app.shared.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nikonlink.app.R

/**
 * 分段控件（飓风相机参考图 018 的「手动/自动」胶囊）：轨道 + 选中段高亮。
 * 用于 AF 模式三选、测光模式四选。
 */
class SegmentBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var onSelected: ((Int) -> Unit)? = null

    private val accent = ContextCompat.getColor(context, R.color.lv_accent)
    private val textOn = 0xE6FFFFFF.toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_lv_seg_track)
        val p = dp(4f)
        setPadding(p, p, p, p)
    }

    fun setItems(items: List<String>, selected: Int, onSelected: (Int) -> Unit) {
        this.onSelected = onSelected
        removeAllViews()
        items.forEachIndexed { index, label ->
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(9f), 0, dp(9f))
                setOnClickListener { this@SegmentBar.onSelected?.invoke(index) }
            }
            addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        updateSelection(selected)
    }

    fun updateSelection(index: Int) {
        for (i in 0 until childCount) {
            val tv = getChildAt(i) as TextView
            val sel = i == index
            if (sel) tv.setBackgroundResource(R.drawable.bg_lv_seg_sel) else tv.background = null
            tv.setTextColor(if (sel) accent else textOn)
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
}

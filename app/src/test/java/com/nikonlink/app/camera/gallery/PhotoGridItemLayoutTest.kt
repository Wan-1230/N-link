package com.nikonlink.app.camera.gallery

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.nikonlink.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 网格项压平为单层 ConstraintLayout 后的几何契约。
 *
 * 之前为了 1:1 套了两层 ConstraintLayout；比例现在直接挂在缩略图上，
 * 这里钉住两件事：格子仍按宽度得到正方形高度；覆盖层仍与缩略图严格同边界。
 * 任何一条失守都会在真机上表现为格子被压扁或对勾/角标错位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-xxhdpi")
class PhotoGridItemLayoutTest {

    @Test
    fun `网格项按列宽保持正方形且覆盖层贴合缩略图`() {
        val context = RuntimeEnvironment.getApplication()
        val parent = FrameLayout(context)
        val item = LayoutInflater.from(context)
            .inflate(R.layout.item_photo_grid, parent, false)
        // 这些覆盖层默认 GONE，GONE 的 View 不参与测量；先全部展开再钉几何契约
        listOf(
            R.id.progressThumb,
            R.id.viewSelectedMask,
            R.id.ivMarkBadge,
            R.id.checkContainer,
            R.id.tvDownloadedBadge,
        ).forEach { item.findViewById<View>(it).visibility = View.VISIBLE }

        val cellWidth = context.resources.displayMetrics.widthPixels / 3
        item.measure(
            View.MeasureSpec.makeMeasureSpec(cellWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        item.layout(0, 0, item.measuredWidth, item.measuredHeight)

        assertEquals("格子宽度必须等于列宽", cellWidth, item.measuredWidth)
        assertEquals("格子高度必须等于宽度", cellWidth, item.measuredHeight)

        val thumb = item.findViewById<View>(R.id.ivThumb)
        assertEquals("缩略图必须自身成正方形", thumb.measuredWidth, thumb.measuredHeight)

        listOf(
            R.id.progressThumb to "加载占位",
            R.id.viewSelectedMask to "选中遮罩",
            R.id.tvFormatBadge to "格式角标",
            R.id.tvDownloadedBadge to "已下载角标",
        ).forEach { (id, name) ->
            val child = item.findViewById<View>(id)
            org.junit.Assert.assertTrue("$name 必须有非零尺寸", child.width > 0 && child.height > 0)
        }

        val mask = item.findViewById<View>(R.id.viewSelectedMask)
        assertEquals("选中遮罩左边必须贴合缩略图", thumb.left, mask.left)
        assertEquals("选中遮罩上边必须贴合缩略图", thumb.top, mask.top)
        assertEquals("选中遮罩右边必须贴合缩略图", thumb.right, mask.right)
        assertEquals("选中遮罩下边必须贴合缩略图", thumb.bottom, mask.bottom)
    }
}

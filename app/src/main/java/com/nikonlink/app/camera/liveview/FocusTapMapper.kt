package com.nikonlink.app.camera.liveview

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import android.widget.ImageView

/**
 * 监看画面触摸点 → 归一化取景坐标 的统一换算。
 *
 * 为什么需要这个工具：相机侧 ChangeAfArea(0x9205) 的目标点是按 **liveview 图像内容**
 * 的归一化位置理解的，任何"按 View 尺寸换算"或"忽略缩放"的近似都会让实际对焦点
 * 偏离用户点击的位置。历史上遥控页把黑边算进归一化坐标（点画面边缘发出图像外的点）、
 * 全屏页忽略双指缩放（放大后点哪儿都偏），都是同一类口径错误。
 *
 * 换算分两步：
 * 1. **View 级缩放还原**：全屏页的双指缩放通过 [View.setScaleX]/[setScaleY]（围绕 pivot）
 *    实现，不含在 imageMatrix 里。先把触摸点从"缩放后视图坐标"还原到"未缩放视图坐标"。
 * 2. **fitCenter 映射**：ImageView scaleType=fitCenter 时图像按比例居中，四周留黑边；
 *    用 imageMatrix 求出图像实际显示矩形，只在矩形内归一化，点黑边直接忽略。
 *
 * 返回 null 表示点击落在图像区域之外（黑边 / 越界），调用方不应下发对焦。
 */
object FocusTapMapper {

    /** 归一化结果：x/y ∈ 0..1，相对 liveview 图像内容 */
    data class NormalizedTap(val x: Float, val y: Float)

    fun mapToNormalized(imageView: ImageView, tapX: Float, tapY: Float): NormalizedTap? {
        val viewScaleX = imageView.scaleX
        val viewScaleY = imageView.scaleY
        val pivotX = imageView.pivotX
        val pivotY = imageView.pivotY
        // Step 1: 还原 View 级缩放（pivot 默认在视图中心）
        val viewX = pivotX + (tapX - pivotX) / viewScaleX
        val viewY = pivotY + (tapY - pivotY) / viewScaleY

        val drawable = imageView.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null

        // Step 2: imageMatrix（fitCenter）求图像实际显示区域
        val rect = RectF(
            0f, 0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        Matrix(imageView.imageMatrix).mapRect(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null
        if (!rect.contains(viewX, viewY)) return null

        val nx = ((viewX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((viewY - rect.top) / rect.height()).coerceIn(0f, 1f)
        return NormalizedTap(nx, ny)
    }

    /** 图像实际显示矩形（View 坐标系，已含 View 级缩放），对焦点框定位/命中测试用 */
    fun displayedImageRect(imageView: ImageView): RectF? {
        val drawable = imageView.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        val rect = RectF(
            0f, 0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        Matrix(imageView.imageMatrix).mapRect(rect)
        val scaleX = imageView.scaleX
        val scaleY = imageView.scaleY
        if (scaleX != 1f || scaleY != 1f) {
            // View 级缩放围绕 pivot 进行，把矩阵矩形同步缩放
            val pivotX = imageView.pivotX
            val pivotY = imageView.pivotY
            val left = pivotX + (rect.left - pivotX) * scaleX
            val top = pivotY + (rect.top - pivotY) * scaleY
            val right = pivotX + (rect.right - pivotX) * scaleX
            val bottom = pivotY + (rect.bottom - pivotY) * scaleY
            rect.set(left, top, right, bottom)
        }
        return if (rect.width() > 0 && rect.height() > 0) rect else null
    }
}

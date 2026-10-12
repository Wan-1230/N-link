package com.nikonlink.app.camera.liveview

import android.graphics.Matrix
import android.graphics.RectF
import android.widget.ImageView

/**
 * 监看画面触摸点 → 归一化取景坐标 的统一换算。
 *
 * 为什么需要这个工具：相机侧 ChangeAfArea(0x9205) 的目标点是按 **liveview 图像内容**
 * 的归一化位置理解的，任何"按 View 尺寸换算"的近似都会让实际对焦点偏离用户点击的位置。
 * 历史上遥控页把黑边算进归一化坐标（点画面边缘发出图像外的点），都是同一类口径错误。
 *
 * 换算只有一步：
 * 1. **fitCenter 映射**：ImageView scaleType=MATRIX 时图像按比例居中，四周留黑边；
 *    用 imageMatrix 求出图像实际显示矩形，只在矩形内归一化，点黑边直接忽略。
 *
 * 关于双指缩放：全屏页的缩放由 [LiveViewZoomController] 做在 **imageMatrix** 里
 * （唯一变换源），View 自身的 scaleX/scaleY 恒为 1 —— 因此这里**不需要**再做
 * "View 级缩放还原"：触摸事件经框架逆变换后本就是视图本地（未缩放）坐标，
 * 若再除一次 scale 反而会把放大后的点击双双拉向支点（旧实现此处是按已废弃的
 * setScaleX 方案写的，随模块 1 矩阵化重构一并更正）。
 *
 * 返回 null 表示点击落在图像区域之外（黑边 / 越界），调用方不应下发对焦。
 */
object FocusTapMapper {

    /** 归一化结果：x/y ∈ 0..1，相对 liveview 图像内容 */
    data class NormalizedTap(val x: Float, val y: Float)

    fun mapToNormalized(imageView: ImageView, tapX: Float, tapY: Float): NormalizedTap? {
        // tapX/tapY 为 ImageView 本地坐标（框架已做逆变换；缩放全在 imageMatrix 里）
        val viewX = tapX
        val viewY = tapY

        val drawable = imageView.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null

        // imageMatrix（含双指缩放/平移）求图像实际显示区域
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

    /**
     * 图像实际显示矩形（View 坐标系，已含 imageMatrix 里的缩放/平移），
     * 对焦点框定位/命中测试用。注意：当前无调用方（预留），改动前先 grep 确认。
     */
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

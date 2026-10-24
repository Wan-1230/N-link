package com.nikonlink.app.camera.liveview

import com.nikonlink.app.device.ptp.PtpConstants
import kotlin.math.roundToInt

/**
 * 归一化监看点击 → 相机 AF 坐标域（ChangeAfArea 0x9205）的**纯换算**，无 Android 依赖，可单测。
 *
 * 口径与证据（2026-10-12 对相机厂商官方应用与一款成熟第三方控制应用的报文分析，
 * 两者独立收敛到同一结论）：
 * - 相机 AF 坐标域就是 live view 图像的 wholeSize 像素空间：厂商官方应用按
 *   `afX = tapX / (viewWidth / wholeWidth)` 单系数线性映射，AF 区域回读同空间作画；
 *   另一款第三方实现用「归一化 × displayArea」（displayArea 缺省即 wholeSize）。
 * - wholeSize 与图像**同宽高比**（官方应用把图像画满 wholeSize 箱后 AF 框才与画面重合），
 *   因此按帧真实宽高比线性映射，而不是把帧塞进固定 4:3 箱。
 * - 历史误区：固定 4:3 箱 + letterbox 的假设无实机标定，帧≠4:3 时纵向系统偏移。
 *
 * 箱宽取标称值 [PtpConstants.AF_COORD_MAX_X]（4000，第三方实现流量实测上限），
 * 箱高按帧宽高比折算；帧尺寸未知时退回标称 4:3 兜底。
 */
object AfCoordMapper {

    /** 帧宽高比对应的 AF 箱高（箱宽固定为 [PtpConstants.AF_COORD_MAX_X]）。 */
    fun boxHeightForFrame(frameWidth: Int, frameHeight: Int): Int {
        if (frameWidth <= 0 || frameHeight <= 0) return PtpConstants.AF_COORD_MAX_Y
        return (PtpConstants.AF_COORD_MAX_X.toFloat() * frameHeight / frameWidth)
            .roundToInt().coerceAtLeast(1)
    }

    /**
     * 已知相机自报 wholeSize 时的精确换算（[LiveViewHeader] 解析成功）。
     * 官方实现就是 `afX = tapX / (viewWidth / wholeWidth)`、x/y 同一系数。
     */
    fun mapToBox(nx: Float, ny: Float, boxWidth: Int, boxHeight: Int): Pair<Int, Int> {
        if (boxWidth <= 0 || boxHeight <= 0) return map(nx, ny, 0, 0)
        val afX = (nx * boxWidth).roundToInt().coerceIn(0, boxWidth)
        val afY = (ny * boxHeight).roundToInt().coerceIn(0, boxHeight)
        return afX to afY
    }

    /**
     * 旧路径（帧头解析失败时的 4000×3000 标称域兜底）。
     * @param nx 归一化 X（0..1，相对监看图像内容）
     * @param ny 归一化 Y（0..1，相对监看图像内容）
     * @return AF 坐标 (x ∈ 0..4000, y ∈ 0..箱高)，已钳位在箱内
     */
    fun map(nx: Float, ny: Float, frameWidth: Int, frameHeight: Int): Pair<Int, Int> {
        val boxW = PtpConstants.AF_COORD_MAX_X
        val boxH = boxHeightForFrame(frameWidth, frameHeight)
        val afX = (nx * boxW).roundToInt().coerceIn(0, boxW)
        val afY = (ny * boxH).roundToInt().coerceIn(0, boxH)
        return afX to afY
    }
}

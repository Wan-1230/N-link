package com.nikonlink.app.camera.liveview

import com.nikonlink.app.device.ptp.PtpConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AF 坐标映射单测（2026-10-12 对焦校准修复）。
 *
 * 口径：AF 坐标箱与 live view 帧同宽高比、线性映射（相机厂商官方应用与一款第三方
 * 控制应用的报文分析证实，见 AfCoordMapper 文档）。这里钉住三件事：
 * ① 帧=4:3 时退化为旧直乘（无行为变化）；
 * ② 帧≠4:3 时箱高按帧宽高比折算，四角与边缘可达（旧 letterbox 假设把边缘勒出 11% 偏移）；
 * ③ 帧尺寸未知时退回标称 4:3 兜底，钳位恒在箱内。
 */
class AfCoordMapperTest {

    @Test
    fun `4x3 frame degenerates to direct multiply`() {
        // 640x480 = 4:3 → 箱高 3000，与旧直乘完全一致
        assertEquals(3000, AfCoordMapper.boxHeightForFrame(640, 480))
        val (x, y) = AfCoordMapper.map(0.5f, 0.5f, 640, 480)
        assertEquals(2000, x)
        assertEquals(1500, y)
    }

    @Test
    fun `3x2 frame box height follows frame aspect`() {
        // 640x424 ≈ 3:2 → 箱高 round(4000 * 424/640) = 2650，不是 3000
        assertEquals(2650, AfCoordMapper.boxHeightForFrame(640, 424))
        val topLeft = AfCoordMapper.map(0f, 0f, 640, 424)
        assertEquals(0 to 0, topLeft)
        val bottomRight = AfCoordMapper.map(1f, 1f, 640, 424)
        assertEquals(4000 to 2650, bottomRight)
        val center = AfCoordMapper.map(0.5f, 0.5f, 640, 424)
        assertEquals(2000 to 1325, center)
    }

    @Test
    fun `16x9 frame box height follows frame aspect`() {
        assertEquals(2250, AfCoordMapper.boxHeightForFrame(1920, 1080))
        val (x, y) = AfCoordMapper.map(1f, 1f, 1920, 1080)
        assertEquals(4000, x)
        assertEquals(2250, y)
    }

    @Test
    fun `unknown frame size falls back to nominal 4x3 box`() {
        assertEquals(PtpConstants.AF_COORD_MAX_Y, AfCoordMapper.boxHeightForFrame(0, 0))
        assertEquals(PtpConstants.AF_COORD_MAX_Y, AfCoordMapper.boxHeightForFrame(-1, 424))
        val (x, y) = AfCoordMapper.map(0.5f, 0.5f, 0, 0)
        assertEquals(2000 to 1500, x to y)
    }

    @Test
    fun `out of range taps are clamped into the box`() {
        val (x, y) = AfCoordMapper.map(1.5f, -0.5f, 640, 424)
        assertEquals(4000, x)
        assertEquals(0, y)
    }

    @Test
    fun `edge taps stay on the edge - letterbox regression guard`() {
        // 旧 letterbox 实现：3:2 帧点顶部会发出 y≈167（箱 5.6% 处），相机对焦点
        // 因此系统性地偏离点击。新实现四角必须精确落在箱角。
        for ((fw, fh) in listOf(640 to 424, 1920 to 1080, 3712 to 3712)) {
            assertEquals(0 to 0, AfCoordMapper.map(0f, 0f, fw, fh))
            val boxH = AfCoordMapper.boxHeightForFrame(fw, fh)
            assertEquals(
                PtpConstants.AF_COORD_MAX_X to boxH,
                AfCoordMapper.map(1f, 1f, fw, fh)
            )
        }
    }
}

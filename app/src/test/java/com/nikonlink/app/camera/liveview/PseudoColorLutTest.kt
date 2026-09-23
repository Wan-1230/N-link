package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-13 伪彩映射表。
 *
 * 锁三件事：**每一级亮度都有归属**、**档号随亮度单调**、
 * **亮度口径与直方图是同一个函数**。这三条错了，用户在取景器里看到的颜色就是假信息，
 * 而假信息比没有信息更坏——它会让人按错曝光。
 */
class PseudoColorLutTest {

    @Test
    fun `0 到 255 每一级都落在某一档，没有漏档`() {
        for (luma in 0..255) {
            val band = PseudoColorLut.bandOf(luma)
            assertTrue("亮度 $luma 没有归属档位", band in 0 until PseudoColorLut.BAND_COUNT)
        }
        assertEquals(
            "最后一条上界必须是 256，否则高亮端有区间没人管",
            256, PseudoColorLut.bandUpper(PseudoColorLut.BAND_COUNT - 1)
        )
    }

    @Test
    fun `档号随亮度单调不降`() {
        var previous = 0
        for (luma in 0..255) {
            val band = PseudoColorLut.bandOf(luma)
            assertTrue("亮度 $luma 的档号 $band 比上一级 $previous 还小", band >= previous)
            previous = band
        }
    }

    @Test
    fun `两端各有专属档，中间按上界归位`() {
        assertEquals(0, PseudoColorLut.bandOf(0))
        assertEquals(0, PseudoColorLut.bandOf(7))     // 第 0 档上界 8（不含）
        assertEquals(1, PseudoColorLut.bandOf(8))     // 边界归下一档
        assertEquals(10, PseudoColorLut.bandOf(249))
        assertEquals(11, PseudoColorLut.bandOf(250))
        assertEquals(11, PseudoColorLut.bandOf(255))
    }

    @Test
    fun `越界亮度就近截断而不是抛`() {
        assertEquals(PseudoColorLut.bandOf(0), PseudoColorLut.bandOf(-40))
        assertEquals(PseudoColorLut.bandOf(255), PseudoColorLut.bandOf(9999))
    }

    @Test
    fun `颜色顺序读得通——暗端冷、中间暖、溢出白`() {
        val darkest = PseudoColorLut.colorOfBand(0)
        val mid = PseudoColorLut.colorOfBand(6)
        val clipped = PseudoColorLut.colorOfBand(PseudoColorLut.BAND_COUNT - 1)

        assertTrue("暗端应以蓝为主", blueOf(darkest) > redOf(darkest))
        assertTrue(
            "中档应偏黄（红绿都高、蓝明显更低）",
            redOf(mid) > 150 && greenOf(mid) > 150 && blueOf(mid) < redOf(mid)
        )
        assertEquals("溢出档就是纯白，一眼认得", 0xFFFFFFFF.toInt(), clipped)
    }

    @Test
    fun `查表与逐档上色结果一致，且全部不透明`() {
        for (luma in 0..255) {
            val fromPalette = PseudoColorLut.palette[luma]
            assertEquals(PseudoColorLut.colorOf(luma), fromPalette)
            assertEquals("叠加透明度由绘制方给，表里必须是不透明色", 0xFF, fromPalette shr 24 and 0xFF)
        }
    }

    @Test
    fun `亮度算法是 Rec601，与直方图同一口径`() {
        assertEquals(0, PseudoColorLut.lumaOf(0xFF000000.toInt()))
        assertEquals("纯白（不含 alpha 通道）亮度 255", 255, PseudoColorLut.lumaOf(0x00FFFFFF))
        assertEquals(76, PseudoColorLut.lumaOf(0x00FF0000))
        assertEquals(149, PseudoColorLut.lumaOf(0x0000FF00))
        assertEquals(29, PseudoColorLut.lumaOf(0x000000FF))
        // 18% 灰卡：中灰落在绿档，与"曝光正常"的直觉对上
        assertEquals(128, PseudoColorLut.lumaOf(0x00808080))
        assertEquals(5, PseudoColorLut.bandOf(128))
    }

    private fun redOf(color: Int) = color shr 16 and 0xFF
    private fun greenOf(color: Int) = color shr 8 and 0xFF
    private fun blueOf(color: Int) = color and 0xFF
}

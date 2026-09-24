package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-13 第二件的累加器。
 *
 * 波形这东西一旦算错就是**给人看错的曝光**：档位偏移、列位置错乱、某一通道静默不落，
 * 都会让画面看着正常而仪器说反话。所以这里钉的全是"位置对不对、数有没有丢"，
 * 不管好不好看——好看与否只能真机判（§九 矩阵）。
 */
class RgbWaveformTest {

    private val columns = 128
    private val bins = 64

    private fun wave() = RgbWaveform(columns, bins)

    private fun gray(level: Int) = 0xFF000000.toInt() or (level shl 16) or (level shl 8) or level

    private fun total(waveform: RgbWaveform, channel: Int): Int {
        var sum = 0
        for (c in 0 until columns) for (b in 0 until bins) sum += waveform.countOf(channel, c, b)
        return sum
    }

    @Test
    fun `纯黑帧只落在最低档，三个通道都在`() {
        val waveform = wave()
        val pixels = IntArray(4 * 2) { gray(0) }
        waveform.accumulate(pixels, 4, 2)

        assertEquals(8, total(waveform, RgbWaveform.CHANNEL_RED))
        assertEquals(8, total(waveform, RgbWaveform.CHANNEL_GREEN))
        assertEquals(8, total(waveform, RgbWaveform.CHANNEL_BLUE))
        for (c in 0 until columns) {
            assertEquals(0, waveform.countOf(RgbWaveform.CHANNEL_RED, c, 1))
        }
        // 源宽 4 → 只占 0/32/64/96 四列，每列 2 个像素，峰值就是这两行
        assertEquals(2, waveform.peak)
    }

    @Test
    fun `纯白帧落在最高档而不是溢出到数组外`() {
        val waveform = wave()
        waveform.accumulate(IntArray(4 * 2) { gray(255) }, 4, 2)
        assertEquals(8, total(waveform, RgbWaveform.CHANNEL_RED))
        var inTopBin = 0
        for (c in 0 until columns) inTopBin += waveform.countOf(RgbWaveform.CHANNEL_RED, c, bins - 1)
        assertEquals("255 必须归最后一档", 8, inTopBin)
    }

    @Test
    fun `三通道各归各的档，蓝色单独溢出看得见`() {
        val waveform = wave()
        // 一半像素纯蓝拉满，一半像素纯红压底
        val pixels = intArrayOf(0xFF0000FF.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFFFF0000.toInt())
        waveform.accumulate(pixels, 4, 1)

        val blueHigh = waveform.countOf(RgbWaveform.CHANNEL_BLUE, 0, bins - 1) +
            waveform.countOf(RgbWaveform.CHANNEL_BLUE, 64, bins - 1)
        val redHigh = waveform.countOf(RgbWaveform.CHANNEL_RED, 32, bins - 1)
        assertTrue("两个纯蓝像素应落在蓝通道最高档（x=0 与 x=2 两列）", blueHigh >= 2)
        assertTrue("纯红像素要按自己的列位落在红通道最高档", redHigh >= 1)
        // 纯蓝像素的绿/红分量为 0，不该混进高档
        assertEquals(0, waveform.countOf(RgbWaveform.CHANNEL_GREEN, 0, bins - 1))
    }

    @Test
    fun `横向位置保留——左亮右暗能分出左右`() {
        val waveform = wave()
        // 用 255 而不是 250：最高档 bins-1 只有 255 落得进（250 → 第 62 档）
        val pixels = intArrayOf(gray(255), gray(255), gray(5), gray(5))
        waveform.accumulate(pixels, 4, 1)

        var leftInHighBin = 0
        var rightInHighBin = 0
        for (c in 0 until columns) {
            val high = waveform.countOf(RgbWaveform.CHANNEL_RED, c, bins - 1) +
                waveform.countOf(RgbWaveform.CHANNEL_GREEN, c, bins - 1) +
                waveform.countOf(RgbWaveform.CHANNEL_BLUE, c, bins - 1)
            if (c < columns / 2) leftInHighBin += high else rightInHighBin += high
        }
        assertTrue("亮的两列必须在左半边（$leftInHighBin vs $rightInHighBin）", leftInHighBin > 0)
        assertEquals("右半边不该有任何高电平样本", 0, rightInHighBin)
    }

    @Test
    fun `每个有效像素贡献三次累加，一个不多一个不少`() {
        val waveform = wave()
        val pixels = IntArray(9 * 3) { gray(it * 7 and 0xFF) }
        waveform.accumulate(pixels, 9, 3)
        val perChannel = pixels.size
        assertEquals(perChannel, total(waveform, RgbWaveform.CHANNEL_RED))
        assertEquals(perChannel, total(waveform, RgbWaveform.CHANNEL_GREEN))
        assertEquals(perChannel, total(waveform, RgbWaveform.CHANNEL_BLUE))
    }

    @Test
    fun `透明像素是清底残留，不该被算成死黑`() {
        val waveform = wave()
        val transparent = 0x00FFFFFF
        waveform.accumulate(intArrayOf(transparent, transparent, gray(200), gray(200)), 4, 1)
        // 两个不透明像素各贡献 R/G/B 一次；透明的两个必须整个不计
        assertEquals(2, total(waveform, RgbWaveform.CHANNEL_RED))
        assertEquals(2, total(waveform, RgbWaveform.CHANNEL_BLUE))
        for (c in 0 until columns) {
            assertEquals("透明像素不该在最低档留下影子", 0, waveform.countOf(RgbWaveform.CHANNEL_RED, c, 0))
        }
    }

    @Test
    fun `clear 之后计数与峰值一起归零`() {
        val waveform = wave()
        waveform.accumulate(IntArray(4) { gray(128) }, 4, 1)
        assertTrue(waveform.peak > 0)
        waveform.clear()
        assertEquals(0, waveform.peak)
        assertEquals(0, total(waveform, RgbWaveform.CHANNEL_RED))
    }

    @Test
    fun `档位与列号的边界不越界`() {
        val waveform = wave()
        assertEquals(0, waveform.binOf(0))
        assertEquals(bins - 1, waveform.binOf(255))
        assertEquals(bins - 1, waveform.binOf(9999))
        assertEquals(0, waveform.binOf(-5))
        assertEquals(columns - 1, waveform.columnOf(999, 1000))
        assertEquals(0, waveform.columnOf(0, 1000))
        assertEquals(0, waveform.columnOf(3, 0))
    }
}

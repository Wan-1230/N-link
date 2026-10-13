package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZebraMask] 单测（C1）。
 *
 * 斑马纹判错的代价很直接：阈值算错 → 该报警的地方不报警，用户拍废一条才发现。
 * 这类"算出来是什么"的问题在真机上靠眼睛很难校准，所以锁在单测里。
 */
class ZebraMaskTest {

    @Test
    fun `ire conversion is clamped and reversible`() {
        assertEquals(0, ZebraMask.ireOf(0))
        assertEquals(100, ZebraMask.ireOf(255))
        assertEquals(100, ZebraMask.ireOf(300))      // 越界截断，不抛异常
        assertEquals(0, ZebraMask.ireOf(-10))

        assertEquals(255, ZebraMask.lumaOfIre(100))
        assertEquals(0, ZebraMask.lumaOfIre(0))
        assertEquals(0, ZebraMask.lumaOfIre(-5))
        assertEquals(255, ZebraMask.lumaOfIre(200))
    }

    @Test
    fun `nothing is marked on a dark frame`() {
        val px = IntArray(4 * 4) { 0xFF000000.toInt() }
        assertEquals(0, ZebraMask.mark(px, 4, 4, thresholdIre = ZebraMask.DEFAULT_IRE))
    }

    @Test
    fun `fully blown frame is fully marked`() {
        val px = IntArray(4 * 4) { 0xFFFFFFFF.toInt() }
        assertEquals(16, ZebraMask.mark(px, 4, 4, thresholdIre = ZebraMask.DEFAULT_IRE))
    }

    @Test
    fun `threshold decides what counts as overexposed`() {
        // 中灰 128 → 50 IRE：90 IRE 档不该圈它，40 IRE 档要圈
        val px = IntArray(2 * 2) { 0xFF808080.toInt() }
        assertEquals(0, ZebraMask.mark(px, 2, 2, thresholdIre = 90))
        assertEquals(4, ZebraMask.mark(px, 2, 2, thresholdIre = 40))
    }

    @Test
    fun `stripes alternate so the mark survives any background`() {
        val px = IntArray(8 * 1) { 0xFFFFFFFF.toInt() }
        ZebraMask.mark(px, 8, 1, thresholdIre = 90, period = 4)

        // 一行里前 4 个与后 4 个必须一黑一白——否则压在同色底上整片消失
        assertEquals(0xFFFFFFFF.toInt(), px[0])
        assertEquals(0xFF000000.toInt(), px[4])
        assertTrue("斑马纹必须两种亮度都出现", px.any { it == 0xFFFFFFFF.toInt() } && px.any { it == 0xFF000000.toInt() })
    }

    @Test
    fun `degenerate sizes are safe`() {
        assertEquals(0, ZebraMask.mark(IntArray(0), 0, 0))
        assertEquals(0, ZebraMask.mark(IntArray(4), 0, 4))
        assertEquals(0, ZebraMask.mark(IntArray(4), 4, 0))
    }
}

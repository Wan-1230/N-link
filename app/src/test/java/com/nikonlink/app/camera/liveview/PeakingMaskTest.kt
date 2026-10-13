package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PeakingMask] 单测（C1）。
 *
 * 峰值判错的代价是"对没对上焦看不出来"——这个工具存在的意义就没了。
 * 梯度算法是纯算术，必须在 JVM 上锁住，不能靠真机肉眼校准。
 */
class PeakingMaskTest {

    private fun frame(w: Int, h: Int, lumaAt: (x: Int, y: Int) -> Int): IntArray {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = lumaAt(x, y)
            px[y * w + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    @Test
    fun `flat field has no edges and peaking stays empty`() {
        val px = frame(8, 6) { _, _ -> 128 }
        val mask = PeakingMask(8, 6)
        assertEquals(0, mask.mark(px))
        assertEquals(0, mask.peakGradient)
    }

    @Test
    fun `hard vertical edge is marked and reported as max gradient`() {
        val px = frame(4, 4) { x, _ -> if (x < 2) 0 else 255 }
        val mask = PeakingMask(4, 4)
        // 内部像素只有 x=1,2 × y=1,2 四格有完整邻居
        val marked = mask.mark(px, threshold = 40)

        assertEquals(4, marked)
        assertEquals(255, mask.peakGradient)
    }

    @Test
    fun `marked pixels carry the peaking color`() {
        val px = frame(4, 4) { x, _ -> if (x < 2) 0 else 255 }
        PeakingMask(4, 4).mark(px, threshold = 40, color = 0xFF00FF00.toInt())

        assertTrue("被标记的像素必须换成峰值色", px.any { it == 0xFF00FF00.toInt() })
    }

    @Test
    fun `threshold above the max gradient marks nothing`() {
        val px = frame(4, 4) { x, _ -> if (x < 2) 0 else 255 }
        val mask = PeakingMask(4, 4)
        assertEquals(0, mask.mark(px, threshold = 256))
        // 但峰值梯度仍要如实上报——它是"合焦程度"读数，不受阈值影响
        assertEquals(255, mask.peakGradient)
    }

    @Test
    fun `soft ramp marks fewer pixels than a hard edge`() {
        // 硬边：0 → 255 一格跳完
        val hard = frame(8, 8) { x, _ -> if (x < 4) 0 else 255 }
        // 软梯度：整幅只铺 0…90，每格约 13。
        // ⚠️ 判据是**中心差分**（x-1 到 x+1，跨两格，约 26），不是相邻两格——
        // 初版注释按"相邻差 36"写，实际算出 72 越过阈值 40，断言当场失败。
        val soft = frame(8, 8) { x, _ -> x * 90 / 7 }
        assertEquals(0, PeakingMask(8, 8).mark(soft, threshold = 40))
        assertTrue(PeakingMask(8, 8).mark(hard, threshold = 40) > 0)
    }

    @Test
    fun `border pixels are skipped because gradient is undefined there`() {
        val px = frame(4, 4) { x, _ -> if (x < 2) 0 else 255 }
        PeakingMask(4, 4).mark(px, threshold = 40)
        // 第 0 行永远不该被描（没有上一行）
        assertEquals(0xFF000000.toInt(), px[0])
        assertEquals(0xFFFFFFFF.toInt(), px[3])
    }

    @Test
    fun `degenerate sizes are safe`() {
        assertEquals(0, PeakingMask(0, 0).mark(IntArray(0)))
        assertEquals(0, PeakingMask(2, 2).mark(IntArray(4)))
    }
}

package com.nikonlink.app.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FilterEngine 单元测试（PRD 4.5 滤镜管线与强度混合）
 */
class FilterEngineTest {

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    private fun channels(px: Int): Triple<Int, Int, Int> =
        Triple((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)

    @Test
    fun `内置滤镜库共 10 款且含原图`() {
        assertEquals(10, FilterLibrary.ALL.size)
        assertEquals("original", FilterLibrary.ALL.first().id)
        assertEquals("黑白", FilterLibrary.byId("bw")?.name)
    }

    @Test
    fun `原图滤镜不改变像素`() {
        val pixels = intArrayOf(argb(120, 80, 200))
        val src = pixels[0]
        FilterEngine.apply(pixels, 1, FilterLibrary.ORIGINAL, 100)
        assertEquals(src, pixels[0])
    }

    @Test
    fun `强度 0 不改变像素`() {
        val pixels = intArrayOf(argb(120, 80, 200))
        val src = pixels[0]
        FilterEngine.apply(pixels, 1, FilterLibrary.byId("teal_orange")!!, 0)
        assertEquals(src, pixels[0])
    }

    @Test
    fun `黑白滤镜三通道相等`() {
        val pixels = intArrayOf(argb(200, 100, 50))
        FilterEngine.apply(pixels, 1, FilterLibrary.byId("bw")!!, 100)
        val (r, g, b) = channels(pixels[0])
        assertTrue("R≈G: $r/$g", Math.abs(r - g) <= 2)
        assertTrue("G≈B: $g/$b", Math.abs(g - b) <= 2)
    }

    @Test
    fun `暖调滤镜红升蓝降`() {
        val pixels = intArrayOf(argb(128, 128, 128))
        FilterEngine.apply(pixels, 1, FilterLibrary.byId("warm")!!, 100)
        val (r, _, b) = channels(pixels[0])
        assertTrue("R 应升高: $r", r > 128)
        assertTrue("B 应降低: $b", b < 128)
    }

    @Test
    fun `强度 50 约为全强度一半`() {
        val full = intArrayOf(argb(128, 128, 128))
        val half = intArrayOf(argb(128, 128, 128))
        val cool = FilterLibrary.byId("cool")!!
        FilterEngine.apply(full, 1, cool, 100)
        FilterEngine.apply(half, 1, cool, 50)
        // 冷调使蓝通道升高，半强度偏移应约为全强度一半
        val bFull = channels(full[0]).third - 128
        val bHalf = channels(half[0]).third - 128
        assertTrue("冷调应提升蓝通道: $bFull", bFull > 0)
        assertTrue(
            "半强度应约为全强度一半: $bHalf vs $bFull",
            bHalf in (bFull / 2 - 1)..(bFull / 2 + 1)
        )
    }

    @Test
    fun `高光染色只影响亮部`() {
        val film = FilterLibrary.byId("film")!!
        val pixels = intArrayOf(argb(230, 230, 230), argb(60, 60, 60))
        FilterEngine.apply(pixels, 2, film, 100)
        val (rHigh, _, bHigh) = channels(pixels[0])
        // film 高光染色 R+6/B-4：亮部应出现明显的暖高光分离
        assertTrue("亮部 R-B 应被拉开: R=$rHigh B=$bHigh", rHigh - bHigh >= 8)
    }

    @Test
    fun `alpha 通道保持不变`() {
        val pixels = intArrayOf(0x80646464.toInt())
        FilterEngine.apply(pixels, 1, FilterLibrary.byId("bw")!!, 100)
        assertEquals(0x80, (pixels[0] ushr 24) and 0xFF)
    }
}

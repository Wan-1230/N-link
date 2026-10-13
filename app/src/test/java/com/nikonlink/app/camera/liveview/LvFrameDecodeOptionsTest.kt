package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LvFrameDecodeOptions.sampleSizeFor] 测试（B1/R1）。
 *
 * 降采样倍率是这条改动里唯一「算错了就要么糊要么没省下来」的地方，
 * 而它在真机上很难直接看出来，所以必须锁在单测里。
 */
class LvFrameDecodeOptionsTest {

    /** 现役监看帧的典型尺寸 */
    private val srcW = 640
    private val srcH = 424

    @Test
    fun `unknown sizes fall back to no downsampling`() {
        // 源尺寸未知（首帧还没解析 bounds）或目标尺寸未知（视图还没测量完）时
        // 一律全尺寸解码——宁可多花一点 CPU，也不能拿 0 去算出一个荒唐的倍率。
        assertEquals(1, LvFrameDecodeOptions.sampleSizeFor(0, 0, 360, 780))
        assertEquals(1, LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 0, 0))
        assertEquals(1, LvFrameDecodeOptions.sampleSizeFor(-1, -1, -1, -1))
    }

    @Test
    fun `target larger than source is never downsampled`() {
        assertEquals(1, LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 1080, 2400))
        assertEquals(1, LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, srcW, srcH))
    }

    @Test
    fun `half size target uses sample 2`() {
        // 320x212 的显示区：640/2=320 >= 320 且 424/2=212 >= 212 → 正好取 2
        assertEquals(2, LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 320, 212))
        // 再降一档 160x106 就小于显示区了，所以停在 2
        assertEquals(2, LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 300, 200))
    }

    @Test
    fun `quarter size target uses sample 4`() {
        assertEquals(4, LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 160, 106))
    }

    @Test
    fun `sample size is always a power of two`() {
        for (dst in listOf(1, 7, 33, 100, 211, 320, 500, 640)) {
            val s = LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, dst, dst)
            assertEquals("$dst → $s 不是 2 的幂", 0, s and (s - 1))
        }
    }

    @Test
    fun `sample size never exceeds the cap`() {
        assertEquals(
            LvFrameDecodeOptions.MAX_SAMPLE_SIZE,
            LvFrameDecodeOptions.sampleSizeFor(4096, 4096, 1, 1)
        )
        // 上限之内：不因为目标极小就把画面彻底毁掉
        assert(LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 1, 1) <= LvFrameDecodeOptions.MAX_SAMPLE_SIZE)
    }

    @Test
    fun `zooming in asks for more pixels`() {
        // 缩到 1/4 看 → sample 4；放大到 4x → 目标回到 640x424 量级 → 不再降采样
        val zoomedOut = LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 160, 106)
        val zoomedIn = LvFrameDecodeOptions.sampleSizeFor(srcW, srcH, 640, 424)
        assertEquals(4, zoomedOut)
        assertEquals(1, zoomedIn)
        assert(zoomedIn < zoomedOut)
    }
}

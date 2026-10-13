package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SafeArea] 单测（C1）。
 *
 * 安全框算错的后果是"拍的时候没出画、播出时人被削一半"，而且事后才发现。
 * 数字很死，必须锁住。
 */
class SafeAreaTest {

    @Test
    fun `action safe is 93 percent and title safe is 90`() {
        assertEquals(0.93f, SafeArea.ACTION_SAFE)
        assertEquals(0.90f, SafeArea.TITLE_SAFE)
    }

    @Test
    fun `inset is split evenly on both sides`() {
        // 1000 宽的 93% 安全框：总共去掉 70，左右各 35
        // 0.93f 不是二进制精确值，浮点算出来是 34.999996——断言必须带容差
        assertEquals(35f, SafeArea.inset(1000f, SafeArea.ACTION_SAFE), 1e-3f)
        // 90% 字幕框：总共去掉 100，左右各 50
        assertEquals(50f, SafeArea.inset(1000f, SafeArea.TITLE_SAFE), 1e-3f)
    }

    @Test
    fun `inner size matches the ratio`() {
        assertEquals(930f, SafeArea.innerSize(1000f, SafeArea.ACTION_SAFE), 1e-3f)
        assertEquals(900f, SafeArea.innerSize(1000f, SafeArea.TITLE_SAFE), 1e-3f)
        assertEquals(0f, SafeArea.innerSize(0f, SafeArea.ACTION_SAFE))
    }

    @Test
    fun `title safe is always inside action safe`() {
        val size = 1080f
        assertTrue(
            "字幕框必须比动作框更靠里",
            SafeArea.inset(size, SafeArea.TITLE_SAFE) > SafeArea.inset(size, SafeArea.ACTION_SAFE)
        )
    }

    @Test
    fun `degenerate input never produces negative geometry`() {
        assertEquals(0f, SafeArea.inset(0f, SafeArea.ACTION_SAFE))
        assertEquals(0f, SafeArea.inset(-100f, SafeArea.ACTION_SAFE))
        // 比例越界按截断处理（1.5 → 1，内缩 0；-0.5 → 0，内缩到整边的一半）
        assertEquals(0f, SafeArea.inset(1000f, 1.5f))
        assertEquals(500f, SafeArea.inset(1000f, -0.5f))
    }
}

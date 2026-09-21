package com.nikonlink.app.camera.params

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-10：互验只在"样本彼此一致"时才允许报单值。 */
class ShutterCrossCheckTest {

    private fun reading(count: Int, verified: Boolean = true, mechanical: Int = -1) =
        NikonShutterCountParser.Reading(
            shutterCount = count,
            mechanicalCount = mechanical,
            imageCount = count,
            deletedImageCount = 0,
            layout = "Nikonv1",
            verified = verified
        )

    @Test
    fun `三张一致时报单值并标记已校验`() {
        val v = ShutterCrossCheck.decide(listOf(reading(1200), reading(1200), reading(1200)))!!
        assertEquals(1200, v.count)
        assertNull(v.range)
        assertTrue(v.verified)
        assertEquals(3, v.sampleCount)
    }

    @Test
    fun `样本不一致时报最大值加区间，且不标已校验`() {
        val v = ShutterCrossCheck.decide(listOf(reading(1200), reading(1180), reading(1200)))!!
        // 快门计数不会倒退，较小的那个必然是解错或读到旧档 → 取上界
        assertEquals(1200, v.count)
        assertEquals(1180..1200, v.range)
        assertFalse(v.verified)
    }

    @Test
    fun `有一张没过恒等式时，即使数值一致也不算已校验`() {
        val v = ShutterCrossCheck.decide(listOf(reading(900), reading(900, verified = false)))!!
        assertEquals(900, v.count)
        assertNull("数值一致时不必给用户看区间", v.range)
        assertFalse(v.verified)
    }

    @Test
    fun `单样本退化成 v2_3_1 的行为`() {
        val one = ShutterCrossCheck.decide(listOf(reading(4321, verified = false)))!!
        assertEquals(4321, one.count)
        assertNull(one.range)
        assertFalse(one.verified)
        assertEquals(1, one.sampleCount)
    }

    @Test
    fun `机械次数取样本里最大的那个正数，没有就保持 -1`() {
        assertEquals(
            1150,
            ShutterCrossCheck.decide(listOf(reading(1200, mechanical = 1100), reading(1200, mechanical = 1150)))!!.mechanical
        )
        assertEquals(
            -1,
            ShutterCrossCheck.decide(listOf(reading(1200), reading(1200)))!!.mechanical
        )
    }

    @Test
    fun `没有可用样本时不编结论`() {
        assertNull(ShutterCrossCheck.decide(emptyList()))
        assertNull(ShutterCrossCheck.decide(listOf(reading(0), reading(-5))))
    }

    @Test
    fun `互验上限是三张，不多花连接时间`() {
        assertEquals(3, ShutterCrossCheck.MAX_SAMPLES)
    }
}

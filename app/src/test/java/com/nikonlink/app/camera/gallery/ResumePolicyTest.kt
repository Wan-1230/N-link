package com.nikonlink.app.camera.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-05 验收①：32 位续传上限的边界必须钉死（2GB±1）。 */
class ResumePolicyTest {

    private val intMax = ResumePolicy.MAX_OFFSET_PARAM  // 2147483647

    @Test
    fun `offset 加分块刚好顶到 32 位上限仍算发得出去`() {
        assertTrue(ResumePolicy.offsetFits(intMax - 1024, 1024))
    }

    @Test
    fun `只差 1 字节越界就不再发`() {
        assertFalse(ResumePolicy.offsetFits(intMax - 1023, 1024))
    }

    @Test
    fun `恰好 2GB 的起点已在范围外`() {
        assertFalse(ResumePolicy.offsetFits(2L * 1024 * 1024 * 1024, 1024))
        assertTrue(ResumePolicy.offsetFits(2L * 1024 * 1024 * 1024 - 1025, 1024))
    }

    @Test
    fun `只有起点为 0 或起点本就越界才清档重来`() {
        assertTrue(ResumePolicy.restartWholeFileAfterAbort(0L))
        assertTrue(ResumePolicy.restartWholeFileAfterAbort(intMax))
        assertFalse("中途断的必须留半成品等下次续", ResumePolicy.restartWholeFileAfterAbort(1024L))
    }

    @Test
    fun `三种结局里只有 Complete 算成功`() {
        assertTrue(DownloadOutcome.Complete.isComplete)
        assertFalse(DownloadOutcome.BeyondResumeRange.isComplete)
        assertFalse(DownloadOutcome.LinkBroken.isComplete)
    }
}

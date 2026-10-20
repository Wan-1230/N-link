package com.nikonlink.app.camera.gallery

import com.nikonlink.app.device.ptp.CommandResponsePacket
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpDataResult
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.shared.common.AppEventLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STA 直读探测状态机（2026-10-19 Z30 真机首轮误判「不可用」的回归）。
 * PtpSessionManager 全部打桩：0x1008 一律回 0x200F（STA 锁），0x9421/0x9431 按用例给。
 */
class StaDirectAlbumProbeTest {

    private val folder = 0x11004000
    private val jpg = 0x29191986
    private val jpg2 = 0x29191987

    private fun session(directOk: Boolean = true, gen: Int = 1): PtpSessionManager {
        val ptp = mockk<PtpSessionManager>(relaxed = true)
        every { ptp.isStaSession } returns true
        every { ptp.connectGeneration } returns gen
        every { ptp.lastDataRejectCode } returns 0
        coEvery { ptp.sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, any(), any(), any()) } coAnswers {
            delay(50) // 让并发用例真的重叠
            PtpDataResult.Failure(CommandResponsePacket(1, PtpConstants.RESPONSE_ACCESS_DENIED))
        }
        coEvery { ptp.getObjectSize64(folder) } returns null
        coEvery { ptp.getPartialObjectEx(folder, any(), any(), any()) } returns null
        coEvery { ptp.getObjectSize64(match { it != folder }) } returns if (directOk) 6_787_934L else null
        coEvery { ptp.getPartialObjectEx(match { it != folder }, any(), any(), any()) } returns
            if (directOk) ByteArray(65536).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() } else null
        return ptp
    }

    private fun album(ptp: PtpSessionManager) = StaDirectAlbum(ptp, mockk<AppEventLogger>(relaxed = true))

    @Test
    fun `只有目录句柄时返回 NO_MEDIA 且不拿目录去试私有命令`() = runBlocking {
        val ptp = session()
        val a = album(ptp)
        assertEquals(StaDirectRead.Access.NO_MEDIA, a.probe(listOf(folder)))
        assertFalse(a.isDirectActive())
        coVerify(exactly = 0) { ptp.getPartialObjectEx(folder, any(), any(), any()) }
        coVerify(exactly = 0) { ptp.sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, any(), any(), any()) }
    }

    @Test
    fun `NO_MEDIA 不缓存 重列出照片后照常探测为 DIRECT`() = runBlocking {
        val a = album(session())
        assertEquals(StaDirectRead.Access.NO_MEDIA, a.probe(listOf(folder)))
        assertEquals(StaDirectRead.Access.DIRECT, a.probe(listOf(folder, jpg)))
        assertTrue(a.isDirectActive())
    }

    @Test
    fun `目录排在前面也用照片句柄探测`() = runBlocking {
        val ptp = session()
        val a = album(ptp)
        assertEquals(StaDirectRead.Access.DIRECT, a.probe(listOf(folder, jpg)))
        coVerify { ptp.getObjectSize64(jpg) }
        coVerify(exactly = 0) { ptp.getObjectSize64(folder) }
    }

    @Test
    fun `UNAVAILABLE 不缓存 下次加载重新探测`() = runBlocking {
        val ptp = session(directOk = false)
        val a = album(ptp)
        assertEquals(StaDirectRead.Access.UNAVAILABLE, a.probe(listOf(jpg, jpg2)))
        assertEquals(StaDirectRead.Access.UNAVAILABLE, a.probe(listOf(jpg, jpg2)))
        // 每轮最多试 2 个媒体句柄，两轮都真的发了命令
        coVerify(exactly = 4) { ptp.getObjectSize64(any()) }
        assertFalse(a.isDirectActive())
    }

    @Test
    fun `DIRECT 缓存到会话结束 同会话不再探测`() = runBlocking {
        val ptp = session()
        val a = album(ptp)
        assertEquals(StaDirectRead.Access.DIRECT, a.probe(listOf(jpg)))
        assertEquals(StaDirectRead.Access.DIRECT, a.probe(listOf(jpg)))
        coVerify(exactly = 1) { ptp.sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, any(), any(), any()) }
    }

    @Test
    fun `并发两次加载只探测一次`() = runBlocking {
        val ptp = session()
        val a = album(ptp)
        val results = listOf(async { a.probe(listOf(jpg)) }, async { a.probe(listOf(jpg)) }).awaitAll()
        assertEquals(listOf(StaDirectRead.Access.DIRECT, StaDirectRead.Access.DIRECT), results)
        coVerify(exactly = 1) { ptp.sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, any(), any(), any()) }
    }

    @Test
    fun `换会话后重新探测`() = runBlocking {
        val ptp = session()
        var gen = 1
        every { ptp.connectGeneration } answers { gen }
        val a = album(ptp)
        assertEquals(StaDirectRead.Access.DIRECT, a.probe(listOf(jpg)))
        gen = 2
        assertFalse(a.isDirectActive())
        assertEquals(StaDirectRead.Access.DIRECT, a.probe(listOf(jpg)))
        coVerify(exactly = 2) { ptp.sendCommandWithData(PtpConstants.OP_GET_OBJECT_INFO, any(), any(), any()) }
    }
}

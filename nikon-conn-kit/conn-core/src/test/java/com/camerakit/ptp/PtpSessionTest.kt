package com.camerakit.ptp

import com.camerakit.api.CameraError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本化假链路：按 opcode 返回预设结果并记录调用。 */
private class FakeLink(
    private val handler: (Int, List<Long>) -> PtpResult,
) : PtpLink {
    val calls = ArrayList<Pair<Int, List<Long>>>()

    override val events: SharedFlow<PtpCameraEvent> = MutableSharedFlow()
    override val isOpen: Boolean = true
    override fun lastReceiveAt(): Long = System.currentTimeMillis()

    override suspend fun transact(
        opcode: Int,
        params: List<Long>,
        dataOut: ByteArray?,
        sink: ((ByteArray) -> Unit)?,
        timeoutMs: Long,
    ): PtpResult {
        calls += opcode to params
        val r = handler(opcode, params)
        r.dataIn?.let { sink?.invoke(it) }
        return r
    }

    override suspend fun sendKeepalive() {}
    override fun close(graceful: Boolean) {}
}

class PtpSessionTest {

    private fun deviceInfoBytes(withEx: Boolean = false): ByteArray {
        val ops = if (withEx) {
            listOf(0x1001, 0x1002, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009, 0x100A, 0x101B, 0x9431)
        } else {
            listOf(0x1001, 0x1002, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009, 0x100A, 0x101B)
        }
        return PtpDatasetWriter()
            .u16(100)
            .u32(6)
            .u16(100)
            .string("ext")
            .u16(0)
            .u16Array(ops)
            .u16Array(listOf(0x4002))
            .u16Array(listOf(0x5003))
            .u16Array(listOf(0x3801))
            .u16Array(listOf(0x3801, 0x3000))
            .string("NIKON CORPORATION")
            .string("NIKON Z 6_3")
            .string("V1.00")
            .string("SN123")
            .toByteArray()
    }

    @Test
    fun open_parsesDeviceInfo_andOpensSession() = runBlocking {
        val link = FakeLink { opcode, _ ->
            when (opcode) {
                PtpConsts.OP_GET_DEVICE_INFO -> PtpResult(PtpConsts.RC_OK, dataIn = deviceInfoBytes())
                PtpConsts.OP_OPEN_SESSION -> PtpResult(PtpConsts.RC_OK)
                else -> PtpResult(PtpConsts.RC_GENERAL_ERROR)
            }
        }
        val session = PtpSession(link, sessionId = 1L)
        session.open()

        val info = session.deviceInfo!!
        assertEquals("NIKON CORPORATION", info.manufacturer)
        assertEquals("NIKON Z 6_3", info.model)
        assertTrue(info.supportsOperation(0x101B))
        // 基准实现 实证顺序：OpenSession 先于 GetDeviceInfo
        assertEquals(
            listOf(PtpConsts.OP_OPEN_SESSION to listOf(1L), PtpConsts.OP_GET_DEVICE_INFO to emptyList()),
            link.calls,
        )
    }

    @Test
    fun listStorages_parses() = runBlocking {
        val link = FakeLink { opcode, params ->
            when (opcode) {
                PtpConsts.OP_GET_DEVICE_INFO -> PtpResult(PtpConsts.RC_OK, dataIn = deviceInfoBytes())
                PtpConsts.OP_OPEN_SESSION -> PtpResult(PtpConsts.RC_OK)
                PtpConsts.OP_GET_STORAGE_IDS ->
                    PtpResult(PtpConsts.RC_OK, dataIn = PtpDatasetWriter().u32Array(listOf(0x00010001L)).toByteArray())
                PtpConsts.OP_GET_STORAGE_INFO -> {
                    assertEquals(listOf(0x00010001L), params)
                    PtpResult(
                        PtpConsts.RC_OK,
                        dataIn = PtpDatasetWriter()
                            .u16(3).u16(2).u16(0)
                            .u64(128L * 1024 * 1024 * 1024).u64(64L * 1024 * 1024 * 1024)
                            .u32(1000)
                            .string("SD card").string("NIKON")
                            .toByteArray(),
                    )
                }
                else -> PtpResult(PtpConsts.RC_GENERAL_ERROR)
            }
        }
        val session = PtpSession(link)
        session.open()
        val storages = session.listStorages()
        assertEquals(1, storages.size)
        assertEquals(0x00010001, storages[0].storageId)
        assertEquals("SD card", storages[0].description)
        assertEquals(128L * 1024 * 1024 * 1024, storages[0].maxCapacity)
    }

    @Test
    fun readRange_fallsBackToNikonEx_whenStandardFails() = runBlocking {
        val link = FakeLink { opcode, _ ->
            when (opcode) {
                PtpConsts.OP_GET_DEVICE_INFO -> PtpResult(PtpConsts.RC_OK, dataIn = deviceInfoBytes(withEx = true))
                PtpConsts.OP_OPEN_SESSION -> PtpResult(PtpConsts.RC_OK)
                PtpConsts.OP_GET_PARTIAL_OBJECT -> PtpResult(PtpConsts.RC_DEVICE_BUSY)
                PtpConsts.OP_NIKON_GET_PARTIAL_OBJECT_EX ->
                    PtpResult(PtpConsts.RC_OK, dataIn = byteArrayOf(1, 2, 3))
                else -> PtpResult(PtpConsts.RC_GENERAL_ERROR)
            }
        }
        val session = PtpSession(link)
        session.open()
        val data = session.readRange(0x10000001L, 0, 1024)
        assertArrayEqualsSafe(byteArrayOf(1, 2, 3), data)
        // 第二次直接走 0x9431（策略已确认）
        session.readRange(0x10000001L, 1024, 1024)
        val last = link.calls.last()
        assertEquals(PtpConsts.OP_NIKON_GET_PARTIAL_OBJECT_EX, last.first)
    }

    @Test
    fun deleteObject_mapsErrorToPtpError() = runBlocking {
        val link = FakeLink { opcode, _ ->
            when (opcode) {
                PtpConsts.OP_GET_DEVICE_INFO -> PtpResult(PtpConsts.RC_OK, dataIn = deviceInfoBytes())
                PtpConsts.OP_OPEN_SESSION -> PtpResult(PtpConsts.RC_OK)
                PtpConsts.OP_DELETE_OBJECT -> PtpResult(PtpConsts.RC_OBJECT_WRITE_PROTECTED)
                else -> PtpResult(PtpConsts.RC_GENERAL_ERROR)
            }
        }
        val session = PtpSession(link)
        session.open()
        val e = assertThrows(CameraError.PtpError::class.java) {
            runBlocking { session.deleteObject(5L) }
        }
        assertEquals(PtpConsts.RC_OBJECT_WRITE_PROTECTED, e.code)
    }

    private fun assertArrayEqualsSafe(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual))
    }
}

package com.nikonlink.app.camera.data

import com.nikonlink.app.camera.gallery.CameraFile
import com.nikonlink.app.camera.gallery.CameraFileFormat
import com.nikonlink.app.shared.data.PhotoMarkDao
import com.nikonlink.app.shared.data.PhotoMarkEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F1 标记仓库行为测试：
 * - 打标 → 按指纹写入实体（captureTime 缺失归一为 0）
 * - 取消标记 → 只删指纹命中的行
 * - 指纹校验 reconcile → 只清理键失配的标记；空列表（抓取失败）不做任何清理
 */
class PhotoMarkRepositoryTest {

    private lateinit var dao: PhotoMarkDao
    private lateinit var repository: PhotoMarkRepository

    @Before
    fun setUp() {
        dao = mockk(relaxed = true)
        repository = PhotoMarkRepository(dao)
    }

    private fun file(
        handle: Int,
        name: String = "DSC_0001.JPG",
        size: Long = 1_000L,
        storageId: Int = 1,
        captureTime: Long? = 500L
    ) = CameraFile(
        handle = handle,
        fileName = name,
        size = size,
        formatCode = 0x3801,
        storageId = storageId,
        format = CameraFileFormat.JPEG,
        captureTimeMillis = captureTime
    )

    private fun mark(
        handle: Int,
        name: String = "DSC_0001.JPG",
        size: Long = 1_000L,
        storageId: Int = 1,
        captureTime: Long = 500L
    ) = PhotoMarkEntity(
        storageId = storageId,
        objectHandle = handle,
        fileName = name,
        fileSize = size,
        captureTime = captureTime
    )

    @Test
    fun `mark writes entities with normalized fingerprint`() = runTest {
        val slot = slot<List<PhotoMarkEntity>>()
        coEvery { dao.insertAll(capture(slot)) } returns Unit

        repository.mark(listOf(file(100, captureTime = null)))

        val written = slot.captured
        assertEquals(1, written.size)
        assertEquals(0L, written.first().captureTime)
        assertEquals("DSC_0001.JPG", written.first().fileName)
    }

    @Test
    fun `unmark deletes only the marks whose key matches`() = runTest {
        val kept = mark(100)
        val stale = mark(200, name = "DSC_0099.JPG")
        coEvery { dao.getAll() } returns listOf(kept, stale)

        repository.unmark(listOf(file(200, name = "DSC_0099.JPG")))

        coVerify(exactly = 1) { dao.delete(listOf(stale)) }
        coVerify(exactly = 0) { dao.delete(match { it.contains(kept) }) }
    }

    @Test
    fun `reconcile removes marks missing from current camera list`() = runTest {
        val alive = mark(100)
        val dead = mark(300)
        coEvery { dao.getAll() } returns listOf(alive, dead)

        repository.reconcile(listOf(file(100), file(101, name = "DSC_0002.JPG")))

        coVerify(exactly = 1) { dao.delete(listOf(dead)) }
    }

    @Test
    fun `reconcile on empty list is a no-op to survive failed fetches`() = runTest {
        coEvery { dao.getAll() } returns listOf(mark(100))

        repository.reconcile(emptyList())

        coVerify(exactly = 0) { dao.delete(any()) }
        coVerify(exactly = 0) { dao.clearAll() }
    }

    @Test
    fun `isMarked matches by full fingerprint`() = runTest {
        coEvery { dao.getAll() } returns listOf(mark(100))

        assertTrue(repository.isMarked(file(100)))
        assertEquals(false, repository.isMarked(file(100, name = "OTHER.JPG")))
    }
}

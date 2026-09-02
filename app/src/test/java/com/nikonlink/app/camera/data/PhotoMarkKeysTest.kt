package com.nikonlink.app.camera.data

import com.nikonlink.app.camera.gallery.CameraFile
import com.nikonlink.app.camera.gallery.CameraFileFormat
import com.nikonlink.app.shared.data.PhotoMarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * F1 标记指纹键测试（PRD AC-8：相机重插卡 handle 复用时，旧标记不得误挂到新文件）。
 *
 * 指纹键 = storage_id + object_handle + file_name + file_size + capture_time，
 * 键的计算必须在「实体」与「相机文件」两个方向上完全一致，否则校验会把活标记误判失效。
 */
class PhotoMarkKeysTest {

    private fun file(
        handle: Int = 100,
        name: String = "DSC_0001.JPG",
        size: Long = 8_000_000L,
        storageId: Int = 0x00010001,
        captureTime: Long? = 1_724_000_000_000L
    ) = CameraFile(
        handle = handle,
        fileName = name,
        size = size,
        formatCode = 0x3801,
        storageId = storageId,
        format = CameraFileFormat.JPEG,
        captureTimeMillis = captureTime
    )

    private fun entity(
        storageId: Int = 0x00010001,
        handle: Int = 100,
        name: String = "DSC_0001.JPG",
        size: Long = 8_000_000L,
        captureTime: Long = 1_724_000_000_000L
    ) = PhotoMarkEntity(
        storageId = storageId,
        objectHandle = handle,
        fileName = name,
        fileSize = size,
        captureTime = captureTime
    )

    @Test
    fun `entity key matches file key for the same physical file`() {
        assertEquals(PhotoMarkKeys.keyOf(entity()), PhotoMarkKeys.keyOf(file()))
    }

    @Test
    fun `same handle but different name produces different key`() {
        val oldMark = PhotoMarkKeys.keyOf(entity())
        val newFile = PhotoMarkKeys.keyOf(file(name = "DSC_0099.JPG"))
        assertNotEquals(oldMark, newFile)
    }

    @Test
    fun `same handle and name but different size produces different key`() {
        val oldMark = PhotoMarkKeys.keyOf(entity())
        val newFile = PhotoMarkKeys.keyOf(file(size = 9_999_999L))
        assertNotEquals(oldMark, newFile)
    }

    @Test
    fun `same handle name size but different capture time produces different key`() {
        val oldMark = PhotoMarkKeys.keyOf(entity())
        val newFile = PhotoMarkKeys.keyOf(file(captureTime = 1_724_999_999_000L))
        assertNotEquals(oldMark, newFile)
    }

    @Test
    fun `null capture time normalizes to zero in both directions`() {
        assertEquals(
            PhotoMarkKeys.keyOf(entity(captureTime = 0L)),
            PhotoMarkKeys.keyOf(file(captureTime = null))
        )
    }

    @Test
    fun `same content on another storage is a different key`() {
        val card1 = PhotoMarkKeys.keyOf(entity(storageId = 0x00010001))
        val card2 = PhotoMarkKeys.keyOf(entity(storageId = 0x00020001))
        assertNotEquals(card1, card2)
    }
}

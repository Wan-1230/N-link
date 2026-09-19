package com.nikonlink.app.camera.params

import com.nikonlink.app.camera.gallery.CameraFile
import com.nikonlink.app.camera.gallery.CameraFileFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 快门次数样张挑选测试（PRD v2.4 / L0）。
 *
 * 核心是回归那条**正确性**缺陷：旧实现按「文件最小」挑样张，容易挑到卡上留存的旧照片，
 * 而 `0x00A7` 记的是「拍摄该照片那一刻」的计数 —— 于是报出一个偏低的假数值并显示成功。
 */
class ShutterSamplePickerTest {

    private fun file(
        handle: Int,
        name: String,
        format: CameraFileFormat,
        takenAt: Long?,
        size: Long = 1_000L
    ) = CameraFile(
        handle = handle,
        fileName = name,
        size = size,
        formatCode = 0,
        storageId = 1,
        format = format,
        captureTimeMillis = takenAt
    )

    @Test
    fun `picks the newest photo even when an older one is smaller`() {
        val big = file(101, "DSC_9000.NEF", CameraFileFormat.RAW, takenAt = 5_000L, size = 45_000_000L)
        val tinyOld = file(102, "DSC_0001.JPG", CameraFileFormat.JPEG, takenAt = 1_000L, size = 200L)

        val picked = ShutterSamplePicker.candidates(listOf(tinyOld, big))
        // 旧实现会挑 tinyOld（最小），读数来自三年前那张照片
        assertEquals(101, picked.first().handle)
    }

    @Test
    fun `prefers raw over jpeg at the same timestamp`() {
        val jpg = file(201, "DSC_1.NEF", CameraFileFormat.RAW, takenAt = 7_000L)
        val nef = file(202, "DSC_1.JPG", CameraFileFormat.JPEG, takenAt = 7_000L)

        assertEquals(listOf(201, 202), ShutterSamplePicker.candidates(listOf(nef, jpg)).map { it.handle })
    }

    @Test
    fun `falls back to handle order when the camera reports no timestamps`() {
        val files = listOf(
            file(301, "a.NEF", CameraFileFormat.RAW, takenAt = null),
            file(309, "b.NEF", CameraFileFormat.RAW, takenAt = null)
        )
        assertEquals(309, ShutterSamplePicker.candidates(files).first().handle)
    }

    @Test
    fun `ignores video and unknown objects`() {
        val files = listOf(
            file(401, "MOV_0001.MOV", CameraFileFormat.VIDEO, takenAt = 9_000L),
            file(402, "note.txt", CameraFileFormat.OTHER, takenAt = 9_000L),
            file(403, "DSC_2.JPG", CameraFileFormat.JPEG, takenAt = 100L)
        )
        assertEquals(listOf(403), ShutterSamplePicker.candidates(files).map { it.handle })
    }

    @Test
    fun `caps attempts and de-duplicates handles`() {
        val files = (1..10).map {
            file(500 + it, "DSC_$it.NEF", CameraFileFormat.RAW, takenAt = it.toLong())
        } + file(500 + 10, "DSC_10.NEF", CameraFileFormat.RAW, takenAt = 10L)

        val picked = ShutterSamplePicker.candidates(files)
        assertEquals(3, picked.size)
        assertEquals(listOf(510, 509, 508), picked.map { it.handle })
    }

    @Test
    fun `empty card yields no candidates`() {
        assertEquals(0, ShutterSamplePicker.candidates(emptyList()).size)
    }
}

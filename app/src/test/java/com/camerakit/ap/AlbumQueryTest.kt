package com.camerakit.ap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AlbumQueryTest {
    private fun file(handle: Long, name: String, date: Long, format: Int = 0x3801) =
        ApPtpClient.ObjectInfo(handle, name, 100, format, false, date)

    @Test fun mixedMediaUsesDatesInsteadOfFormatHandleMagnitude() = runBlocking {
        val files = mapOf(1L to file(1, "old.jpg", 10), 2L to file(2, "new.jpg", 50),
            1000L to file(1000, "old.mov", 20, 0x300D), 1001L to file(1001, "new.mov", 40, 0x300D))
        val index = AlbumIndex(listOf(listOf(1, 2), listOf(1000, 1001)), false)
        assertEquals(listOf("new.jpg", "new.mov"), index.page(0, 2) { files[it] }.map { it.filename })
        assertEquals(listOf("old.mov", "old.jpg"), index.page(2, 2) { files[it] }.map { it.filename })
        assertEquals(listOf("new.jpg", "new.mov"), index.page(0, 2) { files[it] }.map { it.filename })
    }

    @Test fun dateFilterIncludesWholeEndDayAndNameIsCaseInsensitive() {
        val query = AlbumQuery(media = 1, extension = "jpg", filename = "DSC_94", from = 100, until = 200)
        assertTrue(query.accepts(file(1, "dsc_9401.JPG", 100)))
        assertTrue(query.accepts(file(2, "DSC_9402.jpeg", 199)))
        assertFalse(query.accepts(file(3, "DSC_9403.jpg", 200)))
        assertFalse(query.accepts(file(4, "DSC_9404.mov", 150, 0x300D)))
        assertFalse(query.accepts(file(5, "DSC_9301.jpg", 150)))
    }

    @Test fun cameraFormatFiltersDontHideUnknownNikonFiles() {
        val photo = AlbumQuery(media = 1)
        assertTrue(photo.acceptsFormat(0x3801))
        assertTrue(photo.acceptsFormat(0x3802))
        assertFalse(photo.acceptsFormat(0x300D))
        assertTrue(photo.acceptsFormat(0xBFFF))
        assertTrue(AlbumQuery(media = 1).accepts(file(1, "DSC_9400.HIF", 1, 0xB110)))
    }

    @Test fun oldestFirstMergesAndEmptyGroupIsSafe() = runBlocking {
        val files = mapOf(1L to file(1, "one.jpg", 10), 2L to file(2, "two.jpg", 40), 10L to file(10, "three.mov", 20))
        val index = AlbumIndex(listOf(emptyList(), listOf(2, 1), listOf(10)), true)
        assertEquals(listOf(1L, 10L, 2L), index.page(0, 36) { files[it] }.map { it.handle })
        assertTrue(index.page(3, 36) { files[it] }.isEmpty())
    }
}

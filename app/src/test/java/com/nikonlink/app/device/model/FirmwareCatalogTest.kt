package com.nikonlink.app.device.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareCatalogTest {

    @Test
    fun `version comparison treats missing parts as zero and ignores prefixes`() {
        assertTrue(FirmwareCatalog.compareVersions("2.01", "2.0") > 0)
        assertEquals(0, FirmwareCatalog.compareVersions("V2.01", "2.01"))
        assertTrue(FirmwareCatalog.compareVersions("1.1", "1.20") < 0)
        assertTrue(FirmwareCatalog.compareVersions("3.11", "3.9") > 0)
    }

    @Test
    fun `model normalization accepts common PTP spellings`() {
        assertEquals("Z6III", FirmwareCatalog.normalizeModel("NIKON Z 6III"))
        assertEquals("Z5II", FirmwareCatalog.normalizeModel("NIKON Z 5II"))
        // 下划线写法会被归一成 Z62，再由 check 的别名表映射到 Z6II
        assertEquals("Z62", FirmwareCatalog.normalizeModel("NIKON Z 6_2"))
        val result = FirmwareCatalog.check("NIKON Z 6_2", "1.00")
        assertTrue(result is FirmwareCatalog.Result.UpdateAvailable)
        assertEquals(
            "Z6II",
            (result as FirmwareCatalog.Result.UpdateAvailable).entry.modelKey
        )
    }

    @Test
    fun `older firmware reports update available`() {
        val result = FirmwareCatalog.check("NIKON Z 8", "3.00") as FirmwareCatalog.Result.UpdateAvailable
        assertEquals("3.11", result.entry.latestVersion)
    }

    @Test
    fun `newer firmware is not downgraded to catalog`() {
        val result = FirmwareCatalog.check("NIKON Z 8", "3.20")
        assertTrue(result is FirmwareCatalog.Result.NotOlderThanCatalog)
    }

    @Test
    fun `unknown model and blank version are explicit`() {
        assertTrue(FirmwareCatalog.check("NIKON Z 999", "1.0") is FirmwareCatalog.Result.UnknownModel)
        assertTrue(FirmwareCatalog.check("NIKON Z 8", "") is FirmwareCatalog.Result.NoVersion)
    }
}

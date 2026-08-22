package com.nikonlink.app.device.wifi_ap

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiManagerTest {

    @Test
    fun `band label maps wifi frequency bands`() {
        assertEquals("2.4 GHz", WifiManager.bandLabel(2412))
        assertEquals("2.4 GHz", WifiManager.bandLabel(2484))
        assertEquals("5 GHz", WifiManager.bandLabel(5180))
        assertEquals("5 GHz", WifiManager.bandLabel(5825))
        assertEquals("6 GHz", WifiManager.bandLabel(5955))
    }

    @Test
    fun `band label falls back to unknown outside known bands`() {
        assertEquals("未知", WifiManager.bandLabel(0))
        assertEquals("未知", WifiManager.bandLabel(3000))
        assertEquals("未知", WifiManager.bandLabel(5900))
    }
}

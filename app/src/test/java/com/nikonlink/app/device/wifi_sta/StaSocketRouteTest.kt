package com.nikonlink.app.device.wifi_sta

import org.junit.Assert.*
import org.junit.Test

class StaSocketRouteTest {
    @Test
    fun `热点和上游共存时选择目标同网段接口`() {
        val addresses = listOf(
            LocalNetworkInterfaceResolver.LocalAddress("wlan0", "192.168.0.10", 24),
            LocalNetworkInterfaceResolver.LocalAddress("ap0", "192.168.43.1", 24)
        )
        assertEquals("ap0", StaSocketRouteResolver.selectLocalAddress("192.168.43.30", addresses)?.interfaceName)
        assertEquals("wlan0", StaSocketRouteResolver.selectLocalAddress("192.168.0.30", addresses)?.interfaceName)
        assertNull(StaSocketRouteResolver.selectLocalAddress("10.0.0.3", addresses))
    }

    @Test
    fun `优先最长前缀且不选择自身或广播`() {
        val addresses = listOf(
            LocalNetworkInterfaceResolver.LocalAddress("wlan0", "10.1.2.3", 16),
            LocalNetworkInterfaceResolver.LocalAddress("ap0", "10.1.3.1", 24)
        )
        assertEquals("ap0", StaSocketRouteResolver.selectLocalAddress("10.1.3.9", addresses)?.interfaceName)
        assertFalse(StaSocketRouteResolver.sameSubnet("10.1.3.255", "10.1.3.1", 24))
        assertFalse(StaSocketRouteResolver.sameSubnet("10.1.3.0", "10.1.3.1", 24))
        assertFalse(StaSocketRouteResolver.sameSubnet("fe80::1", "10.1.3.1", 24))
        assertNull(StaSocketRouteResolver.selectLocalAddress("10.1.3.1", listOf(addresses[1])))
    }
}

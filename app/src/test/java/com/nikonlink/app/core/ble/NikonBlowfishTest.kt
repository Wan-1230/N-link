package com.nikonlink.app.core.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the Nikon smart-device Blowfish hash against a real captured
 * handshake vector (verified on Nikon Z50II and Z8).
 */
class NikonBlowfishTest {

    @Test
    fun `captured camera hash matches reference vector`() {
        val blocks = intArrayOf(
            0xcd32687f.toInt(), 0xa9e28a30.toInt(),
            0x29fa2680.toInt(), 0x5e3d94b9.toInt(),
            0xdbe113ec.toInt(), 0x44a17d67.toInt()
        )

        val (left, right) = NikonBlowfish().hash(blocks)
        assertEquals(0xe4f2b3a8.toInt(), left)
        assertEquals(0x136ad516.toInt(), right)
    }
}

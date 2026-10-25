package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiveViewHeaderTest {

    private data class AreaSpec(
        val width: Int,
        val height: Int,
        val centerX: Int,
        val centerY: Int
    )

    /**
     * 按真实版式构造：0/2 版本、8/12 头长与 JPEG 长、16/18 whole、
     * 20/22 display 宽高、24/26 display 中心、28/30 JPEG 宽高、48 起 AF 记录。
     */
    private fun header(
        major: Int = 2,
        wholeW: Int = 6000,
        wholeH: Int = 4000,
        displayW: Int = 5400,
        displayH: Int = 3600,
        displayCx: Int = 3000,
        displayCy: Int = 2000,
        focusDrive: Int = 0,
        afMode: Int = 0,
        selectedIndex: Int = 0,
        trackingState: Int = 0,
        declaredCount: Int = 1,
        areas: List<AreaSpec> = listOf(AreaSpec(300, 200, 4350, 2900)),
        headerBytes: Int = if (major == 1) 512 else 1024
    ): ByteArray {
        // 8 字节版本前缀 + 头内容 + 尾部 JPEG 占位（解析只看头）
        val total = 8 + headerBytes + 64
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0, major.toShort())
        buf.putShort(2, 0)
        buf.putInt(8, headerBytes)
        buf.putInt(12, 64)
        buf.putShort(16, wholeW.toShort())
        buf.putShort(18, wholeH.toShort())
        buf.putShort(20, displayW.toShort())
        buf.putShort(22, displayH.toShort())
        buf.putShort(24, displayCx.toShort())
        buf.putShort(26, displayCy.toShort())
        buf.putShort(28, 1620)
        buf.putShort(30, 1080)
        buf.put(40, 1)
        buf.put(41, focusDrive.toByte())
        buf.put(42, 2)
        buf.put(43, afMode.toByte())
        buf.put(44, declaredCount.toByte())
        buf.put(45, selectedIndex.toByte())
        buf.put(46, trackingState.toByte())
        areas.forEachIndexed { i, area ->
            val off = 48 + i * 8
            buf.putShort(off, area.width.toShort())
            buf.putShort(off + 2, area.height.toShort())
            buf.putShort(off + 4, area.centerX.toShort())
            buf.putShort(off + 6, area.centerY.toShort())
        }
        return buf.array()
    }

    @Test
    fun `reads display size and center at real offsets`() {
        val info = LiveViewHeader.parse(header())!!
        assertEquals(2, info.majorVersion)
        assertEquals(1024, info.headerBytes)
        assertEquals(6000, info.wholeWidth)
        assertEquals(4000, info.wholeHeight)
        assertEquals(5400, info.displayWidth)
        assertEquals(3600, info.displayHeight)
        assertEquals(3000, info.displayCenterX)
        assertEquals(2000, info.displayCenterY)
        assertEquals(300, info.displayLeft)
        assertEquals(200, info.displayTop)
        assertEquals(1, info.afAreaCount)
        assertEquals(0, info.focusDrive)
    }

    @Test
    fun `normalizes AF center against display area not whole size`() {
        val info = LiveViewHeader.parse(header())!!
        val area = info.activeAfArea()!!
        assertEquals(4350, area.centerX)
        assertTrue(info.hasUsableDisplayArea)
        val rect = info.normalizedRect(area)!!
        // (4350-300)/5400 = 0.75；(2900-200)/3600 = 0.75
        assertEquals(0.75f, rect.centerX, 0.0001f)
        assertEquals(0.75f, rect.centerY, 0.0001f)
    }

    @Test
    fun `tap mapping is inverse of AF readback and stays inside display area`() {
        val info = LiveViewHeader.parse(header())!!
        assertEquals(4350 to 2900, info.afCoordFromNormalized(0.75f, 0.75f))
        assertEquals(300 to 200, info.afCoordFromNormalized(0f, 0f))
        assertEquals(5700 to 3800, info.afCoordFromNormalized(1f, 1f))
    }

    @Test
    fun `prefers tracking area over subject and control areas`() {
        val info = LiveViewHeader.parse(
            header(
                afMode = 2,
                declaredCount = 2,
                selectedIndex = 1,
                trackingState = 2,
                areas = listOf(
                    AreaSpec(100, 100, 1000, 1000),
                    AreaSpec(200, 200, 4000, 3000)
                )
            )
        )!!
        assertEquals(LiveViewHeader.AfRole.SUBJECT_DETECTION, info.afAreas[0].role)
        assertEquals(LiveViewHeader.AfRole.TRACKING, info.afAreas[1].role)
        assertEquals(4000, info.activeAfArea()!!.centerX)
    }

    @Test
    fun `prefers selected primary control area over earlier secondary area`() {
        val info = LiveViewHeader.parse(
            header(
                afMode = 0,
                declaredCount = 2,
                selectedIndex = 1,
                trackingState = 0,
                areas = listOf(
                    AreaSpec(100, 100, 1000, 1000),
                    AreaSpec(200, 200, 4000, 3000)
                )
            )
        )!!
        assertEquals(LiveViewHeader.AfRole.SECONDARY_CONTROL, info.afAreas[0].role)
        assertEquals(LiveViewHeader.AfRole.PRIMARY_CONTROL, info.afAreas[1].role)
        assertEquals(3000, info.activeAfArea()!!.centerY)
    }

    @Test
    fun `prefers selected subject area among multiple subject detections`() {
        val info = LiveViewHeader.parse(
            header(
                afMode = 1,
                declaredCount = 2,
                selectedIndex = 1,
                trackingState = 0,
                areas = listOf(
                    AreaSpec(100, 100, 1000, 1000),
                    AreaSpec(200, 200, 4000, 3000)
                )
            )
        )!!
        assertEquals(LiveViewHeader.AfRole.SUBJECT_DETECTION, info.afAreas[0].role)
        assertEquals(LiveViewHeader.AfRole.SUBJECT_DETECTION, info.afAreas[1].role)
        assertEquals(1, info.activeAfArea()!!.index)
        assertEquals(4000, info.activeAfArea()!!.centerX)
    }

    @Test
    fun `ignores area whose rectangle falls outside whole frame`() {
        val info = LiveViewHeader.parse(
            header(
                afMode = 0,
                declaredCount = 2,
                selectedIndex = 0,
                areas = listOf(
                    // 右缘 6100 > whole 6000，属于未使用槽残留
                    AreaSpec(400, 200, 5900, 2000),
                    AreaSpec(200, 200, 3000, 2000)
                )
            )
        )!!
        val active = info.activeAfArea()!!
        assertEquals(1, active.index)
        assertEquals(3000, active.centerX)
    }

    @Test
    fun `v1 zero count no longer reads all 42 slots`() {
        val info = LiveViewHeader.parse(
            header(major = 1, declaredCount = 0, areas = emptyList())
        )!!
        assertEquals(0, info.afAreaCount)
        assertNull(info.activeAfArea())
    }

    @Test
    fun `falls back to centered JPEG area when display area is not reported`() {
        val info = LiveViewHeader.parse(
            header(
                displayW = 0, displayH = 0, displayCx = 0, displayCy = 0,
                areas = listOf(AreaSpec(300, 200, 3000, 2000))
            )
        )!!
        assertTrue(!info.hasUsableDisplayArea)
        // JPEG 1620x1080 居中于 whole 6000x4000：left=(6000-1620)/2=2190，top=1460
        val area = info.activeAfArea()!!
        val rect = info.normalizedRect(area)!!
        assertEquals(0.5f, rect.centerX, 0.0001f)
        assertEquals(0.5f, rect.centerY, 0.0001f)
        assertEquals(2190 + 16 to 1460 + 10, info.afCoordFromNormalized(0.01f, 0.01f))
    }

    @Test
    fun `hides echo when area falls outside the centered JPEG fallback`() {
        val info = LiveViewHeader.parse(
            header(displayW = 0, displayH = 0, displayCx = 0, displayCy = 0)
        )!!
        // 默认区域在 4350,2900，超出 JPEG 居中域 2190..3810 / 1460..2540
        assertNull(info.normalizedRect(info.activeAfArea()!!))
    }

    @Test
    fun `v1 header uses 512 byte profile`() {
        val info = LiveViewHeader.parse(
            header(
                major = 1,
                displayW = 6000,
                displayH = 4000,
                displayCx = 3000,
                displayCy = 2000,
                areas = listOf(AreaSpec(300, 200, 4500, 3000))
            )
        )!!
        assertEquals(1, info.majorVersion)
        assertEquals(512, info.headerBytes)
        assertEquals(0.75f, info.normalizedRect(info.activeAfArea()!!)!!.centerX, 0.0001f)
    }

    @Test
    fun `rejects invalid major whole size or short data`() {
        assertNull(LiveViewHeader.parse(header(major = 9)))
        assertNull(LiveViewHeader.parse(header(wholeW = 0)))
        assertNull(LiveViewHeader.parse(ByteArray(48)))
    }

    @Test
    fun `declared count larger than profile max yields no areas`() {
        val info = LiveViewHeader.parse(header(declaredCount = 100))!!
        assertEquals(0, info.afAreaCount)
        assertNull(info.activeAfArea())
    }
}

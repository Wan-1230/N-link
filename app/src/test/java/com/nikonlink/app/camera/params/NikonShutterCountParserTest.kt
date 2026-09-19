package com.nikonlink.app.camera.params

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 尼康快门次数本地解析器测试。
 *
 * 夹具按**真实形态**造，而不是按实现的假设造 —— 旧测试把 MakerNote 挂在 IFD0、IFD 又只有 @8
 * 一种位置，恰好与旧实现的错误假设一致，于是「MakerNote 其实在 Exif 子 IFD」「现代 D/Z 的 IFD 在
 * +18」两个主因一路漏过测试。这里对照 Exiv2 test/data 的真机样张逐条修正：
 * - `0x927C` 在 Exif 子 IFD(`0x8769`)，IFD0 只是宽松兜底；
 * - 形态 A `Nikon\0`+0x02（现代 D/Z，**IFD 在 +18**）：[parses modern v02xx note at ifd offset 18]
 * - 形态 B `Nikon\0`+0x01（E 系列，IFD 在 +8）：[parses e-series v01xx note at ifd offset 8]
 * - 形态 C 无签名头（IFD 在 +0）：[parses headerless big-endian note]
 * - 笔记字节序与主 TIFF 头相反（D70 / D2Hs 实测）：[parses big-endian maker note inside a little-endian container]
 * - `0x00A5 + 0x00A6 == 0x00A7` 才置 verified（D2Hs 样张即为该恒等式的实例）
 */
class NikonShutterCountParserTest {

    // ---------- 夹具构造 ----------

    private data class Entry(val tag: Int, val value: Int, val type: Int = TYPE_LONG)

    /** "Nikon\0" + 0x02 + "0200" + 0x02 + "02x0" + 2 字节填充 = 恰好 18 字节，IFD 紧随其后 */
    private val V02XX_PREFIX: ByteArray = byteArrayOf(
        0x4E, 0x69, 0x6B, 0x6F, 0x6E, 0x00, 0x02,
        0x30, 0x32, 0x30, 0x30,                      // "0200"
        0x02,
        0x30, 0x32, 0x78, 0x30,                      // "02x0"
        0x00, 0x00
    )

    /** "Nikon\0" + 0x01 + 版本字节 = 8 字节头 */
    private val V01XX_PREFIX: ByteArray = byteArrayOf(0x4E, 0x69, 0x6B, 0x6F, 0x6E, 0x00, 0x01, 0x00)

    private fun makerNote(
        prefix: ByteArray,
        ifdAt: Int,
        bigEndian: Boolean = false,
        entries: List<Entry>
    ): ByteArray {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val out = ByteArrayOutputStream()
        out.write(prefix)
        while (out.size() < ifdAt) out.write(0)
        out.write(u16(entries.size, order))
        for (e in entries) {
            out.write(u16(e.tag, order))
            out.write(u16(e.type, order))
            out.write(u32(1, order))                       // count = 1 → 值内联在 value 字段
            out.write(u32(e.value, order))
        }
        out.write(u32(0, order))                           // next IFD = 0
        return out.toByteArray()
    }

    private fun u16(v: Int, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(2).order(order).putShort(v.toShort()).array()

    private fun u32(v: Int, order: ByteOrder): ByteArray =
        ByteBuffer.allocate(4).order(order).putInt(v).array()

    /**
     * TIFF 头 + IFD0(`0x8769` → Exif 子 IFD) + 子 IFD(`0x927C` → MakerNote) + MakerNote 本体。
     * 这是 EXIF 规范位置、也是真机样张实测到的布局。
     * [inIfd0] = true 时退化成「MakerNote 直接挂在 IFD0」的宽松变体，用来覆盖兜底分支。
     */
    private fun exifBlob(maker: ByteArray, bigEndian: Boolean = false, inIfd0: Boolean = false): ByteArray {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val ifd0Offset = 8
        val subIfdOffset = ifd0Offset + 2 + 12 + 4
        val makerOffset = subIfdOffset + if (inIfd0) 0 else 2 + 12 + 4
        val out = ByteArrayOutputStream()
        out.write(if (bigEndian) byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A)
                 else byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00))
        out.write(u32(ifd0Offset, order))
        out.write(u16(1, order))
        if (inIfd0) {
            out.write(u16(0x927C, order))
            out.write(u16(TYPE_UNDEFINED, order))
            out.write(u32(maker.size, order))
            out.write(u32(makerOffset, order))
        } else {
            out.write(u16(0x8769, order))          // Exif 子 IFD 指针
            out.write(u16(TYPE_LONG, order))
            out.write(u32(1, order))
            out.write(u32(subIfdOffset, order))
        }
        out.write(u32(0, order))                   // next IFD
        if (!inIfd0) {
            out.write(u16(1, order))
            out.write(u16(0x927C, order))          // MakerNote
            out.write(u16(TYPE_UNDEFINED, order))
            out.write(u32(maker.size, order))
            out.write(u32(makerOffset, order))
            out.write(u32(0, order))               // next IFD
        }
        require(out.size() == makerOffset) { "maker offset mismatch: ${out.size()} != $makerOffset" }
        out.write(maker)
        return out.toByteArray()
    }

    /** 包一层最小合法 JPEG：SOI + APP1("Exif\0\0") + EOI */
    private fun wrapJpeg(exif: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        val payload = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00))
            write(exif)
        }.toByteArray()
        out.write((payload.size + 2) shr 8)
        out.write((payload.size + 2) and 0xFF)
        out.write(payload)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun jpeg(
        maker: ByteArray,
        bigEndian: Boolean = false,
        inIfd0: Boolean = false
    ) = wrapJpeg(exifBlob(maker, bigEndian, inIfd0))

    /** NEF 是裸 TIFF 容器：文件头即 II/MM，没有 APP1 */
    private fun nef(maker: ByteArray) = exifBlob(maker)

    // ---------- 形态 A：现代 D/Z，IFD@18 ----------

    @Test
    fun `parses modern v02xx note at ifd offset 18`() {
        val maker = makerNote(
            V02XX_PREFIX, ifdAt = 18,
            entries = listOf(Entry(0x00A7, 1500), Entry(0x00A5, 1000), Entry(0x00A6, 500))
        )
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(1500, reading.shutterCount)
        // 旧实现只试 IFD@8/0：那里读到的 entryCount 是 "00" 两字符 = 12336 > 512，直接被拒 → null
        assertEquals("v02xx/le", reading.layout)
        assertTrue("0x00A5 + 0x00A6 == 0x00A7 应判定为已校验", reading.verified)
    }

    @Test
    fun `unverified reading is still returned but flagged`() {
        val maker = makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A7, 123456)))
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(123456, reading.shutterCount)
        assertFalse(reading.verified)
    }

    @Test
    fun `reads mechanical-only count alongside`() {
        val maker = makerNote(
            V02XX_PREFIX, ifdAt = 18,
            entries = listOf(Entry(0x00A7, 8000), Entry(0x0037, 7400))
        )
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(8000, reading.shutterCount)
        assertEquals(7400, reading.mechanicalCount)
    }

    @Test
    fun `parses nef (raw tiff container, no app1)`() {
        val maker = makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A7, 4242)))
        assertEquals(4242, NikonShutterCountParser.parse(nef(maker))!!.shutterCount)
    }

    // ---------- 形态 B / C ----------

    @Test
    fun `parses e-series v01xx note at ifd offset 8`() {
        val maker = makerNote(V01XX_PREFIX, ifdAt = 8, entries = listOf(Entry(0x00A7, 3030)))
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(3030, reading.shutterCount)
        assertTrue(reading.layout.startsWith("v01xx"))
    }

    @Test
    fun `parses headerless big-endian note`() {
        // D1 / 部分 Coolpix：无 "Nikon\0" 签名，IFD 在 +0，大端
        val maker = makerNote(
            ByteArray(0), ifdAt = 0, bigEndian = true,
            entries = listOf(Entry(0x00A7, 4242))
        )
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(4242, reading.shutterCount)
    }

    @Test
    fun `parses big-endian maker note inside a little-endian container`() {
        // 真机实测形态：D70 / D2Hs 的 MakerNote 是大端，而机身主 TIFF 头是小端
        val maker = makerNote(V02XX_PREFIX, ifdAt = 18, bigEndian = true, entries = listOf(Entry(0x00A7, 526)))
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(526, reading.shutterCount)
    }

    @Test
    fun `parses fully big-endian container`() {
        val maker = makerNote(V02XX_PREFIX, ifdAt = 18, bigEndian = true, entries = listOf(Entry(0x00A7, 999)))
        assertEquals(999, NikonShutterCountParser.parse(jpeg(maker, bigEndian = true))!!.shutterCount)
    }

    @Test
    fun `falls back to ifd0 when there is no exif sub-ifd`() {
        // 宽松变体：0x927C 直接挂在 IFD0（真机不这么写，但兜底分支要能走通）
        val maker = makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A7, 777)))
        val reading = NikonShutterCountParser.parse(jpeg(maker, inIfd0 = true))!!
        assertEquals(777, reading.shutterCount)
    }

    @Test
    fun `matches the real D2Hs layout with mechanical shutter identity`() {
        // Exiv2 真机样张 NikonD2Hs.jpg 实测：主 TIFF 小端、笔记大端、A7=2 / A5=2 / A6=0
        val maker = makerNote(
            V02XX_PREFIX, ifdAt = 18, bigEndian = true,
            entries = listOf(Entry(0x00A7, 2), Entry(0x00A5, 2), Entry(0x00A6, 0))
        )
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(2, reading.shutterCount)
        assertTrue(reading.verified)
    }

    @Test
    fun `prefers inner tiff header form when present`() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4E, 0x69, 0x6B, 0x6F, 0x6E, 0x00, 0x02, 0x10, 0x30, 0x32))
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))          // II*\0 at 10
        out.write(byteArrayOf(8, 0, 0, 0))                      // IFD at 10+8 = 18
        out.write(byteArrayOf(1, 0))
        out.write(byteArrayOf(0xA7.toByte(), 0x00, 4, 0, 1, 0, 0, 0))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(6060).array())
        out.write(byteArrayOf(0, 0, 0, 0))
        val reading = NikonShutterCountParser.parse(jpeg(out.toByteArray()))!!
        assertEquals(6060, reading.shutterCount)
    }

    // ---------- 失败路径：一律 null，交由调用方兜底 ----------

    @Test
    fun `identity mismatch downgrades to unverified rather than rejecting`() {
        val maker = makerNote(
            V02XX_PREFIX, ifdAt = 18,
            entries = listOf(Entry(0x00A7, 1500), Entry(0x00A5, 1000), Entry(0x00A6, 400))
        )
        val reading = NikonShutterCountParser.parse(jpeg(maker))!!
        assertEquals(1500, reading.shutterCount)
        assertFalse(reading.verified)
    }

    @Test
    fun `returns null when maker note has no shutter count tag`() {
        val maker = makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A6, 999)))
        assertNull(NikonShutterCountParser.parse(jpeg(maker)))
    }

    @Test
    fun `returns null for implausible and sentinel values`() {
        assertNull(NikonShutterCountParser.parse(
            jpeg(makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A7, 0))))
        ))
        assertNull(NikonShutterCountParser.parse(
            jpeg(makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A7, 60_000_000))))
        ))
        // 0xFFFFF7FF 是机身写「n/a」的哨兵，有符号视角为负 → 必须判失败而不是报一个天文数字
        assertNull(NikonShutterCountParser.parse(
            jpeg(makerNote(V02XX_PREFIX, ifdAt = 18, entries = listOf(Entry(0x00A7, -2049))))
        ))
    }

    @Test
    fun `returns null for garbage input`() {
        assertNull(NikonShutterCountParser.parse(ByteArray(0)))
        assertNull(NikonShutterCountParser.parse(ByteArray(32)))
        assertNull(NikonShutterCountParser.parse(wrapJpeg("NotNikon".toByteArray())))
    }

    @Test
    fun `plain maker note without a container is not parsed`() {
        assertNull(NikonShutterCountParser.parse(
            makerNote(V01XX_PREFIX, ifdAt = 8, entries = listOf(Entry(0x00A7, 1)))
        ))
    }

    @Test
    fun `short-typed entry is accepted`() {
        val maker = makerNote(
            V02XX_PREFIX, ifdAt = 18,
            entries = listOf(Entry(0x00A7, 65000, type = TYPE_SHORT))
        )
        assertEquals(65000, NikonShutterCountParser.parse(jpeg(maker))!!.shutterCount)
    }

    private companion object {
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_UNDEFINED = 7
    }
}

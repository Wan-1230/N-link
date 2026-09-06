package com.nikonlink.app.camera.params

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 尼康快门次数本地解析器测试（设备页「快门次数」可行性验证）。
 *
 * 用合成夹具覆盖 MakerNote 的两种真实形态：
 * - JPEG v0100（"Nikon\0" + 版本 + IFD，无内嵌 TIFF 头，快门数内联 LONG）；
 * - NEF / 变体（MakerNote 内嵌 "II*\0" TIFF 头，偏移以该头为基准）。
 * 另覆盖：无 0x00A7、无 Nikon 头、不合理取值 → 全部返回 null（交由云端兜底）。
 */
class NikonShutterCountParserTest {

    // ---------- 夹具构造 ----------

    /** v0100 形态的 MakerNote：8 字节头 + IFD（0x00A7 内联 LONG） */
    private fun makerNoteV0100(shutterCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4E, 0x69, 0x6B, 0x6F, 0x6E, 0x00, 0x01, 0x00))  // Nikon\0 + 0x0100
        out.write(byteArrayOf(1, 0))                                            // 1 个 IFD entry
        out.write(byteArrayOf(0xA7.toByte(), 0x00))                             // tag 0x00A7
        out.write(byteArrayOf(4, 0))                                            // type LONG
        out.write(byteArrayOf(1, 0, 0, 0))                                      // count=1
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(shutterCount).array())                                      // 内联值
        out.write(byteArrayOf(0, 0, 0, 0))                                      // next IFD = 0
        return out.toByteArray()
    }

    /** NEF 形态：MakerNote 内嵌 TIFF 头（II*\0 + IFD offset=8，相对 TIFF 头） */
    private fun makerNoteWithInnerTiff(shutterCount: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x4E, 0x69, 0x6B, 0x6F, 0x6E, 0x00, 0x02, 0x10))  // Nikon\0 + ver 0x0210
        out.write(byteArrayOf(0, 0))                                            // pad
        val tiffStart = out.size()
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))                          // II*\0
        out.write(byteArrayOf(8, 0, 0, 0))                                      // IFD @ tiff+8
        out.write(byteArrayOf(1, 0))                                            // 1 个 entry
        out.write(byteArrayOf(0xA7.toByte(), 0x00, 4, 0, 1, 0, 0, 0))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(shutterCount).array())
        out.write(byteArrayOf(0, 0, 0, 0))
        // IFD 实际位置 = tiffStart + 8 ✓
        return out.toByteArray()
    }

    /** 包一层最小合法 JPEG：SOI + APP1(Exif) + EOI */
    private fun wrapJpeg(makerNote: ByteArray): ByteArray {
        // TIFF 头 + IFD0（仅 MakerNote 一个 entry，offset 指向 TIFF 之后）
        val ifd0Offset = 8
        val makerOffset = ifd0Offset + 2 + 12 + 4   // IFD: count(2) + entry(12) + next(4)
        val tiff = ByteArrayOutputStream().let { out ->
            out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ifd0Offset).array())
            out.write(byteArrayOf(1, 0))
            out.write(byteArrayOf(0x7C.toByte(), 0x92.toByte()))  // tag 0x927C MakerNote
            out.write(byteArrayOf(7, 0))                          // type UNDEFINED
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(makerNote.size).array())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(makerOffset).array())
            out.write(byteArrayOf(0, 0, 0, 0))
            out.toByteArray()
        }
        require(makerOffset == tiff.size)

        val app1Payload = ByteArrayOutputStream().let { out ->
            out.write(byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00))  // "Exif\0\0"
            out.write(tiff)
            out.write(makerNote)
            out.toByteArray()
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))            // SOI
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))            // APP1
        out.write((app1Payload.size + 2) shr 8)
        out.write((app1Payload.size + 2) and 0xFF)
        out.write(app1Payload)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))            // EOI
        return out.toByteArray()
    }

    // ---------- 用例 ----------

    @Test
    fun `parses shutter count from jpeg v0100 maker note`() {
        assertEquals(123456, NikonShutterCountParser.parse(wrapJpeg(makerNoteV0100(123456))))
    }

    @Test
    fun `parses shutter count from maker note with inner tiff header`() {
        assertEquals(
            4242,
            NikonShutterCountParser.parse(wrapJpeg(makerNoteWithInnerTiff(4242)))
        )
    }

    @Test
    fun `returns null when maker note has no shutter count tag`() {
        // 用合法结构但 tag 改成 0x00A6（非快门数）
        val maker = makerNoteV0100(999).copyOf()
        maker[10] = 0xA6.toByte()
        assertNull(NikonShutterCountParser.parse(wrapJpeg(maker)))
    }

    @Test
    fun `returns null for implausible shutter count`() {
        assertNull(NikonShutterCountParser.parse(wrapJpeg(makerNoteV0100(0))))
        assertNull(
            NikonShutterCountParser.parse(wrapJpeg(makerNoteV0100(60_000_000)))
        )
    }

    @Test
    fun `returns null for garbage input`() {
        assertNull(NikonShutterCountParser.parse(ByteArray(0)))
        assertNull(NikonShutterCountParser.parse(ByteArray(32)))
        assertNull(NikonShutterCountParser.parse(wrapJpeg("NotNikon".toByteArray())))
    }

    @Test
    fun `plain maker note without jpeg wrapper is not parsed`() {
        // 直接给 MakerNote 字节（缺 APP1/TIFF 容器）→ null，不抛异常
        assertNull(NikonShutterCountParser.parse(makerNoteV0100(1)))
    }
}

package com.nikonlink.app.camera.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * STA 直读解析层：句柄类型、0x9434 载荷、JPEG/NEF 文件头里的时间 / 编号 / 内嵌 JPEG。
 * 字节结构按 TIFF 6.0、CIPA DC-007(MPF) 与尼康 MakerNote 布局手工构造。
 */
class StaDirectReadTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun `句柄高 8 位决定类型`() {
        assertEquals(CameraFileFormat.RAW, StaDirectRead.formatFromHandle(0x09001234))
        assertEquals(CameraFileFormat.JPEG, StaDirectRead.formatFromHandle(0x29000001))
        assertEquals(CameraFileFormat.VIDEO, StaDirectRead.formatFromHandle(0x61000002))
        assertNull(StaDirectRead.formatFromHandle(0x01000000))
        // 编号统一 4 位，与机身一致；列表阶段与落盘阶段用同一种格式
        assertEquals("DSC_4660.NEF", StaDirectRead.placeholderName(0x09001234, CameraFileFormat.RAW))
        assertEquals("DSC_0002.MP4", StaDirectRead.placeholderName(0x61000002, CameraFileFormat.VIDEO))
        assertEquals("Z30_6546.JPG", StaDirectRead.placeholderName(0x29191992, CameraFileFormat.JPEG, "Z30"))
    }

    @Test
    fun `卡内文件名 sRGB 与 AdobeRGB 两种形态`() {
        assertEquals("Z30_1001.JPG", StaDirectRead.cardName("Z30", 1001, adobeRgb = false, ext = "JPG"))
        assertEquals("_Z301001.JPG", StaDirectRead.cardName("Z30", 1001, adobeRgb = true, ext = "JPG"))
        assertEquals(
            "_DSC0007.NEF",
            StaDirectRead.nameFromNumber(7, CameraFileFormat.RAW, 0x09000007, adobeRgb = true)
        )
    }

    @Test
    fun `前缀只接受 3 位大写字母数字`() {
        assertTrue(StaDirectRead.isPlausiblePrefix("Z30"))
        assertTrue(StaDirectRead.isPlausiblePrefix("DSC"))
        assertFalse(StaDirectRead.isPlausiblePrefix("z30"))
        assertFalse(StaDirectRead.isPlausiblePrefix("Z3"))
        assertFalse(StaDirectRead.isPlausiblePrefix("NIKON"))
        assertFalse(StaDirectRead.isPlausiblePrefix(null))
    }

    @Test
    fun `0x1014 字符串属性描述解出当前值`() {
        fun ptpString(v: String): ByteArray {
            val chars = v + "\u0000"
            val b = ByteBuffer.allocate(1 + chars.length * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put(chars.length.toByte())
            chars.forEach { b.putShort(it.code.toShort()) }
            return b.array()
        }
        val head = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD0AA.toShort()).putShort(0xFFFF.toShort()).put(1).array()
        val data = head + ptpString("DSC") + ptpString("Z30") + byteArrayOf(0)
        assertEquals(0xD0AA to "Z30", StaDirectRead.parseStringPropDesc(data))

        // 非字符串型（UINT8）不解
        val u8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0xD06C.toShort()).putShort(0x0002).put(1).put(0).put(1).put(0).array()
        assertNull(StaDirectRead.parseStringPropDesc(u8))
        // 截断
        assertNull(StaDirectRead.parseStringPropDesc(head + byteArrayOf(4, 0x44)))
    }

    @Test
    fun `0x9434 载荷逐条解出 handle size 与本地拍摄时间`() {
        val buf = ByteBuffer.allocate(8 + 32).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(100).putInt(2)
        buf.putInt(0x29000010).putInt(5_000_000)
        buf.put(0).put(7).put(30).put(18).put(18).put(10).putShort(2026)
        buf.putInt(0x09000011).putInt(25_000_000)
        buf.put(0).put(0).put(0).put(0).put(0).put(0).putShort(0) // 无效时间
        val list = StaDirectRead.parseObjectsMetaData(buf.array(), utc)!!
        assertEquals(2, list.size)
        assertEquals(0x29000010, list[0].handle)
        assertEquals(5_000_000L, list[0].size)
        assertEquals(
            LocalDateTime.of(2026, 10, 18, 18, 30, 7).toInstant(utc).toEpochMilli(),
            list[0].captureTimeMillis
        )
        assertNull(list[1].captureTimeMillis)
    }

    @Test
    fun `0x9434 计数超出载荷长度视为不可用`() {
        val buf = ByteBuffer.allocate(8 + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(100).putInt(5)
        assertNull(StaDirectRead.parseObjectsMetaData(buf.array(), utc))
    }

    @Test
    fun `JPEG 头里解出 EXIF 时间 文件编号 IFD1 缩略图与 MPF 大预览`() {
        val head = buildJpegWithExifAndMpf()
        val info = StaDirectRead.parseHead(head, utc)
        assertEquals(
            LocalDateTime.of(2026, 10, 18, 18, 26, 1).toInstant(utc).toEpochMilli(),
            info.captureTimeMillis
        )
        assertEquals(1234, info.fileNumber)
        // 最大的在前：MPF 大预览（400 字节）、IFD1 缩略图（64 字节）
        assertEquals(2, info.embeddedJpegs.size)
        assertEquals(400L, info.embeddedJpegs[0].last - info.embeddedJpegs[0].first + 1)
        assertEquals(64L, info.embeddedJpegs[1].last - info.embeddedJpegs[1].first + 1)
        val thumb = head.copyOfRange(info.embeddedJpegs[1].first.toInt(), info.embeddedJpegs[1].last.toInt() + 1)
        assertTrue(StaDirectRead.isCompleteJpeg(thumb))
        assertEquals("DSC_1234.JPG", StaDirectRead.nameFromNumber(info.fileNumber, CameraFileFormat.JPEG, 0x29000001))
    }

    @Test
    fun `EXIF ColorSpace 为 Uncalibrated 时按 AdobeRGB 命名`() {
        assertFalse(StaDirectRead.parseHead(buildJpegWithExifAndMpf(colorSpace = 1), utc).adobeRgb)
        val info = StaDirectRead.parseHead(buildJpegWithExifAndMpf(colorSpace = 0xFFFF), utc)
        assertTrue(info.adobeRgb)
        assertEquals(
            "_Z301234.JPG",
            StaDirectRead.nameFromNumber(info.fileNumber, CameraFileFormat.JPEG, 0x29000001, "Z30", info.adobeRgb)
        )
    }

    @Test
    fun `NEF 的 SubIFD 声明的全尺寸预览区间可超出已读头部`() {
        val head = buildNefHead(previewOffset = 1_000_000, previewLength = 2_000_000)
        val info = StaDirectRead.parseHead(head, utc)
        assertNotNull(info.captureTimeMillis)
        val big = info.embeddedJpegs.first()
        assertEquals(1_000_000L, big.first)
        assertEquals(2_000_000L, big.last - big.first + 1)
    }

    @Test
    fun `非图像头部安全返回空结果`() {
        val info = StaDirectRead.parseHead(ByteArray(64) { 0x41 }, utc)
        assertNull(info.captureTimeMillis)
        assertNull(info.fileNumber)
        assertTrue(info.embeddedJpegs.isEmpty())
    }

    @Test
    fun `截断的 TIFF 不越界`() {
        val head = buildNefHead(previewOffset = 1_000_000, previewLength = 2_000_000)
        for (cut in listOf(8, 20, 40, 90)) StaDirectRead.parseHead(head.copyOf(cut), utc)
    }

    @Test
    fun `目录句柄不算媒体 前缀分布按高 8 位升序计数`() {
        assertTrue(!StaDirectRead.isMediaHandle(0x11004000))
        assertTrue(StaDirectRead.isMediaHandle(0x29191986))
        assertEquals(
            "11:2,29:1",
            StaDirectRead.prefixHistogram(listOf(0x29191986, 0x11004000, 0x11004001))
        )
    }

    @Test
    fun `网格缩略图优先区间内最大的一张 区间内没有退到上限内最大`() {
        // Z30 JPEG 实测三段：IFD1 10700B、MPF 178726B、MPF 759465B
        val ifd1 = 31484L until 31484L + 10700
        val mpf640 = 7_208_090L until 7_208_090L + 178_726
        val mpf1620 = 7_288_000L until 7_288_000L + 759_465
        val all = listOf(mpf1620, mpf640, ifd1)
        assertEquals(mpf640, StaDirectRead.pickGridJpeg(all, 8 * 1024L, 400 * 1024L))
        // 没有 640 那张时退到小图
        assertEquals(ifd1, StaDirectRead.pickGridJpeg(listOf(mpf1620, ifd1), 8 * 1024L, 400 * 1024L))
        // 只有超上限的大图：不选（交给高清档 / 0x9530）
        assertNull(StaDirectRead.pickGridJpeg(listOf(mpf1620), 8 * 1024L, 400 * 1024L))
    }

    // ---------- 构造器 ----------

    /** little-endian TIFF 构造：调用方给定各 IFD 的位置，这里只负责写条目。 */
    private class Tiff(size: Int) {
        val b: ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        init {
            b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42)
        }

        fun ifd(at: Int, entries: List<IntArray>, next: Int) {
            b.position(at)
            b.putShort(entries.size.toShort())
            for (e in entries) {
                b.putShort(e[0].toShort()).putShort(e[1].toShort()).putInt(e[2]).putInt(e[3])
            }
            b.putInt(next)
        }

        fun ascii(at: Int, s: String) {
            b.position(at); b.put(s.toByteArray(Charsets.US_ASCII)); b.put(0)
        }
    }

    private fun tinyJpeg(len: Int): ByteArray = ByteArray(len).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
        it[len - 2] = 0xFF.toByte(); it[len - 1] = 0xD9.toByte()
    }

    private fun buildJpegWithExifAndMpf(colorSpace: Int? = null): ByteArray {
        // EXIF TIFF：IFD0@8 → ExifIFD@200；IFD1@100 → 缩略图@400（64B）
        val t = Tiff(480)
        t.b.putInt(4, 8)
        t.ifd(8, listOf(intArrayOf(0x8769, 4, 1, 200)), next = 100)
        t.ifd(100, listOf(intArrayOf(0x0201, 4, 1, 400), intArrayOf(0x0202, 4, 1, 64)), next = 0)
        t.ifd(
            200,
            listOfNotNull(
                intArrayOf(0x9003, 2, 20, 300),
                intArrayOf(0x9211, 4, 1, 1234),
                colorSpace?.let { intArrayOf(0xA001, 3, 1, it) }
            ),
            next = 0
        )
        t.ascii(300, "2026:10:18 18:26:01")
        System.arraycopy(tinyJpeg(64), 0, t.b.array(), 400, 64)
        val exifTiff = t.b.array()

        // MPF TIFF：IFD@8 含 MPEntry(0xB002, UNDEFINED, 32B @ 50)；第二条指向大预览
        val mpf = ByteBuffer.allocate(82).order(ByteOrder.LITTLE_ENDIAN)
        mpf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        mpf.position(8)
        mpf.putShort(1)
        mpf.putShort(0xB002.toShort()).putShort(7).putInt(32).putInt(50)
        mpf.putInt(0)
        mpf.position(50)
        mpf.putInt(0).putInt(9999).putInt(0).putShort(0).putShort(0) // 主图
        // 大预览：offset 相对 MPF TIFF 头，稍后回填
        val previewEntryPos = 50 + 16
        mpf.position(previewEntryPos)
        mpf.putInt(0x010002).putInt(400).putInt(0).putShort(0).putShort(0)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP1
        val app1Len = 2 + 6 + exifTiff.size
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (app1Len shr 8).toByte(), app1Len.toByte()))
        out.write("Exif".toByteArray()); out.write(byteArrayOf(0, 0)); out.write(exifTiff)
        // APP2 MPF
        val app2Start = out.size()
        val mpfTiffStart = app2Start + 4 + 4
        val app2Len = 2 + 4 + mpf.capacity()
        out.write(byteArrayOf(0xFF.toByte(), 0xE2.toByte(), (app2Len shr 8).toByte(), app2Len.toByte()))
        out.write("MPF".toByteArray()); out.write(0)
        val mpfBytes = mpf.array()
        val previewAbs = mpfTiffStart + mpfBytes.size + 100
        ByteBuffer.wrap(mpfBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(previewEntryPos + 8, previewAbs - mpfTiffStart)
        out.write(mpfBytes)
        // 主图扫描段占位，然后是大预览
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte()))
        while (out.size() < previewAbs) out.write(0)
        out.write(tinyJpeg(400))
        return out.toByteArray()
    }

    private fun buildNefHead(previewOffset: Int, previewLength: Int): ByteArray {
        // IFD0@8：DateTime + SubIFDs(1 个 @ 100)；SubIFD：JPEGInterchangeFormat 指向远处
        val t = Tiff(200)
        t.b.putInt(4, 8)
        t.ifd(8, listOf(intArrayOf(0x0132, 2, 20, 150), intArrayOf(0x014A, 4, 1, 100)), next = 0)
        t.ifd(
            100,
            listOf(intArrayOf(0x0201, 4, 1, previewOffset), intArrayOf(0x0202, 4, 1, previewLength)),
            next = 0
        )
        t.ascii(150, "2026:10:18 18:26:01")
        return t.b.array()
    }
}

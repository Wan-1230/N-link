package com.nikonlink.app.camera.liveview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LvFrame.findJpegStart] 测试（A4/X2 合并的回归闸）。
 *
 * 这个函数此前有三份副本（Manager / 全屏页 / 遥控页），合并后靠这组用例锁住行为：
 * 相机帧头格式一旦变化，改一处 + 跑一次测试即可，不必担心某一页被漏改。
 */
class LvFrameTest {

    private fun frameWithHeader(headerSize: Int, vararg tail: Byte): ByteArray {
        val header = ByteArray(headerSize) { 0x00 }
        return header + byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + tail
    }

    @Test
    fun `no vendor header returns zero`() {
        assertEquals(0, LvFrame.findJpegStart(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01)))
    }

    @Test
    fun `nikon 1024 byte header is skipped`() {
        assertEquals(1024, LvFrame.findJpegStart(frameWithHeader(1024, 0x01)))
    }

    @Test
    fun `arbitrary header size is skipped`() {
        assertEquals(7, LvFrame.findJpegStart(frameWithHeader(7)))
        assertEquals(1, LvFrame.findJpegStart(frameWithHeader(1)))
    }

    @Test
    fun `header containing a stray 0xFF is not mistaken for SOI`() {
        // 前导数据里单独出现 0xFF（不是 0xFFD8）时不能被当成帧起点
        val data = byteArrayOf(0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0xD8.toByte(), 0x12)
        assertEquals(3, LvFrame.findJpegStart(data))
    }

    @Test
    fun `missing SOI falls back to zero`() {
        // 找不到时退回从头解码，保持合并前的既有行为（让 BitmapFactory 自己判失败）
        assertEquals(0, LvFrame.findJpegStart(byteArrayOf(0x01, 0x02, 0x03)))
        assertEquals(0, LvFrame.findJpegStart(ByteArray(64)))
    }

    @Test
    fun `too short to hold SOI returns zero`() {
        assertEquals(0, LvFrame.findJpegStart(byteArrayOf()))
        assertEquals(0, LvFrame.findJpegStart(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `signed bytes are masked correctly`() {
        // ByteArray 是有符号的：0xFF = -1、0xD8 = -40。未做 and 0xFF 掩码会漏匹配。
        assertEquals(0, LvFrame.findJpegStart(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}

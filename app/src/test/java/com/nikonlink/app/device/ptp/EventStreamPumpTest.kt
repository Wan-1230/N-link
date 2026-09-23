package com.nikonlink.app.device.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FR-04 订正后的真正判据：**静默**与**流错位**必须分得开。
 *
 * 混为一谈的代价是实打实的——相机在传大文件时 event 通道本就静默（v2.2 G7 已经认了这件事，
 * 心跳那条路径还专门为此加了 `bulkDepth` 豁免），而 event 读取这条路径没有豁免，
 * 45s 到点就 `markLinkError("event_read_timeout")`，于是"下载一张大 RAW"会自己掐断链路。
 * 反过来，包读到一半才超时**确实**是流错位，接着读就是把包尾当下一个包头，绝不能放行。
 */
class EventStreamPumpTest {

    private sealed class Cue
    private class Data(val bytes: ByteArray) : Cue()
    private object Timeout : Cue()
    private object Eof : Cue()
    private object Boom : Cue()

    /**
     * 脚本化输入流：每次 read() 从队列里取字节，取完再下一个 Cue。
     * 和真 socket 一样，一次 read 不会给出比 `length` 更多的字节（脚本里可以整帧塞，
     * 剩下的自动留到下一次），队尾耗尽即等价于对端关闭。
     */
    private class ScriptedStream(private val cues: MutableList<Cue>) : InputStream() {
        private var carry: ByteArray? = null
        private var carryAt = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            var source = carry
            var at = carryAt
            if (source == null || at >= source.size) {
                while (true) {
                    if (cues.isEmpty()) return -1
                    when (val cue = cues.removeAt(0)) {
                        is Data -> {
                            source = cue.bytes
                            at = 0
                            if (cue.bytes.isNotEmpty()) break
                        }
                        Timeout -> throw SocketTimeoutException("scripted")
                        Eof -> return -1
                        Boom -> throw IOException("scripted")
                    }
                }
            }
            val taken = minOf(length, source!!.size - at)
            System.arraycopy(source, at, buffer, offset, taken)
            carry = source
            carryAt = at + taken
            return taken
        }
    }

    private fun frame(type: Int, body: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(8 + body.size)
        buffer.putInt(type)
        buffer.put(body)
        return buffer.array()
    }

    private fun eventBody(code: Int = 0x400D, txId: Int = 7, vararg params: Int): ByteArray {
        val buffer = ByteBuffer.allocate(6 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(code.toShort())
        buffer.putInt(txId)
        params.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun pump(vararg cues: Cue) = EventStreamPump(ScriptedStream(cues.toMutableList()))

    @Test
    fun `一个完整事件包能读出来`() {
        val body = eventBody(code = 0x400D, txId = 42, params = intArrayOf(0x00010001))
        val outcome = pump(Data(frame(PtpConstants.PACKET_TYPE_EVENT, body))).next()
        val packet = (outcome as EventStreamPump.Outcome.Packet).packet as EventResponsePacket
        assertEquals(0x400D, packet.eventCode)
        assertEquals(42, packet.transactionId)
        assertEquals(listOf(0x00010001), packet.parameters)
    }

    @Test
    fun `头和体分片到达也要拼成一个包`() {
        val body = eventBody(txId = 5)
        val frame = frame(PtpConstants.PACKET_TYPE_EVENT, body)
        val cues = mutableListOf<Cue>(
            Data(frame.copyOfRange(0, 3)),          // 头的前 3 字节
            Data(frame.copyOfRange(3, 8)),          // 头的后 5 字节
            Data(frame.copyOfRange(8, 11)),         // 体的前 3 字节
            Data(frame.copyOfRange(11, frame.size))
        )
        val outcome = EventStreamPump(ScriptedStream(cues)).next()
        assertTrue(outcome is EventStreamPump.Outcome.Packet)
        assertEquals(5, (outcome as EventStreamPump.Outcome.Packet).packet.let { it as EventResponsePacket }.transactionId)
    }

    @Test
    fun `一个字节都没读到的超时是静默，之后流仍然齐`() {
        val stream = ScriptedStream(
            mutableListOf(
                Timeout,
                Data(frame(PtpConstants.PACKET_TYPE_PING, ByteArray(0))),
                Data(frame(PtpConstants.PACKET_TYPE_EVENT, eventBody(txId = 9)))
            )
        )
        val pump = EventStreamPump(stream)
        assertEquals(EventStreamPump.Outcome.Idle, pump.next())
        assertTrue(pump.next() is EventStreamPump.Outcome.Packet)
        val second = pump.next() as EventStreamPump.Outcome.Packet
        assertEquals(9, (second.packet as EventResponsePacket).transactionId)
    }

    @Test
    fun `头读到一半超时是流错位，不能当静默`() {
        val frame = frame(PtpConstants.PACKET_TYPE_EVENT, eventBody())
        val outcome = pump(Data(frame.copyOfRange(0, 5)), Timeout).next()
        assertEquals(EventStreamPump.Outcome.Partial, outcome)
    }

    @Test
    fun `体读到一半超时同样是流错位`() {
        val body = eventBody(params = intArrayOf(1, 2))
        val frame = frame(PtpConstants.PACKET_TYPE_EVENT, body)
        val cues = mutableListOf<Cue>(
            Data(frame.copyOfRange(0, 8)),
            Data(frame.copyOfRange(8, 12)),
            Timeout
        )
        assertEquals(EventStreamPump.Outcome.Partial, EventStreamPump(ScriptedStream(cues)).next())
    }

    @Test
    fun `声明长度比头还短是脏帧`() {
        val bad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(4).putInt(8).array()
        val outcome = pump(Data(bad)).next()
        assertEquals(EventStreamPump.Outcome.BadFrame("length_below_header"), outcome)
    }

    @Test
    fun `荒谬长度不许照单allocate`() {
        val bad = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(EventStreamPump.MAX_BODY_BYTES + 0x1000).putInt(8).array()
        val outcome = pump(Data(bad)).next()
        assertEquals(EventStreamPump.Outcome.BadFrame("length_overflow"), outcome)
    }

    @Test
    fun `对端关闭与线断了要分开`() {
        assertEquals(EventStreamPump.Outcome.Closed, pump(Eof).next())
        assertTrue(pump(Boom).next() is EventStreamPump.Outcome.IoError)
    }

    @Test
    fun `不认识的类型仍是完整包，不是解不出`() {
        val outcome = pump(Data(frame(0x00FF, ByteArray(4)))).next()
        assertTrue(outcome is EventStreamPump.Outcome.Packet)
        assertTrue((outcome as EventStreamPump.Outcome.Packet).packet is UnknownPacket)
    }

    @Test
    fun `帧完整但内容解不出时跳过它，后面的包照读`() {
        val stream = ScriptedStream(
            mutableListOf(
                // 事件包声明了 body，可 body 只有 2 字节，parse 会越界
                Data(frame(PtpConstants.PACKET_TYPE_EVENT, ByteArray(2))),
                Data(frame(PtpConstants.PACKET_TYPE_PING, ByteArray(0)))
            )
        )
        val pump = EventStreamPump(stream)
        assertEquals(
            EventStreamPump.Outcome.Unparsable(PtpConstants.PACKET_TYPE_EVENT),
            pump.next()
        )
        // 关键：跳过之后流依然齐 —— 这种时候杀连接是把相机的问题揽到自己身上
        assertTrue(pump.next() is EventStreamPump.Outcome.Packet)
    }
}

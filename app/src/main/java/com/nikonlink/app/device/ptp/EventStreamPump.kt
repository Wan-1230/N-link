package com.nikonlink.app.device.ptp

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * event 通道的读包泵（FR-04 订正之后真正的缺口）。
 *
 * 为什么要单独一个泵：`PtpPacket.fromStream` 把三种完全不同的事都返回 `null`——
 * 对端关闭、声明长度荒谬、包体解不出来；而 `soTimeout` 到期是从 `readFully` 里
 * 抛 [SocketTimeoutException] 冒出来的，此时**这个包的字节可能只读到一半**。
 * 一半就意味着 TCP 流已经错位：再怎么"接着读"都是在把一个包的尾巴当下一个包的开头。
 * 所以旧实现只能整条判死（`event_read_timeout`），连带把"相机在传大文件时
 * event 通道本来就该静默"这种情况也一起杀掉了。
 *
 * 本泵把判据拆干净，交给调用方按闸门决定宽容还是判死：
 * - [Outcome.Idle] —— 超时，且这一轮**一个字节都没读到** → 流仍是齐的，静默可以继续等
 * - [Outcome.Partial] —— 超时，但包读到一半 → 流已错位，只能重开连接
 * - [Outcome.Closed] —— 对端正常关闭
 * - [Outcome.BadFrame] —— 声明长度不合法（含超出 [MAX_BODY_BYTES] 的荒谬值）
 * - [Outcome.Unparsable] —— 帧是完整的、只是这一类包解不出来 → **流没坏**，可以跳过继续
 * - [Outcome.IoError] —— socket 自己报错了，这是"线断了"不是"话说不清"
 *
 * 不依赖 Android，纯 JVM 可测。
 */
class EventStreamPump(private val input: InputStream) {

    sealed class Outcome {
        data class Packet(val packet: PtpPacket) : Outcome()
        object Idle : Outcome()
        object Partial : Outcome()
        object Closed : Outcome()
        data class BadFrame(val reason: String) : Outcome()
        data class Unparsable(val type: Int) : Outcome()

        /**
         * 底层 IO 异常。与 [BadFrame] 分开：那不是"话说不通"，而是"线断了"，
         * 任何时候都该判死，不受静默容忍闸门管辖。
         */
        data class IoError(val detail: String) : Outcome()
    }

    private val header = ByteArray(HEADER_BYTES)
    private var headerFilled = 0
    private var body: ByteArray? = null
    private var bodyFilled = 0
    private var pendingType = 0

    /**
     * 读下一个包。一次调用最多阻塞一个 soTimeout 周期。
     *
     * [progressed] 之所以是字段而不是局部变量：超时是从循环体内的 `read()` 抛出来的，
     *  catching 它的地方在循环外面，那里必须还能知道"这轮到底读到过字节没有"。
     */
    private var progressed = false

    fun next(): Outcome = try {
        pumpLoop()
    } catch (e: SocketTimeoutException) {
        // 关键在于"这轮有没有读到过字节"：没读到 = 静默，读到一半 = 错位
        if (progressed) Outcome.Partial else Outcome.Idle
    } catch (e: IOException) {
        Outcome.IoError(e.message ?: "unknown")
    }

    private fun pumpLoop(): Outcome {
        progressed = false
        while (true) {
            val buffer = body
            if (buffer == null) {
                val read = input.read(header, headerFilled, HEADER_BYTES - headerFilled)
                if (read == -1) return Outcome.Closed
                progressed = true
                headerFilled += read
                if (headerFilled < HEADER_BYTES) continue

                val length = littleEndianInt(header, 0)
                val type = littleEndianInt(header, 4)
                val bodyBytes = length - HEADER_BYTES
                if (bodyBytes < 0) {
                    // 声明长度比头还短：要么流已经错位，要么对端在说非 PTP/IP 的话
                    return Outcome.BadFrame("length_below_header")
                }
                if (bodyBytes > MAX_BODY_BYTES) {
                    // 不照单全收地 allocate：一个"4GB 的事件包"只可能是脏流，
                    // 照读就是把 OOM 换成了"看起来像相机的问题"
                    return Outcome.BadFrame("length_overflow")
                }
                if (bodyBytes == 0) {
                    headerFilled = 0
                    return decode(type, EMPTY)
                }
                pendingType = type
                body = ByteArray(bodyBytes)
                bodyFilled = 0
            } else {
                val read = input.read(buffer, bodyFilled, buffer.size - bodyFilled)
                if (read == -1) return Outcome.Closed
                progressed = true
                bodyFilled += read
                if (bodyFilled < buffer.size) continue
                body = null
                bodyFilled = 0
                headerFilled = 0
                return decode(pendingType, buffer)
            }
        }
    }

    private fun decode(type: Int, payload: ByteArray): Outcome {
        return try {
            Outcome.Packet(PtpPacket.createPacket(type, payload))
        } catch (e: Exception) {
            // 帧完整、内容解不出：流没坏，跳过这一个包比杀连接更贴近事实
            Outcome.Unparsable(type)
        }
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    companion object {
        /** PTP/IP 头 = 4 字节长度 + 4 字节类型（`PtpConstants.PTP_IP_HEADER_SIZE`） */
        private const val HEADER_BYTES = 8
        private val EMPTY = ByteArray(0)

        /**
         * 单个包体的上限。event 通道实际只走十几字节的事件包与 Ping/Pong，
         * 64MB 已经比任何合法事件大几个数量级——超过它只能是脏流。
         */
        const val MAX_BODY_BYTES = 64 * 1024 * 1024
    }
}

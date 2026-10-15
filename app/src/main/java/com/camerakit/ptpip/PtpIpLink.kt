package com.camerakit.ptpip

import com.camerakit.api.CameraEndpoint
import com.camerakit.api.CameraError
import com.camerakit.ptp.PtpCameraEvent
import com.camerakit.ptp.PtpConsts
import com.camerakit.ptp.PtpIpCodec
import com.camerakit.ptp.PtpLink
import com.camerakit.ptp.PtpResult
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PTP/IP 链路（Wi-Fi AP / STA 共用）。
 *
 * 握手序（基准实现 静态分析 + gphoto ptpip 文档互证）：
 * 1. 命令 socket 连接 `host:15740`，发 InitCommand(type1) = GUID(16) + UTF-16LE 主机名 + 版本 0x00010000；
 * 2. 收 InitCommandAck(type2) = ConnectionID + 相机 GUID + 相机名；type5 = InitFail（相机拒绝）；
 * 3. 事件 socket 再次连接同一端口，发 InitEvent(type3) = ConnectionID，收 InitEventAck(type4)；
 * 4. 之后命令 socket 跑事务（type6/7/9/10/12），事件 socket 上报 type8，Ping(13)/Pong(14) 保活。
 */
class PtpIpLink(
    private val endpoint: CameraEndpoint,
    private val hostGuid: ByteArray,
    private val hostName: String,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val log: (String) -> Unit = {},
    private val onTransportLost: (String) -> Unit = {},
    /**
     * InitCommandAck 与事件通道建链之间的稳定间隔。
     *
     * 真机日志全量统计（2026-10-14 全天）：**成功点全部发生在稳定间隔 ≥650ms 的轮次**
     * （22:23:14 成功轮 settle=650ms；17:35/17:39 成功轮 settle=2000ms），
     * 而 0ms 立即建事件通道的首试从未成功过（事件通道建链后 6~12ms 即被相机复位）。
     * 与基准实现实证一致（重试轮 350/650ms 稳定间隔）。默认 650ms，所有轮次统一。
     */
    private val eventSettleMs: Long = 650,
) : PtpLink {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val SO_TIMEOUT_MS = 1_000

        /** 事件通道 PING 等 Pong 的窗口（旧链路同值；Pong 超时仅容错，不判死）。 */
        private const val EVENT_PING_TIMEOUT_MS = 1_500
    }

    private var cmdSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null
    private var evtSocket: Socket? = null
    private var evtIn: InputStream? = null
    private var evtOut: OutputStream? = null

    private val writeLock = Any()
    private val transactMutex = Mutex()

    @Volatile private var nextTransId = 1
    @Volatile private var open = false
    @Volatile private var lastRx = 0L
    @Volatile private var lostNotified = false
    private var eventJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<PtpCameraEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PtpCameraEvent> = _events

    var connectionId: Int = -1
        private set
    var cameraName: String = ""
        private set

    override val isOpen: Boolean get() = open

    override fun lastReceiveAt(): Long = lastRx

    /** 完成 PTP/IP 握手（命令 + 事件双通道）。 */
    suspend fun open() = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS
        try {
            val s = socketFactory.createSocket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            s.soTimeout = SO_TIMEOUT_MS
            cmdSocket = s
            cmdIn = s.getInputStream()
            cmdOut = s.getOutputStream()
            log("PTP/IP 命令通道已连接 ${s.localAddress?.hostAddress ?: "-"} -> ${endpoint.host}:${endpoint.port}")

            writePacket(PtpConsts.IP_INIT_COMMAND, PtpIpCodec.initCommandPayload(hostGuid, hostName))
            val ackPacket = awaitPacket(cmdIn!!, deadline)
            when (ackPacket.type) {
                PtpConsts.IP_INIT_FAIL ->
                    throw CameraError.CameraRejected(PtpIpCodec.parseInitFail(ackPacket.payload))
                PtpConsts.IP_INIT_COMMAND_ACK -> {
                    val ack = PtpIpCodec.parseInitCommandAck(ackPacket.payload)
                    connectionId = ack.connectionId
                    cameraName = ack.cameraName.ifEmpty { endpoint.displayName }
                    log("PTP/IP init-command-ack connection=$connectionId camera=$cameraName")
                }
                else -> throw CameraError.Protocol("PTP/IP 初始化响应无效（type=${ackPacket.type}）")
            }

            // 所有轮次统一稳定间隔（真机统计：成功全部发生在 settle≥650ms 轮次）
            if (eventSettleMs > 0) {
                log("PTP/IP 事件通道稳定等待 ${eventSettleMs}ms")
                kotlinx.coroutines.delay(eventSettleMs)
            }

            val e = socketFactory.createSocket()
            e.tcpNoDelay = true
            e.keepAlive = true
            // 事件通道绑定到与命令通道相同的本地地址（基准实现 forced 语义），
            // 防止双网手机上事件 socket 漂到其它网络导致相机复位
            val cmdLocal = s.localAddress
            var forced = false
            if (cmdLocal != null && !cmdLocal.isAnyLocalAddress) {
                try {
                    e.bind(InetSocketAddress(cmdLocal, 0))
                    forced = true
                } catch (_: Exception) {
                }
            }
            e.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            e.soTimeout = SO_TIMEOUT_MS
            evtSocket = e
            evtIn = e.getInputStream()
            evtOut = e.getOutputStream()
            log("PTP/IP 事件通道已连接 local=${e.localAddress?.hostAddress ?: "-"} forced=$forced")
            writeEventPacket(PtpConsts.IP_INIT_EVENT, PtpIpCodec.initEventPayload(connectionId))
            val ev = awaitPacket(evtIn!!, deadline)
            if (ev.type != PtpConsts.IP_INIT_EVENT_ACK) {
                throw CameraError.Protocol("相机没有接受 PTP/IP 事件通道（type=${ev.type}）")
            }
            log("PTP/IP init-event-ack")

            // 旧链路实证步骤（本项目 STA/AP 旧栈在同机型上稳定复用）：InitEventAck 后
            // 立即在事件通道发 PING。缺少这一握手的相机会在数秒后把连接判定为失败
            // 并断开（真机现象：连成后 ~4s 被断、后续连接被严拒）。Pong 不强制——
            // 旧栈同款容错：发出去即满足相机侧握手预期，回不回 Pong 都继续。
            runCatching {
                val oldTimeout = e.soTimeout
                e.soTimeout = EVENT_PING_TIMEOUT_MS
                writeEventPacket(PtpConsts.IP_PING, ByteArray(0))
                val pong = readPacketOrNull(evtIn!!)
                if (pong != null && pong.type == PtpConsts.IP_PONG) {
                    log("PTP/IP 事件通道 PING→PONG ✓")
                } else {
                    log("PTP/IP 事件通道 PING 已发，Pong 未回（got=${pong?.type}）——按旧栈容错继续")
                }
                e.soTimeout = oldTimeout
            }.onFailure {
                log("PTP/IP 事件通道 PING 异常（容错继续）：${it.message}")
            }

            open = true
            lastRx = System.currentTimeMillis()
            eventJob = scope.launch { readEventLoop() }
        } catch (e: CameraError) {
            closeInternal()
            throw e
        } catch (e: Exception) {
            closeInternal()
            throw CameraError.TransportLost("PTP/IP 握手失败：${e.message}", e)
        }
    }

    override suspend fun transact(
        opcode: Int,
        params: List<Long>,
        dataOut: ByteArray?,
        sink: ((ByteArray) -> Unit)?,
        timeoutMs: Long,
    ): PtpResult = transactMutex.withLock {
        if (!open) throw CameraError.NotConnected("PTP/IP 链路未打开")
        val transId = nextTransId++
        val deadline = System.currentTimeMillis() + timeoutMs

        writePacket(
            PtpConsts.IP_CMD_REQUEST,
            // 旧链路/基准实现 实证：dataPhaseInfo 无数据相位=1、有数据相位=2；
            // 恒写 1 会让相机忽略数据相位（SetDevicePropValue 等写入类命令全部失效）
            PtpIpCodec.cmdRequestPayload(if (dataOut == null) 1 else 2, opcode, transId, params),
        )
        if (dataOut != null) {
            // 旧链路/基准实现 的 data-out 序列：StartData(总长) → EndData(载荷)，
            // 不发中间 Data 包；EndData 载荷携带完整数据
            writePacket(PtpConsts.IP_START_DATA, PtpIpCodec.startDataPayload(transId, dataOut.size.toLong()))
            writePacket(PtpConsts.IP_END_DATA, PtpIpCodec.dataPayload(transId, dataOut))
        }

        val chunks = ArrayList<ByteArray>()
        var total = 0L
        var result: PtpResult? = null
        var dataExpected: Long? = null
        var dataEnded = false
        while (result == null) {
            val pkt = readPacketOrNull(cmdIn!!)
            if (pkt == null) {
                if (System.currentTimeMillis() > deadline) {
                    sendCancel(transId)
                    throw CameraError.Timeout(
                        "PTP/IP read timed out (opcode=0x${opcode.toString(16)}, tx=$transId)"
                    )
                }
                continue
            }
            when (pkt.type) {
                PtpConsts.IP_CMD_RESPONSE -> {
                    val resp = PtpIpCodec.parseCmdResponse(pkt.payload)
                    if (resp.transId != transId) {
                        // 超时取消后相机可能补发迟到响应（基准实现：取消后事务编号不匹配需容忍）
                        log("PTP/IP 事务编号不匹配 expect=$transId got=${resp.transId}，忽略该响应")
                        continue
                    }
                    // 完整性校验（基准实现 同款防御）：响应 OK 但数据相位不完整
                    // 直接判失败，防止截断帧/截断数据被当成成功（监看黑屏、相册坏图的源头）
                    val expect = dataExpected
                    if (resp.code == PtpConsts.RC_OK && expect != null && (!dataEnded || (expect >= 0 && total != expect))) {
                        throw CameraError.Protocol(
                            "PTP/IP 数据不完整：$total / ${expect} 字节（opcode=0x${opcode.toString(16)}）"
                        )
                    }
                    val aggregated = if (sink == null && chunks.isNotEmpty()) {
                        val out = ByteArray(total.toInt())
                        var off = 0
                        for (c in chunks) {
                            System.arraycopy(c, 0, out, off, c.size)
                            off += c.size
                        }
                        out
                    } else null
                    result = PtpResult(resp.code, resp.params, aggregated)
                }

                PtpConsts.IP_START_DATA -> {
                    val t = PtpIpCodec.payloadTransId(pkt.payload)
                    if (t != transId) throw CameraError.Protocol("PTP/IP 数据事务编号不匹配 expect=$transId got=$t")
                    // 载荷 = transId + 数据总长，不是数据；误收集会让 data-in 多 4 字节垃圾
                    dataExpected = PtpIpCodec.payloadStartDataLen(pkt.payload)
                    log("PTP/IP StartData 总长=$dataExpected")
                }

                PtpConsts.IP_DATA, PtpConsts.IP_END_DATA -> {
                    val t = PtpIpCodec.payloadTransId(pkt.payload)
                    if (t != transId) throw CameraError.Protocol("PTP/IP 数据事务编号不匹配 expect=$transId got=$t")
                    val data = PtpIpCodec.payloadData(pkt.payload)
                    if (data.isNotEmpty()) {
                        total += data.size
                        if (sink != null) {
                            sink(data)
                        } else {
                            if (total > PtpConsts.MAX_PACKET_BYTES) {
                                throw CameraError.Protocol("data-in 超过聚合上限，请改用流式读取")
                            }
                            chunks.add(data)
                        }
                    }
                    if (pkt.type == PtpConsts.IP_END_DATA) dataEnded = true
                }

                PtpConsts.IP_EVENT -> {
                    val (code, evParams) = PtpIpCodec.parseEvent(pkt.payload)
                    _events.tryEmit(PtpCameraEvent(code, evParams))
                }

                PtpConsts.IP_PING -> writePacket(PtpConsts.IP_PONG, ByteArray(0))
                PtpConsts.IP_PONG -> { /* 保活响应，lastRx 已更新 */ }
                PtpConsts.IP_CANCEL -> log("相机取消事务 tx=${PtpIpCodec.payloadTransId(pkt.payload)}")
                else -> log("PTP/IP 未知包类型 ${pkt.type}，忽略")
            }
        }
        result!!
    }

    override suspend fun sendKeepalive() {
        if (!open) return
        transactMutex.withLock {
            try {
                writePacket(PtpConsts.IP_PING, ByteArray(0))
            } catch (e: IOException) {
                transportLost("keepalive ping 失败：${e.message}")
                return
            }
            // 短窗排空命令通道：消费 Pong/Event（Pong 只能在这里被读到），刷新 lastRx
            val deadline = System.currentTimeMillis() + 3_000
            while (open && System.currentTimeMillis() < deadline) {
                val pkt = readPacketOrNull(cmdIn ?: break) ?: break
                when (pkt.type) {
                    PtpConsts.IP_PONG -> log("keepalive pong ok")
                    PtpConsts.IP_EVENT -> {
                        val (code, evParams) = PtpIpCodec.parseEvent(pkt.payload)
                        _events.tryEmit(PtpCameraEvent(code, evParams))
                    }
                    PtpConsts.IP_PING -> writePacket(PtpConsts.IP_PONG, ByteArray(0))
                    else -> log("keepalive 排空忽略 type=${pkt.type}")
                }
            }
        }
    }

    override fun close(graceful: Boolean) {
        val wasOpen = open
        open = false
        closeInternal()
        if (!graceful && wasOpen && !lostNotified) {
            lostNotified = true
            onTransportLost("PTP/IP connection closed")
        }
    }

    // ---------------- 内部 ----------------

    private fun writePacket(type: Int, payload: ByteArray) {
        val out = cmdOut ?: throw CameraError.NotConnected("PTP/IP 命令通道未打开")
        synchronized(writeLock) {
            try {
                PtpIpCodec.writePacket(out, type, payload)
            } catch (e: IOException) {
                transportLost("PTP/IP socket failure：${e.message}")
                throw CameraError.TransportLost("PTP/IP socket failure：${e.message}", e)
            }
        }
    }

    private fun writeEventPacket(type: Int, payload: ByteArray) {
        val out = evtOut ?: throw CameraError.NotConnected("PTP/IP 事件通道未打开")
        synchronized(writeLock) {
            PtpIpCodec.writePacket(out, type, payload)
        }
    }

    private fun sendCancel(transId: Int) {
        try {
            writePacket(PtpConsts.IP_CANCEL, PtpIpCodec.cancelPayload(transId))
        } catch (_: Exception) {
        }
    }

    private fun awaitPacket(input: InputStream, deadline: Long): PtpIpCodec.Packet {
        while (true) {
            val pkt = readPacketOrNull(input)
            if (pkt != null) return pkt
            if (System.currentTimeMillis() > deadline) {
                throw CameraError.Timeout("PTP/IP 握手超时")
            }
        }
    }

    private suspend fun readEventLoop() {
        val input = evtIn ?: return
        try {
            while (open) {
                val pkt = readPacketOrNull(input) ?: continue
                when (pkt.type) {
                    PtpConsts.IP_EVENT -> {
                        val (code, evParams) = PtpIpCodec.parseEvent(pkt.payload)
                        _events.tryEmit(PtpCameraEvent(code, evParams))
                    }
                    PtpConsts.IP_PING -> writeEventPacket(PtpConsts.IP_PONG, ByteArray(0))
                    PtpConsts.IP_INIT_EVENT_ACK -> { /* 重复 ack，忽略 */ }
                    else -> log("PTP/IP 事件通道未知包类型 ${pkt.type}")
                }
            }
        } catch (e: Exception) {
            if (open) transportLost("PTP/IP 事件通道断开：${e.message}")
        }
    }

    /**
     * 读取一个包；仅在「包边界」（读头前）允许空闲超时返回 null。
     * 载荷读取中遇 SocketTimeout 会继续等，保持流同步。
     */
    private fun readPacketOrNull(input: InputStream): PtpIpCodec.Packet? {
        val header = readExactLen(input, PtpIpCodec.HEADER_SIZE, idleOk = true) ?: return null
        val length = le32(header, 0)
        val type = le32(header, 4)
        if (length < PtpIpCodec.HEADER_SIZE || length > PtpConsts.MAX_PACKET_BYTES) {
            throw CameraError.Protocol("PTP/IP 包长度异常：$length")
        }
        val payload = readExactLen(input, length - PtpIpCodec.HEADER_SIZE, idleOk = false)!!
        lastRx = System.currentTimeMillis()
        return PtpIpCodec.Packet(type, payload)
    }

    private fun readExactLen(input: InputStream, n: Int, idleOk: Boolean): ByteArray? {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            try {
                val r = input.read(buf, read, n - read)
                if (r < 0) throw EOFException("相机连接已断开")
                read += r
            } catch (e: SocketTimeoutException) {
                if (idleOk && read == 0) return null
                // 载荷读取中：保持流同步继续等
            }
        }
        return buf
    }

    private fun le32(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)

    private fun transportLost(detail: String) {
        if (!lostNotified) {
            lostNotified = true
            open = false
            onTransportLost(detail)
        }
    }

    private fun closeInternal() {
        eventJob?.cancel()
        eventJob = null
        try {
            cmdSocket?.close()
        } catch (_: Exception) {
        }
        try {
            evtSocket?.close()
        } catch (_: Exception) {
        }
        cmdSocket = null
        evtSocket = null
        cmdIn = null
        cmdOut = null
        evtIn = null
        evtOut = null
    }
}

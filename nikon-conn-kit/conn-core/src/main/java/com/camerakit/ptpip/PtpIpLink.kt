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
import kotlinx.coroutines.delay
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
    // 默认 0 = 与 3.1.0 完全同款时序（AP 链路必须保持原样，实测插入延迟会被相机复位）；
    // 仅 STA 显式传入稳定间隔（350/650ms）
    private val eventSettleMs: Long = 0,
) : PtpLink {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val SO_TIMEOUT_MS = 1_000
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
        val t0 = System.currentTimeMillis()
        val deadline = t0 + HANDSHAKE_TIMEOUT_MS
        // 阶段标记：失败时错误信息必须说清挂在握手哪一步（真机排查主要靠这个定位）
        var stage = "命令通道建链"
        try {
            val s = socketFactory.createSocket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            s.soTimeout = SO_TIMEOUT_MS
            cmdSocket = s
            cmdIn = s.getInputStream()
            cmdOut = s.getOutputStream()
            log("[握手] 命令通道建链 ${s.localAddress?.hostAddress ?: "-"} -> ${endpoint.host}:${endpoint.port}（${System.currentTimeMillis() - t0}ms）")

            stage = "InitCommand"
            writePacket(PtpConsts.IP_INIT_COMMAND, PtpIpCodec.initCommandPayload(hostGuid, hostName))
            val ackPacket = awaitPacket(cmdIn!!, deadline)
            when (ackPacket.type) {
                PtpConsts.IP_INIT_FAIL ->
                    throw CameraError.CameraRejected(PtpIpCodec.parseInitFail(ackPacket.payload))
                PtpConsts.IP_INIT_COMMAND_ACK -> {
                    val ack = PtpIpCodec.parseInitCommandAck(ackPacket.payload)
                    connectionId = ack.connectionId
                    cameraName = ack.cameraName.ifEmpty { endpoint.displayName }
                    log("[握手] InitCommandAck connection=$connectionId camera=$cameraName（${System.currentTimeMillis() - t0}ms）")
                }
                else -> throw CameraError.Protocol("PTP/IP 初始化响应无效（type=${ackPacket.type}）")
            }

            // 基准实现：InitCommandAck 与事件通道之间留稳定间隔，赶太紧会被相机复位
            // （真机实证：事件通道建链后立刻被 Connection reset / 断开的失败集中在这一段）
            if (eventSettleMs > 0) delay(eventSettleMs)

            stage = "事件通道建链"
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
            log("[握手] 事件通道建链 local=${e.localAddress?.hostAddress ?: "-"} forced=$forced settle=${eventSettleMs}ms（${System.currentTimeMillis() - t0}ms）")

            stage = "InitEvent"
            writeEventPacket(PtpConsts.IP_INIT_EVENT, PtpIpCodec.initEventPayload(connectionId))
            val ev = awaitPacket(evtIn!!, deadline)
            if (ev.type != PtpConsts.IP_INIT_EVENT_ACK) {
                throw CameraError.Protocol("相机没有接受 PTP/IP 事件通道（type=${ev.type}）")
            }
            log("[握手] InitEventAck ✓ 双通道就绪（总耗时 ${System.currentTimeMillis() - t0}ms）")

            open = true
            lastRx = System.currentTimeMillis()
            eventJob = scope.launch { readEventLoop() }
        } catch (e: CameraError.CameraRejected) {
            // 相机显式拒绝（InitFail）：保留语义供上层分流（自动配对 / 注册重试）
            closeInternal()
            throw e
        } catch (e: Exception) {
            closeInternal()
            throw CameraError.TransportLost("握手阶段「$stage」失败：${e.message}", e)
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
            // 基准实现 实证：DataOut 字段恒为 1（gphoto 文档示例一致）
            PtpIpCodec.cmdRequestPayload(1, opcode, transId, params),
        )
        if (dataOut != null) {
            writePacket(PtpConsts.IP_START_DATA, PtpIpCodec.startDataPayload(transId, dataOut.size.toLong()))
            writePacket(PtpConsts.IP_DATA, PtpIpCodec.dataPayload(transId, dataOut))
            writePacket(PtpConsts.IP_END_DATA, PtpIpCodec.dataPayload(transId, ByteArray(0)))
        }

        val chunks = ArrayList<ByteArray>()
        var total = 0L
        var result: PtpResult? = null
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
                    log("PTP/IP StartData 总长=${PtpIpCodec.payloadStartDataLen(pkt.payload)}")
                }

                PtpConsts.IP_DATA, PtpConsts.IP_END_DATA -> {
                    val t = PtpIpCodec.payloadTransId(pkt.payload)
                    if (t != transId) throw CameraError.Protocol("PTP/IP 数据事务编号不匹配 expect=$transId got=$t")
                    val data = PtpIpCodec.payloadData(pkt.payload)
                    if (data.isNotEmpty()) {
                        if (sink != null) {
                            sink(data)
                        } else {
                            total += data.size
                            if (total > PtpConsts.MAX_PACKET_BYTES) {
                                throw CameraError.Protocol("data-in 超过聚合上限，请改用流式读取")
                            }
                            chunks.add(data)
                        }
                    }
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
                    PtpConsts.IP_PONG -> { /* 保活响应（部分机身回在事件通道上），lastRx 已刷新 */ }
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

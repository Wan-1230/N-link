package com.camerakit.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.camerakit.api.CameraError
import com.camerakit.ptp.PtpCameraEvent
import com.camerakit.ptp.PtpConsts
import com.camerakit.ptp.PtpLink
import com.camerakit.ptp.PtpResult
import com.camerakit.ptp.PtpUsbCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * USB PTP 链路：Android UsbHost bulk 三端点（bulk IN / bulk OUT / interrupt IN）。
 *
 * 容器 12 字节头（[PtpUsbCodec]）：Command(type1) → Data(type2) → Response(type3)；
 * 事件走 interrupt IN（type4）。批量传输按 16KB 分块；0 字节读（ZLP）视为正常帧结尾重试续读。
 *
 * 参考：开源 PTP 协议实现 ptp-pack.c、android-file-transfer-linux、remoteyourcam-usb、AOSP MtpPacket；
 * 接口选择与 claimInterface 抢占语义为 基准实现/开源共同踩坑点。
 */
class UsbPtpLink private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val interruptIn: UsbEndpoint?,
    private val log: (String) -> Unit = {},
    private val onTransportLost: (String) -> Unit = {},
) : PtpLink {

    private val writeLock = Any()
    private val transactMutex = Mutex()

    @Volatile private var nextTransId = 1
    @Volatile private var open = true
    @Volatile private var lastRx = System.currentTimeMillis()
    @Volatile private var lostNotified = false
    private var eventJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<PtpCameraEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<PtpCameraEvent> = _events

    override val isOpen: Boolean get() = open

    override fun lastReceiveAt(): Long = lastRx

    init {
        if (interruptIn != null) {
            eventJob = scope.launch { readEventLoop() }
        }
    }

    override suspend fun transact(
        opcode: Int,
        params: List<Long>,
        dataOut: ByteArray?,
        sink: ((ByteArray) -> Unit)?,
        timeoutMs: Long,
    ): PtpResult = transactMutex.withLock {
        if (!open) throw CameraError.NotConnected("USB 链路未打开")
        val transId = nextTransId++

        writeContainer(PtpUsbCodec.pack(PtpConsts.USB_CONTAINER_COMMAND, opcode, transId, PtpUsbCodec.paramsPayload(params)))
        if (dataOut != null) {
            writeContainer(PtpUsbCodec.pack(PtpConsts.USB_CONTAINER_DATA, opcode, transId, dataOut))
        }

        val chunks = ArrayList<ByteArray>()
        var total = 0L
        var result: PtpResult? = null
        while (result == null) {
            val c = readContainer(timeoutMs)
            when (c.type) {
                PtpConsts.USB_CONTAINER_DATA -> {
                    if (c.transId != transId && c.transId != 0) {
                        throw CameraError.Protocol("USB 数据事务编号不匹配 expect=$transId got=${c.transId}")
                    }
                    if (c.payload.isNotEmpty()) {
                        if (sink != null) {
                            sink(c.payload)
                        } else {
                            total += c.payload.size
                            if (total > PtpConsts.MAX_PACKET_BYTES) {
                                throw CameraError.Protocol("data-in 超过聚合上限，请改用流式读取")
                            }
                            chunks.add(c.payload)
                        }
                    }
                }

                PtpConsts.USB_CONTAINER_RESPONSE -> {
                    if (c.transId != transId && c.transId != 0) {
                        log("USB 响应事务编号不匹配 expect=$transId got=${c.transId}，忽略")
                        continue
                    }
                    val aggregated = if (sink == null && chunks.isNotEmpty()) {
                        val out = ByteArray(total.toInt())
                        var off = 0
                        for (chunk in chunks) {
                            System.arraycopy(chunk, 0, out, off, chunk.size)
                            off += chunk.size
                        }
                        out
                    } else null
                    result = PtpResult(c.code, PtpUsbCodec.paramsOf(c.payload), aggregated)
                }

                PtpConsts.USB_CONTAINER_EVENT -> {
                    _events.tryEmit(PtpCameraEvent(c.code, PtpUsbCodec.paramsOf(c.payload)))
                }

                else -> log("USB 未知容器类型 ${c.type}")
            }
        }
        result!!
    }

    /** USB 无 PTP/IP Ping，用轻量只读事务保持链路活性与 lastRx 更新。 */
    override suspend fun sendKeepalive() {
        try {
            transact(PtpConsts.OP_GET_DEVICE_INFO, timeoutMs = 10_000)
        } catch (e: CameraError.TransportLost) {
            transportLost(e.message ?: "USB keepalive 失败")
        } catch (_: Exception) {
            // 其它错误（如 DeviceBusy）不视为链路丢失
        }
    }

    override fun close(graceful: Boolean) {
        val wasOpen = open
        open = false
        eventJob?.cancel()
        eventJob = null
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
        if (!graceful && wasOpen && !lostNotified) {
            lostNotified = true
            onTransportLost("USB 相机已断开")
        }
    }

    // ---------------- 内部 ----------------

    private fun writeContainer(bytes: ByteArray) {
        synchronized(writeLock) {
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val len = minOf(PtpConsts.USB_BULK_CHUNK, bytes.size - offset)
                    val sent = connection.bulkTransfer(
                        bulkOut,
                        bytes,
                        offset,
                        len,
                        BULK_TIMEOUT_MS,
                    )
                    if (sent <= 0) throw CameraError.TransportLost("USB bulk 写失败（$sent）")
                    offset += sent
                }
            } catch (e: CameraError) {
                transportLost(e.message ?: "USB 写失败")
                throw e
            } catch (e: Exception) {
                transportLost("USB bulk 写异常：${e.message}")
                throw CameraError.TransportLost("USB bulk 写异常：${e.message}", e)
            }
        }
    }

    private fun readContainer(timeoutMs: Long): PtpUsbCodec.Container {
        val header = readExact(PtpUsbCodec.HEADER_SIZE, timeoutMs)
        val h = PtpUsbCodec.parseHeader(header)
        val payload = readExact(h.length - PtpUsbCodec.HEADER_SIZE, timeoutMs)
        lastRx = System.currentTimeMillis()
        return PtpUsbCodec.Container(h.type, h.code, h.transId, payload)
    }

    /** 读满 n 字节；0 字节读（ZLP）重试续读；超时预算耗尽抛 Timeout。 */
    private fun readExact(n: Int, timeoutMs: Long): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var read = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        var zlpRetries = 0
        while (read < n) {
            val chunk = minOf(PtpConsts.USB_BULK_CHUNK, n - read)
            val r = connection.bulkTransfer(bulkIn, buf, read, chunk, BULK_TIMEOUT_MS)
            when {
                r > 0 -> {
                    read += r
                    zlpRetries = 0
                }
                r == 0 -> {
                    // ZLP：正常帧结尾，重试
                    if (++zlpRetries > MAX_ZLP_RETRIES) {
                        throw CameraError.Timeout("USB 读取 ZLP 重试超限")
                    }
                }
                else -> throw CameraError.TransportLost("USB bulk 读失败（$r）")
            }
            if (System.currentTimeMillis() > deadline) {
                throw CameraError.Timeout("USB 读取超时（$read/$n）")
            }
        }
        return buf
    }

    private suspend fun readEventLoop() {
        val ep = interruptIn ?: return
        val buf = ByteArray(64)
        while (open) {
            try {
                val r = connection.bulkTransfer(ep, buf, buf.size, EVENT_POLL_TIMEOUT_MS)
                if (r < 0) continue // 轮询超时
                if (r >= PtpUsbCodec.HEADER_SIZE) {
                    val c = PtpUsbCodec.parse(buf.copyOf(r))
                    if (c.type == PtpConsts.USB_CONTAINER_EVENT) {
                        lastRx = System.currentTimeMillis()
                        _events.tryEmit(PtpCameraEvent(c.code, PtpUsbCodec.paramsOf(c.payload)))
                    }
                }
            } catch (e: Exception) {
                if (open) transportLost("USB 事件通道断开：${e.message}")
                return
            }
        }
    }

    private fun transportLost(detail: String) {
        if (!lostNotified) {
            lostNotified = true
            open = false
            onTransportLost(detail)
        }
    }

    companion object {
        private const val BULK_TIMEOUT_MS = 20_000
        private const val EVENT_POLL_TIMEOUT_MS = 1_000
        private const val MAX_ZLP_RETRIES = 8

        /**
         * 打开相机 USB PTP 会话链路：
         * 选择 Imaging(6)/StillImage(1)/PTP(1) 接口（无则退回首个含 bulk 双向端点的接口），
         * `claimInterface(force=true)` 抢占系统 MTP 服务。
         */
        fun open(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            log: (String) -> Unit = {},
            onTransportLost: (String) -> Unit = {},
        ): UsbPtpLink {
            var chosen: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == PtpConsts.USB_CLASS_IMAGING &&
                    intf.interfaceSubclass == PtpConsts.USB_SUBCLASS_STILL_IMAGE &&
                    intf.interfaceProtocol == PtpConsts.USB_PROTOCOL_PTP
                ) {
                    chosen = intf
                    break
                }
            }
            if (chosen == null) {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    val hasBulkIn = (0 until intf.endpointCount).any {
                        intf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            intf.getEndpoint(it).direction == UsbConstants.USB_DIR_IN
                    }
                    val hasBulkOut = (0 until intf.endpointCount).any {
                        intf.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                            intf.getEndpoint(it).direction == UsbConstants.USB_DIR_OUT
                    }
                    if (hasBulkIn && hasBulkOut) {
                        chosen = intf
                        break
                    }
                }
            }
            val usbInterface = chosen ?: throw CameraError.Protocol("USB 设备无可用 PTP 接口")

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                when {
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_IN -> bulkIn = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT -> bulkOut = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN -> interruptIn = ep
                }
            }
            val inEp = bulkIn ?: throw CameraError.Protocol("USB 缺少 bulk IN 端点")
            val outEp = bulkOut ?: throw CameraError.Protocol("USB 缺少 bulk OUT 端点")

            if (!connection.claimInterface(usbInterface, true)) {
                throw CameraError.Protocol("USB 接口占用失败（系统 MTP 服务可能已接管）")
            }
            log("USB PTP 接口已占用：bulkIn=${inEp.address} bulkOut=${outEp.address} intrIn=${interruptIn?.address ?: "-"}")
            return UsbPtpLink(connection, usbInterface, inEp, outEp, interruptIn, log, onTransportLost)
        }
    }
}

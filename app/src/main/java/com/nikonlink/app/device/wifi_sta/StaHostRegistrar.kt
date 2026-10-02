package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.ptp.CommandRequestPacket
import com.nikonlink.app.device.ptp.CommandResponsePacket
import com.nikonlink.app.device.ptp.DataPacket
import com.nikonlink.app.device.ptp.EndDataPacket
import com.nikonlink.app.device.ptp.HostRegistrationResult
import com.nikonlink.app.device.ptp.InitCommandPacket
import com.nikonlink.app.device.ptp.InitEventAckPacket
import com.nikonlink.app.device.ptp.InitEventRequestPacket
import com.nikonlink.app.device.ptp.InitFailPacket
import com.nikonlink.app.device.ptp.InitResponsePacket
import com.nikonlink.app.device.ptp.PingPacket
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpDeviceInfo
import com.nikonlink.app.device.ptp.PtpIdentityStore
import com.nikonlink.app.device.ptp.PtpPacket
import com.nikonlink.app.device.ptp.StartDataPacket
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.shared.common.AppEventLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * STA 未连接态自主注册链路（v2.6.6 FR-30）。
 *
 * ## 为什么需要它
 *
 * 旧实现里 [com.nikonlink.app.device.connect.ConnectionManager.registerStaHost] 的第一行是
 * `if (!ptpSession.isConnected()) return Failure("相机未连接…AP 模式…")` —— 注册被绑死在
 * 「先有一条已建立的 PTP 会话」上，而那条会话历史上只有 AP 连接能建立。于是
 * 「相机已连上手机热点、停在主机配置向导」这个**本该直接注册**的现场（ZDROP 教程与
 * 其 2026-10-01 诊断日志的标准流程）反而被判成「相机未连接」。
 *
 * ZDROP 诊断日志（host-register 段）证明注册本身是一条**独立链路**，不依赖任何既有会话：
 * 发现 endpoint → 自建 command/event 双通道 → GetDeviceInfo → OpenSession →
 * 0x952B → 等相机确认 → 0x935A → 直接拆通道（sendCloseSession=false）。
 * 本类把这条链路落地：自有 socket、自有事务号，不碰 [com.nikonlink.app.device.ptp.PtpSessionManager]
 * 的单例会话状态（不置 CONNECTED、不起心跳/事件监听、不打连接漏斗），
 * 因此对 AP 连接、既有 STA 连接与已连接态注册零影响。
 *
 * ## 与已连接态注册的关系
 *
 * 已连接（AP 或 STA 已连）时仍走旧路径（在活会话里 registerHost），本类只在未连接时被调用。
 */
@Singleton
class StaHostRegistrar @Inject constructor(
    private val identity: PtpIdentityStore,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "StaHostReg"

        /** 注册是用户盯着相机点的动作，建链给 10s 够跨过 DHCP 尾段，又不至让用户干等 30s。 */
        private const val CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
        private const val EVENT_PING_TIMEOUT_MS = 1500

        /**
         * init-command-ack 到 event 通道建链之间的稳定窗口。
         * 取值来自 ZDROP 诊断日志 host-register 段实测：注册链路用 350ms（其连接链路用 650ms）。
         */
        private const val PRE_EVENT_SETTLE_MS = 350L

        /**
         * 相机不在主机配置向导时的引导文案。
         * 与 ConnectionManager 预检 / PtpSessionManager 0x201F 两处文案同义：
         * 刻意各留一份而不抽公共常量，是为了不让新链路改动已跑通的两处旧代码。
         */
        private const val WIZARD_GUIDANCE =
            "相机当前模式不支持主机注册（0x952B 不在它声明的能力列表里）。\n" +
                "多数情况是相机没在 STA 主机配置向导里 —— 普通「连接至智能设备」的 " +
                "WiFi 热点也能连上，但它不接受注册。\n" +
                "请在相机上重新进入「Wi-Fi连接（STA mode）」或「连接至 PC」的网络设定向导，" +
                "停在「正在等待连接 / 等待计算机」的画面后再点注册。"

        private const val CANCELLED_GUIDANCE =
            "相机拒绝了注册事务（0x201F，事务被取消）：它现在不在主机配置向导。\n" +
                "请在相机上重新进入「Wi-Fi连接（STA mode）」或「连接至 PC」的网络设定向导，" +
                "停在「正在等待连接 / 等待计算机」的画面后再点注册。\n" +
                "注意：普通的「连接至智能设备」WiFi 热点也能连上相机，但它不接受注册。"
    }

    /**
     * 对 [endpoint] 开一条临时 PTP/IP 链路完成主机注册，结束即拆通道。
     * 失败一律返回 [HostRegistrationResult.Failure]（不抛），调用方可直接把 detail 展示给用户。
     */
    suspend fun register(
        endpoint: WifiEndpoint,
        onProgress: ((String) -> Unit)? = null
    ): HostRegistrationResult = withContext(Dispatchers.IO) {
        eventLogger.event(
            "hostreg", "phase" to "start", "transport" to "standalone", "host" to endpoint.host
        )
        onProgress?.invoke("正在连接相机（临时通道）…")
        var commandSocket: Socket? = null
        var eventSocket: Socket? = null
        try {
            val command = Socket().also { commandSocket = it }
            command.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            command.soTimeout = READ_TIMEOUT_MS
            command.tcpNoDelay = true
            val commandOut = command.getOutputStream()
            val commandIn = command.getInputStream()

            commandOut.write(
                InitCommandPacket(clientGuid = identity.clientGuid, clientName = identity.clientName).toBytes()
            )
            commandOut.flush()
            val init = PtpPacket.fromStream(commandIn)
            if (init is InitFailPacket) {
                eventLogger.event(
                    "hostreg", "phase" to "init_fail", "transport" to "standalone",
                    "reason" to init.reasonCode
                )
                return@withContext HostRegistrationResult.Failure(
                    phase = "init",
                    initFailReason = init.reasonCode,
                    detail = "相机拒绝了握手（${PtpConstants.describeInitFailReason(init.reasonCode)}）。\n" +
                        "它的 PTP/IP 槽位可能正被别的设备占用：先断开其它连着相机的应用" +
                        "（SnapBridge、电脑端软件等），或等相机收回旧会话后再点注册。"
                )
            }
            if (init !is InitResponsePacket) {
                eventLogger.event(
                    "hostreg", "phase" to "init_bad_resp", "transport" to "standalone",
                    "type" to (init?.type ?: -1)
                )
                return@withContext HostRegistrationResult.Failure(
                    phase = "init",
                    detail = "相机没有返回握手应答（包类型=${init?.type}）。" +
                        "请确认相机停在主机配置向导的等待画面后重试"
                )
            }
            val sessionId = init.sessionId
            eventLogger.event("hostreg", "phase" to "init_ok", "transport" to "standalone")

            delay(PRE_EVENT_SETTLE_MS)
            val event = Socket().also { eventSocket = it }
            event.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            event.soTimeout = EVENT_PING_TIMEOUT_MS
            event.tcpNoDelay = true
            val eventOut = event.getOutputStream()
            val eventIn = event.getInputStream()
            eventOut.write(InitEventRequestPacket(sessionId).toBytes())
            eventOut.flush()
            if (PtpPacket.fromStream(eventIn) !is InitEventAckPacket) {
                eventLogger.event("hostreg", "phase" to "event_fail", "transport" to "standalone")
                return@withContext HostRegistrationResult.Failure(
                    phase = "init_event",
                    detail = "相机没有确认事件通道。请确认相机停在主机配置向导的等待画面后重试"
                )
            }
            // 与连接链路同语义：部分机身缺了 EventAck 后的 ping/pong 会判链路失败。
            // 注册链路里没等到 Pong 只记不拦——ZDROP 的注册段日志就没有这一往返。
            runCatching {
                eventOut.write(PingPacket.toBytes())
                eventOut.flush()
                PtpPacket.fromStream(eventIn)
            }
            eventLogger.event("hostreg", "phase" to "event_ok", "transport" to "standalone")

            val tx = AtomicInteger(0)
            onProgress?.invoke("正在读取相机当前模式…")
            val ops = transactData(commandOut, commandIn, tx.incrementAndGet(), PtpConstants.OP_GET_DEVICE_INFO)
                ?.let { PtpDeviceInfo.parseOperations(it) }
            eventLogger.event(
                "device_caps", "transport" to "standalone",
                "ops" to (ops?.size ?: -1),
                "host_reg_0x952b" to ops?.contains(PtpConstants.OP_NIKON_HOST_REGISTRATION_PREPARE)
            )
            if (ops != null && !ops.contains(PtpConstants.OP_NIKON_HOST_REGISTRATION_PREPARE)) {
                eventLogger.event(
                    "hostreg", "phase" to "precheck_blocked", "transport" to "standalone", "ops" to ops.size
                )
                return@withContext HostRegistrationResult.Failure(phase = "precheck", detail = WIZARD_GUIDANCE)
            }

            val open = transact(commandOut, commandIn, tx.incrementAndGet(), PtpConstants.OP_OPEN_SESSION, listOf(1))
                ?: return@withContext HostRegistrationResult.Failure(
                    phase = "opensession",
                    detail = "打开会话超时无响应。请确认相机停在主机配置向导的等待画面后重试"
                )
            if (!open.isOk) {
                val code = "0x${open.responseCode.toString(16)}"
                eventLogger.event("hostreg", "phase" to "opensession_fail", "transport" to "standalone", "code" to code)
                return@withContext HostRegistrationResult.Failure(
                    phase = "opensession",
                    detail = "相机拒绝打开会话（$code）。请确认相机停在主机配置向导的等待画面后重试"
                )
            }

            onProgress?.invoke("正在预备注册…")
            val prepare = transact(
                commandOut, commandIn, tx.incrementAndGet(), PtpConstants.OP_NIKON_HOST_REGISTRATION_PREPARE
            ) ?: return@withContext HostRegistrationResult.Failure(
                phase = "prepare",
                detail = "预备注册超时无响应。请确认相机停在主机配置向导的等待画面后重试"
            )
            if (!prepare.isOk) {
                val code = "0x${prepare.responseCode.toString(16)}"
                eventLogger.event(
                    "hostreg", "phase" to "prepare_fail", "transport" to "standalone", "code" to code
                )
                return@withContext HostRegistrationResult.Failure(
                    phase = "prepare",
                    detail = if (prepare.responseCode == PtpConstants.RESPONSE_TRANSACTION_CANCELLED) {
                        CANCELLED_GUIDANCE
                    } else {
                        "相机未接受预备注册（$code）。请确认相机停在主机配置向导的等待画面后重试"
                    }
                )
            }
            eventLogger.event("hostreg", "phase" to "prepare_ok", "transport" to "standalone")

            onProgress?.invoke("相机正在应用配置（约 9 秒）…")
            eventLogger.event(
                "hostreg", "phase" to "settle", "transport" to "standalone",
                "ms" to PtpConstants.HOST_REGISTRATION_SETTLE_MS
            )
            delay(PtpConstants.HOST_REGISTRATION_SETTLE_MS)

            onProgress?.invoke("正在确认注册…")
            val confirm = transact(
                commandOut, commandIn, tx.incrementAndGet(),
                PtpConstants.OP_NIKON_HOST_REGISTRATION_CONFIRM,
                listOf(PtpConstants.HOST_REGISTRATION_CONFIRM_OK)
            ) ?: return@withContext HostRegistrationResult.Failure(
                phase = "confirm",
                detail = "确认注册超时无响应。请保持相机屏幕处于连接画面后重试"
            )
            if (!confirm.isOk) {
                val code = "0x${confirm.responseCode.toString(16)}"
                eventLogger.event(
                    "hostreg", "phase" to "confirm_fail", "transport" to "standalone", "code" to code
                )
                return@withContext HostRegistrationResult.Failure(
                    phase = "confirm",
                    detail = "确认注册失败（$code），请保持相机屏幕处于连接画面后重试"
                )
            }
            eventLogger.event("hostreg", "phase" to "confirm_ok", "transport" to "standalone")
            HostRegistrationResult.Success
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "standalone host registration failed host=${endpoint.host}")
            eventLogger.event(
                "hostreg", "phase" to "error", "transport" to "standalone",
                "err" to "${e.javaClass.simpleName}:${e.message}"
            )
            HostRegistrationResult.Failure(
                phase = "transport",
                detail = "临时通道异常（${e.javaClass.simpleName}）。请确认相机已连上本机热点或同一路由器、" +
                    "并停在主机配置向导的等待画面后重试"
            )
        } finally {
            // 不发 CloseSession：注册完成后相机会自己退出向导并重建 WiFi，
            // 在这里等它回应只会拖慢拆链（与 ZDROP 的 sendCloseSession=false 同语义）。
            runCatching { eventSocket?.close() }
            runCatching { commandSocket?.close() }
        }
    }

    /** 发一条命令并等本事务的响应包；中途的数据/事件包跳过，超时报 null。 */
    private fun transact(
        out: OutputStream,
        input: InputStream,
        txId: Int,
        operationCode: Int,
        parameters: List<Int> = emptyList()
    ): CommandResponsePacket? = runCatching {
        out.write(CommandRequestPacket(txId, operationCode, parameters).toBytes())
        out.flush()
        var response: CommandResponsePacket? = null
        while (response == null) {
            when (val pkt = PtpPacket.fromStream(input)) {
                null -> break
                is CommandResponsePacket -> if (pkt.transactionId == txId) response = pkt
                else -> {}
            }
        }
        response
    }.getOrNull()

    /** 发一条带数据相的命令并拼回载荷（注册链路里只用于 GetDeviceInfo）；被拒或超时返回 null。 */
    private fun transactData(
        out: OutputStream,
        input: InputStream,
        txId: Int,
        operationCode: Int
    ): ByteArray? = runCatching {
        out.write(CommandRequestPacket(txId, operationCode, emptyList()).toBytes())
        out.flush()
        val chunks = mutableListOf<ByteArray>()
        var ok = false
        while (true) {
            when (val pkt = PtpPacket.fromStream(input)) {
                null -> break
                is StartDataPacket -> {}
                is DataPacket -> chunks.add(pkt.data)
                is EndDataPacket -> chunks.add(pkt.data)
                is CommandResponsePacket -> if (pkt.transactionId == txId) {
                    ok = pkt.isOk
                    break
                }
                else -> {}
            }
        }
        if (!ok) return@runCatching null
        val total = chunks.fold(0) { n, c -> n + c.size }
        ByteArray(total).also { buf ->
            var offset = 0
            chunks.forEach { chunk ->
                System.arraycopy(chunk, 0, buf, offset, chunk.size)
                offset += chunk.size
            }
        }
    }.getOrNull()
}

/**
 * 注册候选挑选：优先 PTP 握手已应答的相机。
 * 「待唤醒」条目是 TCP 通但握手没应答的休眠相机，注册必然超时，排到最后兜底。
 */
internal fun pickRegistrationCandidate(candidates: List<WifiCameraCandidate>): WifiCameraCandidate? =
    candidates.firstOrNull { !it.name.contains("待唤醒") } ?: candidates.firstOrNull()

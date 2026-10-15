package com.camerakit.client

import android.content.Context
import com.camerakit.api.CameraConnection
import com.camerakit.api.CameraEndpoint
import com.camerakit.api.CameraError
import com.camerakit.api.CameraEvent
import com.camerakit.api.CameraSession
import com.camerakit.api.CameraState
import com.camerakit.api.ConnectionMethod
import com.camerakit.discovery.ApEndpointResolver
import com.camerakit.discovery.MdnsCameraDiscovery
import com.camerakit.net.NetworkBinder
import com.camerakit.pairing.HostIdentityStore
import com.camerakit.pairing.HostRegistrar
import com.camerakit.ptp.PtpConsts
import com.camerakit.ptpip.PtpIpLink
import com.camerakit.ptp.PtpLink
import com.camerakit.ptp.PtpSession
import com.camerakit.usb.UsbCameraDiscovery
import com.camerakit.usb.UsbPtpLink
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 连接编排：发现 → 端点选择 → 网络绑定 → 链路握手 → 会话 → 保活/自动恢复。
 *
 * 三种连接方式共享同一 [PtpSession] 会话层；无线断链按同 GUID 有限次自动恢复，
 * 事件模型与 基准实现 五态语义对齐（临时失败/传输丢失/自动恢复/令牌变更/断开）。
 */
class NikonCameraClient internal constructor(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : CameraConnection {

    private val identity = HostIdentityStore.loadOrCreate(context)
    private val registrar = HostRegistrar(log)

    private companion object {
        /** 基准实现 实证：STA 发现窗口 6000ms，取首个结果 */
        const val STA_DISCOVER_TIMEOUT_MS = 6_000L

        /** 基准实现 实证：注册握手失败退避 {0,2,4,6}s，共 4 次 */
        val REGISTRATION_BACKOFF_MS = longArrayOf(0, 2_000, 4_000, 6_000)

        /** 基准实现 实证：注册流事件通道稳定等待（首试 350ms / 重试 650ms） */
        const val REG_SETTLE_FIRST_MS = 350L
        const val REG_SETTLE_RETRY_MS = 650L

        /**
         * 无线连接失败后的冷却时长（真机日志实证）。
         * 相机侧半开会话清理窗口约 10s，期间再次发起握手会把相机锤进
         * 拒绝新连接的残留态（日志表现为 connection 号单调递增、全部秒败）。
         */
        const val CONNECT_FAIL_COOLDOWN_MS = 10_000L
    }

    // 惰性获取：避免构造期强转系统服务（无 USB host / NSD 的设备上为 null 会启动崩溃）
    private val usbDiscovery by lazy { UsbCameraDiscovery(context, log) }
    private val mdnsDiscovery by lazy { MdnsCameraDiscovery(context, log) }

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CameraEvent> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectMutex = Mutex()

    private var link: PtpLink? = null
    private var ptpSession: PtpSession? = null
    private var watchdogJob: Job? = null
    private var networkBinder: NetworkBinder? = null
    private var activeMethod: ConnectionMethod? = null
    private var currentEndpoint: CameraEndpoint? = null
    private var currentSocketFactory: SocketFactory? = null

    /** 最近一次无线连接失败的时间戳（0=无失败），供 [CONNECT_FAIL_COOLDOWN_MS] 冷却门使用 */
    @Volatile
    private var lastConnectFailAt = 0L

    init {
        log(
            "主机身份：name=${identity.hostName} guid=" +
                identity.guid.joinToString("") { "%02x".format(it) },
        )
    }

    // ---------------- 发现 ----------------

    override suspend fun discover(method: ConnectionMethod): List<CameraEndpoint> {
        _state.value = CameraState.Discovering
        try {
            return when (method) {
                is ConnectionMethod.Usb -> {
                    usbDiscovery.listCameras().map {
                        CameraEndpoint(it.productName ?: "Nikon USB", "usb:${it.deviceId}", 0, "usb")
                    }
                }

                is ConnectionMethod.WifiAp ->
                    listOf(ApEndpointResolver.defaultEndpoint(method.host, method.port))

                is ConnectionMethod.WifiSta -> {
                    method.endpoint?.let { return listOf(it) }
                    val found = ArrayList<CameraEndpoint>()
                    try {
                        withTimeout(12_000) {
                            mdnsDiscovery.discover().collect { ep ->
                                if (!found.contains(ep)) found.add(ep)
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        // 采样窗口结束，返回已发现端点
                    }
                    found
                }
            }
        } finally {
            if (_state.value == CameraState.Discovering) {
                _state.value = CameraState.Disconnected
            }
        }
    }

    // ---------------- 连接 ----------------

    override suspend fun connect(
        method: ConnectionMethod,
        endpoint: CameraEndpoint?,
        autoPair: Boolean,
    ) = connectMutex.withLock {
        // 失败冷却门（真机日志实证：快速连点把相机锤进永久拒绝态）。
        // 冷却期内的点击直接提示等待，不发起新的握手。
        val failAt = lastConnectFailAt
        if (failAt > 0 && method !is ConnectionMethod.Usb) {
            val remaining = (failAt + CONNECT_FAIL_COOLDOWN_MS) - System.currentTimeMillis()
            if (remaining > 0) {
                val err = CameraError.Protocol(
                    "上次连接失败，相机约需 10s 释放残留连接。" +
                        "请等待 ${remaining / 1000 + 1}s 后再试（连续快速点击会延长恢复时间）。",
                )
                _state.value = CameraState.Failed(err)
                throw err
            }
        }
        disconnectInternal(emitState = false)
        activeMethod = method
        _state.value = CameraState.Connecting("准备连接")
        try {
            when (method) {
                is ConnectionMethod.Usb -> connectUsb()

                is ConnectionMethod.WifiAp -> {
                    val ep = endpoint ?: ApEndpointResolver.defaultEndpoint(method.host, method.port)
                    connectWireless(ep, autoPair)
                }

                is ConnectionMethod.WifiSta -> {
                    val ep = endpoint ?: method.endpoint ?: discoverStaEndpoint()
                    connectWireless(ep, autoPair)
                }
            }
        } catch (e: Throwable) {
            disconnectInternal(emitState = false)
            val err = e as? CameraError ?: CameraError.Protocol(e.message ?: "连接失败", e)
            _state.value = CameraState.Failed(err)
            throw err
        }
    }

    private suspend fun discoverStaEndpoint(): CameraEndpoint {
        _state.value = CameraState.Discovering
        // 基准实现 实证：STA 发现窗口 6s 且取首个结果。这里发现首个端点即返回，
        // 窗口只作上限——旧版无条件收满整窗，相机秒到也要白等十几秒。
        val found = try {
            withTimeout(STA_DISCOVER_TIMEOUT_MS) { mdnsDiscovery.discover().first() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }
        return found
            ?: throw CameraError.NoCameraFound(
                "mDNS 未发现相机。请确认相机已进入 STA 连接状态，且手机与相机在同一非访客 Wi-Fi（关闭 AP 隔离）。",
            )
    }

    private suspend fun connectWireless(endpoint: CameraEndpoint, autoPair: Boolean) {
        currentEndpoint = endpoint

        // 路由就绪门（基准实现 策略）：本地接口须已有到相机网段的直连路由才开始 PTP。
        // 不做一次性 TCP 探测——探测连接会打扰相机 PTP 服务（实测 ECONNREFUSED/复位）。
        _state.value = CameraState.Connecting("等待到相机的网络路由…")
        if (!ApEndpointResolver.awaitLocalRoute(endpoint.host, totalTimeoutMs = 8_000)) {
            // 基准实现 式二次等待节奏（8s 后再给 2.5s）
            if (!ApEndpointResolver.awaitLocalRoute(endpoint.host, totalTimeoutMs = 2_500)) {
                throw CameraError.NoCameraFound(
                    "未发现到 ${endpoint.host} 的网络路由（手机本地地址不含相机网段）。" +
                        "请确认手机已连接相机 Wi-Fi（AP 模式）或与相机在同一局域网后重试。",
                )
            }
        }

        val binder = NetworkBinder(context, endpoint.host, log).also { networkBinder = it }
        val factory = binder.cameraSocketFactory()
        currentSocketFactory = factory

        // 等待路由稳定（基准实现：AP network readiness wait≈1.2s stable=true）
        _state.value = CameraState.Connecting("等待网络稳定…")
        delay(1_200)

        var paired = false
        while (true) {
            try {
                openWirelessSession(endpoint, factory)
                val storages = ptpSession?.listStorages() ?: emptyList()
                if (storages.isEmpty()) {
                    if (autoPair && !paired) {
                        log("相机未开放存储卡，尝试主机配对后重连")
                        closeSessionQuietly()
                        paired = true
                        runRegistration(endpoint, factory)
                        continue
                    }
                    throw CameraError.StorageUnavailable("相机未开放可读取的存储卡（不可用存储：0）")
                }
                onConnected()
                return
            } catch (e: CameraError.CameraRejected) {
                if (autoPair && !paired) {
                    log("相机拒绝该主机（0x${e.failCode.toString(16)}），尝试主机配对")
                    closeSessionQuietly()
                    paired = true
                    runRegistration(endpoint, factory)
                    continue
                }
                throw e
            }
        }
    }

    /**
     * PTP/IP 握手 + 会话建立（基准实现 AP 双通道编排语义）：
     * - **只试 2 次**（与基准实现一致）：失败 → 全清场 + 静置 → 再试 1 次 → 仍败
     *   立即收口报错，由用户按自己的节奏再点。真机日志证明：快速连续重试
     *   （每 8~10s 一轮、轮内 2s/4s）会把相机锤进「拒绝新连接」的残留态
     *   （connection 号连续递增 21→34 全败），而用户 20s+ 间隔的慢节奏点击能成；
     * - 全部轮次统一 650ms 事件通道稳定间隔（真机统计：成功全部发生在
     *   settle≥650ms 轮次，0ms 首试从未成功）；
     * - 失败后全清场（释放并重建 NetworkBinder，旧 Network 复用会 EPERM）。
     */
    private suspend fun openWirelessSession(endpoint: CameraEndpoint, factory: SocketFactory) {
        var lastError: CameraError? = null
        var currentFactory = factory
        repeat(2) { attempt ->
            // 路由复查（基准实现：路由不可用直接报「请重新绑定后连接」）
            if (attempt > 0 &&
                !ApEndpointResolver.awaitLocalRoute(endpoint.host, totalTimeoutMs = 1_500)
            ) {
                throw CameraError.NoCameraFound(
                    "到 ${endpoint.host} 的网络路由不可用（相机 Wi-Fi 可能已断开）。请重新连接相机 Wi-Fi 后再试。",
                )
            }
            _state.value = CameraState.Connecting("PTP/IP 握手（${attempt + 1}/2）")
            try {
                val l = PtpIpLink(
                    endpoint, handshakeGuid(), identity.hostName, currentFactory, log,
                    onTransportLost = { reason -> handleTransportLost(reason) },
                )
                link = l
                l.open()
                log("PTP/IP 握手完成 connection=${l.connectionId} camera=${l.cameraName}")
                val s = PtpSession(l, log = log)
                ptpSession = s
                _state.value = CameraState.Connecting("打开会话")
                s.open()
                return
            } catch (e: CameraError) {
                if (e !is CameraError.TransportLost && e !is CameraError.Timeout) throw e
                closeSessionQuietly()
                lastError = e
                if (attempt < 1) {
                    log("握手失败：${e.message}；全清场后 1s 再试一次（基准实现节奏）")
                    _state.value = CameraState.Connecting("清理残留连接（1s）…")
                    // 全清场（基准实现语义）：释放旧 NetworkBinder 并重建
                    networkBinder?.release()
                    networkBinder = null
                    delay(1_000)
                    val binder = NetworkBinder(context, endpoint.host, log).also { networkBinder = it }
                    currentFactory = binder.cameraSocketFactory()
                    currentSocketFactory = currentFactory
                }
            }
        }
        // 失败冷却：快速连续点击会把相机锤进永久拒绝态（真机日志实证）。
        // 记录失败时刻，下一次 connect 入口会等冷却结束再发起。
        lastConnectFailAt = System.currentTimeMillis()
        throw lastError ?: CameraError.TransportLost("PTP/IP 握手失败")
    }

    /**
     * 握手 GUID 选择（基准实现 AP 实证：每次握手用新随机 GUID）。
     *
     * 相机对「同 GUID 残留会话」做冲突清理时会掐掉新会话的事件通道
     * （真机现象：事件通道刚建即被复位、InitEventAck 收不到）；每次换新 GUID
     * 可避开该冲突。AP 直连不校验主机注册，GUID 不需要稳定；
     * **STA 与主机注册路径必须用持久身份**（注册把 GUID 绑进相机主机白名单）。
     */
    private fun handshakeGuid(): ByteArray =
        if (activeMethod is ConnectionMethod.WifiAp) {
            ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        } else {
            identity.guid
        }

    /** 主机配对（PC 主机注册）：独立注册会话，相机须停留在主机配置向导。 */
    private suspend fun runRegistration(
        endpoint: CameraEndpoint,
        factory: SocketFactory,
        preSettleMs: Long = 0,
    ) {
        _state.value = CameraState.Connecting("建立 PC 主机注册会话")
        _events.tryEmit(CameraEvent.PairingProgress("正在建立 PC 主机注册会话…"))
        if (preSettleMs > 0) delay(preSettleMs)
        val regLink = PtpIpLink(
            endpoint, identity.guid, identity.hostName, factory, log,
            onTransportLost = {},
        )
        try {
            regLink.open()
            val regSession = PtpSession(regLink, log = log)
            regSession.open()
            registrar.register(
                regSession,
                onAwaitConfirm = { prepare ->
                    _state.value = CameraState.AwaitingHostConfirm(prepare)
                    _events.tryEmit(
                        CameraEvent.PairingProgress("请在相机的主机确认界面确认 ${identity.hostName}，并等待相机完成保存。"),
                    )
                },
            )
        } catch (e: CameraError.CameraRejected) {
            throw CameraError.PairingRejected(
                "主机注册握手被拒绝（0x${e.failCode.toString(16)}）。请让相机停留在 STA/PC 主机配置向导后重试。",
            )
        } finally {
            regLink.close(graceful = true)
        }
        // 相机保存主机配置需要时间
        delay(1_500)
    }

    private suspend fun connectUsb() {
        _state.value = CameraState.Connecting("检查 USB 设备")
        val device = usbDiscovery.listCameras().firstOrNull()
            ?: throw CameraError.NoCameraFound(
                "未发现 Nikon USB 设备。请使用数据线、启用手机 OTG，并将相机设置为 USB → MTP/PTP。",
            )
        if (!usbDiscovery.hasPermission(device)) {
            usbDiscovery.requestPermission(device)
            throw CameraError.UsbPermissionDenied("USB 权限被拒绝，请在系统弹窗中允许后重试。")
        }

        _state.value = CameraState.Connecting("打开 USB 设备")
        val connection = usbDiscovery.openDevice(device)
            ?: throw CameraError.UsbPermissionDenied("无法打开 USB 设备")

        _state.value = CameraState.Connecting("创建 MTP/PTP 会话")
        val l = UsbPtpLink.open(connection, device, log) { reason -> handleTransportLost(reason) }
        link = l
        val s = PtpSession(l, log = log)
        ptpSession = s
        s.open()

        currentEndpoint = CameraEndpoint(
            device.productName ?: "Nikon USB",
            "usb:${device.deviceId}",
            0,
            "usb",
        )
        onConnected()
    }

    private suspend fun onConnected() {
        val s = ptpSession ?: throw CameraError.NotConnected()
        val ep = currentEndpoint ?: throw CameraError.NotConnected()
        val info = s.deviceInfo ?: s.getDeviceInfo()
        _state.value = CameraState.Connected(ep, info)
        log("已连接 ${info.manufacturer} ${info.model} via $ep")
        startWatchdog()
    }

    // ---------------- 保活与恢复 ----------------

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(PtpConsts.KEEPALIVE_INTERVAL_MS)
                val l = link ?: break
                try {
                    l.sendKeepalive()
                    if (System.currentTimeMillis() - l.lastReceiveAt() > PtpConsts.KEEPALIVE_DEAD_MS) {
                        _events.tryEmit(
                            CameraEvent.KeepaliveFailed("超过 ${PtpConsts.KEEPALIVE_DEAD_MS}ms 未收到相机响应"),
                        )
                        throw CameraError.TransportLost("保活失败：无响应")
                    }
                } catch (e: Exception) {
                    handleTransportLost("保活失败：${e.message}")
                    break
                }
            }
        }
    }

    private fun handleTransportLost(reason: String) {
        scope.launch {
            if (link == null) return@launch
            _events.tryEmit(CameraEvent.TransportLost(reason))
            log("transport-lost：$reason")

            if (activeMethod is ConnectionMethod.Usb) {
                disconnectInternal(emitState = false)
                _state.value = CameraState.Failed(CameraError.TransportLost(reason))
                return@launch
            }

            _state.value = CameraState.Recovering(reason)
            val ep = currentEndpoint
            val factory = currentSocketFactory ?: SocketFactory.getDefault()
            if (ep == null) {
                disconnectInternal(emitState = false)
                _state.value = CameraState.Failed(CameraError.TransportLost(reason))
                return@launch
            }

            var recovered = false
            repeat(3) { attempt ->
                if (recovered) return@repeat
                try {
                    // 全清场 + 重建绑定（基准实现语义）：旧 Network 被系统回收后复用会 EPERM
                    closeSessionQuietly()
                    networkBinder?.release()
                    networkBinder = null
                    delay(1_500L * (attempt + 1))
                    val binder = NetworkBinder(context, ep.host, log).also { networkBinder = it }
                    val freshFactory = binder.cameraSocketFactory()
                    currentSocketFactory = freshFactory
                    openWirelessSession(ep, freshFactory)
                    recovered = true
                } catch (e: Exception) {
                    log("无线会话自动恢复失败（第 ${attempt + 1}/3 次）：${e.message}")
                }
            }

            if (recovered) {
                _events.tryEmit(CameraEvent.SessionRecovered("无线会话已自动恢复：${ep.host}"))
                _events.tryEmit(CameraEvent.SessionTokenChanged(System.currentTimeMillis()))
                _state.value = CameraState.Connected(ep, ptpSession?.deviceInfo ?: return@launch)
                startWatchdog()
            } else {
                disconnectInternal(emitState = false)
                _events.tryEmit(CameraEvent.WirelessDisconnected("无线连接已断开：$reason"))
                _state.value = CameraState.Failed(CameraError.TransportLost("恢复失败：$reason"))
            }
        }
    }

    // ---------------- 配对 / 会话 / 断开 ----------------

    override suspend fun pair() = connectMutex.withLock {
        val s = ptpSession ?: throw CameraError.NotConnected("请先建立无线连接（AP/STA）再执行主机配对")
        val ep = currentEndpoint ?: throw CameraError.NotConnected()
        registrar.register(
            s,
            onAwaitConfirm = { prepare ->
                _state.value = CameraState.AwaitingHostConfirm(prepare)
                _events.tryEmit(CameraEvent.PairingProgress("请在相机的主机确认界面确认 ${identity.hostName}"))
            },
        )
        _state.value = CameraState.Connected(ep, s.deviceInfo ?: s.getDeviceInfo())
    }

    /**
     * STA 主机注册（PC 主机注册）：相机停留在主机配置向导时的独立注册入口，
     * 无需先建连接（基准实现 实证：注册是独立会话，仅在向导中显式触发）。
     *
     * 重试语义同 基准实现：握手阶段（建链/初始化）失败按 {0,2,4,6}s 退避共 4 次；
     * 相机明确拒绝注册（不在向导界面）立即失败，重试无意义。
     */
    override suspend fun registerStaHost(endpoint: CameraEndpoint?) = connectMutex.withLock {
        disconnectInternal(emitState = false)
        val ep = endpoint ?: discoverStaEndpoint()

        // 路由就绪门（与无线连接同款策略）：本地须已有到相机网段的直连路由才开始注册
        _state.value = CameraState.Connecting("等待到相机的网络路由…")
        if (!ApEndpointResolver.awaitLocalRoute(ep.host, totalTimeoutMs = 8_000)) {
            if (!ApEndpointResolver.awaitLocalRoute(ep.host, totalTimeoutMs = 2_500)) {
                throw CameraError.NoCameraFound(
                    "未发现到 ${ep.host} 的网络路由。请确认手机与相机在同一局域网后重试。",
                )
            }
        }

        val binder = NetworkBinder(context, ep.host, log).also { networkBinder = it }
        val factory = binder.cameraSocketFactory()
        currentEndpoint = ep
        currentSocketFactory = factory

        var lastError: Exception? = null
        REGISTRATION_BACKOFF_MS.forEachIndexed { attempt, backoffMs ->
            if (backoffMs > 0) {
                _state.value = CameraState.Connecting("主机注册重试（${attempt + 1}/${REGISTRATION_BACKOFF_MS.size}）")
                delay(backoffMs)
            }
            try {
                runRegistration(
                    ep,
                    factory,
                    preSettleMs = if (attempt == 0) REG_SETTLE_FIRST_MS else REG_SETTLE_RETRY_MS,
                )
                _events.tryEmit(CameraEvent.PairingProgress("PC 主机注册完成，可发起 STA 连接"))
                disconnectInternal(emitState = false)
                _state.value = CameraState.Disconnected
                return@withLock
            } catch (e: CameraError.PairingRejected) {
                disconnectInternal(emitState = false)
                _state.value = CameraState.Failed(e)
                throw e
            } catch (e: Exception) {
                lastError = e
                log("主机注册握手失败（第 ${attempt + 1}/${REGISTRATION_BACKOFF_MS.size} 次）：${e.message}")
            }
        }
        val err = CameraError.PairingRejected("主机注册未完成：${lastError?.message ?: "无响应"}")
        disconnectInternal(emitState = false)
        _state.value = CameraState.Failed(err)
        throw err
    }

    override fun session(): CameraSession? = ptpSession

    override fun requireSession(): CameraSession =
        ptpSession ?: throw CameraError.NotConnected()

    override fun disconnect() {
        disconnectInternal(emitState = true)
    }

    private fun disconnectInternal(emitState: Boolean) {
        watchdogJob?.cancel()
        watchdogJob = null
        closeSessionQuietly()
        networkBinder?.release()
        networkBinder = null
        currentEndpoint = null
        currentSocketFactory = null
        activeMethod = null
        if (emitState) {
            _state.value = CameraState.Disconnected
        }
    }

    private fun closeSessionQuietly() {
        try {
            ptpSession?.close()
        } catch (_: Exception) {
        }
        ptpSession = null
        try {
            link?.close(graceful = true)
        } catch (_: Exception) {
        }
        link = null
    }
}

/** 工厂。 */
object CameraConnectionFactory {
    fun create(context: Context, log: (String) -> Unit = {}): CameraConnection =
        NikonCameraClient(context, log)
}

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
import com.camerakit.discovery.StaSubnetProber
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
         * 注册完成后的配置保存等待。基准实现在配置保存后等 15s 才自动连接
         * （相机在应用配置、可能重启 PTP 服务）；注册场景保存更快，取 8s。
         */
        const val REG_POST_REGISTER_SETTLE_MS = 8_000L
    }

    // 惰性获取：避免构造期强转系统服务（无 USB host / NSD 的设备上为 null 会启动崩溃）
    private val usbDiscovery by lazy { UsbCameraDiscovery(context, log) }
    private val mdnsDiscovery by lazy { MdnsCameraDiscovery(context, log) }
    private val staSubnetProber by lazy { StaSubnetProber(log) }
    private val staCache by lazy { StaEndpointCache(context) }
    @Volatile private var staTopologyActive = false

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
        disconnectInternal(emitState = false)
        activeMethod = method
        _state.value = CameraState.Connecting("准备连接")
        try {
            when (method) {
                is ConnectionMethod.Usb -> connectUsb()

                is ConnectionMethod.WifiAp -> {
                    val ep = endpoint ?: ApEndpointResolver.defaultEndpoint(method.host, method.port)
                    // AP 路径保持 3.1.0 原样（无握手延迟/无子网过滤），staTopology=false
                    connectWireless(ep, autoPair, staTopology = false)
                }

                is ConnectionMethod.WifiSta -> {
                    val ep = endpoint ?: method.endpoint ?: discoverStaEndpoint()
                    connectWireless(ep, autoPair, staTopology = true)
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
        // 快速重连（基准实现语义）：上次连过的地址经路由校验且端口通 → 直接用，跳过发现
        val cached = staCache.endpoint()
        if (cached != null) {
            if (staCache.isRoutableTo(cached.host) && staSubnetProber.probeHost(cached.host)) {
                log("[发现] 使用缓存地址 ${cached.host}:${cached.port}（快速重连）")
                return cached
            }
            log("[发现] 缓存地址 ${cached.host} 不可用（路由不通或端口无响应），转入全量发现")
        }
        // 基准实现 实证：STA 发现窗口 6s 且取首个结果。这里发现首个端点即返回，
        // 窗口只作上限——旧版无条件收满整窗，相机秒到也要白等十几秒。
        val found = try {
            withTimeout(STA_DISCOVER_TIMEOUT_MS) { mdnsDiscovery.discover().first() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }
        if (found != null) {
            staCache.saveEndpoint(found)
            return found
        }

        // mDNS 兜底：网段探测。真机实证部分网络/ROM 组合收不到组播
        // （服务发现启动正常却零命中），而相机的 PTP/IP 端口一定在线。
        _state.value = CameraState.Connecting("mDNS 无结果，网段探测中…")
        staSubnetProber.findFirst()?.let {
            staCache.saveEndpoint(it)
            return it
        }

        throw CameraError.NoCameraFound(
            "未发现相机。请确认相机已进入 STA 连接状态，且手机与相机在同一非访客 Wi-Fi（关闭 AP 隔离）；" +
                "仍不行可用「STA 手动IP」直接输入相机 IP 连接。",
        )
    }

    private suspend fun connectWireless(
        endpoint: CameraEndpoint,
        autoPair: Boolean,
        staTopology: Boolean = false,
    ) {
        currentEndpoint = endpoint
        // 记录本次会话拓扑：断线自动恢复时保持同一时序（AP=原样 / STA=稳定间隔）
        staTopologyActive = staTopology

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

        // 子网过滤只在 STA 启用（双网手机防绑错网口）；AP 保持 3.1.0 行为不过滤
        val binder = NetworkBinder(
            context,
            if (staTopology) endpoint.host else null,
            log,
        ).also { networkBinder = it }
        val factory = binder.cameraSocketFactory()
        currentSocketFactory = factory

        // 等待路由稳定（基准实现：AP network readiness wait≈1.2s stable=true）
        _state.value = CameraState.Connecting("等待网络稳定…")
        delay(1_200)

        var paired = false
        while (true) {
            try {
                openWirelessSession(endpoint, factory, staTopology)
                val storages = ptpSession?.listStorages() ?: emptyList()
                if (storages.isEmpty()) {
                    if (autoPair && !paired && !(staTopology && staCache.isRegistered())) {
                        log("相机未开放存储卡，尝试主机配对后重连")
                        closeSessionQuietly()
                        paired = true
                        runRegistration(endpoint, factory)
                        continue
                    }
                    throw CameraError.StorageUnavailable(
                        if (staTopology && staCache.isRegistered()) {
                            "相机未开放可读取的存储卡。本机已注册过 STA 主机，" +
                                "若相机更换了配置或恢复出厂，请重新执行「STA 主机注册」。"
                        } else {
                            "相机未开放可读取的存储卡（不可用存储：0）"
                        },
                    )
                }
                onConnected()
                return
            } catch (e: CameraError.CameraRejected) {
                // 基准实现语义：已注册过的 STA 主机不再自动重做注册（用户多半不在
                // 注册向导，注册必败且报错难懂）——引导显式重新注册
                if (staTopology && staCache.isRegistered() && autoPair && !paired) {
                    throw CameraError.PairingRejected(
                        "相机拒绝连接（0x${e.failCode.toString(16)}）。本机已注册过 STA 主机，" +
                            "若相机更换了配置或恢复出厂，请重新执行「STA 主机注册」。",
                    )
                }
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
     * PTP/IP 握手 + 会话建立，带退避重试：
     * - 端口未开放（ECONNREFUSED）：相机 Wi-Fi/PTP 服务未就绪；
     * - Connection reset：相机正在释放上一条无线会话（基准实现 同款场景），稍候重试即可。
     */
    private suspend fun openWirelessSession(
        endpoint: CameraEndpoint,
        factory: SocketFactory,
        staTopology: Boolean = false,
    ) {
        var lastError: CameraError? = null
        repeat(3) { attempt ->
            _state.value = CameraState.Connecting("PTP/IP 握手（${attempt + 1}/3）")
            try {
                val l = PtpIpLink(
                    endpoint, identity.guid, identity.hostName, factory, log,
                    onTransportLost = { reason -> handleTransportLost(reason) },
                    // STA 才加事件通道稳定间隔（重试轮取更大值）；AP = 0 保持 3.1.0 时序
                    eventSettleMs = if (!staTopology) 0L
                    else if (attempt == 0) 350L else 650L,
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
                if (attempt < 2) {
                    val waitMs = 2_000L * (attempt + 1)
                    log("握手失败：${e.message}；${waitMs / 1000}s 后自动重试（相机可能未开放端口，或正在释放上一条无线会话）")
                    _state.value = CameraState.Connecting("等待相机就绪（${waitMs / 1000}s）…")
                    delay(waitMs)
                }
            }
        }
        throw lastError ?: CameraError.TransportLost("PTP/IP 握手失败")
    }

    /**
     * 主机配对（PC 主机注册）：独立注册会话，相机须停留在主机配置向导。
     *
     * 错误分两段（基准实现 阶段判定语义）：
     * - 握手段（建链/初始化/事件通道）失败 → 抛 [RegistrationHandshakeException]，
     *   调用方可重试——InitFail 也属此段（上一条会话残留占位时稍候重试即过）；
     * - 注册段（0x952B→0x935A）失败 → 抛 [CameraError.PairingRejected]，
     *   相机明确未确认，重试整个流程没有意义。
     */
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
            // 注册流程是 STA 专属动作（AP 不走这里），保持稳定间隔
            eventSettleMs = if (preSettleMs > 0) 650L else 350L,
        )
        try {
            val regSession = try {
                regLink.open()
                PtpSession(regLink, log = log).also { it.open() }
            } catch (e: Exception) {
                throw RegistrationHandshakeException(e)
            }
            try {
                registrar.register(
                    regSession,
                    onAwaitConfirm = { prepare ->
                        _state.value = CameraState.AwaitingHostConfirm(prepare)
                        _events.tryEmit(
                            CameraEvent.PairingProgress("请在相机的主机确认界面确认 ${identity.hostName}，并等待相机完成保存。"),
                        )
                    },
                )
            } catch (e: CameraError.PairingRejected) {
                throw e
            } catch (e: Exception) {
                throw CameraError.PairingRejected(
                    "主机注册未获相机确认：${e.message ?: e.javaClass.simpleName}。" +
                        "请让相机停留在 STA/PC 主机配置向导后重试。",
                )
            }
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
                    val silenceMs = System.currentTimeMillis() - l.lastReceiveAt()
                    if (silenceMs > PtpConsts.KEEPALIVE_DEAD_MS) {
                        // 判死前先做一次往返事务探测：部分机身空闲时既不回 Ping 也不发
                        // 任何包，纯静默判死会把好链路错杀（真机实证：错杀后的恢复期整段
                        // 挂在事件通道上，表现为「连着连着自己断开且重连失败」）。
                        val probed = runCatching { ptpSession?.getDeviceInfo() }.getOrNull() != null
                        if (probed) {
                            log("[保活] 静默 ${silenceMs}ms 但往返探测通过——链路正常（相机空闲不回包），不判死")
                            _events.tryEmit(
                                CameraEvent.KeepaliveFailed("空闲 ${silenceMs}ms 无回包，事务探测通过，连接正常"),
                            )
                            continue
                        }
                        _events.tryEmit(
                            CameraEvent.KeepaliveFailed("静默 ${silenceMs}ms 且往返探测无响应"),
                        )
                        throw CameraError.TransportLost("静默 ${silenceMs}ms，往返探测无响应")
                    }
                } catch (e: Exception) {
                    handleTransportLost("保活失败：${e.message ?: e.javaClass.simpleName}")
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
                    closeSessionQuietly()
                    // 相机端释放旧会话/事件槽位需要时间（真机实证：残留期间事件通道
                    // 持续被复位），恢复轮次间隔拉长到 5s/10s/15s，别在残留期里打光子弹
                    delay(5_000L * (attempt + 1))
                    log("[恢复] 第 ${attempt + 1}/3 次重建会话（${ep.host}）")
                    openWirelessSession(ep, factory, staTopologyActive)
                    recovered = true
                } catch (e: Exception) {
                    log("[恢复] 第 ${attempt + 1}/3 次失败：${e.message}")
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
                // 注册完成 ≠ 马上能连：相机要保存主机配置、可能重启 PTP 服务，
                // 立刻连接大概率撞上「拒绝/无响应」（基准实现：建档后同样等相机应用配置再连）。
                _events.tryEmit(CameraEvent.PairingProgress("PC 主机注册完成，等待相机保存配置…"))
                delay(REG_POST_REGISTER_SETTLE_MS)
                _events.tryEmit(CameraEvent.PairingProgress("PC 主机注册完成，可发起 STA 连接"))
                // 注册态入档（基准实现：注册成功记 host+registered，重连走直连入口）
                staCache.markRegistered(ep)
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
        val err = CameraError.PairingRejected(
            "主机注册未完成（已重试 ${REGISTRATION_BACKOFF_MS.size} 次）：${lastError?.message ?: "无响应"}。" +
                "请确认相机停在 STA 主机配置向导的等待画面后重试。",
        )
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

/** 注册握手段（建链/初始化/事件通道）失败——可按退避重试（含 InitFail 型残留占位）。 */
private class RegistrationHandshakeException(cause: Throwable) : Exception(cause)

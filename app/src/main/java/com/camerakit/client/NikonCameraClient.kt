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
        val found = ArrayList<CameraEndpoint>()
        try {
            withTimeout(12_000) {
                mdnsDiscovery.discover().collect { ep ->
                    if (!found.contains(ep)) found.add(ep)
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        }
        return found.firstOrNull()
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

        val binder = NetworkBinder(context, log).also { networkBinder = it }
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
     * PTP/IP 握手 + 会话建立，带退避重试：
     * - 端口未开放（ECONNREFUSED）：相机 Wi-Fi/PTP 服务未就绪；
     * - Connection reset：相机正在释放上一条无线会话（基准实现 同款场景），稍候重试即可。
     */
    private suspend fun openWirelessSession(endpoint: CameraEndpoint, factory: SocketFactory) {
        var lastError: CameraError? = null
        repeat(3) { attempt ->
            _state.value = CameraState.Connecting("PTP/IP 握手（${attempt + 1}/3）")
            try {
                val l = PtpIpLink(endpoint, identity.guid, identity.hostName, factory, log) { reason ->
                    handleTransportLost(reason)
                }
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

    /** 主机配对（PC 主机注册）：独立注册会话，相机须停留在主机配置向导。 */
    private suspend fun runRegistration(endpoint: CameraEndpoint, factory: SocketFactory) {
        _state.value = CameraState.Connecting("建立 PC 主机注册会话")
        _events.tryEmit(CameraEvent.PairingProgress("正在建立 PC 主机注册会话…"))
        val regLink = PtpIpLink(endpoint, identity.guid, identity.hostName, factory, log) {}
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
                    closeSessionQuietly()
                    delay(1_500L * (attempt + 1))
                    openWirelessSession(ep, factory)
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

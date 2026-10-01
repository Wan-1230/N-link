package com.nikonlink.app.device.connect

import android.content.Context
import com.nikonlink.app.device.ble.BleConnectionState
import com.nikonlink.app.device.ble.BleManager
import com.nikonlink.app.device.ble.WifiCredential
import com.nikonlink.app.device.model.ConnectionMetrics
import com.nikonlink.app.device.model.ChannelType
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.model.ConnectionEvent
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.ptp.PtpSessionState
import com.nikonlink.app.device.ptp.PtpIpProbe
import com.nikonlink.app.device.ptp.HostRegistrationResult
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.device.wifi.WifiEndpoint
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.device.wifi_sta.WifiCameraCandidate
import com.nikonlink.app.device.wifi_sta.WifiDirectConnector
import com.nikonlink.app.device.wifi_sta.WifiScanner
import com.nikonlink.app.device.data.DeviceRepository
import com.nikonlink.app.camera.gallery.TransferManager
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.device.RomDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接管理器 - 协调 BLE、WiFi、PTP 三大子系统
 *
 * PRD 3.1 双通道架构:
 * BLE 通道永远在线（低功耗），WiFi 通道按需唤醒（高带宽）
 *
 * PRD 3.5 断联自动恢复:
 * - WiFi 断开，BLE 正常 → 通过 BLE 发送 WiFi 重连指令
 * - BLE 断开，WiFi 正常 → 后台自动重新扫描 BLE
 * - 双通道均断开 → 全量重连流程（指数退避）
 * - 相机关机 → 低功耗等待模式
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
    private val wifiManager: WifiManager,
    private val wifiScanner: WifiScanner,
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val stateMachine: ConnectionStateMachine,
    private val deviceRepository: DeviceRepository,
    private val transferManager: TransferManager,
    private val connector: WifiDirectConnector,
    private val eventLogger: AppEventLogger,
    private val apGatewayResolver: com.nikonlink.app.device.wifi_ap.ApGatewayResolver,
    private val connFlags: ConnFlags,
    private val funnel: ConnFunnel,
    private val preflight: PreflightGate
) {
    companion object {
        private const val TAG = "ConnectionMgr"
        private const val WIFI_UPGRADE_DELAY_MS = 1000L
        private const val PREFS_NAME = "nl_settings"
        private const val PREFS_WIFI_5G_PREFER = "wifi_band_5g_prefer"
        /** STA 主机注册标记：相机已记住本机 GUID（门控解除） */
        private const val PREFS_STA_HOST_REGISTERED = "sta_host_registered"

        // P0：目标地址分级取值的来源标识（event 字段，便于日志区分真实地址与兜底）
        private const val SOURCE_BLE_CREDENTIAL = "ble_credential"
        private const val SOURCE_DISCOVERED = "discovered"
        private const val SOURCE_AP_GATEWAY = "ap_gateway"
        private const val SOURCE_DEFAULT_FALLBACK = "default_fallback"
        private const val DEFAULT_FALLBACK_HOST = "192.168.1.1"

        /**
         * v2.6.4：兜底地址（[DEFAULT_FALLBACK_HOST]）**自动重试**的冷却时长。
         *
         * 取值理由：日志里每轮盲猜要烧掉 157s，两轮之间相隔约 2.6 分钟 ——
         * 也就是说网络条件根本没变过，重试只是重复已知结论。取 180s 冷却，
         * 让自动恢复把时间留给"等网络事件"（手机重新接上相机热点）而不是反复撞墙。
         * 用户主动点击不受此限（见 [connectToWifiCamera] 的冷却分支）。
         */
        private const val FALLBACK_COOLDOWN_MS = 180_000L
    }

    private var scope: CoroutineScope? = null
    private var pairedDeviceAddress: String? = null
    private var stateListener: ((ConnectionState, ConnectionState) -> Unit)? = null

    /** v1.3.2：事件级监听（失败原因可见化），与 stateListener 同生命周期 */
    private var eventListener: ((ConnectionEvent) -> Unit)? = null
    private var userDisconnectRequested = false
    private var connectedSince: Long? = null
    private var pairingJob: Job? = null
    private var recoveryJob: Job? = null
    /** STA 直连场景：WiFi 路由器断网标记（网络恢复后触发 PTP 重建） */
    private var staNetworkLost = false

    /**
     * P0：本轮/会话内确认过的真实相机 IP（BLE 凭证给出、历史连接过且当前可达、或真实地址建链成功）。
     * 一旦拿到过，后续重试优先用它，不再退回默认兜底地址——消除日志里"12:14 又打回 192.168.1.1"的回退。
     */
    private var realCameraIp: String? = null

    /** v2.6.4：兜底地址上一次连接失败的时间戳（0 = 从未失败），供自动重连做冷却 */
    private var lastFallbackFailAt = 0L

    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()

    private val _statusMessage = MutableStateFlow("未连接")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectionHint = MutableStateFlow<ConnectionHint?>(null)
    val connectionHint: StateFlow<ConnectionHint?> = _connectionHint.asStateFlow()

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /**
     * 相机是否已记住本机为「信任主机」（STA 门控已解除）。
     * 持久化：注册一次后长期有效，重装/清数据才失效。
     */
    private val _staHostRegistered = MutableStateFlow(
        prefs.getBoolean(PREFS_STA_HOST_REGISTERED, false)
    )
    val staHostRegistered: StateFlow<Boolean> = _staHostRegistered.asStateFlow()

    /**
     * 是否需要引导用户做 STA 主机注册 → UI 把注册按钮切成强调色。
     * 置 true 的两个来源：① 从未注册过；② 相机明确拒绝了本次握手（InitFail）。
     */
    private val _staRegisterNeeded = MutableStateFlow(
        !prefs.getBoolean(PREFS_STA_HOST_REGISTERED, false)
    )
    val staRegisterNeeded: StateFlow<Boolean> = _staRegisterNeeded.asStateFlow()

    /** 对外暴露的连接状态（来自状态机） */
    val connectionState: StateFlow<ConnectionState> = stateMachine.state

    fun start(scope: CoroutineScope) {
        this.scope = scope
        bleManager.start(scope)
        wifiManager.start(scope)
        ptpSession.start(scope)
        usbPtpManager.start(scope)
        transferManager.start(scope)
        // v2.2（G6）：重试预算 —— 超过上限就停手，避免把相机热点喂进系统黑名单
        stateMachine.budgetEnabled = connFlags.isEnabled(ConnFlags.ATTEMPT_BUDGET)
        stateMachine.onBudgetExhausted = { budget ->
            funnel.fail(
                ConnFunnel.Reason.BUDGET_EXHAUSTED,
                "budget=$budget channel=${funnel.currentChannel() ?: "unknown"}"
            )
        }
        stateMachine.start(scope)
        connector.start(scope)

        observeBleEvents()
        observeWifiEvents()
        observePtpEvents()
        observeUsbEvents()
        observeStateMachine()
        observeReconnectTrigger()
        observeStaRecovery()
        observeHostRegistrationHint()
        startMetricsUpdater()
        scope.launch { reconnectLastDeviceIfPaired() }

        Timber.tag(TAG).i("ConnectionManager started")
    }

    fun stop() {
        pairingJob?.cancel()
        pairingJob = null
        stateListener?.let { stateMachine.removeStateListener(it) }
        stateListener = null
        eventListener?.let { stateMachine.removeEventListener(it) }
        eventListener = null
        bleManager.stop()
        wifiManager.stop()
        ptpSession.stop()
        usbPtpManager.stop()
        transferManager.stop()
        stateMachine.stop()
        connector.stop()
        _connectionHint.value = null
        userDisconnectRequested = false
        connectedSince = null
        scope = null
        Timber.tag(TAG).i("ConnectionManager stopped")
    }

    /**
     * 用户触发连接（首次配对或手动重连）
     */
    fun connectToDevice(address: String) {
        pairedDeviceAddress = address
        userDisconnectRequested = false
        stateMachine.dispatch(ConnectionEvent.StartConnect)
        scope?.launch(Dispatchers.IO) {
            try {
                deviceRepository.savePairedDevice(address, address, "")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to persist paired device")
            }
        }
    }

    /**
     * 用户主动断开：进入 DISCONNECTED，不触发自动重连。
     */
    fun disconnectDevice() {
        pairingJob?.cancel()
        pairingJob = null
        connector.cancelActive()
        _connectionHint.value = null
        userDisconnectRequested = true
        stopScan()
        wifiManager.disconnect()
        bleManager.disconnect()
        ptpSession.closeSession()
        stateMachine.dispatch(ConnectionEvent.CameraShutdown)
        userDisconnectRequested = false
        Timber.tag(TAG).i("Device disconnected by user")
    }

    /**
     * 通过 WiFi 直连相机（AP/STA 均适用）。
     * RC-1/2/7/8：连接全部收敛到 WifiDirectConnector 单一入口——
     * Mutex + activeJob 槽位消灭并发 pair_start 风暴，generation 令牌保证
     * 旧任务在每个挂起点失效，指数退避把重试窗口拉长到 ~90s。
     */
    fun connectToWifiCamera(
        ipAddress: String,
        port: Int = 15740,
        deviceName: String? = null,
        /**
         * FR-18c：这一轮是谁发起的。真机日志里出现过 4.9 秒内 22 条 `sta_try`
         * 的风暴，但静态读代码只能排除"UI 点击"和 15 分钟一轮的 HealthWorker，
         * 无法唯一锁定触发者 —— 因为 `conn_attempt` 这行不带来源。
         * 风暴的**放大机制**已经查清并修掉了（下面的 FR-19），
         * 剩下"谁在按按钮"这一半必须有数据才能定，所以先把它记下来。
         */
        caller: ConnFunnel.Caller = ConnFunnel.Caller.USER_TAP
    ) {
        val endpoint = WifiEndpoint.parse("wifi:$ipAddress:$port")
        if (endpoint == null) {
            eventLogger.event("sta_fail", "reason" to "invalid_endpoint", "host" to ipAddress)
            stateMachine.dispatch(
                ConnectionEvent.ErrorOccurred("IP 地址无效，请填写形如 192.168.1.1 的 IPv4 地址", recoverable = true)
            )
            return
        }

        // 同一台相机的连接循环还在跑时，这次点击不再另起一轮。
        // 另起一轮的代价是真机日志里看到的：connector.connect() 会 cancel 掉仍在退避
        // 等待的旧循环、把指数退避从 1s 清零，而机身唯一的 PTP/IP 客户端槽需要十几秒
        // 才自己收干净 —— 越快点、越连不上（2026-09-19：30 秒内 4 轮互相 cancel）。
        // 旧循环本身每 1~15s 就在重试，用户在机身上按了 OK 也会立刻被它接住。
        val loopRunning = connector.isActive && pairedDeviceAddress == endpoint.address &&
            !ptpSession.isConnected()
        // FR-19：上面那道守卫只看 `connector.isActive`，而 learnFirst 分支要先跑
        // ~2.4s 的网关学习（READINESS 1200ms + RESAMPLE 700ms + 筛探）才碰得到
        // connector —— 那段窗口里 `connector.isActive` 恒为 false，任何重入都放行。
        // 放行之后 `pairingJob = scope?.launch{...}` 又是**覆盖而不取消**，
        // 于是每条重入都留下一枚孤儿协程，各自跑完 resolve() 后各自发起一代连接。
        // 真机日志：4.9 秒内 22 条 `sta_try`，45 个连接样本里 36 个（80%）记 `superseded`。
        // 同一个类里 `:834` 和 `:1058` 两处守卫**本来就判了** `pairingJob`，只有这里漏了。
        val learning = connFlags.isEnabled(ConnFlags.CONN_SERIALIZE) &&
            pairingJob?.isActive == true && pairedDeviceAddress == endpoint.address
        if (loopRunning || learning) {
            _connectionHint.value = ConnectionHint("相机正在重试连接中，请稍候…")
            Timber.tag(TAG).i("connect request ignored: ${endpoint.display}")
            eventLogger.event(
                "connect_ignored", "host" to endpoint.host, "caller" to caller.code,
                "reason" to if (loopRunning) "loop_running" else "learn_running"
            )
            return
        }

        // v2.2（G11/G12）：先预检再连接 —— 权限/位置开关/OTG 这类硬阻断不该靠重试去碰运气
        val mode = if (endpoint.host == DEFAULT_FALLBACK_HOST) "AP-fallback" else "host"
        funnel.begin("WIFI", mode, caller.code)
        emitEnvironment(endpoint.host)
        // 已经连在相机热点上时跳过硬阻断：这条路径不需要扫描权限，WLAN 也必然是开的，
        // 拿权限项把用户当前能用的连接拦掉是倒退。
        if (connFlags.isEnabled(ConnFlags.PREFLIGHT)) {
            val onCameraAp = apGatewayResolver.isOnCameraAp()
            // FR-24：预检分两类。**硬阻断**（WLAN 关着、没权限）沿用"已在相机热点上
            // 就不拦"的规则；**软告警**（VPN、避开不良网络、电池优化）一律照查照报 ——
            // 恰恰是在相机热点上，这两项才是致败因素：它们会把没有网络的相机热点
            // 踢掉、或把相机流量劫持进隧道。真机日志里那台连着 `vgate0` 反复失败的
            // 机器，VPN 告警就因为这道豁免从来没有出现过。
            // 软告警**不改变任何连接/重试/绑定行为**，只上日志与状态行。
            if (connFlags.isEnabled(ConnFlags.PREFLIGHT_SOFT)) {
                val warns = preflight.warnings(PreflightGate.CHANNEL_WIFI)
                warns.forEach { warn ->
                    eventLogger.event(
                        "preflight_warn", "code" to warn.reason.code, "label" to warn.label
                    )
                }
                // 一次上屏而不是逐条覆盖 —— 循环里每条都写一遍 ` _statusMessage`
                // 只会剩下最后一条，前面几条等于白报。
                warns.lastOrNull()?.let { _statusMessage.value = it.surfaceText() }
            }
            if (!onCameraAp) {
                val blocker = preflight.firstBlocking(PreflightGate.CHANNEL_WIFI)
                if (blocker != null) {
                    funnel.stage(ConnFunnel.Stage.PREFLIGHT, blocker.reason, blocker.label)
                    funnel.fail(blocker.reason, blocker.label)
                    stateMachine.dispatch(
                        ConnectionEvent.ErrorOccurred(
                            "${blocker.label}\n${blocker.reason.hint ?: "请检查系统设置后重试"}",
                            recoverable = true
                        )
                    )
                    return
                }
            }
        }

        // v2.6.4：**兜底地址的自动重试冷却**。
        // 真机证据（2026-09-30 日志）：22:51 / 22:53 / 22:56 / 00:07 / 00:50 各有一轮
        // `sta_fail reason=camera_unreachable host=192.168.1.1 fallback=true waited_ms≈157294`
        // —— 每 2.6 分钟就用 157 秒去撞那个"没连上相机时纯属盲猜"的默认地址，
        // 而相机其实一直在 10.184.219.14（手机热点上，`in_subnet=true` 那轮一次就成）。
        // 盲猜地址对**用户主动点击**永远放行（他要试就该让他试），
        // 只对自动恢复（restore_paired / 健康检查）做冷却，省下重复的 157 秒空转。
        if (endpoint.host == DEFAULT_FALLBACK_HOST &&
            caller != ConnFunnel.Caller.USER_TAP &&
            System.currentTimeMillis() - lastFallbackFailAt < FALLBACK_COOLDOWN_MS
        ) {
            val waited = (System.currentTimeMillis() - lastFallbackFailAt) / 1000
            eventLogger.event(
                "connect_ignored", "host" to endpoint.host, "caller" to caller.code,
                "reason" to "fallback_cooldown", "waited_s" to waited
            )
            return
        }

        // v2.2（PRD §5.1 T-A2/T-A3）：AP 模式下相机自任网关，手机已连相机热点时
        // 「网关就是相机」——先学地址再发起配对，用户/历史给的地址（尤其是默认
        // 192.168.1.1）只在学不出来时作为兜底。学不到（如 STA 场景网关是路由器）
        // 时按原地址继续，行为与 v2.1.1 一致。
        val learnFirst = connFlags.isEnabled(ConnFlags.AP_GATEWAY) &&
            ((connFlags.isEnabled(ConnFlags.AP_BLELESS) && apGatewayResolver.isOnCameraAp()) ||
                endpoint.host == DEFAULT_FALLBACK_HOST)
        if (learnFirst) {
            _connectionHint.value = null
            pairedDeviceAddress = endpoint.address
            userDisconnectRequested = false
            // RC-1：先登记 job 再派发状态，避免状态机观察者并发发起第二次连接
            // FR-19：覆盖 `pairingJob` 之前必须先取消旧 job。直接赋值会让上一条 learnFirst
            // 协程变成**孤儿** —— 它照样会跑完网关学习、照样会调 beginWifiPairing，
            // 于是每个孤儿各发起一代连接并把上一代 cancel 掉，
            // 日志里就是 `sta_try gen=N` 与 `sta_cancel gen=N-1` 严格成对刷屏
            // （真机：4.9 秒内 22 条 sta_try）。
            if (connFlags.isEnabled(ConnFlags.CONN_SERIALIZE)) pairingJob?.cancel()
            pairingJob = scope?.launch(Dispatchers.IO) {
                val learned = runCatching {
                    apGatewayResolver.resolve(
                        network = wifiManager.getActiveNetwork(),
                        wifiNetwork = wifiManager.currentWifiNetwork()
                    )
                }.getOrNull()
                val target = learned?.let {
                    WifiEndpoint.parse("wifi:${it.host}:${endpoint.port}") ?: endpoint
                } ?: endpoint
                if (target.host != endpoint.host) {
                    eventLogger.event(
                        "ap_host_override",
                        "from" to endpoint.host, "to" to target.host, "stable" to learned!!.stable
                    )
                    Timber.tag(TAG).i("AP gateway learned: %s -> %s", endpoint.host, target.host)
                }
                withContext(Dispatchers.Main) { beginWifiPairing(target, deviceName, caller.code) }
            }
            stateMachine.dispatch(ConnectionEvent.StartConnect)
            return
        }

        beginWifiPairing(endpoint, deviceName, caller.code)
        stateMachine.dispatch(ConnectionEvent.StartConnect)
    }

    /**
     * FR-18e：把「手机当时到底在不在相机那张网上」写进日志。
     *
     * 三份用户报障日志里，我们连"用户当时连的是相机热点还是家里路由器"都判断不了，
     * 于是 D1（绑错网）与 D5（走蜂窝黑洞）这两条致败链只能推断、不能定谳。
     *
     * **脱敏边界**：只记 SSID 是不是 `NIKON*` 这个布尔，绝不记 SSID 原文 / BSSID /
     * MAC —— 与 `DiagnosticsDisclosure` 的「不包含 WiFi SSID / BSSID / 密码、蓝牙 MAC」一致。
     * 局域网 IP **是**会被记的（`ifaces=` / `host=`），那份声明里已如实写明，
     * 因为不记它就没法判断"手机到底在哪张网上"。
     */
    private fun emitEnvironment(host: String) {
        val ssid = apGatewayResolver.connectedSsid()
        eventLogger.event(
            "ap_ctx",
            "on_camera_ap" to apGatewayResolver.isOnCameraAp(),
            "ssid_is_nikon" to com.nikonlink.app.device.wifi_ap.ApGatewayResolver
                .looksLikeCameraAp(ssid),
            "ssid_known" to (ssid != null && ssid != "<unknown ssid>"),
            "host" to host
        )
    }

    /**
     * 真正发起一次 WiFi 相机配对/连接（RC-1/2/7/8 的并发治理都在 connector 内部）。
     * 调用方负责在合适时机派发 [ConnectionEvent.StartConnect]。
     */
    private fun beginWifiPairing(endpoint: WifiEndpoint, deviceName: String?, source: String) {
        _connectionHint.value = null
        pairedDeviceAddress = endpoint.address
        userDisconnectRequested = false
        // FR-01：地址学到手 = "发现相机"结束。漏斗原来这一段不打卡，
        // 于是 TCP 的耗时把"扫描/学地址"和"建链"混在一起，看不出该优化哪个。
        funnel.stage(ConnFunnel.Stage.DISCOVER, detail = endpoint.display)

        // RC-1：必须先同步登记 activeJob，再派发 StartConnect，
        // 否则状态机触发的重连观察者会认为没有任务在跑而再次发起连接
        pairingJob = connector.connect(
            endpoint = endpoint,
            mode = WifiDirectConnector.Mode.PAIRING,
            source = source,
            onWaitingCameraOk = {
                if (_connectionHint.value == null) {
                    _connectionHint.value =
                        ConnectionHint("请在相机配对完成画面按 OK，然后等待即可")
                }
            },
            onRetry = { hint ->
                if (_connectionHint.value != null) {
                    // FR-22②：connector 给了具体等待文案时优先用它，
                    // 因为"约还需 40 秒"比"请稍候"更能拦住用户的重复点击。
                    _connectionHint.value = ConnectionHint(
                        hint ?: "请稍候，相机正在完成重新连接。请勿切换相机网络设置界面或启动其他连接。"
                    )
                }
            },
            onSuccess = {
                funnel.stage(ConnFunnel.Stage.READY, detail = endpoint.display)
                eventLogger.event("pair_ok", "host" to endpoint.host, "port" to endpoint.port)
                _connectionHint.value = ConnectionHint(
                    "配对完成。OK确定",
                    kind = ConnectionHintKind.PAIRING_COMPLETE
                )
                // 回调非挂起上下文，持久化是 fire-and-forget，放独立协程（与旧实现语义一致）
                scope?.launch(Dispatchers.IO) {
                    try {
                        deviceRepository.savePairedDevice(
                            endpoint.address,
                            deviceName ?: "尼康相机",
                            endpoint.host
                        )
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Failed to persist WiFi camera")
                    }
                }
            },
            onFail = { reason ->
                funnel.fail(mapConnectorReason(reason), reason)
                _connectionHint.value = null
                // v2.6.4：兜底地址（默认 192.168.1.1）失败的时间戳，供自动重连做冷却
                // （见 [connectToWifiCamera] 的 fallback 冷却分支）。
                if (endpoint.host == DEFAULT_FALLBACK_HOST) {
                    lastFallbackFailAt = System.currentTimeMillis()
                }
                eventLogger.event(
                    "pair_fail",
                    "host" to endpoint.host, "port" to endpoint.port, "reason" to reason
                )
                // 状态行文案由事件监听统一写入（见 observeStateMachine 的 eventListener），
                // 这里不再重复设置，避免两处文案互相覆盖
            }
        )
    }

    /**
     * 用户取消相机端配对确认。
     */
    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        connector.cancelActive()
        _connectionHint.value = null
        userDisconnectRequested = true
        ptpSession.closeSession()
        stateMachine.dispatch(ConnectionEvent.CameraShutdown)
        userDisconnectRequested = false
        Timber.tag(TAG).i("Pairing cancelled by user")
    }

    /**
     * 用户确认配对完成提示。
     */
    fun confirmPairingComplete() {
        _connectionHint.value = null
    }

    /**
     * 扫描当前 WiFi 网络中的尼康相机（UDP 5353 + TCP 15740 探测）。
     */
    suspend fun scanWifiCameras(
        timeoutMs: Long = 18_000L,
        hotspotMode: Boolean = false
    ): List<WifiCameraCandidate> {
        // STA: 显式拿到 WiFi Network，让 scan() 内部的 mDNS/探测 Socket 绑定到 WiFi，
        // 避免手机蜂窝数据等其它网络抢走默认路由导致扫描失败。
        val network = wifiManager.currentWifiNetwork()
        val candidates = wifiScanner.scan(timeoutMs, network, hotspotMode).toMutableList()
        // 扫描失败时直接复用历史 IP 发起 PTP/IP，避免每次都全段盲扫。
        // RC-3：地址统一走 WifiEndpoint 归一化（前导零校正、非法段拦截）
        val last = runCatching { deviceRepository.getLastAutoConnectDevice() }.getOrNull()
        val lastEndpoint = last?.let { WifiEndpoint.parse(it.address) }
        if (lastEndpoint != null &&
            candidates.none { it.ipAddress == lastEndpoint.host }
        ) {
            // 历史 IP 需要先通过 PTP/IP Init Ack 确认，避免把已失效地址展示给用户。
            if (PtpIpProbe.probe(lastEndpoint, timeoutMs = 1000L, network = network)) {
                candidates.add(
                    WifiCameraCandidate(
                        lastEndpoint.host,
                        lastEndpoint.port,
                        last.deviceName.ifBlank { "尼康相机(历史)" },
                        "sta-history"
                    )
                )
            }
        }
        return candidates
    }

    /** B4：转发最近一次 WiFi 扫描统计（失败原因可见化的数据源） */
    val lastWifiScanStats: kotlinx.coroutines.flow.StateFlow<WifiScanner.ScanStats?>
        get() = wifiScanner.lastScanStats

    /**
     * 后台健康检查/启动时恢复最后配对的设备。
     */
    suspend fun reconnectLastDevice(): Boolean {
        return try {
            val device = deviceRepository.getLastAutoConnectDevice()
            if (device != null) {
                val endpoint = WifiEndpoint.parse(device.address)
                if (endpoint != null) {
                    connectToWifiCamera(
                        ipAddress = endpoint.host,
                        port = endpoint.port,
                        deviceName = device.deviceName,
                        caller = ConnFunnel.Caller.HEALTH_WORKER
                    )
                } else {
                    connectToDevice(device.address)
                }
                true
            } else {
                startScan()
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Reconnect to last device failed")
            false
        }
    }

    /**
     * 开始扫描设备
     */
    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    val discoveredDevices = bleManager.discoveredDevices

    /**
     * 监听 BLE 事件并驱动状态机
     */
    private fun observeBleEvents() {
        scope?.launch {
            bleManager.connectionState.collect { bleState ->
                when (bleState) {
                    com.nikonlink.app.device.ble.BleConnectionState.CONNECTED -> {
                        stateMachine.dispatch(ConnectionEvent.BleConnected)
                    }
                    com.nikonlink.app.device.ble.BleConnectionState.DISCONNECTED -> {
                        if (stateMachine.state.value != ConnectionState.DISCONNECTED && !userDisconnectRequested) {
                            stateMachine.dispatch(ConnectionEvent.BleDisconnected)
                        }
                    }
                    else -> {}
                }
            }
        }

        // BLE 连接失败（地址无效/GATT 错误）进入可恢复重试
        scope?.launch {
            bleManager.connectFailed.collect {
                Timber.tag(TAG).w("BLE connect failed, scheduling retry")
                stateMachine.dispatch(ConnectionEvent.ErrorOccurred("BLE 连接失败", recoverable = true))
            }
        }

        // BLE 配对阶段提示（相机端按 OK 后才会进入持续连接）
        scope?.launch {
            bleManager.pairingMessage.collect { message ->
                _connectionHint.value = message?.let {
                    ConnectionHint(it, kind = ConnectionHintKind.WAITING_FOR_CAMERA)
                }
            }
        }

        // 监听 WiFi 凭证（BLE 通道交换）
        scope?.launch {
            bleManager.wifiCredential.collect { credential ->
                Timber.tag(TAG).i("WiFi credential received, upgrading channel")
                upgradeToWifi(credential)
            }
        }

        // 监听新文件通知
        scope?.launch {
            bleManager.fileNotification.collect { notification ->
                Timber.tag(TAG).i("New file: ${notification.fileName} (${notification.fileSize} bytes)")
                // 如果有新文件且 WiFi 未连接，触发 WiFi 升级
                if (!wifiManager.isConnected()) {
                    requestWifiReconnect()
                }
                // 设置「自动下载新照片」开启时，同步最新照片入队
                transferManager.scheduleAutoSync()
            }
        }
    }

    /**
     * 通过 BLE 通知相机恢复 WiFi AP，同时用已缓存凭证在手机侧重连。
     */
    fun requestWifiReconnect() {
        scope?.launch {
            delay(WIFI_UPGRADE_DELAY_MS)
            if (bleManager.isConnected()) {
                bleManager.requestWifiReconnect()
            } else {
                Timber.tag(TAG).i("BLE not connected, trying cached WiFi directly")
            }
            if (!wifiManager.isConnected()) {
                wifiManager.reconnect()
            }
        }
    }

    /**
     * 等待 PTP 会话就绪，供传输页在 WiFi 通道建立后继续读取照片。
     */
    suspend fun awaitPtpSession(timeoutMs: Long = 20000L): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!ptpSession.isConnected()) {
                delay(250)
            }
            true
        } ?: false
    }

    /**
     * STA 主机注册（ZDROP 式）：在已建立的 PTP 会话内发送
     * PrepareHost(0x952B) / ConfirmHost(0x935A)，把本机 GUID 注册为相机信任主机。
     * 注册成功后，相机切 STA 模式才会放行 InitCommandRequest（否则会 InitFail 拒绝）。
     *
     * 前置校验：PTP 会话已连接（AP 模式连上相机后调用，由 UI 引导相机进入
     * 「连接至 PC」首次配置向导）。
     */
    suspend fun registerStaHost(
        onProgress: ((String) -> Unit)? = null
    ): HostRegistrationResult {
        if (!ptpSession.isConnected()) {
            return HostRegistrationResult.Failure(
                phase = "not_connected",
                detail = "相机未连接。请先通过 WiFi 连上相机（AP 模式），再执行 STA 主机注册"
            )
        }
        // v2.6.3 R2（ConnFlags.HOSTREG_PRECHECK）：发 0x952B 之前先看相机**当前模式**
        // 有没有把它列进 OperationsSupported。尼康的 SnapBridge AP 与主机配置向导都在
        // 192.168.1.1 提供 PTP/IP，但只有向导支持注册 —— 这是唯一能在**发命令之前**
        // 分辨两者的信号，省掉一次注定失败的往返和一句含糊的报错。
        // ops 为 null（没取到/解析失败）时不拦，按旧路径盲发。
        if (connFlags.isEnabled(ConnFlags.HOSTREG_PRECHECK)) {
            val ops = ptpSession.lastSupportedOperations
            if (ops != null && !ops.contains(PtpConstants.OP_NIKON_HOST_REGISTRATION_PREPARE)) {
                eventLogger.event("hostreg", "phase" to "precheck_blocked", "ops" to ops.size)
                return HostRegistrationResult.Failure(
                    phase = "precheck",
                    detail = "相机当前模式不支持主机注册（0x952B 不在它声明的能力列表里）。\n" +
                        "多数情况是相机没在 STA 主机配置向导里 —— 普通「连接至智能设备」的 " +
                        "WiFi 热点也能连上，但它不接受注册。\n" +
                        "请在相机上重新进入「Wi-Fi连接（STA mode）」或「连接至 PC」的网络设定向导，" +
                        "停在「正在等待连接 / 等待计算机」的画面后再点注册。"
                )
            }
        }

        val result = ptpSession.registerHost(onProgress)
        if (result is HostRegistrationResult.Success) {
            prefs.edit().putBoolean(PREFS_STA_HOST_REGISTERED, true).apply()
            _staHostRegistered.value = true
            _staRegisterNeeded.value = false
        }
        return result
    }

    /**
     * 监听 WiFi 事件
     */
    private fun observeWifiEvents() {
        scope?.launch {
            wifiManager.wifiState.collect { wifiState ->
                when (wifiState) {
                    com.nikonlink.app.device.wifi_ap.WifiChannelState.CONNECTED -> {
                        // WiFi 网络就绪后先建立 PTP 会话，避免网络恢复但相机未在线时误报已连接
                        stateMachine.dispatch(ConnectionEvent.WifiUpgradeRequested)
                        establishPtpSession()
                        transferManager.scheduleAutoSync()
                    }
                    com.nikonlink.app.device.wifi_ap.WifiChannelState.DISCONNECTED -> {
                        if (stateMachine.state.value == ConnectionState.FULLY_CONNECTED ||
                            stateMachine.state.value == ConnectionState.WIFI_UPGRADING) {
                            stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
                            // PRD 3.5: WiFi 断开，BLE 正常 → 通过 BLE 发送 WiFi 重连指令
                            if (bleManager.isConnected()) {
                                requestWifiReconnect()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // WiFi 网络丢失时尝试重连
        scope?.launch {
            wifiManager.networkLost.collect {
                Timber.tag(TAG).w("WiFi network lost, attempting recovery")
                if (pairedDeviceAddress?.startsWith("wifi:") == true) {
                    staNetworkLost = true
                    eventLogger.event("wifi_sta", "state" to "network_lost")
                    Timber.tag(TAG).i("STA camera link: network lost, waiting for network restore")
                }
                if (bleManager.isConnected()) {
                    requestWifiReconnect()
                }
            }
        }
    }

    /**
     * 相机主动拒绝握手（InitFail）说明 STA 门控未解除 → 提示需要做主机注册。
     * 只在收到 InitFail 时触发，网络类失败（无 WiFi / 相机不可达）不误报。
     *
     * **v2.6.3 R1（`ConnFlags.HOSTREG_HINT_V2`）**：原来**所有** InitFail 都算"需要注册"，
     * 但 PTP/IP 的三个 fail reason 语义完全不同 —— reason=1 是"机身唯一的客户端槽还被
     * 上一代会话占着"（该等，不该去注册），reason=3 是"本机已在这个连接里"
     * （已注册的证据）。一律当"需要注册"会把用户指向一个相机根本没在等待的向导，
     * 随后的 0x952B 必然被 0x201F 拒（2026-09-30 日志实证）。
     * 闸门关掉 = 回到"一律需要注册"的旧行为。
     */
    private fun observeHostRegistrationHint() {
        scope?.launch {
            ptpSession.lastInitFailReason.collect { reason ->
                if (reason == null) return@collect
                eventLogger.event("sta_hostreg_hint", "reason" to reason)
                val graded = connFlags.isEnabled(ConnFlags.HOSTREG_HINT_V2)
                when {
                    graded && reason == PtpConstants.INIT_FAIL_HOST_ALREADY_CONNECTED -> {
                        // 机身明确说"本机已连接" = 注册是生效的，把它记为已注册而不是待注册
                        _staHostRegistered.value = true
                        _staRegisterNeeded.value = false
                        prefs.edit().putBoolean(PREFS_STA_HOST_REGISTERED, true).apply()
                        eventLogger.event("sta_hostreg_grade", "grade" to "already_connected")
                    }

                    graded && reason == PtpConstants.INIT_FAIL_CONNECTION_IN_USE -> {
                        // 单槽被占：不动注册标记，等机身把上一代收干净后自动重试
                        _statusMessage.value =
                            "相机正忙：上一次的连接还没被机身收干净，稍等十几秒会自动重试"
                        eventLogger.event("sta_hostreg_grade", "grade" to "busy_wait")
                    }

                    else -> {
                        // 含 graded=false 的全部情况：维持"需要注册"的旧行为
                        _staRegisterNeeded.value = true
                        if (graded) {
                            eventLogger.event("sta_hostreg_grade", "grade" to "needs_register")
                        }
                        revokeStaleHostClaim(reason)
                    }
                }
                promoteAddressOnInitFail()
            }
        }
    }

    /**
     * 收到 InitFail 就把当前地址记为「已确认的真实相机 IP」（v2.6）。
     *
     * 为什么被拒也算确认：`InitFail` 是**机身自己发的 PTP/IP 包**。能收到它，
     * 就说明这个地址上确实有一台尼康相机在应答 —— 这比"TCP 端口开着"强得多，
     * 只是它不认本机这个客户端而已。
     *
     * 旧实现只在整条连接成功时才提升 [realCameraIp]（`:1015`），于是被拒的地址
     * 一律不被记住，App 重启后 `resolvePtpTarget` 回落到硬编码的 192.168.1.1。
     * 真机日志实证这次回落的代价：01:11:07 起对 192.168.1.1 / 192.168.0.1 /
     * 192.168.3.1 / 10.0.0.1 各探一次全部 TIMEOUT（`ap_probe grade=TIMEOUT`，
     * 源地址是蜂窝的 /10.56.31.140），随后 `socket_try err=TIMEOUT ms=30027` ——
     * 而相机其实一直在 10.184.219.14（手机自己的热点上，`in_subnet=true`）。
     * 40 秒纯浪费，且用户看到的是"连不上"。
     */
    private fun promoteAddressOnInitFail() {
        if (!connFlags.isEnabled(ConnFlags.IP_PROMOTE)) return
        val endpoint = pairedDeviceAddress?.let { WifiEndpoint.parse(it) } ?: return
        if (endpoint.host == DEFAULT_FALLBACK_HOST) return
        if (realCameraIp == endpoint.host) return
        realCameraIp = endpoint.host
        eventLogger.event("camera_ip_promoted", "host" to endpoint.host, "evidence" to "init_fail")
        Timber.tag(TAG).i("InitFail 证明 ${endpoint.host} 上就是相机，记为真实地址")
    }

    /**
     * FR-06：机身刚刚明确拒绝过本机，「已注册」就必须从「声称」退回「未确认」。
     *
     * 旧实现只在 ConfirmHost 回过 OK 时写一次永久布尔，之后无论被拒多少次都继续显示
     * 「已完成主机注册」—— 于是注册按钮既骗人，也让"STA 首连成功率"这件事无法解释。
     * 闸门关掉即回到那次性的旧行为（逐位等价）。
     */
    private fun revokeStaleHostClaim(reason: Int) {
        if (!connFlags.isEnabled(ConnFlags.HOSTREG_VERIFY)) return
        if (!_staHostRegistered.value) return
        _staHostRegistered.value = false
        prefs.edit().putBoolean(PREFS_STA_HOST_REGISTERED, false).apply()
        eventLogger.event("sta_hostreg_revoked", "reason" to reason)
        Timber.tag(TAG).i("STA 主机注册标记已撤销：相机回 InitFail(0x%x)", reason)
    }

    /**
     * STA 直连场景的网络监护：路由器断网后置标记，网络恢复即重建 PTP 会话。
     * 补原实现缺口（旧逻辑只覆盖 BLE+相机AP 场景，纯 WiFi 直连断网后无法自愈）。
     */
    private fun observeStaRecovery() {
        scope?.launch {
            wifiManager.networkAvailable.collect { network ->
                if (staNetworkLost && !userDisconnectRequested &&
                    pairedDeviceAddress?.startsWith("wifi:") == true &&
                    !connector.isActive
                ) {
                    staNetworkLost = false
                    eventLogger.event("wifi_sta", "state" to "network_restored")
                    Timber.tag(TAG).i("STA WiFi network restored, rebuilding PTP session over $network")
                    recoverWifiSession(network)
                }
            }
        }
    }

    /**
     * 监听状态机变化，更新 UI 状态
     */
    private fun observeStateMachine() {
        stateListener?.let { stateMachine.removeStateListener(it) }
        stateListener = { oldState, newState ->
            _statusMessage.value = when (newState) {
                ConnectionState.DISCONNECTED -> "未连接"
                ConnectionState.CONNECTING -> "正在连接..."
                ConnectionState.BLE_CONNECTED -> {
                    if (pairedDeviceAddress?.startsWith("wifi:") == true) {
                        "连接中断，正在恢复..."
                    } else {
                        "BLE 已连接"
                    }
                }
                ConnectionState.WIFI_UPGRADING -> "正在建立 WiFi 通道..."
                ConnectionState.FULLY_CONNECTED -> {
                    if (pairedDeviceAddress?.startsWith("wifi:") == true) {
                        "WiFi 已连接，相机确认成功"
                    } else {
                        "BLE + WiFi 已连接"
                    }
                }
                ConnectionState.ERROR_WAITING_RETRY -> "连接中断，正在重试..."
            }
            when (newState) {
                ConnectionState.DISCONNECTED -> connectedSince = null
                ConnectionState.BLE_CONNECTED,
                ConnectionState.FULLY_CONNECTED -> {
                    if (connectedSince == null) connectedSince = System.currentTimeMillis()
                }
                else -> {}
            }
        }
        stateMachine.addStateListener(stateListener!!)
        // v1.3.2（STA 反馈修复）：失败原因不再依赖状态变化。
        // 状态回调只在状态迁移时触发，而"WiFi 没连 / 相机不可达"这类失败往往在
        // 状态推进之前就发生 —— 旧版因此静默，UI 停在旧文案。事件级监听保证
        // 任何 ErrorOccurred 都会把原因写到状态行上。
        eventListener?.let { stateMachine.removeEventListener(it) }
        val listener: (ConnectionEvent) -> Unit = { event ->
            if (event is ConnectionEvent.ErrorOccurred) {
                // P2：小米/红米系 ROM 在连接失败时追加针对性引导。仅改提示文案，不改变任何
                // 连接 / 重试 / 绑定行为（RomDetector 只读 Build / 系统属性，且用 runCatching 包住）。
                _statusMessage.value = if (RomDetector.isXiaomi) {
                    "${event.message}\n${RomDetector.XIAOMI_HINT}"
                } else {
                    event.message
                }
                Timber.tag(TAG).w("Connection error surfaced to UI: ${_statusMessage.value}")
            }
        }
        eventListener = listener
        stateMachine.addEventListener(listener)
    }

    /**
     * PTP/IP 会话断开后自动恢复（WiFi 直连场景没有 BLE 兜底）。
     */
    private fun observePtpEvents() {
        scope?.launch {
            ptpSession.sessionState.collect { state ->
                if ((state == PtpSessionState.ERROR || state == PtpSessionState.DISCONNECTED) &&
                    !userDisconnectRequested &&
                    !connector.isActive && pairingJob?.isActive != true &&
                    stateMachine.state.value != ConnectionState.DISCONNECTED &&
                    pairedDeviceAddress?.startsWith("wifi:") == true
                ) {
                    Timber.tag(TAG).w("PTP session lost, attempting recovery")
                    stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
                    recoverWifiSession()
                }
            }
        }
    }

    /**
     * USB 有线通道独立于无线状态机运行；传输层始终优先使用 USB。
     */
    private fun observeUsbEvents() {
        scope?.launch {
            usbPtpManager.usbState.collect { state ->
                Timber.tag(TAG).d("USB state changed: $state")
                // v2.2（G12）：USB 也纳入同一条漏斗，别再各说各话
                if (state == UsbConnectionState.CONNECTING && !funnel.hasOngoing("USB")) {
                    // v2.6：USB 的 conn_attempt 此前一律 `caller=unknown`，
                    // 于是"用户插了一次线"和"UsbPtpManager 自己的退避循环重试了 8 轮"
                    // 在日志里长得一模一样 —— 而这两者的处置完全相反。
                    funnel.begin("USB", caller = ConnFunnel.Caller.USB_STATE.code)
                    funnel.stage(ConnFunnel.Stage.PREFLIGHT)
                } else if (state == UsbConnectionState.CONNECTED && funnel.hasOngoing("USB")) {
                    funnel.stage(ConnFunnel.Stage.READY)
                }
                if (state == UsbConnectionState.ERROR && funnel.hasOngoing("USB")) {
                    funnel.fail(
                        funnelReasonForUsbError(),
                        usbPtpManager.usbErrorMessage.value ?: "usb_error"
                    )
                }
            }
        }
    }

    /** 把 USB 的分类文案映射回统一原因码。 */
    private fun funnelReasonForUsbError(): ConnFunnel.Reason {
        // v2.6：优先用机器可读码。文案关键词匹配留作兜底 ——
        // 它对 `no_ptp_interface` / `claim_failed` 是对的（那两句提示里说的就是接口与占用），
        // 唯独 `session_open_failed` 会被误伤：那句提示是**给用户的建议**，
        // 里面写着"确认 USB 模式为 PTP/MTP"，于是命中 `contains("模式")`
        // 被判成 NO_USB_INTERFACE，而 `usb_open ok=true model=Z 50II` 已经证伪了它。
        when (usbPtpManager.usbFailCode.value) {
            "session_open_failed" -> return ConnFunnel.Reason.USB_PTP_NO_RESPONSE
            "no_ptp_interface" -> return ConnFunnel.Reason.NO_USB_INTERFACE
            "claim_failed" -> return ConnFunnel.Reason.CLAIM_FAILED
            "open_null" -> return ConnFunnel.Reason.NO_USB_INTERFACE
        }
        val msg = usbPtpManager.usbErrorMessage.value.orEmpty()
        return when {
            msg.contains("OTG") -> ConnFunnel.Reason.OTG_DISABLED
            msg.contains("权限") -> ConnFunnel.Reason.USB_PERMISSION_DENIED
            msg.contains("占用") || msg.contains("接口") -> ConnFunnel.Reason.CLAIM_FAILED
            msg.contains("模式") -> ConnFunnel.Reason.NO_USB_INTERFACE
            msg.contains("超时") -> ConnFunnel.Reason.SESSION_DROP
            else -> ConnFunnel.Reason.UNKNOWN
        }
    }

    /** 连接类失败码 → 统一原因码（不新增语义，只是收口）。 */
    private fun mapConnectorReason(reason: String): ConnFunnel.Reason = when {
        // FR-20④：BIND_FAILED 这个原因码自加入起就是**零调用点**的 dead branch ——
        // 「连了一张到不了相机的 WiFi」当时会被归进 NO_WIFI_NETWORK，
        // 于是把"换一张网"的场景指引到"去连 WiFi"那一句上。
        reason.contains("bind_failed") -> ConnFunnel.Reason.BIND_FAILED
        reason.contains("no_wifi_network") -> ConnFunnel.Reason.NO_WIFI_NETWORK
        // v2.6.6 FR-29①：改挂 CAMERA_UNREACHABLE。原来映射到 TCP_TIMEOUT，而那个码
        // 全仓零生产者 —— STA 最高频的失败因此在日志里长期写着"端口超时"，误导排查。
        reason.contains("camera_unreachable") -> ConnFunnel.Reason.CAMERA_UNREACHABLE
        // v2.6.6 FR-29②：这两类是"我们这轮没跑完"，不是相机的问题，必须能与 UNKNOWN 分开统计。
        reason.contains("loop_error") -> ConnFunnel.Reason.LOOP_ERROR
        reason.contains("round_budget_exhausted") -> ConnFunnel.Reason.ROUND_BUDGET_EXHAUSTED
        reason.contains("event_ack_timeout") -> ConnFunnel.Reason.EVENT_ACK_TIMEOUT
        // 争抢型必须先判：它是"相机把连接位给了别人"，不是"握手失败"，指引完全不同
        reason.contains("ptp_busy_other_client") -> ConnFunnel.Reason.PTP_BUSY_OTHER_CLIENT
        // "denied" 要在下面的 ptp_handshake 之前判，否则被它的 contains 抢先吃掉
        reason.contains("ptp_handshake_denied") -> ConnFunnel.Reason.PTP_INIT_REJECTED
        reason.contains("ptp_handshake") -> ConnFunnel.Reason.PTP_HANDSHAKE_FAILED
        reason.contains("invalid_endpoint") -> ConnFunnel.Reason.NOT_ON_CAMERA_AP
        reason.contains("not_started") -> ConnFunnel.Reason.AP_NOT_FOUND
        else -> ConnFunnel.Reason.UNKNOWN
    }

    private fun recoverWifiSession(network: android.net.Network? = null) {
        val endpoint = pairedDeviceAddress?.let { WifiEndpoint.parse(it) } ?: return
        // RC-1：恢复走 connector 单一入口，避免与配对循环并发双写会话
        if (connector.isActive) return

        recoveryJob = scope?.launch(Dispatchers.IO) {
            eventLogger.event("wifi_recover_start", "host" to endpoint.host, "port" to endpoint.port)
            connector.connect(
                endpoint = endpoint,
                mode = WifiDirectConnector.Mode.RESUME,
                // v2.6：这条路径**不经漏斗**，所以它砍掉正在退避重试的配对循环时
                // 原本一行日志都不留 —— 真机日志里 4 个 generation 全在 attempt 2~4
                // 被 sta_cancel，而 conn_result 只写着 superseded，查不出是谁干的。
                source = "recovery"
            )
            recoveryJob = null
        }
    }

    /**
     * 状态机进入 CONNECTING 时真正发起 BLE 连接（自动重连闭环）。
     */
    private fun observeReconnectTrigger() {
        scope?.launch {
            stateMachine.state.collect { state ->
                if (state == ConnectionState.CONNECTING && !userDisconnectRequested) {
                    val address = pairedDeviceAddress
                    if (address != null && address.startsWith("wifi:")) {
                        // Fix STA: 状态机重试闭环补齐 WiFi 直连分支（旧逻辑只重连 BLE）
                        if (!connector.isActive && pairingJob?.isActive != true && !ptpSession.isConnected()) {
                            // 地址解析统一走 WifiEndpoint（RC-3 收敛：全项目唯一解析入口）
                            val endpoint = WifiEndpoint.parse(address)
                            if (endpoint != null) {
                                Timber.tag(TAG).i("Auto-reconnecting to WiFi camera ${endpoint.display}")
                                connectToWifiCamera(
                                    endpoint.host,
                                    endpoint.port,
                                    caller = ConnFunnel.Caller.RECONNECT_TRIGGER
                                )
                            }
                        }
                    } else if (address != null &&
                        bleManager.connectionState.value != BleConnectionState.CONNECTED &&
                        bleManager.connectionState.value != BleConnectionState.CONNECTING
                    ) {
                        Timber.tag(TAG).i("Auto-reconnecting to $address")
                        bleManager.connect(address)
                    }
                }
            }
        }
    }

    /**
     * WiFi 通道升级
     * PRD 3.1: BLE→WiFi 通道升级
     */
    private suspend fun upgradeToWifi(credential: WifiCredential) {
        if (wifiManager.isConnected()) return
        if (wifiManager.wifiState.value == com.nikonlink.app.device.wifi_ap.WifiChannelState.CONNECTING) return
        stateMachine.dispatch(ConnectionEvent.WifiUpgradeRequested)
        // 设置页「5GHz 优先」开关：双频相机 AP 时优先连接 5GHz，失败自动回退 2.4GHz
        val prefer5G = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFS_WIFI_5G_PREFER, false)
        val success = wifiManager.connectToCamera(credential, preferBand5GHz = prefer5G)
        if (!success) {
            Timber.tag(TAG).w("WiFi upgrade failed, staying on BLE only")
            stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
        }
    }

    /**
     * 建立 PTP/IP 会话
     */
    private suspend fun establishPtpSession() {
        if (ptpSession.isConnected()) {
            // isConnected() 只是内存标志，半开 TCP（相机已休眠/链路已死）时仍为 true——
            // 旧版直接凭脏标志 dispatch WifiConnected，状态栏「未连接却显示已连接」的根因。
            // 现在先探活（DeviceReady 轻量命令），探不通就拆掉脏会话走完整建链。
            val alive = runCatching {
                ptpSession.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY).isOk
            }.getOrDefault(false)
            if (alive) {
                stateMachine.dispatch(ConnectionEvent.WifiConnected)
                return
            }
            Timber.tag(TAG).w("Stale PTP session detected (DeviceReady failed), rebuilding")
            runCatching { ptpSession.closeSession() }
        }
        // 等待 WiFi 网络稳定
        delay(500)
        wifiManager.bindToActiveWifi()

        // P0：目标地址分级取值，避免一直在默认地址（192.168.1.1）上空转。
        // 优先级：BLE 凭证 ipAddress → 本轮已发现真实相机 IP → 历史连接过且当前可达的 IP → 默认兜底
        val (host, port, source) = resolvePtpTarget()
        val isFallback = source == SOURCE_DEFAULT_FALLBACK
        Timber.tag(TAG).i("PTP target resolved host=$host port=$port source=$source")
        eventLogger.event(
            "ptp_target",
            "host" to host, "port" to port, "source" to source, "fallback" to isFallback
        )

        // 兜底地址（默认 192.168.1.1）：只试 1 轮（3 次）就收口，不让它跨 generation 空转，
        // 把时间让给真正的发现/扫描流程。一旦本轮发现过真实相机 IP，后续重试会优先用它（见 resolvePtpTarget）。
        // ⚠ 重要：AP 模式（手机连相机热点）下，192.168.1.1 就是相机的合法地址
        // （相机做热点时自任网关），不属于"盲猜兜底"。只有"未连上任何 WiFi"时该地址才是盲猜。
        // 若对 AP 模式也限制尝试次数，会在相机尚未就绪时过早放弃，反而更连不上。
        val apConnected = runCatching { wifiManager.isConnected() }.getOrDefault(false)
        if (isFallback && !apConnected) {
            repeat(3) { attempt ->
                val success = ptpSession.connect(host, port)
                if (success) {
                    Timber.tag(TAG).i("✓ PTP session ready (attempt ${attempt + 1})")
                    stateMachine.dispatch(ConnectionEvent.WifiConnected)
                    return
                }
                if (attempt < 2) delay(1500L * (attempt + 1))
            }
            Timber.tag(TAG).w("PTP fallback address exhausted, stopping retry to yield to discovery")
            stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
            return
        }

        // Fix STA/AP: 相机可能尚未就绪，连试 3 次再判定失败
        repeat(3) { attempt ->
            val success = ptpSession.connect(host, port)
            if (success) {
                // 真实地址建链成功 → 记录为已发现 IP，后续重试优先使用
                realCameraIp = host
                Timber.tag(TAG).i("✓ PTP session ready (attempt ${attempt + 1})")
                stateMachine.dispatch(ConnectionEvent.WifiConnected)
                return
            }
            if (attempt < 2) delay(1500L * (attempt + 1))
        }
        Timber.tag(TAG).w("PTP session failed, will retry on next WiFi reconnect")
        stateMachine.dispatch(ConnectionEvent.WifiDisconnected)
    }

    /**
     * P0：分级解析 PTP/IP 目标地址。
     *
     * 这是「连接目标选择」，不触碰 bindProcessToNetwork / requestNetwork 等网络绑定逻辑。
     * 优先级：
     *  1. BLE 凭证里的相机 IP（AP 模式 BLE 通道交换得到，最权威）
     *  2. 本轮已发现/确认过的真实相机 IP（[realCameraIp]，不退回默认）
     *  3. 历史连接过的相机 IP（发现结果来源），需当前可达（PTP/IP Init 探测通过）才采用
     *  4. 默认兜底 192.168.1.1（调用方只试 1 轮并收口）
     */
    private suspend fun resolvePtpTarget(): Triple<String, Int, String> {
        val credential = bleManager.wifiCredential.replayCache.firstOrNull()
        val credIp = credential?.ipAddress?.takeIf { it.isNotBlank() && it != DEFAULT_FALLBACK_HOST }
        val credPort = credential?.port ?: 15740

        // 优先级 1：BLE 凭证里的相机 IP
        if (credIp != null) {
            realCameraIp = credIp
            return Triple(credIp, credPort, SOURCE_BLE_CREDENTIAL)
        }
        // 优先级 2：本轮已发现/确认过的真实相机 IP（一旦拿到过，后续重试优先用它）
        if (!realCameraIp.isNullOrBlank()) {
            return Triple(realCameraIp!!, credPort, SOURCE_DISCOVERED)
        }
        // 优先级 2.5（v2.2 T-A2）：AP 模式下网关就是相机 —— 不依赖 BLE 凭证，
        // 也不依赖出厂地址猜测。学不到时（非 AP 场景 / 网关是路由器）继续往下走。
        if (connFlags.isEnabled(ConnFlags.AP_GATEWAY)) {
            val apConnected = runCatching { wifiManager.isConnected() }.getOrDefault(false)
            if (apConnected || apGatewayResolver.isOnCameraAp()) {
                val learned = runCatching {
                    apGatewayResolver.resolve(
                        network = wifiManager.getActiveNetwork(),
                        wifiNetwork = wifiManager.currentWifiNetwork()
                    )
                }.getOrNull()
                if (learned != null) {
                    realCameraIp = learned.host
                    Timber.tag(TAG).i("PTP target from AP gateway=${learned.host} stable=${learned.stable}")
                    return Triple(learned.host, learned.port, SOURCE_AP_GATEWAY)
                }
            }
        }
        // 优先级 3：历史连接过的相机 IP（发现结果来源），当前可达才采用
        val last = runCatching { deviceRepository.getLastAutoConnectDevice() }.getOrNull()
        val lastEndpoint = last?.let { WifiEndpoint.parse(it.address) }
        if (lastEndpoint != null && lastEndpoint.host != DEFAULT_FALLBACK_HOST) {
            val network = runCatching { wifiManager.currentWifiNetwork() }.getOrNull()
            if (PtpIpProbe.probe(lastEndpoint, timeoutMs = 800L, network = network)) {
                realCameraIp = lastEndpoint.host
                return Triple(lastEndpoint.host, lastEndpoint.port, SOURCE_DISCOVERED)
            }
        }
        // 兜底：硬编码默认地址（调用方只试 1 轮并收口，不再跨 generation 空转）
        return Triple(DEFAULT_FALLBACK_HOST, 15740, SOURCE_DEFAULT_FALLBACK)
    }

    /**
     * 定期更新连接指标
     * PRD 3.4: 连接状态可视化
     */
    private fun startMetricsUpdater() {
        scope?.launch {
            while (isActive) {
                delay(2000)
                _connectionMetrics.value = ConnectionMetrics(
                    bleRssi = bleManager.rssi.value,
                    wifiRssi = wifiManager.wifiRssi.value,
                    wifiFrequencyMhz = wifiManager.wifiFrequencyMhz.value,
                    lastHeartbeatTime = bleManager.lastHeartbeatAt.value,
                    connectionDuration = connectedSince?.let { System.currentTimeMillis() - it } ?: 0L,
                    activeChannels = buildSet {
                        if (bleManager.isConnected()) add(ChannelType.BLE)
                        if (wifiManager.isConnected() || ptpSession.isConnected()) add(ChannelType.WIFI)
                        if (usbPtpManager.isConnected()) add(ChannelType.USB)
                    },
                    reconnectCount = stateMachine.retryCount.value
                )
            }
        }
    }

    private suspend fun reconnectLastDeviceIfPaired() {
        try {
            val device = deviceRepository.getLastAutoConnectDevice()
            if (device != null && pairedDeviceAddress == null) {
                Timber.tag(TAG).i("Restoring connection to ${device.deviceName} [${device.address}]")
                val endpoint = WifiEndpoint.parse(device.address)
                if (endpoint != null) {
                    connectToWifiCamera(
                        ipAddress = endpoint.host,
                        port = endpoint.port,
                        deviceName = device.deviceName,
                        caller = ConnFunnel.Caller.RESTORE_PAIRED
                    )
                } else {
                    connectToDevice(device.address)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to restore last connection")
        }
    }

    /**
     * 获取 PTP 会话（供 feature 层使用）
     */
    fun getPtpSession(): PtpSessionManager = ptpSession

    fun isFullyConnected(): Boolean = stateMachine.state.value == ConnectionState.FULLY_CONNECTED
    fun isBleConnected(): Boolean = bleManager.isConnected()

    /**
     * 健康检查入口：WiFi 直连设备会话死亡时走 PTP 快速恢复，
     * 其余（BLE/未配对）走完整重连流程。
     */
    fun recoverLostLink() {
        val address = pairedDeviceAddress
        if (address != null && address.startsWith("wifi:") &&
            !connector.isActive && pairingJob?.isActive != true &&
            stateMachine.state.value != ConnectionState.DISCONNECTED
        ) {
            recoverWifiSession()
        } else {
            scope?.launch { reconnectLastDevice() }
        }
    }

    /**
     * 连接状态机与底层会话是否一致。
     * 用于健康检查，避免 UI 显示已连接但 PTP/IP 或 USB 会话实际已死亡。
     */
    fun isLinkHealthy(): Boolean {
        return when (stateMachine.state.value) {
            ConnectionState.FULLY_CONNECTED ->
                ptpSession.isConnected() || usbPtpManager.isConnected()
            ConnectionState.BLE_CONNECTED -> bleManager.isConnected()
            else -> true
        }
    }
}

/**
 * 连接向导提示类型
 */
enum class ConnectionHintKind {
    WAITING_FOR_CAMERA,
    PAIRING_COMPLETE
}

/**
 * 连接向导提示
 */
data class ConnectionHint(
    val message: String,
    val kind: ConnectionHintKind = ConnectionHintKind.WAITING_FOR_CAMERA
)

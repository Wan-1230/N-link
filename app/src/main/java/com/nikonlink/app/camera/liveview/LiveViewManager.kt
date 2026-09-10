package com.nikonlink.app.camera.liveview

import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppEventLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live View 实时取景管理器
 *
 * PRD 2.3 实时取景监看:
 * - 实时画面：相机 Live View 画面实时传输至手机屏幕 (P0)
 * - 触摸对焦：点击手机屏幕任意位置触发对焦点移动 (P0)
 * - 低延迟：端到端延迟目标 < 200ms (P0)
 * - 构图辅助：三分线、黄金分割、对角线、自定义网格 (P1)
 * - 直方图：实时 RGB 直方图 (P1)
 * - 画面放大：双指缩放 / 双击放大至 100% (P1)
 *
 * PRD 5.1 性能指标:
 * - 分辨率：最低 720p，推荐 1080p
 * - 帧率：≥ 30fps
 * - 延迟：< 200ms (WiFi 5GHz)
 * - 编码：H.264 / MJPEG
 */
@Singleton
class LiveViewManager @Inject constructor(
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "LiveViewMgr"
        private const val FRAME_INTERVAL_MS = 66L  // ~15fps，预留保活通道余量防断联
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** RC-9：StartLiveView 成功后等待相机完成切换（gphoto2 实测 ~250ms）再验帧 */
        private const val LV_START_SETTLE_MS = 300L

        /** RC-10：最多重试轮数，每轮都重发 0x9201 */
        private const val MAX_START_ATTEMPTS = 6

        /** ChangeAfArea(0x9205) 生效后再 AfDrive 的间隔 */
        private const val AF_AREA_SETTLE_MS = 150L
    }

    private var scope: CoroutineScope? = null
    private var frameJob: Job? = null
    private var consecutiveErrors = 0

    /**
     * 进行中的异步停止作业（0x9202 EndLiveView）。
     * stopLiveView 是 fire-and-forget，而 startLiveView 由另一协程发起——
     * kotlin Mutex 互斥但不保证 FIFO，End 可能晚于 Start 才到相机：
     * 相机先收到 Start（已在监看中）再收到 End（把监看关了），随后帧循环
     * 全部取帧失败 → 全屏后画面异常。startLiveView 必须 join 该作业再启动。
     */
    private var stopJob: Job? = null

    /**
     * 帧错误宽限期截止时间（epoch ms）。
     * 拍照（AF + 曝光 + 写卡）期间 0x9203 取不到帧是**正常现象**，
     * 遥控模式下机身处理更久；按默认阈值连续 5 次无帧（约 0.3~1s）就会
     * 误判「长时间无画面」自停监看——用户感知即「点快门后断连」。
     * 拍照入口调用 [grantFrameErrorGrace] 开启宽限，宽限期内错误不累计。
     */
    @Volatile
    private var errorGraceUntilMs = 0L

    /**
     * 「期望停流」标记（v1.3.0 需求 7）：录像期间置位。
     * 与 [errorGraceUntilMs] 的区别是**没有时长上限**，由 begin/end 显式控制，
     * 覆盖整段录像（时长由用户决定，无法预估）。
     */
    @Volatile
    private var expectedStall = false

    private val _liveViewState = MutableStateFlow(LiveViewState.STOPPED)
    val liveViewState: StateFlow<LiveViewState> = _liveViewState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 最新帧数据（JPEG/MJPEG） */
    private val _latestFrame = MutableSharedFlow<LiveViewFrame>(extraBufferCapacity = 2)
    val latestFrame: SharedFlow<LiveViewFrame> = _latestFrame.asSharedFlow()

    /** 帧率统计 */
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    /** 延迟统计 (ms) */
    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency.asStateFlow()

    /** 构图辅助线类型 */
    private val _gridOverlay = MutableStateFlow(GridOverlay.THIRDS)
    val gridOverlay: StateFlow<GridOverlay> = _gridOverlay.asStateFlow()

    /** 画面缩放比例 */
    private val _zoomLevel = MutableStateFlow(1.0f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private var frameCount = 0
    private var lastFpsTime = 0L

    /** 进入无线控制模式前的曝光程序模式，停止监看时恢复（wireless control mode exited） */
    private var savedExposureMode: ByteArray? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        Timber.tag(TAG).i("LiveViewManager started")
    }

    fun stop() {
        stopLiveView()
        scope = null
    }

    /**
     * 开启 Live View
     * PRD 2.3: 相机 Live View 画面实时传输至手机屏幕
     */
    suspend fun startLiveView(): Boolean {
        // 幂等：已在监看中直接成功。供全屏入口复用——遥控页与全屏页共用同一
        // LiveViewManager，监看进行中进全屏不重启，帧循环无缝延续。
        if (_liveViewState.value == LiveViewState.RUNNING) return true
        // 等待在途的 EndLiveView 收敛后再启动，杜绝 End/Start 到达相机的顺序颠倒
        stopJob?.let { runCatching { it.join() } }
        stopJob = null
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected()) {
            Timber.tag(TAG).w("PTP not connected")
            _liveViewState.value = LiveViewState.ERROR
            _errorMessage.value = "相机尚未连接，请先完成配对"
            return false
        }

        _liveViewState.value = LiveViewState.STARTING
        _errorMessage.value = null
        return withContext(Dispatchers.IO) {
            try {
                // 监看依赖无线控制模式（相机随后上报 DevicePropChanged prop=0x500E）。
                // 启动前先把曝光程序模式切到 Remote(0x8012)，失败不致命（相机可能已在遥控模式）
                if (!usbPtpManager.isConnected()) {
                    runCatching {
                        // 先备份当前曝光程序模式，停止监看时恢复
                        savedExposureMode = ptpSession.getDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_PROGRAM_MODE
                        )
                        val modeOk = ptpSession.setDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_PROGRAM_MODE,
                            byteArrayOf(
                                (PtpConstants.PROP_VALUE_REMOTE_MODE and 0xFF).toByte(),
                                ((PtpConstants.PROP_VALUE_REMOTE_MODE shr 8) and 0xFF).toByte()
                            )
                        )
                        Timber.tag(TAG).i("Wireless control mode 0x500E=0x8012 set: $modeOk")
                    }
                    delay(300)  // 等待相机完成模式切换
                }
                // 启动 LiveView 前先设置 Nikon 图像配置 0xD1AC=3，
                // 否则相机可能不输出 JPEG 帧
                runCatching {
                    val profileOk = onActiveChannel(
                        wifi = {
                            ptpSession.setDevicePropValue(
                                PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE,
                                byteArrayOf(3)
                            )
                        },
                        usb = {
                            usbPtpManager.setDevicePropValue(
                                PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE,
                                byteArrayOf(3)
                            )
                        }
                    )
                    if (profileOk) {
                        Timber.tag(TAG).i("LiveView image profile set 0xD1AC=3")
                    } else {
                        Timber.tag(TAG).w("LiveView image profile 0xD1AC=3 rejected (non-fatal)")
                    }
                }
                val success = startLiveViewCommand()
                if (success) {
                    _liveViewState.value = LiveViewState.RUNNING
                    consecutiveErrors = 0
                    // USB 监看期间暂停 DeviceReady 保活：帧流量本身即保活，
                    // 保活命令只会与帧竞争命令串行通道、拉高帧延迟
                    if (usbPtpManager.isConnected()) usbPtpManager.setKeepAlivePaused(true)
                    startFrameLoop()
                    Timber.tag(TAG).i("✓ Live View started")
                } else {
                    // 全链路优化: 启动失败时恢复原曝光模式，
                    // 避免相机卡在无线控制模式导致后续下载被拒绝
                    usbPtpManager.setKeepAlivePaused(false)
                    restoreExposureModeIfNeeded()
                    Timber.tag(TAG).e("Failed to start Live View")
                    _liveViewState.value = LiveViewState.ERROR
                    _errorMessage.value =
                        "实时取景启动失败，请检查相机模式（P/A/S/M）并重试"
                }
                success
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Start Live View error")
                _liveViewState.value = LiveViewState.ERROR
                _errorMessage.value = "实时取景启动异常：${e.message ?: "未知错误"}"
                false
            }
        }
    }

    /**
     * RC-12 通道选择：USB 已连接且链路判活才走 USB，否则一律回退 WiFi。
     * 避免相机假在线（TCP 在但命令无响应）时把命令打进黑洞。
     */
    private suspend fun <T> onActiveChannel(
        wifi: suspend () -> T,
        usb: suspend () -> T
    ): T {
        return if (usbPtpManager.isConnected() && usbPtpManager.isAlive()) usb() else wifi()
    }

    /**
     * RC-9/10/11 闭环启动：
     * 1) 先读 0xD1A4 禁止条件位图——只记录与提示，不阻断（位含义无权威定义）；
     * 2) 每一轮都重发 0x9201（相机假成功/状态回退后，重发才有效）；
     * 3) 返回成功后等 300ms 再拉一帧 0x9203 验真，探测失败不计入 consecutiveErrors；
     * 4) DeviceBusy 时用 DeviceReady 等待，最多 MAX_START_ATTEMPTS 轮。
     */
    private suspend fun startLiveViewCommand(): Boolean {
        readProhibitCondition()?.let { desc ->
            eventLogger.event("lv_prohibit", "reason" to desc)
            _errorMessage.value = "相机提示：$desc"
        }

        repeat(MAX_START_ATTEMPTS) { attempt ->
            val (ok, code) = startLiveViewResult()
            when {
                ok -> {
                    delay(LV_START_SETTLE_MS)
                    if (probeLiveViewFrame()) {
                        eventLogger.event("lv_probe", "ok" to true, "attempt" to attempt)
                        return true
                    }
                    // 假成功：下一轮重发 0x9201
                    eventLogger.event("lv_probe", "ok" to false, "attempt" to attempt)
                }
                code < 0 -> return false
                code != PtpConstants.RESPONSE_DEVICE_BUSY &&
                    code != PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW -> {
                    eventLogger.event("lv_start", "ok" to false, "code" to code, "attempt" to attempt)
                    Timber.tag(TAG).w("StartLiveView rejected: 0x${code.toString(16)}")
                    return false
                }
                else -> {
                    eventLogger.event("lv_start_retry", "attempt" to attempt, "code" to code)
                    waitDeviceReady(attempt)
                }
            }
        }
        return false
    }

    /**
     * 读 0xD1A4 LiveView 禁止条件位图（只读、不阻断）。
     * 返回 null 表示读取失败或无已知含义。
     */
    private suspend fun readProhibitCondition(): String? {
        return try {
            val data = onActiveChannel(
                wifi = { ptpSession.getDevicePropValue(PtpConstants.PROP_NIKON_LV_PROHIBIT_CONDITION) },
                usb = { usbPtpManager.getDevicePropValue(PtpConstants.PROP_NIKON_LV_PROHIBIT_CONDITION) }
            ) ?: return null
            if (data.size < 4) return null
            val value = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            PtpConstants.describeProhibitCondition(value)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * RC-11 假成功验证：真正拉一帧 0x9203。
     * 失败不计入 consecutiveErrors（帧循环的错误计数只管运行期）。
     */
    private suspend fun probeLiveViewFrame(): Boolean {
        return try {
            fetchFrame()?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchFrame(): ByteArray? = onActiveChannel(
        wifi = { ptpSession.getLiveViewImage() },
        usb = { usbPtpManager.getLiveViewImage() }
    )

    /**
     * DeviceBusy 时等待相机就绪（只等待，不判定成功；成功与否由调用方重发 0x9201 验证）。
     */
    private suspend fun waitDeviceReady(fromAttempt: Int) {
        repeat(10) {
            delay(250)
            val (readyOk, readyCode) = deviceReadyResult()
            if (readyOk) {
                Timber.tag(TAG).i("DeviceReady after wait (attempt=$fromAttempt)")
                return
            }
            if (readyCode != -1 && readyCode != PtpConstants.RESPONSE_DEVICE_BUSY) {
                Timber.tag(TAG).w("DeviceReady code=0x${readyCode.toString(16)} (attempt=$fromAttempt)")
                return
            }
        }
    }

    private suspend fun startLiveViewResult(): Pair<Boolean, Int> {
        return onActiveChannel(
            wifi = {
                val response = ptpSession.sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
                Pair(response.isOk, response.responseCode)
            },
            usb = {
                val response = usbPtpManager.sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
                if (response == null) Pair(false, -1) else Pair(response.isOk, response.responseCode)
            }
        )
    }

    private suspend fun deviceReadyResult(): Pair<Boolean, Int> {
        return onActiveChannel(
            wifi = {
                val response = ptpSession.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
                Pair(response.isOk, response.responseCode)
            },
            usb = {
                val response = usbPtpManager.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
                if (response == null) Pair(false, -1) else Pair(response.isOk, response.responseCode)
            }
        )
    }

    /**
     * 停止 Live View
     */
    fun stopLiveView() {
        frameJob?.cancel()
        frameJob = null
        _errorMessage.value = null
        // 无论此前走哪条通道，恢复 USB 保活（无 USB 连接时该调用无副作用）
        usbPtpManager.setKeepAlivePaused(false)
        if (_liveViewState.value == LiveViewState.RUNNING) {
            // 记录停止作业：startLiveView 会 join 它，保证 End 先于下一次 Start 到相机
            stopJob = scope?.launch(Dispatchers.IO) {
                try {
                    val ok = onActiveChannel(
                        wifi = {
                            val wifiOk = ptpSession.stopLiveView()
                            if (!wifiOk) eventLogger.event("lv_stop_fail", "channel" to "wifi")
                            wifiOk
                        },
                        usb = { usbPtpManager.stopLiveView() }
                    )
                    if (!ok) Timber.tag(TAG).w("stopLiveView returned false")
                } catch (_: Exception) {}
            }
        }
        _liveViewState.value = LiveViewState.STOPPED
        _fps.value = 0
        // 停止监看后退出无线控制模式，恢复原曝光程序模式
        restoreExposureModeIfNeeded()
        Timber.tag(TAG).i("Live View stopped")
    }

    /**
     * 监看未运行时启动之；已运行直接返回成功。
     * 供视频录制等依赖实时取景的功能前置调用（Nikon 0x920A 需要 LiveView 处于开启状态）。
     * STARTING 状态下等待进行中的启动收敛后再判定，避免并发重复启动。
     */
    suspend fun ensureRunning(): Boolean {
        when (_liveViewState.value) {
            LiveViewState.RUNNING -> return true
            LiveViewState.STARTING -> {
                repeat(40) {
                    delay(100)
                    when (_liveViewState.value) {
                        LiveViewState.RUNNING -> return true
                        LiveViewState.STOPPED, LiveViewState.ERROR -> return startLiveView()
                        else -> {}
                    }
                }
                return _liveViewState.value == LiveViewState.RUNNING
            }
            else -> return startLiveView()
        }
    }

    /** 恢复进入无线控制模式前的曝光程序模式（停止/启动失败时调用） */
    private fun restoreExposureModeIfNeeded() {
        val restore = savedExposureMode
        savedExposureMode = null
        if (restore != null && !usbPtpManager.isConnected()) {
            scope?.launch(Dispatchers.IO) {
                runCatching {
                    // 守卫：监看期间用户可能已远程切换了模式（0x500E 可写），
                    // 仅当相机仍停留在遥控值 0x8012 时才回退，避免覆盖用户选择
                    val current = ptpSession.getDevicePropValue(
                        PtpConstants.PROP_EXPOSURE_PROGRAM_MODE
                    )
                    val stillRemote = current != null && current.size >= 2 &&
                        ((current[0].toInt() and 0xFF) or
                            ((current[1].toInt() and 0xFF) shl 8)) ==
                        PtpConstants.PROP_VALUE_REMOTE_MODE
                    if (stillRemote) {
                        ptpSession.setDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, restore
                        )
                        Timber.tag(TAG).i("Wireless control mode exited, exposure mode restored")
                    } else {
                        Timber.tag(TAG).i("Exposure mode changed during LV, skip restore")
                    }
                }
            }
        }
    }

    /**
     * 帧获取循环
     * PRD 5.1: 帧率 ≥ 30fps, 延迟 < 200ms
     */
    private fun startFrameLoop() {
        frameJob?.cancel()
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()

        frameJob = scope?.launch(Dispatchers.IO) {
            while (isActive && _liveViewState.value == LiveViewState.RUNNING) {
                val frameStart = System.currentTimeMillis()

                try {
                    val imageData = fetchFrame()
                    if (imageData != null && imageData.isNotEmpty()) {
                        val latency = System.currentTimeMillis() - frameStart
                        _latency.value = latency

                        val frame = LiveViewFrame(
                            data = imageData,
                            timestamp = frameStart,
                            latencyMs = latency
                        )
                        _latestFrame.tryEmit(frame)
                        consecutiveErrors = 0

                        // FPS 计算
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            _fps.value = frameCount
                            frameCount = 0
                            lastFpsTime = now
                        }
                    } else {
                        handleFrameFailure(null)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    // 取帧超时：多数是链路拥塞或相机瞬时忙碌，属可自愈的软错误
                    Timber.tag(TAG).w("Frame timeout: ${e.message}")
                    handleFrameFailure(e)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Frame error: ${e.message}")
                    handleFrameFailure(e)
                }

                // 帧间隔控制（避免过度请求）
                val elapsed = System.currentTimeMillis() - frameStart
                if (elapsed < FRAME_INTERVAL_MS) {
                    delay(FRAME_INTERVAL_MS - elapsed)
                }
            }
        }
    }

    /**
     * 开启帧错误宽限期：durationMs 内取帧失败不累计 consecutiveErrors。
     * 拍照入口（capture）调用——AF/曝光/写卡期间无监看帧是正常现象，
     * 不得据此判定「长时间无画面」而自停监看。
     */
    fun grantFrameErrorGrace(durationMs: Long = 6000L) {
        errorGraceUntilMs = System.currentTimeMillis() + durationMs
    }

    /**
     * 进入「期望停流」状态（v1.3.0 需求 7：视频模式 + 联动画面下录像期间保持画面）。
     *
     * **背景**：联动画面（相机屏同显）下开始录像时，机身会转入录像应用模式
     * （0x9435 ChangeApplicationMode）并**停止向 PTP 推送 LV 帧**。旧版把这种
     * 预期内的停流当成链路故障，连续 5 次取帧失败即判 ERROR 并 cancel 帧循环，
     * 且没有任何重连 —— 用户看到的就是"点开始录像 → 画面断开，但快门还能按"。
     *
     * **做法**：整段录像期间不再累计取帧错误（等价于一个时长无上限的宽限），
     * 停止录像时由 [endExpectedStall] 退出。机身在录像期间仍出帧的机型不受影响
     * （照常有画面）；真的断链仍会被 [isWifiLinkDead] 的确定性判定立即收口。
     */
    fun beginExpectedStall(reason: String) {
        expectedStall = true
        errorGraceUntilMs = System.currentTimeMillis() + 2000L
        Timber.tag(TAG).i("Live view expected stall ON ($reason)")
    }

    /** 退出「期望停流」状态（停止录像后调用） */
    fun endExpectedStall() {
        if (!expectedStall) return
        expectedStall = false
        Timber.tag(TAG).i("Live view expected stall OFF")
    }

    /**
     * 取帧失败处理。
     *
     * 断连体感的根因之一：旧实现不分错误类型一律累加 [consecutiveErrors]，
     * 达到 5 次才停。而单次 `getLiveViewImage` 的命令超时是 10s，
     * 链路其实早就断了，界面却还要空转约 50 秒才弹出错误提示。
     *
     * 现在分三级（优先级从高到低）：
     * - **链路已死**（PTP 会话进入 ERROR）：立即停止并给出「连接已断开」提示，
     *   把控制权交还重连流程，一次多余的重试都不做。这是确定性信号，
     *   优先于宽限期——拍照途中真断链也应立即收口，不该再干等 6 秒；
     * - **宽限期内**（拍照 AF/曝光/写卡期间无帧属正常）：不累计错误，
     *   但仍节流重试，避免失败快速返回形成紧密轮询加重命令通道负担；
     * - **链路还在**（偶发超时/相机忙碌）：按 [MAX_CONSECUTIVE_ERRORS] 累计，
     *   保持原有「抖动自愈」能力，不会因一两次超时就掐断监看。
     */
    private suspend fun handleFrameFailure(cause: Throwable?) {
        if (isWifiLinkDead()) {
            Timber.tag(TAG).w("Link already dead, stop live view immediately")
            eventLogger.event(
                "lv_stop",
                "reason" to "link_dead",
                "errors" to consecutiveErrors,
                "cause" to (cause?.javaClass?.simpleName ?: "empty_frame")
            )
            _liveViewState.value = LiveViewState.ERROR
            _errorMessage.value = "与相机的连接已断开，请返回重连后再开启监看"
            frameJob?.cancel()
            return
        }
        // 宽限期内（拍照等已知会暂停出帧的场景）错误不累计，但仍节流重试；
        // expectedStall = 录像期间（联动画面下机身停推 LV 帧属预期），同样不累计
        if (expectedStall || System.currentTimeMillis() < errorGraceUntilMs) {
            delay(100)
            return
        }
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Timber.tag(TAG).e("Too many consecutive frame errors, stopping Live View")
            eventLogger.event("lv_stop", "reason" to "max_errors", "errors" to consecutiveErrors)
            _liveViewState.value = LiveViewState.ERROR
            _errorMessage.value = "实时取景长时间无画面，已自动停止，请重试"
            // 帧循环自停路径不走 stopLiveView()，保活需在此恢复
            usbPtpManager.setKeepAlivePaused(false)
            frameJob?.cancel()
        } else {
            delay(100)  // 短暂等待后重试
        }
    }

    /** WiFi(PTP/IP) 通道是否已被判死；USB 通道不受此判定影响。 */
    private fun isWifiLinkDead(): Boolean {
        if (usbPtpManager.isConnected()) return false
        return ptpSession.isLinkDead()
    }

    // ==================== 触摸对焦 ====================

    /**
     * 触摸对焦
     * PRD 2.3: 点击手机屏幕任意位置触发对焦点移动
     * @param x 归一化 X 坐标 (0.0 ~ 1.0)
     * @param y 归一化 Y 坐标 (0.0 ~ 1.0)
     */
    suspend fun touchFocus(x: Float, y: Float): Boolean {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected()) return false
        return withContext(Dispatchers.IO) {
            try {
                // 触摸对焦坐标范围约 x:0~4000, y:0~3000，
                // 将归一化坐标映射到该范围
                val afX = (x * PtpConstants.AF_COORD_MAX_X).toInt()
                    .coerceIn(0, PtpConstants.AF_COORD_MAX_X)
                val afY = (y * PtpConstants.AF_COORD_MAX_Y).toInt()
                    .coerceIn(0, PtpConstants.AF_COORD_MAX_Y)

                val response = onActiveChannel(
                    wifi = {
                        ptpSession.sendCommand(
                            PtpConstants.OP_NIKON_CHANGE_AF_AREA,
                            listOf(afX, afY)
                        ).isOk
                    },
                    usb = { usbPtpManager.changeAfArea(afX, afY) }
                )

                if (response) {
                    // 触发 AF 驱动：稍等 AF 区域生效后再驱动，部分机型立刻驱动会仍按旧区域对焦
                    delay(AF_AREA_SETTLE_MS)
                    onActiveChannel(
                        wifi = { ptpSession.afDrive() },
                        usb = { usbPtpManager.afDrive() }
                    )
                    Timber.tag(TAG).d("Touch focus: ($afX, $afY)")
                }
                response
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Touch focus failed")
                false
            }
        }
    }

    /**
     * 手动对焦驱动
     * @param direction 1=远端, -1=近端
     * @param speed 驱动速度 1-3
     */
    suspend fun manualFocusDrive(direction: Int, speed: Int = 2): Boolean {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected()) return false
        return withContext(Dispatchers.IO) {
            try {
                onActiveChannel(
                    wifi = {
                        ptpSession.sendCommand(
                            PtpConstants.OP_NIKON_MF_DRIVE,
                            listOf(direction, speed)
                        ).isOk
                    },
                    usb = { usbPtpManager.manualFocusDrive(direction, speed) }
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "MF drive failed")
                false
            }
        }
    }

    // ==================== 构图辅助 ====================

    fun setGridOverlay(grid: GridOverlay) {
        _gridOverlay.value = grid
    }

    fun cycleGridOverlay() {
        val values = GridOverlay.entries
        val nextIdx = (values.indexOf(_gridOverlay.value) + 1) % values.size
        _gridOverlay.value = values[nextIdx]
    }

    // ==================== 画面缩放 ====================

    fun setZoom(level: Float) {
        _zoomLevel.value = level.coerceIn(1.0f, 5.0f)
    }

    fun zoomIn() {
        _zoomLevel.value = (_zoomLevel.value + 0.5f).coerceAtMost(5.0f)
    }

    fun zoomOut() {
        _zoomLevel.value = (_zoomLevel.value - 0.5f).coerceAtLeast(1.0f)
    }

    fun resetZoom() {
        _zoomLevel.value = 1.0f
    }

    fun isRunning(): Boolean = _liveViewState.value == LiveViewState.RUNNING}

/**
 * Live View 状态
 */
enum class LiveViewState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * 单帧数据
 */
data class LiveViewFrame(
    val data: ByteArray,
    val timestamp: Long,
    val latencyMs: Long
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * 构图辅助线类型
 * PRD 2.3: 三分线、黄金分割、对角线、自定义网格
 */
enum class GridOverlay(val displayName: String) {
    NONE("无"),
    THIRDS("三分线"),
    GOLDEN_RATIO("黄金分割"),
    DIAGONAL("对角线"),
    GRID_4X4("4x4 网格")
}

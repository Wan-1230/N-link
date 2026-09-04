package com.nikonlink.app.capture

import com.nikonlink.app.camera.liveview.LiveViewManager
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 遥控拍摄管理器
 *
 * PRD 2.2 远程遥控拍摄:
 * - 远程快门：手机触屏触发快门，支持半按对焦 + 全按拍摄 (P0)
 * - 定时拍摄：自定义倒计时 2s/5s/10s/自定义 (P1)
 * - 间隔拍摄：设定间隔时间 + 拍摄张数 (P1)
 * - B 门遥控：长按开始曝光，松开结束 (P1)
 * - 连拍模式：远程触发连拍 (P2)
 * - 视频录制：远程开始/停止 (P2)
 *
 * PRD 5.1: 遥控快门延迟 < 150ms
 */
@Singleton
class RemoteShootingManager @Inject constructor(
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val liveViewManager: LiveViewManager
) {
    companion object {
        private const val TAG = "RemoteShooting"

        /** 快门前对焦等待时长：点按对焦耗时约 1.2~1.5s，取 1200ms 保证合焦 */
        private const val AF_SETTLE_MS = 1200L

        /** 长按对焦重触发间隔：必须大于单次 AF Drive 对焦周期，
         *  否则会打断相机正在进行的对焦（旧值 600ms 导致对焦反复中断） */
        private const val AF_HOLD_INTERVAL_MS = 1200L

        /** 录像启动前的监看收敛等待：StartLiveView 验帧成功后画面仍需短暂稳定 */
        private const val VIDEO_LV_SETTLE_MS = 800L

        /** 录像命令 DeviceBusy 时的重试上限 */
        private const val VIDEO_BUSY_RETRIES = 3
    }

    private var scope: CoroutineScope? = null
    private var intervalJob: Job? = null
    private var timerJob: Job? = null
    private var focusJob: Job? = null
    private var bulbStartTime = 0L

    private val _shootingState = MutableStateFlow(ShootingState.IDLE)
    val shootingState: StateFlow<ShootingState> = _shootingState.asStateFlow()

    /**
     * 拍摄动作的用户可读结果（失败原因 / 前置动作提示）。
     * UI 收到后以 Toast 呈现并随即消费；null 表示无可展示内容。
     */
    private val _shootingMessage = MutableStateFlow<String?>(null)
    val shootingMessage: StateFlow<String?> = _shootingMessage.asStateFlow()

    fun consumeMessage() {
        _shootingMessage.value = null
    }

    /** 拍摄计数器 */
    private val _shotCount = MutableStateFlow(0)
    val shotCount: StateFlow<Int> = _shotCount.asStateFlow()

    /** 间隔拍摄进度 */
    private val _intervalProgress = MutableStateFlow(IntervalProgress())
    val intervalProgress: StateFlow<IntervalProgress> = _intervalProgress.asStateFlow()

    /** B门已曝光时长 (ms) */
    private val _bulbExposureTime = MutableStateFlow(0L)
    val bulbExposureTime: StateFlow<Long> = _bulbExposureTime.asStateFlow()

    /** 定时器倒计时 */
    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown: StateFlow<Int> = _timerCountdown.asStateFlow()

    /**
     * 拍摄完成事件（优化项 3：相册自动同步的触发源）。
     *
     * 载荷 = 完成时刻（[System.currentTimeMillis]），订阅方可据此去重。
     * 单张 / 定时 / 间隔 / B 门四条路径最终都收敛到 [capture] 或 [bulbStop]，
     * 所以订阅者监听这一个流即可覆盖所有出片场景，无需逐处埋点。
     *
     * 用 [MutableSharedFlow] 而不是 Channel：
     * - 支持多个订阅者（相册 ViewModel 等）同时监听；
     * - 无人订阅时 [tryEmit] 直接丢弃，不会像 Channel 那样堆积或挂起发射方，
     *   拍摄主流程永远不会被"没人接事件"这件事阻塞。
     */
    private val _captureEvents = MutableSharedFlow<Long>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val captureEvents: SharedFlow<Long> = _captureEvents.asSharedFlow()

    /** 相机剩余存储空间 */
    private val _remainingShots = MutableStateFlow(-1)
    val remainingShots: StateFlow<Int> = _remainingShots.asStateFlow()

    /** 相机电池电量 */
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    fun start(scope: CoroutineScope) {
        this.scope = scope
        Timber.tag(TAG).i("RemoteShootingManager started")
    }

    fun stop() {
        cancelInterval()
        cancelTimer()
        scope = null
    }

    // ==================== 远程快门 ====================

    /**
     * 半按对焦
     * PRD 2.2: 支持半按对焦 + 全按拍摄
     */
    suspend fun halfPressFocus(): Boolean {
        if (!isRemoteReady()) return false
        return withContext(Dispatchers.IO) {
            try {
                if (usbPtpManager.isConnected()) {
                    usbPtpManager.afDrive()
                } else {
                    ptpSession.afDrive()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Half press focus failed")
                false
            }
        }
    }

    /**
     * 全按拍摄（单张）
     * PRD 5.1: 遥控快门延迟 < 150ms（指对焦完成后的快门响应）
     * 拍照请求 autofocus=true 时先触发 AF，对焦完成后再释放快门
     * Fix: 旧版发完 AF_DRIVE 立即释放快门导致无法合焦；
     * 现改为触发对焦 → 等待 AF_SETTLE_MS 对焦收敛 → 再释放快门
     */
    suspend fun capture(autofocus: Boolean = true, focusWaitMs: Long = AF_SETTLE_MS): Boolean {
        if (!isRemoteReady()) return false
        return withContext(Dispatchers.IO) {
            try {
                _shootingState.value = ShootingState.CAPTURING
                if (autofocus) {
                    // 第一步: 触发 AF 对焦
                    runCatching { halfPressFocus() }
                    // 第二步: 等待对焦收敛（对焦未完成不释放快门，确保合焦拍摄）
                    Timber.tag(TAG).d("Waiting ${focusWaitMs}ms for AF to settle")
                    delay(focusWaitMs)
                }
                // 第三步: 释放快门
                val success = if (usbPtpManager.isConnected()) {
                    usbPtpManager.capture()
                } else {
                    ptpSession.initiateCapture()
                }
                if (success) {
                    _shotCount.value++
                    // 通知相册自动同步（优化项 3）
                    _captureEvents.tryEmit(System.currentTimeMillis())
                    Timber.tag(TAG).i("Capture success (total: ${_shotCount.value})")
                }
                _shootingState.value = ShootingState.IDLE
                success
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Capture failed")
                _shootingState.value = ShootingState.IDLE
                false
            }
        }
    }

    /**
     * 长按持续对焦（模拟实体对焦按键：按住-对焦-松开-停止）。
     * Fix: 旧版每 600ms 重发 AF_DRIVE，短于单次对焦周期，
     * 会不断打断相机正在进行的对焦，表现为“对焦刚开始就停了”。
     * 新版: 按下时触发一次对焦，之后每 AF_HOLD_INTERVAL_MS（一个完整对焦周期后）
     * 才重新触发，保证按住期间对焦连续不中断；松开时发 AF_DRIVE_CANCEL 停止。
     */
    fun startContinuousFocus() {
        if (focusJob?.isActive == true) return
        focusJob = scope?.launch {
            // 按下立即启动对焦
            try {
                if (usbPtpManager.isConnected()) usbPtpManager.afDrive()
                else ptpSession.afDrive()
                Timber.tag(TAG).i("Continuous AF: initial drive sent")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Continuous AF initial drive failed")
            }
            // 按住期间: 等上一轮对焦完成后再重新触发，保持对焦连续
            while (isActive && isRemoteReady()) {
                delay(AF_HOLD_INTERVAL_MS)
                if (!isActive) break
                try {
                    if (usbPtpManager.isConnected()) usbPtpManager.afDrive()
                    else ptpSession.afDrive()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Continuous AF re-drive failed")
                }
            }
        }
        Timber.tag(TAG).i("Continuous focus started")
    }

    fun stopContinuousFocus() {
        focusJob?.cancel()
        focusJob = null
        scope?.launch {
            runCatching {
                if (usbPtpManager.isConnected()) usbPtpManager.afDriveCancel()
                else ptpSession.afDriveCancel()
            }
        }
        Timber.tag(TAG).i("Continuous focus stopped")
    }

    // ==================== 定时拍摄 ====================

    /**
     * 定时拍摄
     * PRD 2.2: 自定义倒计时（2s/5s/10s/自定义）
     */
    fun startTimerCapture(delaySeconds: Int) {
        cancelTimer()
        _shootingState.value = ShootingState.TIMER_COUNTDOWN
        _timerCountdown.value = delaySeconds

        timerJob = scope?.launch {
            for (i in delaySeconds downTo 1) {
                _timerCountdown.value = i
                delay(1000)
            }
            _timerCountdown.value = 0
            capture()
            _shootingState.value = ShootingState.IDLE
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerCountdown.value = 0
        if (_shootingState.value == ShootingState.TIMER_COUNTDOWN) {
            _shootingState.value = ShootingState.IDLE
        }
    }

    // ==================== 间隔拍摄 ====================

    /**
     * 间隔拍摄（延时摄影）
     * PRD 2.2: 设定间隔时间 + 拍摄张数，支持长时间延时摄影
     */
    fun startIntervalCapture(config: IntervalConfig) {
        cancelInterval()
        _shootingState.value = ShootingState.INTERVAL_SHOOTING
        _intervalProgress.value = IntervalProgress(
            totalShots = config.totalShots,
            completedShots = 0,
            intervalMs = config.intervalMs
        )

        intervalJob = scope?.launch {
            for (i in 1..config.totalShots) {
                if (!isActive) break

                val success = capture()
                _intervalProgress.value = _intervalProgress.value.copy(
                    completedShots = i,
                    lastShotSuccess = success
                )

                Timber.tag(TAG).i("Interval shot $i/${config.totalShots}: ${if (success) "OK" else "FAIL"}")

                // 等待间隔时间（最后一张不用等）
                if (i < config.totalShots) {
                    delay(config.intervalMs)
                }
            }
            _shootingState.value = ShootingState.IDLE
            Timber.tag(TAG).i("Interval shooting completed")
        }
    }

    fun cancelInterval() {
        intervalJob?.cancel()
        intervalJob = null
        if (_shootingState.value == ShootingState.INTERVAL_SHOOTING) {
            _shootingState.value = ShootingState.IDLE
        }
    }

    // ==================== B 门遥控 ====================

    /**
     * B 门开始曝光
     * PRD 2.2: 长按开始曝光，松开结束，显示已曝光时长
     */
    suspend fun bulbStart(): Boolean {
        if (!isRemoteReady()) return false
        return withContext(Dispatchers.IO) {
            try {
                val ok = if (usbPtpManager.isConnected()) {
                    usbPtpManager.initiateOpenCapture()
                } else {
                    ptpSession.sendCommand(
                        PtpConstants.OP_INITIATE_OPEN_CAPTURE, listOf(0)
                    ).isOk
                }
                if (ok) {
                    _shootingState.value = ShootingState.BULB_EXPOSING
                    bulbStartTime = System.currentTimeMillis()
                    startBulbTimer()
                    Timber.tag(TAG).i("Bulb exposure started")
                }
                ok
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Bulb start failed")
                false
            }
        }
    }

    /**
     * B 门结束曝光
     */
    suspend fun bulbStop(): Boolean {
        if (!isRemoteReady()) return false
        return withContext(Dispatchers.IO) {
            try {
                val ok = if (usbPtpManager.isConnected()) {
                    usbPtpManager.terminateOpenCapture()
                } else {
                    ptpSession.sendCommand(
                        PtpConstants.OP_TERMINATE_OPEN_CAPTURE, listOf(0)
                    ).isOk
                }
                _shootingState.value = ShootingState.IDLE
                _shotCount.value++
                if (ok) _captureEvents.tryEmit(System.currentTimeMillis())
                Timber.tag(TAG).i("Bulb exposure ended: ${_bulbExposureTime.value}ms")
                ok
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Bulb stop failed")
                false
            }
        }
    }

    private fun startBulbTimer() {
        scope?.launch {
            while (_shootingState.value == ShootingState.BULB_EXPOSING) {
                _bulbExposureTime.value = System.currentTimeMillis() - bulbStartTime
                delay(100)  // 100ms 更新频率
            }
        }
    }

    // ==================== 视频录制 ====================

    /**
     * 开始视频录制（Nikon 0x920A StartMovieRecInCard）。
     *
     * 旧版直接裸发 0x920A，机身在未开实时取景时以 NotLiveView 拒绝且 UI 无任何反馈，
     * 表现为「点录制没反应」。新版对齐影犀 / SnapBridge 的遥控录像流程：
     * 1. 未开监看时先自动启动（依赖实时取景的录像链路）；
     * 2. 收到 NotLiveView → 重启监看后重试；
     * 3. DeviceBusy → 等待就绪后重试；
     * 4. 其余失败码给出可操作的中文化提示（如拨盘未切到视频模式）。
     */
    suspend fun startVideoRecording(): Boolean {
        if (!isRemoteReady()) {
            _shootingMessage.value = "相机尚未连接"
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                // 前置：录像依赖 LiveView。未开启（或启动中）则等待/拉起（全流程状态反馈）
                if (!liveViewManager.isRunning()) {
                    _shootingState.value = ShootingState.VIDEO_PREPARING
                    _shootingMessage.value = "正在启动监看…"
                    if (!liveViewManager.ensureRunning()) {
                        _shootingState.value = ShootingState.IDLE
                        _shootingMessage.value = "监看启动失败，无法开始录制"
                        return@withContext false
                    }
                    delay(VIDEO_LV_SETTLE_MS)
                } else {
                    _shootingState.value = ShootingState.VIDEO_PREPARING
                }

                var attempt = 0
                while (true) {
                    val (ok, code) = movieRecordingCommand(start = true)
                    when {
                        ok -> {
                            _shootingState.value = ShootingState.VIDEO_RECORDING
                            _shootingMessage.value = null
                            Timber.tag(TAG).i("Video recording started")
                            return@withContext true
                        }
                        code == PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW && attempt == 0 -> {
                            // 机身认为不在取景状态（假成功/中途退出）：重启监看后重试一次
                            Timber.tag(TAG).w("Movie start rejected NotLiveView, restarting LV")
                            liveViewManager.startLiveView()
                            delay(VIDEO_LV_SETTLE_MS)
                            attempt++
                        }
                        code == PtpConstants.RESPONSE_DEVICE_BUSY && attempt < VIDEO_BUSY_RETRIES -> {
                            delay(500)
                            attempt++
                        }
                        else -> {
                            _shootingState.value = ShootingState.IDLE
                            _shootingMessage.value = describeMovieRejection(code)
                            return@withContext false
                        }
                    }
                }
                // 不可达：循环内所有路径均已 return，仅为满足类型检查
                error("unreachable")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Video start failed")
                _shootingState.value = ShootingState.IDLE
                _shootingMessage.value = "录制启动异常：${e.message ?: "未知错误"}"
                false
            }
        }
    }

    /**
     * 停止视频录制（Nikon 0x920B EndMovieRec）
     */
    suspend fun stopVideoRecording(): Boolean {
        if (!isRemoteReady()) {
            _shootingMessage.value = "相机尚未连接"
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                var attempt = 0
                while (true) {
                    val (ok, code) = movieRecordingCommand(start = false)
                    when {
                        ok -> {
                            _shootingState.value = ShootingState.IDLE
                            Timber.tag(TAG).i("Video recording stopped")
                            return@withContext true
                        }
                        code == PtpConstants.RESPONSE_DEVICE_BUSY && attempt < VIDEO_BUSY_RETRIES -> {
                            delay(500)
                            attempt++
                        }
                        else -> {
                            // 停止失败也回归空闲，避免 UI 永远停在「录制中」
                            _shootingState.value = ShootingState.IDLE
                            _shootingMessage.value = describeMovieRejection(code)
                            return@withContext false
                        }
                    }
                }
                // 不可达：循环内所有路径均已 return，仅为满足类型检查
                error("unreachable")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Video stop failed")
                _shootingState.value = ShootingState.IDLE
                _shootingMessage.value = "停止录制异常：${e.message ?: "未知错误"}"
                false
            }
        }
    }

    /** 按当前通道发录像命令，返回 (是否成功, 响应码) */
    private suspend fun movieRecordingCommand(start: Boolean): Pair<Boolean, Int> {
        return if (usbPtpManager.isConnected()) {
            if (start) usbPtpManager.startMovieRecordingResult()
            else usbPtpManager.stopMovieRecordingResult()
        } else {
            if (start) ptpSession.startMovieRecordingResult()
            else ptpSession.stopMovieRecordingResult()
        }
    }

    /** 录像被拒绝时给用户可操作的提示 */
    private fun describeMovieRejection(code: Int): String {
        return when (code) {
            PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW ->
                "相机未处于实时取景状态，请重新开始录制"
            PtpConstants.RESPONSE_ACCESS_DENIED ->
                "相机拒绝录制：请确认拨盘已切到视频模式，且未开启点测白平衡等占用功能"
            PtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED ->
                "当前机型不支持远程录制"
            PtpConstants.RESPONSE_STORE_FULL ->
                "存储卡已满，无法录制"
            else -> "录制失败：${PtpConstants.describeResponseCode(code)}"
        }
    }

    // ==================== 相机状态查询 ====================

    /**
     * 更新相机状态（电量/存储）
     * PRD 2.2: 剩余存储空间 / 电量显示
     */
    suspend fun refreshCameraStatus() {
        if (!isRemoteReady()) return
        withContext(Dispatchers.IO) {
            try {
                // 读取电池电量
                val batteryData = if (usbPtpManager.isConnected()) {
                    usbPtpManager.getDevicePropValue(PtpConstants.PROP_BATTERY_LEVEL)
                } else {
                    ptpSession.getDevicePropValue(PtpConstants.PROP_BATTERY_LEVEL)
                }
                if (batteryData != null && batteryData.isNotEmpty()) {
                    _batteryLevel.value = batteryData[0].toInt() and 0xFF
                }

                // 读取剩余存储
                val storageIds = if (usbPtpManager.isConnected()) {
                    usbPtpManager.getStorageIds()
                } else {
                    ptpSession.getStorageIds()
                }
                if (storageIds.isNotEmpty()) {
                    // 暂未实现 GetStorageInfo 解析，不展示虚构的剩余张数
                    _remainingShots.value = -1
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Refresh camera status failed")
            }
        }
    }

    fun resetShotCount() {
        _shotCount.value = 0
    }

    private fun isRemoteReady(): Boolean {
        return ptpSession.isConnected() || usbPtpManager.isConnected()
    }
}

/**
 * 拍摄状态
 */
enum class ShootingState {
    IDLE,
    CAPTURING,
    TIMER_COUNTDOWN,
    INTERVAL_SHOOTING,
    BULB_EXPOSING,
    VIDEO_PREPARING,
    VIDEO_RECORDING
}

/**
 * 间隔拍摄配置
 */
data class IntervalConfig(
    val intervalMs: Long = 5000L,    // 间隔时间 (ms)
    val totalShots: Int = 100         // 总拍摄张数
)

/**
 * 间隔拍摄进度
 */
data class IntervalProgress(
    val totalShots: Int = 0,
    val completedShots: Int = 0,
    val intervalMs: Long = 0L,
    val lastShotSuccess: Boolean = true
) {
    val progressPercent: Float
        get() = if (totalShots > 0) completedShots.toFloat() / totalShots else 0f
}

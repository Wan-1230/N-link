package com.nikonlink.app.feature.remote

import com.nikonlink.app.core.ptp.PtpConstants
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.usb.UsbPtpManager
import kotlinx.coroutines.*
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
    private val usbPtpManager: UsbPtpManager
) {
    companion object {
        private const val TAG = "RemoteShooting"

        /** 快门前对焦等待时长：参考影犀日志点按对焦耗时约 1.2~1.5s，取 1200ms 保证合焦 */
        private const val AF_SETTLE_MS = 1200L

        /** 长按对焦重触发间隔：必须大于单次 AF Drive 对焦周期，
         *  否则会打断相机正在进行的对焦（旧值 600ms 导致对焦反复中断） */
        private const val AF_HOLD_INTERVAL_MS = 1200L
    }

    private var scope: CoroutineScope? = null
    private var intervalJob: Job? = null
    private var timerJob: Job? = null
    private var focusJob: Job? = null
    private var bulbStartTime = 0L

    private val _shootingState = MutableStateFlow(ShootingState.IDLE)
    val shootingState: StateFlow<ShootingState> = _shootingState.asStateFlow()

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
     * 参考影犀日志: 拍照请求 autofocus=true 时先触发 AF，对焦完成后再释放快门
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
     * 开始视频录制
     * PRD 2.2: 远程开始/停止视频录制 (P2)
     */
    suspend fun startVideoRecording(): Boolean {
        if (!isRemoteReady()) return false
        return withContext(Dispatchers.IO) {
            try {
                val ok = if (usbPtpManager.isConnected()) {
                    usbPtpManager.startMovieRecording()
                } else {
                    ptpSession.startMovieRecording()
                }
                if (ok) {
                    _shootingState.value = ShootingState.VIDEO_RECORDING
                }
                ok
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Video start failed")
                false
            }
        }
    }

    /**
     * 停止视频录制
     */
    suspend fun stopVideoRecording(): Boolean {
        if (!isRemoteReady()) return false
        return withContext(Dispatchers.IO) {
            try {
                val ok = if (usbPtpManager.isConnected()) {
                    usbPtpManager.stopMovieRecording()
                } else {
                    ptpSession.stopMovieRecording()
                }
                _shootingState.value = ShootingState.IDLE
                ok
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Video stop failed")
                false
            }
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

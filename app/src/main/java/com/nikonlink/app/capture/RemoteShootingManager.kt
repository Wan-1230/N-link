package com.nikonlink.app.capture

import com.nikonlink.app.camera.liveview.LiveViewManager
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.shared.common.AppSettings
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
    private val liveViewManager: LiveViewManager,
    private val settings: AppSettings
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

        /** 0x9435 进入/退出应用模式后等待机身生效的时间 */
        private const val VIDEO_MODE_SETTLE_MS = 300L
        /**
         * 应用模式切换（进/出录像）后，等待相机恢复推流再判定是否需要重启监看的时间（v1.3.0 需求 7）。
         * 取 600ms：短于机身上报 CaptureComplete 的常见间隔，又能避免刚切完模式就抢 PTP 通道。
         */
        private const val LIVE_VIEW_RESTART_SETTLE_MS = 600L

        /** 收门策略①（恢复快门值）在曝光中的重试上限：曝光中机身可能持续回忙 */
        private const val BULB_STOP_WRITE_RETRIES = 6

        /** 定时 B 门自动收门的重试上限（每秒一次，≈30s 窗口） */
        private const val BULB_AUTO_STOP_RETRIES = 30

        /** 控制模式进入/退出失败的重试次数与间隔 */
        private const val CONTROL_MODE_EXIT_RETRIES = 2
        private const val CONTROL_MODE_EXIT_RETRY_DELAY_MS = 400L

        /** 切到 Bulb 档后等待机身应用的间隔：立刻发拍摄命令会被 DeviceBusy 拒绝 */
        private const val BULB_APPLY_DELAY_MS = 600L
    }

    /**
     * 自持的应用级 scope。
     *
     * v1.0.2 的 start(viewModelScope) 把外部 VM 的 scope 存进来，而本单例被
     * 遥控页与全屏监看页两个 ViewModel 先后注入：后创建的 VM（如退出全屏监看页）
     * 销毁时其 viewModelScope 取消，此后 intervalJob/bulbTimeoutJob 在已取消的
     * scope 上 launch 全部静默死亡——间隔拍摄"完全失效"的根因。
     * 本单例与应用同生命周期，任务一律落在自持 scope 上，与任何 VM 生命周期解耦。
     */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /**
     * 定时 B 门的总时长 (ms)；null = 手动模式（开始/结束全手动）。
     * 模块 3 选型：定时模式为主（长曝光动辄 30s~数分钟，要求手指按住屏幕不现实，
     * 且按住期间锁屏/误触都会中断曝光——ZRelay BulbTimeConfigUi(durationSeconds=30)
     * 同款交互）；手动「开始/结束」保留为次要方式。
     */
    private val _bulbDurationMs = MutableStateFlow<Long?>(null)
    val bulbDurationMs: StateFlow<Long?> = _bulbDurationMs.asStateFlow()

    /** 定时 B 门的自动收门任务；手动 bulbStop / 取消时一并撤销 */
    private var bulbTimeoutJob: Job? = null

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

    /** 兼容保留：scope 已自持，外部注入仅做日志（避免旧调用点报错） */
    fun start(scope: CoroutineScope) {
        Timber.tag(TAG).i("RemoteShootingManager started (self-owned scope, external scope ignored)")
    }

    fun stop() {
        cancelInterval()
        cancelTimer()
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
        // 拍照宽限期：AF（含等待收敛）+ 曝光 + 写卡期间 0x9203 取不到监看帧是
        // 正常现象，遥控模式（0x90C2 机身控制）下相机处理更久。不宽限的话，
        // 帧循环按「连续 5 次无帧」阈值在 ~1s 内自停监看——用户感知即
        // 「遥控画面下点快门后监看经常断开」。
        liveViewManager.grantFrameErrorGrace()
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
        focusJob = managerScope.launch {
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
        managerScope.launch {
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

        timerJob = managerScope.launch {
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

        intervalJob = managerScope.launch {
            var failures = 0
            for (i in 1..config.totalShots) {
                if (!isActive) break

                val success = capture()
                if (!success) failures++
                _intervalProgress.value = _intervalProgress.value.copy(
                    completedShots = i,
                    lastShotSuccess = success,
                    failedShots = failures
                )

                Timber.tag(TAG).i("Interval shot $i/${config.totalShots}: ${if (success) "OK" else "FAIL"}")

                // 等待间隔时间（最后一张不用等）
                if (i < config.totalShots) {
                    delay(config.intervalMs)
                }
            }
            _shootingState.value = ShootingState.IDLE
            if (failures > 0) {
                _shootingMessage.value =
                    "间隔拍摄结束：${config.totalShots} 张中 $failures 张失败（相机未响应或连接中断）"
            }
            Timber.tag(TAG).i("Interval shooting completed (failures=$failures)")
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

    // B 门链路状态（v1.0.2 全链路修复）：
    // - shutterWasSwitched：本次曝光是否由 App 把 0x500D 切到了 Bulb（收门时恢复原值）
    // - previousShutterRaw：切档前的 0x500D 值（null = 未知，不可恢复式收门）
    // - openCaptureStarted：本次曝光是否经 0x100F 开启（收门需补 0x1010）
    @Volatile
    private var shutterWasSwitched = false
    private var previousShutterRaw: Int? = null
    private var openCaptureStarted = false

    /** 双通道读 0x500D ExposureTime（4 字节 u32 LE）；读不到返回 null */
    private suspend fun readShutterRaw(): Int? {
        val data = if (usbPtpManager.isConnected()) {
            usbPtpManager.getDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME)
        } else {
            ptpSession.getDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME)
        }
        if (data == null || data.size < 4) return null
        return java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    }

    /** 双通道写 0x500D；返回是否成功 */
    private suspend fun writeShutterRaw(value: Int): Boolean {
        val data = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return if (usbPtpManager.isConnected()) {
            usbPtpManager.setDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME, data)
        } else {
            ptpSession.setDevicePropValue(PtpConstants.PROP_EXPOSURE_TIME, data)
        }
    }

    /** 按通道发一条开启/收门命令，返回 (是否成功, 响应码) */
    private suspend fun bulbCommand(opcode: Int, params: List<Int>): Pair<Boolean, Int> {
        return if (usbPtpManager.isConnected()) {
            val response = usbPtpManager.sendCommand(opcode, params)
            if (response == null) false to 0 else (response.isOk) to response.responseCode
        } else {
            val response = ptpSession.sendCommand(opcode, params)
            response.isOk to response.responseCode
        }
    }

    /**
     * B 门开始曝光（v1.0.2 全链路修复）。
     *
     * 1. 前置校验：读 0x500D，非 Bulb 档时**自动切档**（digiCamControl 同款）；
     *    写不进去（只读 / 非 M·S 档）给出可操作提示后中止，不再裸发必败命令。
     * 2. 开启曝光：0x9207 → 0x100E → 0x100F 多级降级，busy（0x2019/0xA200）短暂等待重试。
     * 3. 全程响应码可见：失败中文化 + 忙态可重试，不再「点了没反应」。
     * 4. PRD D3：曝光中重复触发直接忽略；失败时恢复快门档位，不留脏状态。
     */
    suspend fun bulbStart(): Boolean {
        if (!isRemoteReady()) {
            _shootingMessage.value = "相机尚未连接"
            return false
        }
        if (_shootingState.value == ShootingState.BULB_EXPOSING) {
            // PRD D3：不允许重复触发
            _shootingMessage.value = "B 门曝光中"
            return true
        }
        if (_shootingState.value == ShootingState.INTERVAL_SHOOTING ||
            _shootingState.value == ShootingState.TIMER_COUNTDOWN ||
            _shootingState.value == ShootingState.VIDEO_RECORDING
        ) {
            _shootingMessage.value = "请先停止当前拍摄任务再使用 B 门"
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // ---- 前置：进入远程控制模式并切到 M 档（gphoto2 _put_Nikon_Bulb 同款，best-effort）----
                // 画面模式=联动：曝光期间临时进入控制模式（0x90C2(1)，相机屏熄并显示
                //   「已连接到智能设备」），收门后自动退出恢复相机屏显示；
                // 画面模式=遥控：用户已显式进入控制模式，这里不再重复进出（收门后保持）。
                // gphoto2 开 B 门先 changecameramode(1) 再写 0x500E=1（Exposure Mode = Full
                // Manual），拨盘不在 M/S 档时也有机会远程完成；两步都允许失败（部分机身只读）。
                if (settings.remoteDisplayMode == AppSettings.DISPLAY_MODE_REMOTE) {
                    bulbControlModeEntered = false
                } else {
                    changeCameraMode(enter = true)
                    bulbControlModeEntered = true
                    runCatching {
                        val modeData = java.nio.ByteBuffer.allocate(2)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(1).array()
                        if (usbPtpManager.isConnected()) {
                            usbPtpManager.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, modeData)
                        } else {
                            ptpSession.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, modeData)
                        }
                    }
                    delay(BULB_APPLY_DELAY_MS)
                }

                // ---- 前置：确保机身快门在 Bulb 档 ----
                previousShutterRaw = readShutterRaw()
                shutterWasSwitched = false
                if (previousShutterRaw != BulbPolicy.SHUTTER_BULB_RAW) {
                    if (!writeShutterRaw(BulbPolicy.SHUTTER_BULB_RAW)) {
                        _shootingMessage.value =
                            "无法切换到 B 门：请将相机拨盘切到 M 档并确认快门速度可调，再重试"
                        Timber.tag(TAG).w("Bulb aborted: 0x500D write rejected (prev=$previousShutterRaw)")
                        return@withContext false
                    }
                    shutterWasSwitched = true
                    // 机身应用档位需要短暂时间，立刻发拍摄命令会被 DeviceBusy 拒绝
                    delay(BULB_APPLY_DELAY_MS)
                }
                openCaptureStarted = false

                // ---- 开启曝光：多级降级 + busy 重试 ----
                // v1.0.2 真机"存储 ID 无效"：部分机身把 0x9207 的第一参数按存储 ID 解释，
                // 不接受 0xFFFFFFFF——把 GetStorageIDs 的真实值加入候选重试。
                val storageIds = runCatching {
                    if (usbPtpManager.isConnected()) {
                        usbPtpManager.getStorageIds()
                    } else {
                        ptpSession.getStorageIds()
                    }
                }.getOrDefault(emptyList())
                val channel =
                    if (usbPtpManager.isConnected()) BulbPolicy.Channel.USB else BulbPolicy.Channel.WIFI
                var lastCode = 0
                var firstCode = 0
                var firstLabel = ""
                for (command in BulbPolicy.startPlan(channel, storageIds)) {
                    var attempt = 0
                    while (true) {
                        val (ok, code) = bulbCommand(command.opcode, command.params)
                        if (ok) {
                            openCaptureStarted = command.usesOpenCapture
                            _shootingState.value = ShootingState.BULB_EXPOSING
                            bulbStartTime = System.currentTimeMillis()
                            _bulbExposureTime.value = 0L
                            // 手动模式语义：不带定时（bulbStartTimed 在返回后登记时长）
                            _bulbDurationMs.value = null
                            startBulbTimer()
                            _shootingMessage.value = null
                            Timber.tag(TAG).i("Bulb exposure started via ${command.label}")
                            return@withContext true
                        }
                        lastCode = code
                        if (firstCode == 0) {
                            firstCode = code
                            firstLabel = command.label
                        }
                        if (BulbPolicy.isBusy(code) && attempt < BulbPolicy.MAX_BUSY_RETRIES) {
                            delay(BulbPolicy.BUSY_RETRY_DELAY_MS)
                            attempt++
                            continue
                        }
                        Timber.tag(TAG).w("Bulb start rejected by ${command.label}: 0x${code.toString(16)}")
                        break
                    }
                }

                // ---- 全部策略失败：恢复快门档位并给可操作提示 ----
                // 提示用**首个**策略的失败码（主通道语义最明确）；
                // 旧版展示末位策略的码，把主通道的真实原因（如存储 ID 无效）掩盖掉了
                val switched = shutterWasSwitched
                if (shutterWasSwitched) {
                    previousShutterRaw?.let { runCatching { writeShutterRaw(it) } }
                    shutterWasSwitched = false
                }
                // 退出本次曝光进入的远程控制模式（仅在遥控模式开启时主动退出；best-effort）
                exitControlModeIfEntered()
                _shootingMessage.value = BulbPolicy.describeRejection(firstCode, switched)
                false
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Bulb start failed")
                _shootingMessage.value = "开启曝光异常：${e.message ?: "未知错误"}"
                if (shutterWasSwitched) {
                    previousShutterRaw?.let { runCatching { writeShutterRaw(it) } }
                    shutterWasSwitched = false
                }
                exitControlModeIfEntered()
                false
            }
        }
    }

    /**
     * B 门结束曝光（v1.0.3 全链路修复）。
     *
     * 收门策略（逐级降级，任一成功即收门）：
     * ① 恢复切档前的快门值（改快门值即结束曝光，digiCamControl 模型）——
     *    曝光中机身可能持续回 DeviceBusy，重试放宽到 6×600ms；
     * ② **0x920C(0,0) TerminateCapture**——gphoto2 `_put_Nikon_Bulb` 收门的权威实现，
     *    是 0x9207 开启的长曝光的官方配套结束命令（v1.0.2 缺失它导致 15s 定时曝光
     *    实际远超设定值：旧版只有恢复快门 + 0x1010 两条路，Z 系无线对两者都回忙/不支持）；
     * ③ 0x1010 TerminateOpenCapture（0x100F 路径的配套，保留兜底）。
     *
     * 失败时**保持曝光态**并提示重试——旧版失败也置 IDLE，用户以为已收门，
     * 实际机身还在曝光，是最危险的静默失败。
     */
    suspend fun bulbStop(): Boolean {
        if (!isRemoteReady()) {
            _shootingMessage.value = "相机尚未连接，请重新连接后结束曝光或直接操作机身快门"
            return false
        }
        if (_shootingState.value != ShootingState.BULB_EXPOSING) return false

        return withContext(Dispatchers.IO) {
            var closed = false
            try {
                // ---- 策略①：恢复原快门值收门（仅 App 切过档时有意义） ----
                if (shutterWasSwitched && previousShutterRaw != null) {
                    var attempt = 0
                    while (true) {
                        if (writeShutterRaw(previousShutterRaw!!)) {
                            shutterWasSwitched = false
                            closed = true
                            break
                        }
                        if (attempt >= BULB_STOP_WRITE_RETRIES) break
                        delay(BulbPolicy.BUSY_RETRY_DELAY_MS)
                        attempt++
                    }
                }

                // ---- 策略②/③：0x920C TerminateCapture → 0x1010（OpenCapture 路径配套） ----
                if (!closed) {
                    for (command in BulbPolicy.stopPlan()) {
                        var attempt = 0
                        while (true) {
                            val (ok, code) = bulbCommand(command.opcode, command.params)
                            if (ok) {
                                closed = true
                                break
                            }
                            lastBulbStopCode = code
                            if (BulbPolicy.isBusy(code) && attempt < BulbPolicy.MAX_BUSY_RETRIES) {
                                delay(BulbPolicy.BUSY_RETRY_DELAY_MS)
                                attempt++
                                continue
                            }
                            Timber.tag(TAG).w("Bulb stop rejected by ${command.label}: 0x${code.toString(16)}")
                            break
                        }
                        if (closed) break
                    }
                } else if (openCaptureStarted) {
                    // 快门恢复已收门，OpenCapture 会话仍需显式终止（尽力而为，不阻塞收门结果）
                    runCatching {
                        val terminate = BulbPolicy.stopPlan().last()
                        bulbCommand(terminate.opcode, terminate.params)
                    }
                }

                if (closed) {
                    shutterWasSwitched = false
                    openCaptureStarted = false
                    _shootingState.value = ShootingState.IDLE
                    _shotCount.value++
                    _captureEvents.tryEmit(System.currentTimeMillis())
                    Timber.tag(TAG).i("Bulb exposure ended: ${_bulbExposureTime.value}ms")
                    clearBulbTimeout()
                    // 仅退出"本次曝光临时进入"的控制模式；遥控模式下保持相机屏熄
                    exitControlModeIfEntered()
                    true
                } else {
                    // 保持曝光态：用户可重试，机身端仍在曝光是真实状态
                    _shootingMessage.value =
                        "结束曝光失败：${PtpConstants.describeResponseCode(lastBulbStopCode)}，请重试或直接轻按机身快门"
                    Timber.tag(TAG).w("Bulb stop failed, keep exposing: 0x${lastBulbStopCode.toString(16)}")
                    false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Bulb stop failed")
                _shootingMessage.value = "结束曝光异常：${e.message ?: "未知错误"}，请重试"
                false
            }
        }
    }

    private var lastBulbStopCode = 0

    /** 本次 B 门曝光是否由 App 临时进入控制模式（遥控模式下为 false，收门后不退出） */
    @Volatile
    private var bulbControlModeEntered = false

    /** 收门/失败后退出"本次临时进入"的控制模式；带重试（v1.0.2 反馈退出后相机仍滞留已连接画面） */
    private suspend fun exitControlModeIfEntered() {
        if (!bulbControlModeEntered) return
        bulbControlModeEntered = false
        var attempt = 0
        while (attempt <= CONTROL_MODE_EXIT_RETRIES) {
            val ok = changeCameraModeResult(enter = false)
            if (ok) return
            delay(CONTROL_MODE_EXIT_RETRY_DELAY_MS)
            attempt++
        }
    }

    /** 发 0x90C2 并返回是否 OK（带响应码日志） */
    private suspend fun changeCameraModeResult(enter: Boolean): Boolean {
        val param = if (enter) 1 else 0
        return runCatching {
            val response = if (usbPtpManager.isConnected()) {
                usbPtpManager.sendCommand(PtpConstants.OP_NIKON_CHANGE_CAMERA_MODE, listOf(param))
            } else {
                ptpSession.sendCommand(PtpConstants.OP_NIKON_CHANGE_CAMERA_MODE, listOf(param))
            }
            val code = when (response) {
                is com.nikonlink.app.device.usb.UsbPtpResponse -> response.responseCode
                is com.nikonlink.app.device.ptp.CommandResponsePacket -> response.responseCode
                else -> null
            }
            Timber.tag(TAG).d("ChangeCameraMode($param) response=${code?.toString(16)}")
            code == PtpConstants.RESPONSE_OK || code == null
        }.getOrDefault(false)
    }

    /**
     * 显式切换画面模式（用户在长按菜单选择）。
     *
     * 两种模式对应用户提议的连接策略：
     * - 联动（remote=false）：相机屏与手机同时显示画面（默认）；
     * - 遥控（remote=true）：手机显示监看画面、相机屏熄并显示「已连接到智能设备」
     *   （0x90C2(1) 进入机身控制模式，B 门等远程控制操作的建议模式）。
     * 退出失败时自动重试，避免相机滞留「已连接」画面导致后续快门无响应。
     */
    suspend fun setCameraDisplayMode(remote: Boolean): Boolean {
        if (!isRemoteReady()) {
            _shootingMessage.value = "相机尚未连接"
            return false
        }
        var attempt = 0
        while (attempt <= CONTROL_MODE_EXIT_RETRIES) {
            if (changeCameraModeResult(enter = remote)) return true
            delay(CONTROL_MODE_EXIT_RETRY_DELAY_MS)
            attempt++
        }
        _shootingMessage.value = if (remote) "进入遥控模式失败，请重试" else "退出遥控模式失败，请重试或重启相机 WiFi"
        return false
    }

    /**
     * 定时 B 门：开始曝光并调度 [durationSec] 后自动收门。
     * 曝光中可随时 [bulbStop] 提前结束（定时任务一并撤销）。
     */
    suspend fun bulbStartTimed(durationSec: Int): Boolean {
        // 先启动再登记时长：bulbStart 成功路径会把时长清空（手动模式语义），
        // 这里在其后写入，保证定时模式的剩余时长展示生效
        val started = bulbStart()
        if (!started) {
            _bulbDurationMs.value = null
            return false
        }
        _bulbDurationMs.value = durationSec * 1000L
        bulbTimeoutJob = managerScope.launch {
            // 逐段检查而非单次 delay(duration)：曝光中取消/协程取消都能正确收敛
            val total = durationSec * 1000L
            while (isActive && _shootingState.value == ShootingState.BULB_EXPOSING) {
                val elapsed = System.currentTimeMillis() - bulbStartTime
                if (elapsed >= total) {
                    // 自动收门必须重试到成功为止（v1.0.2 的 15s 过曝根因之一：
                    // 收门失败时旧版直接放弃，曝光无限延长且 UI 永久置灰）。
                    // 机身收门期间可能持续回忙，每秒重试一次，最多 30s；
                    // 仍失败则把 bulbStop 的失败提示交给 UI（手动重试通道保持可用）。
                    Timber.tag(TAG).i("Timed bulb duration reached ($durationSec s), auto stop")
                    var retries = 0
                    while (isActive &&
                        _shootingState.value == ShootingState.BULB_EXPOSING &&
                        retries < BULB_AUTO_STOP_RETRIES
                    ) {
                        val stopped = bulbStop()
                        if (stopped || _shootingState.value != ShootingState.BULB_EXPOSING) return@launch
                        delay(1000)
                        retries++
                    }
                    return@launch
                }
                delay(200)
            }
        }
        return true
    }

    /** 清理定时 B 门状态（收门成功/失败后的统一出口） */
    private fun clearBulbTimeout() {
        bulbTimeoutJob?.cancel()
        bulbTimeoutJob = null
        _bulbDurationMs.value = null
    }

    private fun startBulbTimer() {
        managerScope.launch {
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
     * 对齐影犀的实测链路（其 STA 录像可正常跑通）：
     * 1. **0x9435(1) 进入应用模式**（gphoto2 ChangeApplicationMode）；
     * 2. **直接发 0x920A**——多数 Z 系机身录像是「录像优先」，不强制监看；
     * 3. 仅当机身以 0xA00B（NotLiveView）拒绝时，才启动监看后重试（复用 LiveViewManager
     *    的禁止条件预读 / busy 重试 / 验帧链路）；
     * 4. DeviceBusy → 等待重试；结束/失败路径统一退出应用模式（0x9435(0)）。
     *
     * v1.0.2 的 0x90C2(ChangeCameraMode) 序列在真机上表现为：相机闪现「已连接到智能设备」
     * 不到一秒即退出且未开录——0x90C2 是 B 门/拍照的远程控制入口（gphoto2 仅在 B 门使用），
     * 不属于录像链路，本版已从录像路径移除。
     */
    suspend fun startVideoRecording(): Boolean {
        if (!isRemoteReady()) {
            _shootingMessage.value = "相机尚未连接"
            return false
        }
        return withContext(Dispatchers.IO) {
            // v1.3.0（需求 7）：联动画面（相机屏同显）下，机身转入录像应用模式后会停止
            // 向 PTP 推 LV 帧 —— 这是**预期行为**，必须提前给监看开"期望停流"，
            // 否则连续 5 次取帧失败会把监看判死并 cancel 帧循环（用户看到"画面断开"）。
            val graceForMovie = isLinkedDisplay()
            if (graceForMovie) liveViewManager.beginExpectedStall("movie_recording")
            try {
                _shootingState.value = ShootingState.VIDEO_PREPARING

                // ① 进入应用模式（影犀 ChangeApplicationMode(1)；失败不阻断——部分机型不需要）
                changeApplicationMode(enter = true)
                delay(VIDEO_MODE_SETTLE_MS)

                var lvStartedHere = false
                var attempt = 0
                while (true) {
                    val (ok, code) = movieRecordingCommand(start = true)
                    when {
                        ok -> {
                            _shootingState.value = ShootingState.VIDEO_RECORDING
                            _shootingMessage.value = null
                            Timber.tag(TAG).i(
                                "Video recording started (lvStartedHere=$lvStartedHere)"
                            )
                            // 需求 7：应用模式切换后确认监看仍在跑；掉了就重启一次
                            // （对齐 libgphoto2「CaptureComplete 后 restart liveview」）
                            if (graceForMovie) ensureLiveViewAfterModeChange()
                            return@withContext true
                        }
                        code == PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW && !lvStartedHere -> {
                            // 录像依赖监看的机型：此时才启动监看并重试
                            Timber.tag(TAG).i("Movie start needs live view, starting LV")
                            _shootingMessage.value = "正在启动监看…"
                            if (!liveViewManager.isRunning() && !liveViewManager.ensureRunning()) {
                                _shootingState.value = ShootingState.IDLE
                                _shootingMessage.value = describeMovieRejection(code)
                                changeApplicationMode(enter = false)
                                if (graceForMovie) liveViewManager.endExpectedStall()
                                return@withContext false
                            }
                            delay(VIDEO_LV_SETTLE_MS)
                            lvStartedHere = true
                        }
                        BulbPolicy.isBusy(code) && attempt < VIDEO_BUSY_RETRIES -> {
                            delay(500)
                            attempt++
                        }
                        else -> {
                            _shootingState.value = ShootingState.IDLE
                            _shootingMessage.value = describeMovieRejection(code)
                            changeApplicationMode(enter = false)
                            if (graceForMovie) liveViewManager.endExpectedStall()
                            return@withContext false
                        }
                    }
                }
                // 不可达：循环内所有路径均已 return，仅为满足类型检查
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Video start failed")
                _shootingState.value = ShootingState.IDLE
                _shootingMessage.value = "录制启动异常：${e.message ?: "未知错误"}"
                changeApplicationMode(enter = false)
                if (graceForMovie) liveViewManager.endExpectedStall()
                false
            }
        }
    }

    /**
     * 「联动画面」判定（v1.3.0 需求 7）：
     * 只有相机屏同显模式下录像才会暂停/中断 PTP 的画面推送；
     * 遥控画面模式（相机屏熄）本来就没有这个停流，因此不做任何介入，行为保持原样。
     */
    private fun isLinkedDisplay(): Boolean =
        settings.remoteDisplayMode == AppSettings.DISPLAY_MODE_LINKED

    /**
     * 应用模式切换（进入/退出录像）后确认监看仍在运行，掉了就重启一次。
     *
     * 依据：libgphoto2 在收到 CaptureComplete / CaptureCompleteRecInSdram 时，
     * 若处于 liveview 会调用 `ptp_nikon_start_liveview` 重启；参考实现影犀也有
     * `USB remote LiveView restart after camera mode failure / after config retry`。
     * 只重启一次并带短暂沉降等待，避免与正在恢复的相机抢通道。
     */
    private suspend fun ensureLiveViewAfterModeChange() {
        if (liveViewManager.isRunning()) return
        delay(LIVE_VIEW_RESTART_SETTLE_MS)
        if (liveViewManager.isRunning()) return
        val ok = runCatching { liveViewManager.ensureRunning() }.getOrDefault(false)
        Timber.tag(TAG).i("Live view restart after mode change: ok=$ok")
    }

    /**
     * 停止视频录制（Nikon 0x920B EndMovieRec），结束后退出应用模式并恢复拍照链路。
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
                            // 退出应用模式（影犀 ChangeApplicationMode(0)）
                            changeApplicationMode(enter = false)
                            // 需求 7：停止录像 → 退出"期望停流"并确认监看恢复
                            // （相机恢复推流后画面自动回来，无需用户手动点"开始监看"）
                            liveViewManager.endExpectedStall()
                            ensureLiveViewAfterModeChange()
                            Timber.tag(TAG).i("Video recording stopped")
                            return@withContext true
                        }
                        BulbPolicy.isBusy(code) && attempt < VIDEO_BUSY_RETRIES -> {
                            delay(500)
                            attempt++
                        }
                        else -> {
                            // 停止失败也回归空闲，避免 UI 永远停在「录制中」
                            _shootingState.value = ShootingState.IDLE
                            _shootingMessage.value = describeMovieRejection(code)
                            changeApplicationMode(enter = false)
                            liveViewManager.endExpectedStall()
                            ensureLiveViewAfterModeChange()
                            return@withContext false
                        }
                    }
                }
                // 不可达：循环内所有路径均已 return，仅为满足类型检查
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Video stop failed")
                _shootingState.value = ShootingState.IDLE
                _shootingMessage.value = "停止录制异常：${e.message ?: "未知错误"}"
                changeApplicationMode(enter = false)
                liveViewManager.endExpectedStall()
                ensureLiveViewAfterModeChange()
                false
            }
        }
    }

    /** 双通道发 0x9435 ChangeApplicationMode（1=进入应用模式，0=退出）；失败不抛出 */
    private suspend fun changeApplicationMode(enter: Boolean) {
        val param = if (enter) 1 else 0
        runCatching {
            if (usbPtpManager.isConnected()) {
                usbPtpManager.sendCommand(PtpConstants.OP_NIKON_CHANGE_APPLICATION_MODE, listOf(param))
            } else {
                ptpSession.sendCommand(PtpConstants.OP_NIKON_CHANGE_APPLICATION_MODE, listOf(param))
            }
        }.onSuccess { response ->
            val code = when (response) {
                is com.nikonlink.app.device.usb.UsbPtpResponse -> response.responseCode
                is com.nikonlink.app.device.ptp.CommandResponsePacket -> response.responseCode
                else -> null
            }
            Timber.tag(TAG).d("ChangeApplicationMode($param) response=${code?.toString(16)}")
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "ChangeApplicationMode($param) failed")
        }
    }

    /** 双通道发 0x90C2 ChangeCameraMode（1=进入控制模式，0=退出）；失败不抛出 */
    private suspend fun changeCameraMode(enter: Boolean) {
        val param = if (enter) 1 else 0
        runCatching {
            if (usbPtpManager.isConnected()) {
                usbPtpManager.sendCommand(PtpConstants.OP_NIKON_CHANGE_CAMERA_MODE, listOf(param))
            } else {
                ptpSession.sendCommand(PtpConstants.OP_NIKON_CHANGE_CAMERA_MODE, listOf(param))
            }
        }.onSuccess { response ->
            val code = when (response) {
                is com.nikonlink.app.device.usb.UsbPtpResponse -> response.responseCode
                is com.nikonlink.app.device.ptp.CommandResponsePacket -> response.responseCode
                else -> null
            }
            Timber.tag(TAG).d("ChangeCameraMode($param) response=${code?.toString(16)}")
        }.onFailure { e ->
            Timber.tag(TAG).w(e, "ChangeCameraMode($param) failed")
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
                "该机型录像依赖监看，且监看启动失败：请确认拨盘已切到视频档后重试"
            PtpConstants.RESPONSE_NIKON_INVALID_STATUS ->
                "相机当前状态不允许录制（Nikon 0xA004）：请将模式拨盘切到视频档，且不要停留在回放/菜单界面"
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
    val lastShotSuccess: Boolean = true,
    /** 累计失败张数（v1.0.2 反馈"间隔拍摄失效"时用于 UI 透出真实失败原因） */
    val failedShots: Int = 0
) {
    val progressPercent: Float
        get() = if (totalShots > 0) completedShots.toFloat() / totalShots else 0f
}

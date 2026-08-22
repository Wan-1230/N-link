package com.nikonlink.app.camera.liveview

import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
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
    private val usbPtpManager: UsbPtpManager
) {
    companion object {
        private const val TAG = "LiveViewMgr"
        private const val FRAME_INTERVAL_MS = 66L  // ~15fps，预留保活通道余量防断联
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }

    private var scope: CoroutineScope? = null
    private var frameJob: Job? = null
    private var consecutiveErrors = 0

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
                    val profileOk = if (usbPtpManager.isConnected()) {
                        usbPtpManager.setDevicePropValue(
                            PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE,
                            byteArrayOf(3)
                        )
                    } else {
                        ptpSession.setDevicePropValue(
                            PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE,
                            byteArrayOf(3)
                        )
                    }
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
                    startFrameLoop()
                    Timber.tag(TAG).i("✓ Live View started")
                } else {
                    // 全链路优化: 启动失败时恢复原曝光模式，
                    // 避免相机卡在无线控制模式导致后续下载被拒绝
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
     * StartLiveView 返回 DeviceBusy 时，
     * 等待相机就绪（DeviceReady）而不是立即报错。
     */
    private suspend fun startLiveViewCommand(): Boolean {
        val (firstOk, firstCode) = startLiveViewResult()
        if (firstCode < 0) return false

        if (firstOk) return true
        if (firstCode != PtpConstants.RESPONSE_DEVICE_BUSY &&
            firstCode != PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW
        ) {
            Timber.tag(TAG).w("StartLiveView rejected: 0x${firstCode.toString(16)}")
            return false
        }

        repeat(10) { attempt ->
            delay(250)
            val (readyOk, readyCode) = deviceReadyResult()
            if (readyOk) {
                Timber.tag(TAG).i("LiveView became ready after $attempt retries")
                return true
            }
            if (readyCode != -1 && readyCode != PtpConstants.RESPONSE_DEVICE_BUSY) {
                Timber.tag(TAG).w("DeviceReady while starting LiveView: 0x${readyCode.toString(16)}")
                return false
            }
        }
        return false
    }

    private suspend fun startLiveViewResult(): Pair<Boolean, Int> {
        return if (usbPtpManager.isConnected()) {
            val response = usbPtpManager.sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
            if (response == null) Pair(false, -1) else Pair(response.isOk, response.responseCode)
        } else {
            val response = ptpSession.sendCommand(PtpConstants.OP_NIKON_START_LIVE_VIEW)
            Pair(response.isOk, response.responseCode)
        }
    }

    private suspend fun deviceReadyResult(): Pair<Boolean, Int> {
        return if (usbPtpManager.isConnected()) {
            val response = usbPtpManager.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
            if (response == null) Pair(false, -1) else Pair(response.isOk, response.responseCode)
        } else {
            val response = ptpSession.sendCommand(PtpConstants.OP_NIKON_DEVICE_READY)
            Pair(response.isOk, response.responseCode)
        }
    }

    /**
     * 停止 Live View
     */
    fun stopLiveView() {
        frameJob?.cancel()
        frameJob = null
        _errorMessage.value = null
        if (_liveViewState.value == LiveViewState.RUNNING) {
            scope?.launch(Dispatchers.IO) {
                try {
                    if (usbPtpManager.isConnected()) {
                        usbPtpManager.stopLiveView()
                    } else {
                        ptpSession.stopLiveView()
                    }
                } catch (_: Exception) {}
            }
        }
        _liveViewState.value = LiveViewState.STOPPED
        _fps.value = 0
        // 停止监看后退出无线控制模式，恢复原曝光程序模式
        restoreExposureModeIfNeeded()
        Timber.tag(TAG).i("Live View stopped")
    }

    /** 恢复进入无线控制模式前的曝光程序模式（停止/启动失败时调用） */
    private fun restoreExposureModeIfNeeded() {
        val restore = savedExposureMode
        savedExposureMode = null
        if (restore != null && !usbPtpManager.isConnected()) {
            scope?.launch(Dispatchers.IO) {
                runCatching {
                    ptpSession.setDevicePropValue(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, restore)
                    Timber.tag(TAG).i("Wireless control mode exited, exposure mode restored")
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
                    val imageData = if (usbPtpManager.isConnected()) {
                        usbPtpManager.getLiveViewImage()
                    } else {
                        ptpSession.getLiveViewImage()
                    }
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
                        handleError()
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Frame error: ${e.message}")
                    handleError()
                }

                // 帧间隔控制（避免过度请求）
                val elapsed = System.currentTimeMillis() - frameStart
                if (elapsed < FRAME_INTERVAL_MS) {
                    delay(FRAME_INTERVAL_MS - elapsed)
                }
            }
        }
    }

    private suspend fun handleError() {
        consecutiveErrors++
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Timber.tag(TAG).e("Too many errors, stopping Live View")
            _liveViewState.value = LiveViewState.ERROR
            _errorMessage.value = "实时取景长时间无画面，已自动停止，请重试"
            frameJob?.cancel()
        } else {
            delay(100)  // 短暂等待后重试
        }
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

                val response = if (usbPtpManager.isConnected()) {
                    usbPtpManager.changeAfArea(afX, afY)
                } else {
                    ptpSession.sendCommand(
                        PtpConstants.OP_NIKON_CHANGE_AF_AREA,
                        listOf(afX, afY)
                    ).isOk
                }

                if (response) {
                    // 触发 AF 驱动
                    if (usbPtpManager.isConnected()) {
                        usbPtpManager.afDrive()
                    } else {
                        ptpSession.afDrive()
                    }
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
                if (usbPtpManager.isConnected()) {
                    usbPtpManager.manualFocusDrive(direction, speed)
                } else {
                    ptpSession.sendCommand(
                        PtpConstants.OP_NIKON_MF_DRIVE,
                        listOf(direction, speed)
                    ).isOk
                }
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

    fun isRunning(): Boolean = _liveViewState.value == LiveViewState.RUNNING
}

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

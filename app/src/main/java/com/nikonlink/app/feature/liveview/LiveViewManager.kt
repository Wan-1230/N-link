package com.nikonlink.app.feature.liveview

import com.nikonlink.app.core.ptp.PtpConstants
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.usb.UsbPtpManager
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
        private const val FRAME_INTERVAL_MS = 33L  // ~30fps
        private const val MAX_CONSECUTIVE_ERRORS = 5
    }

    private var scope: CoroutineScope? = null
    private var frameJob: Job? = null
    private var consecutiveErrors = 0

    private val _liveViewState = MutableStateFlow(LiveViewState.STOPPED)
    val liveViewState: StateFlow<LiveViewState> = _liveViewState.asStateFlow()

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
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val success = if (usbPtpManager.isConnected()) {
                    usbPtpManager.startLiveView()
                } else {
                    ptpSession.startLiveView()
                }
                if (success) {
                    _liveViewState.value = LiveViewState.RUNNING
                    consecutiveErrors = 0
                    startFrameLoop()
                    Timber.tag(TAG).i("✓ Live View started")
                } else {
                    Timber.tag(TAG).e("Failed to start Live View")
                }
                success
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Start Live View error")
                false
            }
        }
    }

    /**
     * 停止 Live View
     */
    fun stopLiveView() {
        frameJob?.cancel()
        frameJob = null
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
        Timber.tag(TAG).i("Live View stopped")
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
                // 将归一化坐标转换为相机 AF 区域坐标 (320x240 参考)
                val afX = (x * 320).toInt().coerceIn(0, 319)
                val afY = (y * 240).toInt().coerceIn(0, 239)

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

package com.nikonlink.app.feature.settings

import com.nikonlink.app.core.ptp.PtpConstants
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.usb.UsbPtpManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相机参数管理器
 *
 * PRD 2.4 相机参数管理:
 * - 曝光三要素：光圈/快门速度/ISO 实时读取与调整 (P0)
 * - 白平衡：预设选择 + 色温 K 值精确调整 (P0)
 * - 对焦模式：AF-S / AF-C / MF 切换 (P0)
 * - 拍摄模式：P / S / A / M / AUTO 切换 (P1)
 * - 测光模式：矩阵/中央重点/点测光/高光重点 (P1)
 * - 相机信息：电量、快门次数、存储卡容量、固件版本 (P1)
 *
 * PRD 2.4 参数调整 UI:
 * - 参数变更实时同步至相机（< 100ms 响应）
 * - 参数锁定/解锁机制，防止误触
 */
@Singleton
class CameraParameterManager @Inject constructor(
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager
) {
    companion object {
        private const val TAG = "CameraParams"
    }

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private val refreshInProgress = AtomicBoolean(false)

    // ==================== 曝光三要素 ====================

    private val _aperture = MutableStateFlow(CameraParam("光圈", "--", emptyList()))
    val aperture: StateFlow<CameraParam> = _aperture.asStateFlow()

    private val _shutterSpeed = MutableStateFlow(CameraParam("快门速度", "--", emptyList()))
    val shutterSpeed: StateFlow<CameraParam> = _shutterSpeed.asStateFlow()

    private val _iso = MutableStateFlow(CameraParam("ISO", "--", emptyList()))
    val iso: StateFlow<CameraParam> = _iso.asStateFlow()

    // ==================== 白平衡 ====================

    private val _whiteBalance = MutableStateFlow(CameraParam("白平衡", "--", emptyList()))
    val whiteBalance: StateFlow<CameraParam> = _whiteBalance.asStateFlow()

    private val _colorTemperature = MutableStateFlow(5500)  // K值
    val colorTemperature: StateFlow<Int> = _colorTemperature.asStateFlow()

    // ==================== 对焦 ====================

    private val _focusMode = MutableStateFlow(CameraParam("对焦模式", "--", emptyList()))
    val focusMode: StateFlow<CameraParam> = _focusMode.asStateFlow()

    // ==================== 拍摄模式 ====================

    private val _exposureProgram = MutableStateFlow(CameraParam("拍摄模式", "--", emptyList()))
    val exposureProgram: StateFlow<CameraParam> = _exposureProgram.asStateFlow()

    // ==================== 测光模式 ====================

    private val _meteringMode = MutableStateFlow(CameraParam("测光模式", "--", emptyList()))
    val meteringMode: StateFlow<CameraParam> = _meteringMode.asStateFlow()

    // ==================== 相机信息 ====================

    private val _cameraInfo = MutableStateFlow(CameraInfo())
    val cameraInfo: StateFlow<CameraInfo> = _cameraInfo.asStateFlow()

    /** 参数是否锁定（防误触） */
    private val _paramsLocked = MutableStateFlow(false)
    val paramsLocked: StateFlow<Boolean> = _paramsLocked.asStateFlow()

    fun start(scope: CoroutineScope) {
        this.scope = scope
        // 相机主动推送属性变更事件时立即刷新参数，避免轮询延迟
        scope.launch {
            ptpSession.events.collect { event ->
                if (event.eventCode == PtpConstants.EVENT_DEVICE_PROP_CHANGED) {
                    refreshFromDeviceEvent()
                }
            }
        }
        scope.launch {
            usbPtpManager.events.collect { event ->
                if (event.eventCode == PtpConstants.EVENT_DEVICE_PROP_CHANGED) {
                    refreshFromDeviceEvent()
                }
            }
        }
        Timber.tag(TAG).i("CameraParameterManager started")
    }

    private fun refreshFromDeviceEvent() {
        if (!refreshInProgress.compareAndSet(false, true)) return
        scope?.launch {
            try {
                readAllParameters()
            } finally {
                refreshInProgress.set(false)
            }
        }
    }

    fun stop() {
        stopPolling()
        scope = null
    }

    fun toggleLock() {
        _paramsLocked.value = !_paramsLocked.value
    }

    // ==================== 参数读取 ====================

    /**
     * 一次性读取所有参数
     */
    suspend fun readAllParameters() {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected()) {
            Timber.tag(TAG).w("PTP not connected")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                readAperture()
                readShutterSpeed()
                readIso()
                readWhiteBalance()
                readFocusMode()
                readExposureProgram()
                readMeteringMode()
                Timber.tag(TAG).i("All parameters read")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Read parameters failed")
            }
        }
    }

    /**
     * 开始周期性轮询参数（用于实时同步显示）
     */
    fun startPolling(intervalMs: Long = 2000L) {
        stopPolling()
        pollingJob = scope?.launch {
            while (isActive) {
                readAllParameters()
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun readAperture() {
        val data = readDeviceProp(PtpConstants.PROP_F_NUMBER) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val fStop = value / 100.0
            _aperture.value = _aperture.value.copy(
                currentValue = "f/${String.format("%.1f", fStop)}",
                rawValue = value
            )
        }
    }

    private suspend fun readShutterSpeed() {
        val data = readDeviceProp(PtpConstants.PROP_EXPOSURE_TIME) ?: return
        if (data.size >= 4) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
            // 快门速度以 1/10000s 为单位
            val seconds = value / 10000.0
            val display = if (seconds >= 1.0) {
                "${String.format("%.1f", seconds)}s"
            } else {
                "1/${(1.0 / seconds).toInt()}"
            }
            _shutterSpeed.value = _shutterSpeed.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    private suspend fun readIso() {
        val data = readDeviceProp(PtpConstants.PROP_EXPOSURE_INDEX) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val display = when {
                value == 0xFFFF -> "Auto"
                value == 0xFFFE -> "Lo"
                else -> "ISO $value"
            }
            _iso.value = _iso.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    private suspend fun readWhiteBalance() {
        val data = readDeviceProp(PtpConstants.PROP_WHITE_BALANCE) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val display = when (value) {
                1 -> "自动"
                2 -> "日光"
                3 -> "荧光灯"
                4 -> "白炽灯"
                5 -> "闪光灯"
                6 -> "阴天"
                7 -> "阴影"
                0x8010 -> "色温"
                0x8011 -> "预设"
                else -> "未知($value)"
            }
            _whiteBalance.value = _whiteBalance.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    private suspend fun readFocusMode() {
        val data = readDeviceProp(PtpConstants.PROP_FOCUS_MODE) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val display = when (value) {
                1 -> "AF-S (单次)"
                2 -> "AF-C (连续)"
                3 -> "MF (手动)"
                0x8010 -> "AF-F (全时)"
                else -> "未知($value)"
            }
            _focusMode.value = _focusMode.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    private suspend fun readExposureProgram() {
        val data = readDeviceProp(PtpConstants.PROP_EXPOSURE_PROGRAM_MODE) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val display = when (value) {
                1 -> "M (手动)"
                2 -> "P (程序)"
                3 -> "A (光圈优先)"
                4 -> "S (快门优先)"
                0x8010 -> "Auto"
                0x8011 -> "场景"
                0x8012 -> "U1"
                0x8013 -> "U2"
                else -> "未知($value)"
            }
            _exposureProgram.value = _exposureProgram.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    private suspend fun readMeteringMode() {
        val data = readDeviceProp(PtpConstants.PROP_EXPOSURE_METERING_MODE) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val display = when (value) {
                1 -> "中央重点"
                2 -> "矩阵测光"
                3 -> "点测光"
                0x8010 -> "高光重点"
                else -> "未知($value)"
            }
            _meteringMode.value = _meteringMode.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    // ==================== 参数设置 ====================

    /**
     * 设置光圈值
     * @param fStopX100 光圈值 x100 (如 f/2.8 = 280)
     */
    suspend fun setAperture(fStopX100: Int): Boolean {
        if (_paramsLocked.value) return false
        val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(fStopX100.toShort()).array()
        val success = writeDeviceProp(PtpConstants.PROP_F_NUMBER, data)
        if (success) readAperture()
        return success
    }

    /**
     * 设置快门速度
     * @param exposureTime 以 1/10000s 为单位 (如 1/250s = 40)
     */
    suspend fun setShutterSpeed(exposureTime: Int): Boolean {
        if (_paramsLocked.value) return false
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(exposureTime).array()
        val success = writeDeviceProp(PtpConstants.PROP_EXPOSURE_TIME, data)
        if (success) readShutterSpeed()
        return success
    }

    /**
     * 设置 ISO
     */
    suspend fun setIso(isoValue: Int): Boolean {
        if (_paramsLocked.value) return false
        val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(isoValue.toShort()).array()
        val success = writeDeviceProp(PtpConstants.PROP_EXPOSURE_INDEX, data)
        if (success) readIso()
        return success
    }

    /**
     * 设置白平衡模式
     */
    suspend fun setWhiteBalance(mode: Int): Boolean {
        if (_paramsLocked.value) return false
        val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(mode.toShort()).array()
        val success = writeDeviceProp(PtpConstants.PROP_WHITE_BALANCE, data)
        if (success) readWhiteBalance()
        return success
    }

    /**
     * 设置对焦模式
     * @param mode 1=AF-S, 2=AF-C, 3=MF
     */
    suspend fun setFocusMode(mode: Int): Boolean {
        if (_paramsLocked.value) return false
        val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(mode.toShort()).array()
        val success = writeDeviceProp(PtpConstants.PROP_FOCUS_MODE, data)
        if (success) readFocusMode()
        return success
    }

    /**
     * 设置测光模式
     */
    suspend fun setMeteringMode(mode: Int): Boolean {
        if (_paramsLocked.value) return false
        val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(mode.toShort()).array()
        val success = writeDeviceProp(PtpConstants.PROP_EXPOSURE_METERING_MODE, data)
        if (success) readMeteringMode()
        return success
    }

    // ==================== 常用预设值 ====================

    val commonApertures = listOf(140, 180, 200, 280, 350, 400, 560, 800, 1100, 1600, 2200)
    val commonShutterSpeeds = listOf(10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000)
    val commonIsoValues = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200)
    val whiteBalancePresets = listOf(
        1 to "自动", 2 to "日光", 6 to "阴天", 7 to "阴影",
        3 to "荧光灯", 4 to "白炽灯", 5 to "闪光灯"
    )

    private suspend fun readDeviceProp(propCode: Int): ByteArray? {
        return if (usbPtpManager.isConnected()) {
            usbPtpManager.getDevicePropValue(propCode)
        } else {
            ptpSession.getDevicePropValue(propCode)
        }
    }

    private suspend fun writeDeviceProp(propCode: Int, value: ByteArray): Boolean {
        return if (usbPtpManager.isConnected()) {
            usbPtpManager.setDevicePropValue(propCode, value)
        } else {
            ptpSession.setDevicePropValue(propCode, value)
        }
    }
}

/**
 * 相机参数数据模型
 */
data class CameraParam(
    val name: String,
    val currentValue: String,
    val availableValues: List<String>,
    val rawValue: Int = 0
)

/**
 * 相机信息
 * PRD 2.4: 电量、快门次数、存储卡容量、固件版本
 */
data class CameraInfo(
    val batteryLevel: Int = -1,
    val shutterCount: Int = -1,
    val storageFreeMb: Long = -1,
    val storageTotalMb: Long = -1,
    val firmwareVersion: String = "",
    val modelName: String = ""
)

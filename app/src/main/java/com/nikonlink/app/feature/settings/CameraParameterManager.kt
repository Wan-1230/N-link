package com.nikonlink.app.feature.settings

import android.content.Context
import com.nikonlink.app.core.ptp.PtpConstants
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.usb.UsbPtpManager
import com.nikonlink.app.feature.transfer.CameraFileFormat
import com.nikonlink.app.feature.transfer.TransferManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import java.util.Locale
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
    @ApplicationContext private val context: Context,
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val transferManager: TransferManager,
    private val digeekerClient: DigeekerShutterCountClient
) {
    companion object {
        private const val TAG = "CameraParams"
    }

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private val refreshInProgress = AtomicBoolean(false)
    private var shutterQueryJob: Job? = null
    private val shutterQueryInProgress = AtomicBoolean(false)

    // ==================== 曝光三要素 ====================

    private val _aperture = MutableStateFlow(CameraParam("光圈", "--", emptyList()))
    val aperture: StateFlow<CameraParam> = _aperture.asStateFlow()

    private val _shutterSpeed = MutableStateFlow(CameraParam("快门速度", "--", emptyList()))
    val shutterSpeed: StateFlow<CameraParam> = _shutterSpeed.asStateFlow()

    private val _iso = MutableStateFlow(CameraParam("ISO", "--", emptyList()))
    val iso: StateFlow<CameraParam> = _iso.asStateFlow()

    private val _evCompensation = MutableStateFlow(CameraParam("曝光补偿", "--", emptyList()))
    val evCompensation: StateFlow<CameraParam> = _evCompensation.asStateFlow()

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
                readEvCompensation()
                readWhiteBalance()
                readFocusMode()
                readExposureProgram()
                readMeteringMode()
                readCameraInfo()
                Timber.tag(TAG).i("All parameters read")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Read parameters failed")
            }
        }
    }

    /**
     * 设备信息：镜头焦段/光圈、存储卡容量。
     * 与曝光参数分开读取，避免频繁轮询时增加 PTP 通道压力。
     */
    private suspend fun readCameraInfo() {
        readStorageInfo()
        readLensInfo()
        readBatteryLevel()
        readFirmwareAndModel()
        ensureShutterCountQuery()
    }

    private suspend fun readStorageInfo() {
        val storageIds = if (usbPtpManager.isConnected()) {
            usbPtpManager.getStorageIds()
        } else {
            ptpSession.getStorageIds()
        }
        if (storageIds.isEmpty()) return

        var totalBytes = 0L
        var freeBytes = 0L
        var description = ""
        var cardCount = 0
        storageIds.forEach { storageId ->
            val raw = if (usbPtpManager.isConnected()) {
                usbPtpManager.getStorageInfo(storageId)
            } else {
                ptpSession.getStorageInfo(storageId)
            }
            val info = raw?.let(::parseStorageInfo) ?: return@forEach
            val max = info.maxCapacityBytes
            if (max <= 0 || max == 0xFFFFFFFFL) return@forEach
            totalBytes += max
            freeBytes += info.freeSpaceBytes.coerceIn(0, max)
            cardCount++
            if (info.storageDescription.isNotBlank()) description = info.storageDescription
        }
        if (cardCount == 0) return

        val storageLabel = when {
            description.isNotBlank() && cardCount > 1 -> "$description ×$cardCount"
            description.isNotBlank() -> description
            cardCount > 1 -> "存储卡 ×$cardCount"
            else -> "存储卡"
        }
        _cameraInfo.value = _cameraInfo.value.copy(
            storageTotalMb = totalBytes / 1024 / 1024,
            storageFreeMb = freeBytes / 1024 / 1024,
            storageDescription = storageLabel
        )
    }

    private suspend fun readLensInfo() {
        val lensId = readDeviceProp(PtpConstants.PROP_NIKON_LENS_ID)?.let(::readUInt)
        val focalMinData = readDeviceProp(PtpConstants.PROP_NIKON_FOCAL_LENGTH_MIN)
        val focalMaxData = readDeviceProp(PtpConstants.PROP_NIKON_FOCAL_LENGTH_MAX)
        val apMinData = readDeviceProp(PtpConstants.PROP_NIKON_MAX_AP_AT_MIN)
        val apMaxData = readDeviceProp(PtpConstants.PROP_NIKON_MAX_AP_AT_MAX)

        val focalMin = focalMinData?.let { readUInt(it).toDouble() / 100.0 }
        val focalMax = focalMaxData?.let { readUInt(it).toDouble() / 100.0 }
        val apMin = apMinData?.let(::readUInt)
        val apMax = apMaxData?.let(::readUInt)
        val lensName = buildLensName(lensId, focalMin, focalMax, apMin, apMax)
        if (lensName.isNotBlank()) {
            _cameraInfo.value = _cameraInfo.value.copy(lensName = lensName)
        }
    }

    /**
     * 电池电量：优先读标准 PTP 0x5001，读不到保持 -1，UI 隐藏该行。
     */
    private suspend fun readBatteryLevel() {
        val data = readDeviceProp(PtpConstants.PROP_BATTERY_LEVEL) ?: return
        if (data.isNotEmpty()) {
            val level = data[0].toInt() and 0xFF
            _cameraInfo.value = _cameraInfo.value.copy(batteryLevel = level)
        }
    }

    /**
     * 固件版本 / 型号：从 GetDeviceInfo 的 DeviceVersion 与 Model 解析。
     */
    private suspend fun readFirmwareAndModel() {
        val data = if (usbPtpManager.isConnected()) {
            usbPtpManager.getDeviceInfo()
        } else {
            ptpSession.getDeviceInfo()
        } ?: return
        val info = parseDeviceInfo(data) ?: return
        if (info.modelName.isNotBlank() || info.firmwareVersion.isNotBlank()) {
            _cameraInfo.value = _cameraInfo.value.copy(
                modelName = info.modelName.ifBlank { _cameraInfo.value.modelName },
                firmwareVersion = info.firmwareVersion
            )
        }
    }

    /**
     * 快门次数：机身属性普遍不提供，直接后台导出照片到缓存并走 digeeker 解析。
     */
    private fun ensureShutterCountQuery(force: Boolean = false) {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected()) return
        val state = _cameraInfo.value.shutterQueryState
        if (!force && state != ShutterCountState.NONE) return
        if (!shutterQueryInProgress.compareAndSet(false, true)) return

        _cameraInfo.value = _cameraInfo.value.copy(
            shutterQueryState = ShutterCountState.QUERYING
        )
        val job = scope?.launch(Dispatchers.IO) {
            try {
                val photos = transferManager.fetchPhotoList()
                val targetDir = File(context.cacheDir, "n-link_shutter").apply { mkdirs() }
                val sample = photos.filter { it.format == CameraFileFormat.JPEG }
                    .minByOrNull { it.size }
                    ?: photos.minByOrNull { it.size }
                if (sample == null) {
                    markShutterQueryFailed()
                    return@launch
                }

                val target = File(targetDir, "shutter_sample_${sample.handle}.jpg")
                val downloaded = transferManager.downloadPhotoToCache(sample, target)
                if (!downloaded) {
                    markShutterQueryFailed()
                    return@launch
                }

                val count = digeekerClient.queryShutterCount(target)
                if (count != null && count >= 0) {
                    _cameraInfo.value = _cameraInfo.value.copy(
                        shutterCount = count,
                        shutterQueryState = ShutterCountState.SUCCESS
                    )
                    Timber.tag(TAG).i("Shutter count resolved: $count")
                } else {
                    markShutterQueryFailed()
                }
                target.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Shutter count query failed")
                markShutterQueryFailed()
            } finally {
                shutterQueryInProgress.set(false)
            }
        }
        if (job == null) {
            shutterQueryInProgress.set(false)
        } else {
            shutterQueryJob = job
        }
    }

    fun retryShutterCountQuery() {
        ensureShutterCountQuery(force = true)
    }

    private fun markShutterQueryFailed() {
        _cameraInfo.value = _cameraInfo.value.copy(
            shutterQueryState = ShutterCountState.FAILED
        )
    }

    private fun parseDeviceInfo(data: ByteArray): PtpDeviceInfo? {
        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            buffer.short
            buffer.int
            buffer.short
            readDeviceInfoString(buffer)
            buffer.short // functional mode
            skipU32Array(buffer) // operations supported
            skipU32Array(buffer) // events supported
            skipU32Array(buffer) // device properties supported
            skipU16Array(buffer) // capture formats
            skipU16Array(buffer) // image formats
            val manufacturer = readDeviceInfoString(buffer)
            val model = readDeviceInfoString(buffer)
            val version = readDeviceInfoString(buffer)
            val serial = readDeviceInfoString(buffer)
            PtpDeviceInfo(
                manufacturer = manufacturer,
                modelName = model,
                firmwareVersion = version,
                serialNumber = serial
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse device info")
            null
        }
    }

    private fun readDeviceInfoString(buffer: ByteBuffer): String {
        if (buffer.remaining() < 1) return ""
        val length = buffer.get().toInt() and 0xFF
        if (length == 0) return ""
        val chars = CharArray(length)
        for (i in 0 until length) {
            if (buffer.remaining() < 2) break
            val low = buffer.get().toInt() and 0xFF
            val high = buffer.get().toInt() and 0xFF
            chars[i] = ((high shl 8) or low).toChar()
        }
        return chars.joinToString("").trimEnd('\u0000')
    }

    private fun skipU32Array(buffer: ByteBuffer) {
        val count = buffer.int.coerceIn(0, 4096)
        repeat(count) {
            if (buffer.remaining() >= 4) buffer.int
        }
    }

    private fun skipU16Array(buffer: ByteBuffer) {
        val count = (buffer.short.toInt() and 0xFFFF).coerceAtMost(4096)
        repeat(count) {
            if (buffer.remaining() >= 2) buffer.short
        }
    }

    private fun readUInt(data: ByteArray): Int = when {
        data.size >= 4 -> ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
        data.size >= 2 -> ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        data.size >= 1 -> data[0].toInt() and 0xFF
        else -> 0
    }

    private fun buildLensName(
        lensId: Int?,
        focalMinMm: Double?,
        focalMaxMm: Double?,
        apMinX100: Int?,
        apMaxX100: Int?
    ): String {
        val focalRange = when {
            focalMinMm != null && focalMaxMm != null && focalMinMm != focalMaxMm ->
                "${formatFocal(focalMinMm)}-${formatFocal(focalMaxMm)}mm"
            focalMinMm != null -> "${formatFocal(focalMinMm)}mm"
            else -> ""
        }
        val aperture = when {
            apMinX100 != null && apMaxX100 != null && apMinX100 != apMaxX100 ->
                "f/${formatAperture(apMinX100)}-${formatAperture(apMaxX100)}"
            apMinX100 != null -> "f/${formatAperture(apMinX100)}"
            else -> ""
        }
        val specs = listOf(focalRange, aperture).filter { it.isNotBlank() }.joinToString(" ")
        return if (specs.isNotBlank()) {
            "NIKKOR $specs"
        } else if (lensId != null && lensId > 0) {
            "镜头 ID $lensId"
        } else {
            ""
        }
    }

    private fun formatFocal(value: Double): String =
        String.format(Locale.US, "%.0f", value)

    private fun formatAperture(valueX100: Int): String {
        val formatted = String.format(Locale.US, "%.1f", valueX100 / 100.0)
        return formatted.removeSuffix(".0")
    }

    private fun parseStorageInfo(data: ByteArray): PtpStorageInfo? {
        if (data.size < 26) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val storageType = buffer.short.toInt() and 0xFFFF
        val filesystemType = buffer.short.toInt() and 0xFFFF
        val accessCapability = buffer.short.toInt() and 0xFFFF
        val maxCapacityBytes = buffer.long
        val freeSpaceBytes = buffer.long
        val freeSpaceImages = buffer.int

        var offset = 26
        val (description, nextOffset) = readPtpString(data, offset)
        offset = nextOffset
        val (volumeLabel, _) = readPtpString(data, offset)
        return PtpStorageInfo(
            storageType = storageType,
            filesystemType = filesystemType,
            accessCapability = accessCapability,
            maxCapacityBytes = maxCapacityBytes,
            freeSpaceBytes = freeSpaceBytes,
            freeSpaceImages = freeSpaceImages,
            storageDescription = description,
            volumeLabel = volumeLabel
        )
    }

    private fun readPtpString(data: ByteArray, start: Int): Pair<String, Int> {
        if (start >= data.size) return "" to start
        val length = data[start].toInt() and 0xFF
        if (length == 0) return "" to (start + 1)
        val bytesEnd = start + 1 + length * 2
        if (bytesEnd > data.size) return "" to data.size
        val chars = CharArray(length)
        for (i in 0 until length) {
            val low = data[start + 1 + i * 2].toInt() and 0xFF
            val high = data[start + 1 + i * 2 + 1].toInt() and 0xFF
            chars[i] = ((high shl 8) or low).toChar()
        }
        return chars.joinToString("").trimEnd('\u0000') to bytesEnd
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

    private suspend fun readEvCompensation() {
        val data = readDeviceProp(PtpConstants.PROP_EXPOSURE_BIAS_COMPENSATION) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            // Nikon 以 1/6 EV 为步进存储曝光补偿
            val display = String.format(Locale.US, "%+.1f", value / 6.0)
            _evCompensation.value = _evCompensation.value.copy(
                currentValue = display,
                rawValue = value
            )
        }
    }

    private suspend fun readWhiteBalance() {
        val data = readDeviceProp(PtpConstants.PROP_WHITE_BALANCE) ?: return
        if (data.size >= 2) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            // Nikon PTP 0x5005：标准值 2/4/5/6/7 + 厂商值 0x8010-0x8013、0x8016
            val display = when (value) {
                2 -> "自动"
                0x8016 -> "自然光自动适应"
                4 -> "晴天"
                0x8010 -> "阴天"
                0x8011 -> "背阴"
                6 -> "白炽灯"
                5 -> "荧光灯"
                7 -> "闪光灯"
                0x8012 -> "选择色温"
                0x8013 -> "手动预设"
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
                1 -> "MF (手动)"
                0x8010 -> "AF-S (单次)"
                0x8011 -> "AF-C (连续)"
                0x8012 -> "AF-A (自动切换)"
                0x8013 -> "AF-F (全时)"
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
            // Nikon PTP 0x500B：2=中央重点、3=矩阵、4=点测光、0x8010=高光重点
            val display = when (value) {
                2 -> "中央重点"
                3 -> "矩阵测光"
                4 -> "点测光"
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
     * @param mode 0x8010=AF-S, 0x8011=AF-C, 1=MF
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
        2 to "自动", 0x8016 to "自然光自动适应", 4 to "晴天",
        0x8010 to "阴天", 0x8011 to "背阴", 6 to "白炽灯",
        5 to "荧光灯", 7 to "闪光灯", 0x8012 to "选择色温", 0x8013 to "手动预设"
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
    val storageDescription: String = "",
    val lensName: String = "",
    val firmwareVersion: String = "",
    val modelName: String = "",
    val shutterQueryState: ShutterCountState = ShutterCountState.NONE
)

/**
 * 快门次数查询状态：机身读不到时后台自动导出照片并查询。
 */
enum class ShutterCountState {
    NONE,
    QUERYING,
    SUCCESS,
    FAILED
}

private data class PtpDeviceInfo(
    val manufacturer: String,
    val modelName: String,
    val firmwareVersion: String,
    val serialNumber: String
)

/**
 * PTP StorageInfo（ISO 15740 标准结构）
 */
data class PtpStorageInfo(
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacityBytes: Long,
    val freeSpaceBytes: Long,
    val freeSpaceImages: Int,
    val storageDescription: String,
    val volumeLabel: String
)

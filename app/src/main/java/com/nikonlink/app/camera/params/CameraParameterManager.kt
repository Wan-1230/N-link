package com.nikonlink.app.camera.params

import android.content.Context
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.camera.gallery.CameraFileFormat
import com.nikonlink.app.camera.gallery.TransferManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.roundToInt
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

    /**
     * 最近一次读到的当前焦距（mm）。
     * 按 PRD Q3 决定：焦距只在打开光圈滚轮时读一次，不进 [readAllParameters] 轮询，
     * 因此这里缓存下来供 [apertureOptions] 与 [setAperture] 复用。
     */
    private var cachedFocalMm: Double? = null

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

    /**
     * 镜头信息：除拼展示用的 [CameraInfo.lensName] 外，
     * 还要把焦段与两端最大光圈结构化保存，供 [apertureOptions] 生成与镜头联动的档位。
     *
     * 0xD0E3–0xD0E6 是尼康厂商私有属性，老机身 / CPU 镜头 / 转接镜头可能不支持，
     * 所有读取都走 [readPropOrNull]，取到 0（或异常）一律视为"未知"并走回退路径。
     */
    private suspend fun readLensInfo() {
        val lensId = readPropOrNull(PtpConstants.PROP_NIKON_LENS_ID)?.let(::readUInt)
        val focalMin = readPropOrNull(PtpConstants.PROP_NIKON_FOCAL_LENGTH_MIN)
            ?.let { readUInt(it) / 100.0 }
        val focalMax = readPropOrNull(PtpConstants.PROP_NIKON_FOCAL_LENGTH_MAX)
            ?.let { readUInt(it) / 100.0 }
        val apMin = readPropOrNull(PtpConstants.PROP_NIKON_MAX_AP_AT_MIN)?.let(::readUInt)
        val apMax = readPropOrNull(PtpConstants.PROP_NIKON_MAX_AP_AT_MAX)?.let(::readUInt)

        // 0 / 负数视为未知
        val focalMinMm = focalMin?.takeIf { it > 0.0 }
        val focalMaxMm = focalMax?.takeIf { it > 0.0 }
        val apMinX100 = apMin?.takeIf { it > 0 }
        val apMaxX100 = apMax?.takeIf { it > 0 }

        // 两端最大光圈都要读到才认为光圈范围已知；只有一侧时无法安全插值，仍走回退
        val apertureRangeKnown = apMinX100 != null && apMaxX100 != null

        val lensName = buildLensName(lensId, focalMinMm, focalMaxMm, apMinX100, apMaxX100)
        if (lensName.isNotBlank() || apertureRangeKnown) {
            _cameraInfo.value = _cameraInfo.value.copy(
                lensName = lensName.ifBlank { _cameraInfo.value.lensName },
                lensFocalMinMm = focalMinMm ?: 0.0,
                lensFocalMaxMm = focalMaxMm ?: 0.0,
                lensMaxApAtMinFocalX100 = apMinX100 ?: 0,
                lensMaxApAtMaxFocalX100 = apMaxX100 ?: 0,
                lensApertureRangeKnown = apertureRangeKnown
            )
        }
        if (apertureRangeKnown) {
            Timber.tag(TAG).d(
                "Lens aperture range: ${focalMinMm ?: 0.0}-${focalMaxMm ?: 0.0}mm " +
                    "f/${(apMinX100 ?: 0) / 100.0}-${(apMaxX100 ?: 0) / 100.0}"
            )
        }
    }

    /**
     * 读取设备属性并吞掉异常：厂商私有属性在部分机身上会直接返回错误响应。
     */
    private suspend fun readPropOrNull(propCode: Int): ByteArray? = runCatching {
        readDeviceProp(propCode)
    }.onFailure {
        Timber.tag(TAG).d(it, "Read prop 0x${Integer.toHexString(propCode)} failed")
    }.getOrNull()

    /**
     * 读取当前焦距（mm）。
     * 只在打开光圈滚轮时调用一次（PRD Q3），不进 [readAllParameters] 轮询。
     */
    suspend fun readCurrentFocalLengthMm(): Double? {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected()) return null
        val focalMm = runCatching {
            val data = readDeviceProp(PtpConstants.PROP_FOCAL_LENGTH)
            // 标准 PTP 0x5008：当前焦距 ×100，不支持时为 0
            val raw = data?.let(::readUInt) ?: 0
            if (raw > 0) raw / 100.0 else null
        }.onFailure {
            Timber.tag(TAG).w(it, "Read current focal length failed")
        }.getOrNull()
        cachedFocalMm = focalMm
        return focalMm
    }

    /** 光圈档位是否来自镜头信息（false 表示走通用档位回退） */
    val isLensApertureRangeKnown: Boolean
        get() = _cameraInfo.value.lensApertureRangeKnown

    /**
     * 当前镜头可用光圈档位（f 值 ×100），按 f 值升序（大光圈 → 小光圈）。
     *
     * - 镜头光圈范围未知 → 回退 [ApertureCatalog.FALLBACK]（f/1.4–f/22），行为与 v0.1.2 一致
     * - 变焦镜头 → 按当前焦距在广角端 / 长焦端最大光圈之间线性插值出最小可用 f 值
     * - 定焦镜头两端相等，插值自然退化为定值
     * - 最小光圈 PTP 无对应属性，按 f/22 兜底
     *
     * @param currentFocalMm 当前焦距（mm）；为 null 时复用 [readCurrentFocalLengthMm] 缓存的值。
     */
    fun apertureOptions(currentFocalMm: Double? = null): List<Int> {
        val info = _cameraInfo.value
        if (!info.lensApertureRangeKnown) return ApertureCatalog.FALLBACK

        // 最大光圈 = f 值下限；读不到焦距时 ApertureCatalog 内部会保守取 f 值较大的一侧
        val minFStop = ApertureCatalog.maxApertureAt(info, currentFocalMm ?: cachedFocalMm)
        val maxFStop = ApertureCatalog.DEFAULT_MIN_APERTURE_X100
        val options = ApertureCatalog.FALLBACK.filter { it in minFStop..maxFStop }
        if (options.isEmpty()) {
            // 镜头最大光圈比 f/22 还小（罕见）：至少退回镜头自身的最大光圈这一合法档
            Timber.tag(TAG).w("No common aperture >= f/${minFStop / 100.0}, use lens max aperture only")
            return listOf(minFStop)
        }
        return options
    }

    /** 快门显示格式化：raw 以 1/10000s 为单位，< 1s 显示 1/N，≥ 1s 显示 Ns */
    fun formatShutter(rawX10000: Int): String = ShutterCatalog.format(rawX10000)

    /** 光圈显示格式化：f 值 ×100 → "f/2.8" */
    fun formatAperture(fStopX100: Int): String = ApertureCatalog.format(fStopX100)

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
                "f/${ApertureCatalog.formatNumber(apMinX100)}-${ApertureCatalog.formatNumber(apMaxX100)}"
            apMinX100 != null -> "f/${ApertureCatalog.formatNumber(apMinX100)}"
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
                currentValue = "f/${String.format(Locale.US, "%.1f", fStop)}",
                rawValue = value
            )
        }
    }

    private suspend fun readShutterSpeed() {
        val data = readDeviceProp(PtpConstants.PROP_EXPOSURE_TIME) ?: return
        if (data.size >= 4) {
            val value = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
            // 快门速度以 1/10000s 为单位；显示统一走 formatShutter，
            // 保证"当前值显示"与滚轮刻度口径完全一致（v0.1.2 两处口径不一致导致方向反转）
            _shutterSpeed.value = _shutterSpeed.value.copy(
                currentValue = formatShutter(value),
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
     *
     * 越界值会被 clamp 到镜头支持的最近合法档（并打日志）；
     * 写入成功后回读比对，相机拒绝生效时给出警示。
     */
    suspend fun setAperture(fStopX100: Int): Boolean {
        if (_paramsLocked.value) return false
        val options = apertureOptions()
        val clamped = ApertureCatalog.clampToNearest(fStopX100, options)
        if (clamped != fStopX100) {
            Timber.tag(TAG).w("Aperture clamped: f/${fStopX100 / 100.0} -> f/${clamped / 100.0}")
        }
        val data = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(clamped.toShort()).array()
        val success = writeDeviceProp(PtpConstants.PROP_F_NUMBER, data)
        if (success) {
            readAperture()
            val applied = _aperture.value.rawValue
            if (applied != clamped) {
                Timber.tag(TAG).w("Aperture write not applied, device reports $applied, expected $clamped")
            }
        }
        return success
    }

    /**
     * 设置快门速度
     * @param exposureTime 以 1/10000s 为单位 (如 1/250s = 40)
     *
     * 与光圈一致做上下限限位，避免按值设置（滚轮直接选值）时越界。
     */
    suspend fun setShutterSpeed(exposureTime: Int): Boolean {
        if (_paramsLocked.value) return false
        val values = ShutterCatalog.VALUES
        val clamped = exposureTime.coerceIn(values.first(), values.last())
        if (clamped != exposureTime) {
            Timber.tag(TAG).w("Shutter clamped: $exposureTime -> $clamped")
        }
        val data = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(clamped).array()
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

    /** 常用快门档位 raw 值（1/10000s，升序 = 从快到慢），真源见 [ShutterCatalog.VALUES] */
    val commonShutterSpeeds: List<Int> get() = ShutterCatalog.VALUES

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
 * 快门档位唯一真源（PRD 3.4 S1）。
 *
 * 单位约定：`PROP_EXPOSURE_TIME` 以 **1/10000 秒** 为单位（`1/250s = 40`）。
 * 列表按 raw **升序** = 曝光时间由短到长 = **index 0 最快，lastIndex 最慢**。
 *
 * UI（拍摄页 / 全屏监看页）一律从这里取档位，禁止再各自硬编码一份。
 *
 * ## 档位名与 raw 不是简单倒数关系（重要）
 *
 * 机身档位表是**按 1/3 档递进后取整到"两位有效数字"的干净值**（`0.006s`、`0.016s`、
 * `0.032s`、`1.3s`、`3.2s` …），而不是标称值的精确倒数：
 *
 * | 机身标称 | 标称精确 raw | **机身实际 raw** | 旧版按倒数反算的显示 |
 * | --- | --- | --- | --- |
 * | 1/160 | 62.5 | **60** | ~~1/167~~ ❌ |
 * | 1/800 | 12.5 | **13** | ~~1/769~~ ❌ |
 * | 1/640 | 15.625 | **15** | ~~1/667~~ ❌ |
 * | 1/320 | 31.25 | **30** | ~~1/333~~ ❌ |
 * | 1/60 | 166.67 | **160** | ~~1/63~~ ❌ |
 * | 1/30 | 333.33 | **320** | ~~1/31~~ ❌ |
 *
 * 因此**显示名必须由档位表给出，不能用 `10000 / raw` 反算**（旧版正是这样才出现了
 * 机身根本不存在的 `1/167`）。表外 raw（机身回读的特殊值）仍走数值反算兜底。
 */
object ShutterCatalog {

    /** 一个档位：协议 raw 值 + 机身标称显示名 */
    data class Stop(val raw: Int, val label: String)

    /**
     * 不足 1 秒的档位：raw（1/10000s）→ **机身标称名**。
     *
     * raw 序列即机身真实档位（升序 = 快 → 慢），label 是机身上显示的名字；
     * 两者按位置一一对应，不要拿 label 去反推 raw。
     *
     * 高速段 `2..8`（1/4000 → 1/1250）为 v0.1.5 新增，上限由 1/1000 扩展到 1/4000，
     * 延续同一套"1/3 档取整"约定（1/4000 的精确值 2.5 无法用整数表示，取 2；
     * 1/3200 = 3.125 取 3，1/1600 = 6.25 取 6）。
     */
    private val FRACTION_STOPS: List<Stop> = listOf(
        // —— 高速段（1/4000 → 1/1250）——
        Stop(2, "1/4000"), Stop(3, "1/3200"), Stop(4, "1/2500"),
        Stop(5, "1/2000"), Stop(6, "1/1600"), Stop(8, "1/1250"),
        // —— 常用段（1/1000 → 1/2.5）——
        Stop(10, "1/1000"), Stop(13, "1/800"), Stop(15, "1/640"), Stop(20, "1/500"),
        Stop(25, "1/400"), Stop(30, "1/320"), Stop(40, "1/250"), Stop(50, "1/200"),
        Stop(60, "1/160"), Stop(80, "1/125"), Stop(100, "1/100"), Stop(125, "1/80"),
        Stop(160, "1/60"), Stop(200, "1/50"), Stop(250, "1/40"), Stop(320, "1/30"),
        Stop(400, "1/25"), Stop(500, "1/20"), Stop(640, "1/15"), Stop(800, "1/13"),
        Stop(1000, "1/10"), Stop(1250, "1/8"), Stop(1600, "1/6"), Stop(2000, "1/5"),
        Stop(2500, "1/4"), Stop(3200, "1/3"), Stop(4000, "1/2.5")
    )

    /** 长曝光段 raw（0.5s → 30s，1/3 档步进）：≥1s 的数值本身就是标称值，显示可直接算 */
    private val LONG_EXPOSURE_RAW: List<Int> = listOf(
        5000, 6250, 8000, 10000, 13000, 16000, 20000, 25000, 32000, 40000,
        50000, 64000, 80000, 100000, 130000, 160000, 200000, 250000, 300000
    )

    /**
     * 全部档位，升序 = 快 → 慢。
     *
     * `setShutterSpeed` 的上下限限位取本表首尾（**2 → 300000**，即 1/4000s → 30s），越界自动 clamp。
     * B 门（ShootingState.BULB）不在此表内，独立控制。
     */
    val STOPS: List<Stop> = FRACTION_STOPS +
        LONG_EXPOSURE_RAW.map { Stop(it, formatNumeric(it)) }

    /** 档位 raw 值（升序 = 从快到慢），供滚轮索引 / 限位 / 就近匹配使用 */
    val VALUES: List<Int> = STOPS.map { it.raw }

    private val LABEL_BY_RAW: Map<Int, String> = FRACTION_STOPS.associate { it.raw to it.label }

    /**
     * raw（1/10000s）→ 显示文本。
     *
     * 优先取档位表的**标称名**（保证与机身显示一致，不会出现 `1/167` 这类机身没有的档位名）；
     * 表外 raw（机身回读的特殊值、B 门等）按数值反算兜底，以 **1 秒** 为界：
     *
     * | raw | 曝光时间 | 显示 |
     * | --- | --- | --- |
     * | `40` | 1/250s | `1/250` |
     * | `4000` | 1/2.5s | `1/2.5` |
     * | `10000` | 1s | `1s` |
     * | `13000` | 1.3s | `1.3s` |
     * | `300000` | 30s | `30s` |
     *
     * 即：**不足 1 秒显示分数，1 秒及以上直接显示秒数**（整数秒不带小数位）。
     *
     * 分母的取值精度分两档：
     * - ≥10 时取整 —— 大分母下取整误差可忽略，且 `1/769.2` 这类读数没有意义
     * - <10 时保留一位小数 —— `0.4s` 的真值是 `1/2.5`，若四舍五入成 `1/3` 会偏差 20%，
     *   相机肩屏显示 1/3 而实际按 1/2.5 曝光，属于误导
     *
     * 数字格式化固定 [Locale.US]，避免中文本地化下出现全角符号。
     */
    fun format(rawX10000: Int): String {
        if (rawX10000 <= 0) return "--"
        LABEL_BY_RAW[rawX10000]?.let { return it }
        return formatNumeric(rawX10000)
    }

    /** 数值兜底：≥1s 显示秒数（如 `1.3s`），<1s 显示分数（如 `1/2`、`1/1.2`） */
    private fun formatNumeric(rawX10000: Int): String {
        val seconds = rawX10000 / 10000.0
        if (seconds >= 1.0) return formatSeconds(seconds)
        return "1/${formatDenominator(1.0 / seconds)}"
    }

    /** 秒数显示：整数秒不带小数位（`1s` / `30s`），其余保留一位（`1.3s`） */
    private fun formatSeconds(seconds: Double): String {
        return "${String.format(Locale.US, "%.1f", seconds).removeSuffix(".0")}s"
    }

    /** 分数分母：≥10 取整，<10 保留一位小数并去掉无意义的 `.0` */
    private fun formatDenominator(denominator: Double): String {
        if (denominator >= 10.0) return denominator.roundToInt().toString()
        return String.format(Locale.US, "%.1f", denominator).removeSuffix(".0")
    }

    /**
     * 解析用户输入的快门速度 → 秒。非法输入返回 `null`。
     *
     * 支持写法（与机身 / 主流 App 输入习惯一致）：
     * - `1/250`、`1 / 250`、`1／250`（全角斜杠）
     * - `250`、`4000` —— **纯整数且 > 30 视为分母**（本机最长曝光 30s，
     *   因此 >30 的整数不可能是秒数，只能是 `1/N`）
     * - `0.6`、`2.5`、`30` —— 带小数点或 ≤30 视为**秒**
     * - `1.3s`、`30s` —— 带 `s` 后缀视为秒
     *
     * @return 曝光时间（秒）；越界（快于 1/8000 或慢于 30s）时返回 `null`
     */
    fun parseSeconds(input: String?): Double? {
        val text = input?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        val normalized = text.replace("／", "/").replace(" ", "")
        // 「s / 秒」后缀必须在分母判定之前识别，否则 `60s` 会被当成 1/60
        val hasSecondsSuffix = normalized.endsWith("s") || normalized.endsWith("秒")
        val raw = normalized.removeSuffix("s").removeSuffix("秒")
        val seconds = when {
            raw.contains("/") -> {
                val parts = raw.split("/")
                if (parts.size != 2) return null
                val num = parts[0].toDoubleOrNull() ?: return null
                val den = parts[1].toDoubleOrNull() ?: return null
                if (den <= 0.0 || num <= 0.0) return null
                num / den
            }
            else -> {
                val value = raw.toDoubleOrNull() ?: return null
                // 无秒后缀、纯整数且 > 30（超过本机最长曝光）→ 当作分母 1/N；否则当作秒
                if (!hasSecondsSuffix && value > MAX_EXPOSURE_SECONDS && value % 1.0 == 0.0) {
                    1.0 / value
                } else {
                    value
                }
            }
        }
        if (seconds <= 0.0 || seconds.isNaN() || seconds.isInfinite()) return null
        // 支持范围：1/8000s（0.000125s）→ 30s，超出即判非法
        if (seconds < MIN_EXPOSURE_SECONDS || seconds > MAX_EXPOSURE_SECONDS) return null
        return seconds
    }

    /**
     * 按曝光时间（秒）匹配**最接近**的档位 raw。
     *
     * 用对数距离而非线性差：1/3 档在短曝光端只差几毫秒、在长曝光端差几秒，
     * 线性差会让短端永远匹配到同一个档。对数距离等价于"差几档"。
     *
     * ⚠️ 高速档（1/4000 = 2.5、1/3200 = 3.125）无法用整数 raw 精确表示，纯按距离匹配会把
     * `1/4000` 判给 raw 3（1/3200）。因此调用方应**先用 [matchByLabel] 做标称名精确匹配**，
     * 匹配不到时再回退到本函数。
     */
    fun matchNearest(seconds: Double): Int {
        if (VALUES.isEmpty()) return 0
        val target = kotlin.math.ln(seconds)
        return VALUES.minByOrNull { kotlin.math.abs(kotlin.math.ln(it / 10000.0) - target) }
            ?: VALUES.first()
    }

    /**
     * 按**机身标称名**精确匹配档位 raw，匹配不到返回 `null`。
     *
     * 覆盖 `1/250`、`1/4000`、`1/160` 这类直接写档位名的输入——其中高速档的 raw
     * 是取整值（1/4000 → 2），只有按名字才能精确命中。
     * 大小写、空格、`f/` 式前缀与全角斜杠都做了宽容处理。
     */
    fun matchByLabel(input: String?): Int? {
        val text = input?.trim()?.lowercase(Locale.US)
            ?.replace("／", "/")
            ?.replace(" ", "")
            ?: return null
        if (text.isEmpty()) return null
        return STOPS.firstOrNull { it.label.lowercase(Locale.US) == text }?.raw
    }

    /** 支持的最快 / 最慢曝光时间（秒），用于输入校验 */
    const val MIN_EXPOSURE_SECONDS = 1.0 / 8000.0
    const val MAX_EXPOSURE_SECONDS = 30.0
}

/**
 * 光圈档位唯一真源（PRD 3.4 S1 / S6）。
 * f 值以 ×100 存储（f/2.8 = 280），列表按 f 值升序 = 从大光圈到小光圈。
 */
object ApertureCatalog {

    /** 镜头信息未知时的通用档位 f/1.4 – f/22，行为与 v0.1.2 一致 */
    val FALLBACK: List<Int> = listOf(140, 180, 200, 280, 350, 400, 560, 800, 1100, 1600, 2200)

    /** 最小光圈兜底值：PTP 无对应属性，按行业惯例取 f/22 */
    const val DEFAULT_MIN_APERTURE_X100 = 2200

    /** f 值 ×100 → "f/2.8"（≤0 视为未知） */
    fun format(fStopX100: Int): String {
        if (fStopX100 <= 0) return "--"
        return "f/${formatNumber(fStopX100)}"
    }

    /** f 值 ×100 → "2.8"（不带 f/ 前缀），整数档去掉小数位 */
    fun formatNumber(fStopX100: Int): String {
        val text = String.format(Locale.US, "%.1f", fStopX100 / 100.0)
        return text.removeSuffix(".0")
    }

    /**
     * 当前焦距下镜头可用的最大光圈（= f 值下限，×100）。
     *
     * - 变焦镜头：在广角端 / 长焦端最大光圈之间按焦距线性插值
     * - 定焦镜头：两端相等，插值退化为定值
     * - 焦距未知或焦段无效：保守取 **f 值较大的一侧**，避免给出镜头不支持的档位
     */
    fun maxApertureAt(info: CameraInfo, currentFocalMm: Double?): Int {
        val wide = info.lensMaxApAtMinFocalX100
        val tele = info.lensMaxApAtMaxFocalX100
        val focalMin = info.lensFocalMinMm
        val focalMax = info.lensFocalMaxMm
        if (currentFocalMm == null || focalMin <= 0.0 || focalMax <= focalMin) {
            return maxOf(wide, tele)
        }
        val ratio = ((currentFocalMm - focalMin) / (focalMax - focalMin)).coerceIn(0.0, 1.0)
        return (wide + (tele - wide) * ratio).roundToInt()
    }

    /** 越界时取最接近的合法档（用于写入前的 clamp） */
    fun clampToNearest(fStopX100: Int, options: List<Int>): Int {
        if (options.isEmpty()) return fStopX100
        return options.minByOrNull { abs(it - fStopX100) } ?: fStopX100
    }

    /**
     * 解析用户输入的光圈值 → f 值 ×100。非法输入返回 `null`。
     *
     * 支持：`f/2.8`、`F2.8`、`2.8`、`f8`、`8`。
     * 合法范围 f/1.0 – f/64（超出即判非法，避免把 `128` 这类笔误当 f/128 下发）。
     */
    fun parseFStop(input: String?): Int? {
        val text = input?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        val raw = text
            .removePrefix("f/")
            .removePrefix("f")
            .replace("／", "/")
            .replace(" ", "")
            .removePrefix("/")
        val value = raw.toDoubleOrNull() ?: return null
        if (value < 1.0 || value > 64.0) return null
        return (value * 100).roundToInt()
    }
}

/**
 * ISO 档位工具。
 *
 * ISO 走标准 `PROP_EXPOSURE_INDEX`（UINT16），档位表沿用 [CameraParameterManager.commonIsoValues]
 * （整档列表），手动输入时按**对数距离**就近匹配 —— 与快门同理，ISO 是等比数列，
 * 用线性差会让高感端永远匹配到同一档（例如 6400 与 12800 的线性差远大于 100 与 200）。
 */
object IsoCatalog {

    /** 自动 ISO 在 PTP 中的哨兵值（与 [CameraParameterManager.readIso] 的显示映射一致） */
    const val AUTO_ISO_RAW = 0xFFFF

    /**
     * 解析用户输入的 ISO → raw 值。非法输入返回 `null`。
     *
     * 支持：`800`、`ISO 800`、`iso800`、`auto` / `自动`（→ [AUTO_ISO_RAW]）。
     * 合法范围 50 – 819200（超出即判非法）。
     */
    fun parse(input: String?): Int? {
        val text = input?.trim()?.lowercase(Locale.US) ?: return null
        if (text.isEmpty()) return null
        if (text == "auto" || text == "自动" || text == "isoauto") return AUTO_ISO_RAW
        val raw = text.removePrefix("iso").replace(" ", "")
        val value = raw.toIntOrNull() ?: return null
        if (value < 50 || value > 819200) return null
        return value
    }

    /** 在给定档位表中匹配最接近的 ISO（对数距离；[AUTO_ISO_RAW] 原样返回） */
    fun matchNearest(isoValue: Int, options: List<Int>): Int {
        if (isoValue == AUTO_ISO_RAW || options.isEmpty()) return isoValue
        val target = kotlin.math.ln(isoValue.toDouble())
        return options.minByOrNull { kotlin.math.abs(kotlin.math.ln(it.toDouble()) - target) }
            ?: isoValue
    }
}

/**
 * 滚轮当前值定位（PRD 3.4 S4）。
 *
 * 不再用显示字符串反查（v0.1.2 里"当前值"与"滚轮刻度"口径不一致，恒匹配失败导致跳回首项）。
 * 优先精确匹配 raw 值；匹配不到时按升序档位表取第一个不小于当前值的档。
 */
fun resolvePickerIndex(rawValues: List<Int>, currentRaw: Int): Int {
    if (rawValues.isEmpty()) return 0
    val exact = rawValues.indexOf(currentRaw)
    if (exact >= 0) return exact
    val next = rawValues.indexOfFirst { it >= currentRaw }
    return if (next >= 0) next else rawValues.lastIndex
}

/**
 * 相机信息
 * PRD 2.4: 电量、快门次数、存储卡容量、固件版本
 *
 * 镜头部分除展示用的 [lensName] 外，还保存结构化的焦段与两端最大光圈，
 * 供 [CameraParameterManager.apertureOptions] 生成与镜头联动的光圈档位。
 * 0 表示未知（尼康私有属性 0xD0E3–0xD0E6 在部分机身 / 转接镜头上读不到）。
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
    val shutterQueryState: ShutterCountState = ShutterCountState.NONE,
    /** 广角端焦距（mm），0 = 未知 */
    val lensFocalMinMm: Double = 0.0,
    /** 长焦端焦距（mm），0 = 未知 */
    val lensFocalMaxMm: Double = 0.0,
    /** 广角端最大光圈（f 值 ×100），0 = 未知 */
    val lensMaxApAtMinFocalX100: Int = 0,
    /** 长焦端最大光圈（f 值 ×100），0 = 未知 */
    val lensMaxApAtMaxFocalX100: Int = 0,
    /** 光圈范围是否已知；false 时 [CameraParameterManager.apertureOptions] 走通用档位回退 */
    val lensApertureRangeKnown: Boolean = false
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

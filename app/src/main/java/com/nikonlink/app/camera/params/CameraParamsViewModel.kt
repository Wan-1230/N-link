package com.nikonlink.app.camera.params

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 相机参数管理 ViewModel
 * PRD 2.4: 曝光三要素实时读取与调整、参数锁定/解锁
 */
@HiltViewModel
class CameraParamsViewModel @Inject constructor(
    private val paramManager: CameraParameterManager
) : ViewModel() {

    val aperture: StateFlow<CameraParam> = paramManager.aperture
    val shutterSpeed: StateFlow<CameraParam> = paramManager.shutterSpeed
    val iso: StateFlow<CameraParam> = paramManager.iso
    val evCompensation: StateFlow<CameraParam> = paramManager.evCompensation
    val whiteBalance: StateFlow<CameraParam> = paramManager.whiteBalance
    val focusMode: StateFlow<CameraParam> = paramManager.focusMode
    val exposureProgram: StateFlow<CameraParam> = paramManager.exposureProgram
    val meteringMode: StateFlow<CameraParam> = paramManager.meteringMode
    val paramsLocked: StateFlow<Boolean> = paramManager.paramsLocked
    val cameraInfo: StateFlow<CameraInfo> = paramManager.cameraInfo

    // ==================== 档位真源（PRD 3.4 S1） ====================
    // 所有档位表只在 CameraParameterManager / ShutterCatalog / ApertureCatalog 维护一份，
    // 拍摄页与全屏监看页都从这里取，不再各自硬编码。

    /** 快门档位 raw 值（1/10000s），**升序 = 曝光时间由短到长 = index 0 最快，lastIndex 最慢** */
    val commonShutterSpeeds: List<Int> get() = ShutterCatalog.VALUES

    /** ISO 档位 */
    val commonIsoValues: List<Int> get() = paramManager.commonIsoValues

    /** 白平衡预设：PTP 枚举值 → 显示名 */
    val whiteBalancePresets: List<Pair<Int, String>> get() = paramManager.whiteBalancePresets

    /** 快门显示文本（与相机回读值同一口径，见 [CameraParameterManager.formatShutter]） */
    fun formatShutter(rawX10000: Int): String = paramManager.formatShutter(rawX10000)

    /** 光圈显示文本：f 值 ×100 → "f/2.8" */
    fun formatAperture(fStopX100: Int): String = paramManager.formatAperture(fStopX100)

    private val _apertureOptions = MutableStateFlow(ApertureCatalog.FALLBACK)

    /**
     * 当前镜头可用光圈档位（f 值 ×100，升序 = 大光圈 → 小光圈）。
     * 打开光圈滚轮前先调用 [refreshApertureOptions] 刷新，再从这里读取。
     */
    val apertureOptions: StateFlow<List<Int>> = _apertureOptions.asStateFlow()

    /** 光圈档位是否来自镜头信息；false 时 UI 需提示"未获取到镜头信息，使用通用档位" */
    val apertureRangeFromLens: Boolean get() = paramManager.isLensApertureRangeKnown

    /**
     * 重新生成光圈档位并写入 [apertureOptions]。
     *
     * 按 PRD Q3 的决定：当前焦距（`PROP_FOCAL_LENGTH`）只在**打开光圈滚轮时读一次**，
     * 不进 `readAllParameters()` 的 2 秒轮询，避免增加 PTP 通道压力。
     */
    suspend fun refreshApertureOptions() = withContext(Dispatchers.IO) {
        if (paramManager.isLensApertureRangeKnown) {
            paramManager.readCurrentFocalLengthMm()
        }
        val options = paramManager.apertureOptions()
        _apertureOptions.value = options
        options
    }

    init {
        paramManager.start(viewModelScope)
    }

    fun startPolling(intervalMs: Long = 2000L) {
        paramManager.startPolling(intervalMs)
    }

    fun stopPolling() {
        paramManager.stopPolling()
    }

    fun readAll() {
        viewModelScope.launch { paramManager.readAllParameters() }
    }

    /** 快门次数查询失败后手动重试 */
    fun retryShutterCountQuery() {
        paramManager.retryShutterCountQuery()
    }

    fun toggleLock() {
        paramManager.toggleLock()
    }

    /**
     * 调整 ISO（按预设列表步进）
     * @param direction +1 升一档, -1 降一档
     */
    fun adjustIso(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.iso.value.rawValue
            val list = paramManager.commonIsoValues
            val newIdx = stepIndex(current, list, direction)
            paramManager.setIso(list[newIdx])
        }
    }

    /**
     * 调整光圈（按档位步进）
     *
     * 档位表按 f 值升序（大光圈 → 小光圈）。
     * @param direction **> 0 向"f 值变大 / 光圈收小"步进；< 0 向"f 值变小 / 光圈放大"步进**
     */
    fun adjustAperture(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.aperture.value.rawValue
            val list = paramManager.apertureOptions()
            val newIdx = stepIndex(current, list, direction)
            paramManager.setAperture(list[newIdx])
        }
    }

    /**
     * 调整快门速度（按档位步进）
     *
     * 档位表按**曝光时间升序**排列（index 0 最快，lastIndex 最慢）。
     * @param direction **> 0 向"曝光变长 / 快门变慢"步进；< 0 向"曝光变短 / 快门变快"步进**
     */
    fun adjustShutter(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.shutterSpeed.value.rawValue
            val list = paramManager.commonShutterSpeeds
            val newIdx = stepIndex(current, list, direction)
            paramManager.setShutterSpeed(list[newIdx])
        }
    }

    private fun stepIndex(currentRaw: Int, values: List<Int>, direction: Int): Int {
        if (values.isEmpty()) return 0
        val idx = values.indexOfFirst { it >= currentRaw }
        val base = if (idx == -1) values.lastIndex else idx
        return (base + direction).coerceIn(0, values.lastIndex)
    }

    fun setWhiteBalance(mode: Int) {
        viewModelScope.launch { paramManager.setWhiteBalance(mode) }
    }

    /** 滚轮选择器：直接按值设定光圈 / 快门 / ISO */
    fun setApertureByValue(fStopX100: Int) {
        viewModelScope.launch { paramManager.setAperture(fStopX100) }
    }

    fun setShutterByValue(exposureTime: Int) {
        viewModelScope.launch { paramManager.setShutterSpeed(exposureTime) }
    }

    fun setIsoByValue(iso: Int) {
        viewModelScope.launch { paramManager.setIso(iso) }
    }

    /** 测光模式循环：矩阵 → 中央重点 → 点测光 → 高光重点 */
    fun cycleMeteringMode() {
        viewModelScope.launch {
            val codes = listOf(3, 2, 4, 0x8010)
            val current = paramManager.meteringMode.value.rawValue
            val idx = codes.indexOf(current)
            paramManager.setMeteringMode(codes[(idx + 1).coerceAtLeast(0) % codes.size])
        }
    }

    fun setFocusMode(mode: Int) {
        viewModelScope.launch { paramManager.setFocusMode(mode) }
    }

    fun setMeteringMode(mode: Int) {
        viewModelScope.launch { paramManager.setMeteringMode(mode) }
    }

    fun cycleWhiteBalance() {
        viewModelScope.launch {
            val presets = paramManager.whiteBalancePresets
            if (presets.isEmpty()) return@launch
            val current = paramManager.whiteBalance.value.rawValue
            val idx = presets.indexOfFirst { it.first == current }
            val next = presets[(idx + 1).coerceAtLeast(0) % presets.size]
            paramManager.setWhiteBalance(next.first)
        }
    }

    fun cycleFocusMode() {
        viewModelScope.launch {
            val modes = listOf(0x8010, 0x8011, 1)
            val current = paramManager.focusMode.value.rawValue
            val idx = modes.indexOf(current)
            paramManager.setFocusMode(modes[(idx + 1).coerceAtLeast(0) % modes.size])
        }
    }
}

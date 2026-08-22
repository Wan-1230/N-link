package com.nikonlink.app.camera.params

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
     * 调整光圈
     */
    fun adjustAperture(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.aperture.value.rawValue
            val list = paramManager.commonApertures
            val newIdx = stepIndex(current, list, direction)
            paramManager.setAperture(list[newIdx])
        }
    }

    /**
     * 调整快门速度
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

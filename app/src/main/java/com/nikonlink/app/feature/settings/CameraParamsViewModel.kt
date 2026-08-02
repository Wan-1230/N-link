package com.nikonlink.app.feature.settings

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
            val modes = listOf(1, 2, 3)
            val current = paramManager.focusMode.value.rawValue
            val idx = modes.indexOf(current)
            paramManager.setFocusMode(modes[(idx + 1).coerceAtLeast(0) % modes.size])
        }
    }
}

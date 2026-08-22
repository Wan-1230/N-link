package com.nikonlink.app.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 遥控拍摄 ViewModel
 * PRD 2.2: 远程快门、定时、间隔、B门
 */
@HiltViewModel
class RemoteShootingViewModel @Inject constructor(
    private val remoteManager: RemoteShootingManager
) : ViewModel() {

    val shootingState: StateFlow<ShootingState> = remoteManager.shootingState
    val shotCount: StateFlow<Int> = remoteManager.shotCount
    val intervalProgress: StateFlow<IntervalProgress> = remoteManager.intervalProgress
    val bulbExposureTime: StateFlow<Long> = remoteManager.bulbExposureTime
    val timerCountdown: StateFlow<Int> = remoteManager.timerCountdown
    val batteryLevel: StateFlow<Int> = remoteManager.batteryLevel
    val remainingShots: StateFlow<Int> = remoteManager.remainingShots

    init {
        remoteManager.start(viewModelScope)
    }

    fun capture() {
        viewModelScope.launch { remoteManager.capture() }
    }

    fun halfPressFocus() {
        viewModelScope.launch { remoteManager.halfPressFocus() }
    }

    /** 任务6: 长按持续对焦 */
    fun startContinuousFocus() {
        remoteManager.startContinuousFocus()
    }

    fun stopContinuousFocus() {
        remoteManager.stopContinuousFocus()
    }

    fun startTimerCapture(seconds: Int) {
        remoteManager.startTimerCapture(seconds)
    }

    fun cancelTimer() {
        remoteManager.cancelTimer()
    }

    fun startInterval(config: IntervalConfig) {
        remoteManager.startIntervalCapture(config)
    }

    fun cancelInterval() {
        remoteManager.cancelInterval()
    }

    fun bulbStart() {
        viewModelScope.launch { remoteManager.bulbStart() }
    }

    fun bulbStop() {
        viewModelScope.launch { remoteManager.bulbStop() }
    }

    fun startVideo() {
        viewModelScope.launch { remoteManager.startVideoRecording() }
    }

    fun stopVideo() {
        viewModelScope.launch { remoteManager.stopVideoRecording() }
    }

    fun refreshStatus() {
        viewModelScope.launch { remoteManager.refreshCameraStatus() }
    }

    fun resetCount() {
        remoteManager.resetShotCount()
    }
}

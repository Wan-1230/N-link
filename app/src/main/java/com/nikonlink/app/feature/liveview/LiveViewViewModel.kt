package com.nikonlink.app.feature.liveview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Live View ViewModel
 * PRD 2.3: 实时画面、触摸对焦、构图辅助、画面放大
 */
@HiltViewModel
class LiveViewViewModel @Inject constructor(
    private val liveViewManager: LiveViewManager
) : ViewModel() {

    val liveViewState: StateFlow<LiveViewState> = liveViewManager.liveViewState
    val latestFrame: SharedFlow<LiveViewFrame> = liveViewManager.latestFrame
    val fps: StateFlow<Int> = liveViewManager.fps
    val latency: StateFlow<Long> = liveViewManager.latency
    val gridOverlay: StateFlow<GridOverlay> = liveViewManager.gridOverlay
    val zoomLevel: StateFlow<Float> = liveViewManager.zoomLevel
    val errorMessage: StateFlow<String?> = liveViewManager.errorMessage

    init {
        liveViewManager.start(viewModelScope)
    }

    fun startLiveView() {
        viewModelScope.launch { liveViewManager.startLiveView() }
    }

    fun stopLiveView() {
        liveViewManager.stopLiveView()
    }

    fun touchFocus(x: Float, y: Float) {
        viewModelScope.launch { liveViewManager.touchFocus(x, y) }
    }

    fun autoFocus() {
        viewModelScope.launch { liveViewManager.touchFocus(0.5f, 0.5f) }
    }

    fun cycleGrid() {
        liveViewManager.cycleGridOverlay()
    }

    fun zoomIn() = liveViewManager.zoomIn()
    fun zoomOut() = liveViewManager.zoomOut()
    fun resetZoom() = liveViewManager.resetZoom()
}

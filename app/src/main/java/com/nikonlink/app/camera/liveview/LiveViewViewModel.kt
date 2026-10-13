package com.nikonlink.app.camera.liveview

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

    /**
     * 本 VM 在监看会话里的宿主标识（A5/S1）。
     *
     * 旧实现在 `init` 里直接 `manager.start(viewModelScope)`，两页各建一个本 VM 时
     * 后建的把先建的 scope 顶掉；现在改为显式登记宿主，宿主身份可追溯、可交接。
     */
    private var hostId: String? = null

    /**
     * 以 [id] 身份登记为监看宿主。页面每次 `onViewCreated` 都调，幂等：
     * 旋屏重建时 VM 是同一个实例，重复调用不会重复登记，也不会误判成「接管」。
     */
    fun attachAsHost(id: String) {
        if (hostId == id) return
        hostId = id
        liveViewManager.attachHost(id)
    }

    fun startLiveView() {
        viewModelScope.launch { liveViewManager.startLiveView() }
    }

    fun stopLiveView() {
        liveViewManager.stopLiveView()
    }

    /**
     * VM 被回收才是「这个页面真的走了」（旋屏不回收 VM，所以旋屏不会走到这里）。
     * 放这里而不是 `onDestroyView`：后者在旋屏时也会触发，在那里停流正是 X1
     * 「旋屏黑屏 1-2s」的根因。
     */
    override fun onCleared() {
        hostId?.let { liveViewManager.detachHost(it) }
        hostId = null
        super.onCleared()
    }

    fun touchFocus(x: Float, y: Float) {
        viewModelScope.launch { liveViewManager.touchFocus(x, y) }
    }

    /** MF 相对驱动（0x9204）：跟焦轮旋转换算成 direction + speed */
    fun manualFocusDrive(direction: Int, speed: Int) {
        viewModelScope.launch { liveViewManager.manualFocusDrive(direction, speed) }
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

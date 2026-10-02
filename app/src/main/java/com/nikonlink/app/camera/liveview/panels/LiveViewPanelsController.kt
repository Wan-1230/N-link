package com.nikonlink.app.camera.liveview.panels

import android.content.Context
import android.view.View
import com.nikonlink.app.R
import com.nikonlink.app.camera.liveview.LiveViewState
import com.nikonlink.app.camera.liveview.LiveViewViewModel
import com.nikonlink.app.camera.liveview.LiveViewZoomController
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.camera.params.resolvePickerIndex
import com.nikonlink.app.databinding.FragmentLiveviewBinding
import com.nikonlink.app.shared.ui.NlFeedback
import com.nikonlink.app.shared.ui.pressEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 全屏监看底部 sheet 面板控制器（v2.7 重构）。
 *
 * 承接原 NumberPicker 弹窗 / 测光对话框 / 更多 PopupMenu 的全部能力，
 * 交互换成参考图范式：刻度尺、分段控件、跟焦轮、列表行。
 * 档位真源与下发逻辑仍全部走 CameraParamsViewModel，本类只做 UI 接线。
 */
class LiveViewPanelsController(
    private val binding: FragmentLiveviewBinding,
    private val params: CameraParamsViewModel,
    private val live: LiveViewViewModel,
    private val scope: CoroutineScope,
    private val context: Context,
    private val zoomProvider: () -> LiveViewZoomController?,
) {

    companion object {
        /** 对焦模式 PTP 码：AF-S / AF-C / MF */
        private val AF_CODES = listOf(0x8010, 0x8011, 1)

        /** 测光模式 PTP 码：矩阵 / 中央重点 / 点测光 / 高光重点 */
        private val METERING_CODES = listOf(3, 2, 4, 0x8010)
        private val METERING_LABELS = listOf("矩阵测光", "中央重点", "点测光", "高光重点")
    }

    private val allPanels = listOf(
        binding.panelFocus, binding.panelExposure, binding.panelWb,
        binding.panelMetering, binding.panelOverlays, binding.panelMore
    )

    private var current: View? = null

    /**
     * 面板开合通知（true = 有面板展开）。由 Fragment 接线：面板展开时让参数条与工具栏
     * 让位，这样快门行能一直留在控制簇最底部，不再被面板覆盖。
     */
    var onOpenStateChanged: ((Boolean) -> Unit)? = null

    fun isAnyOpen(): Boolean = current != null

    fun closeAll() {
        if (current == null) return
        closeInternal()
        onOpenStateChanged?.invoke(false)
    }

    /** 收起但不发通知：换面板时避免先 false 后 true 抖动一次 */
    private fun closeInternal() {
        current?.visibility = View.GONE
        current = null
    }

    /** 同一面板再点一次 = 收起；换面板 = 互斥切换 */
    fun toggle(panel: View, onOpen: (() -> Unit)? = null) {
        if (current === panel) {
            closeAll()
            return
        }
        closeInternal()
        panel.visibility = View.VISIBLE
        current = panel
        onOpenStateChanged?.invoke(true)
        onOpen?.invoke()
    }

    /** 曝光面板入口：打开时顺带按当前焦距刷新光圈档位 */
    fun openExposure() = toggle(binding.panelExposure) { refreshApertureOptions() }

    /** 供玻璃 HUD 挂载：全部面板根视图 */
    fun panelRoots(): List<View> = allPanels

    fun setup() {
        setupParamChips()
        setupExposurePanel()
        setupWbPanel()
        setupMeteringPanel()
        setupMorePanel()
        setupFocusPanel()
        observeParams()
    }

    // ---------------- 参数快览条芯片 ----------------

    private fun setupParamChips() {
        // 快门/光圈/ISO 三格都打开曝光面板（三条尺子各自定位到对应参数）
        listOf(binding.cellShutter, binding.cellAperture, binding.cellIso).forEach { cell ->
            cell.pressEffect()
            cell.setOnClickListener { openExposure() }
        }
        // EV 只读：与旧版一致，点击给明确提示
        binding.cellEv.pressEffect()
        binding.cellEv.setOnClickListener {
            NlFeedback.show(context, "EV 请通过相机机内拨盘调整")
        }
        binding.cellWb.pressEffect()
        binding.cellWb.setOnClickListener { toggle(binding.panelWb) }
    }

    // ---------------- 曝光面板（快门/光圈/ISO 刻度尺） ----------------

    private fun setupExposurePanel() {
        binding.rulerShutter.onSelected = { index ->
            val raws = params.commonShutterSpeeds
            val raw = raws.getOrNull(index)
            if (raw != null && raw != params.shutterSpeed.value.rawValue) {
                params.setShutterByValue(raw)
            }
        }
        binding.rulerAperture.onSelected = { index ->
            val options = params.apertureOptions.value
            val raw = options.getOrNull(index)
            if (raw != null && raw != params.aperture.value.rawValue) {
                params.setApertureByValue(raw)
            }
        }
        binding.rulerIso.onSelected = { index ->
            val raws = params.commonIsoValues
            val raw = raws.getOrNull(index)
            if (raw != null && raw != params.iso.value.rawValue) {
                params.setIsoByValue(raw)
            }
        }
    }

    /** 光圈档位与镜头联动：打开面板时读一次当前焦距再生成档位（PRD Q3） */
    private fun refreshApertureOptions() {
        scope.launch {
            params.refreshApertureOptions()
            val options = params.apertureOptions.value
            val raw = params.aperture.value.rawValue
            binding.rulerAperture.setItems(
                options.map { params.formatAperture(it) },
                resolvePickerIndex(options, raw)
            )
            if (!params.apertureRangeFromLens) {
                NlFeedback.show(context, "未获取到镜头信息，使用通用档位")
            }
        }
    }

    // ---------------- 白平衡面板 ----------------

    private fun setupWbPanel() {
        binding.rulerWb.onSelected = { index ->
            val presets = params.whiteBalancePresets
            val preset = presets.getOrNull(index)
            if (preset != null && preset.first != params.whiteBalance.value.rawValue) {
                params.setWhiteBalance(preset.first)
            }
        }
    }

    // ---------------- 测光面板 ----------------

    private fun setupMeteringPanel() {
        val currentIdx = METERING_CODES.indexOf(params.meteringMode.value.rawValue)
            .coerceAtLeast(0)
        binding.segMetering.setItems(METERING_LABELS, currentIdx) { index ->
            params.setMeteringMode(METERING_CODES[index])
        }
    }

    // ---------------- 更多面板（监看启停 + 缩放三件） ----------------

    private fun setupMorePanel() {
        listOf(binding.rowLvToggle, binding.rowZoomIn, binding.rowZoomOut, binding.rowZoomReset)
            .forEach { it.pressEffect() }
        binding.rowLvToggle.setOnClickListener { toggleLiveView() }
        binding.rowZoomIn.setOnClickListener { zoomProvider()?.zoomBy(1.25f) }
        binding.rowZoomOut.setOnClickListener { zoomProvider()?.zoomBy(0.8f) }
        binding.rowZoomReset.setOnClickListener { zoomProvider()?.reset() }
    }

    private fun toggleLiveView() {
        if (live.liveViewState.value == LiveViewState.RUNNING) {
            live.stopLiveView()
        } else {
            live.startLiveView()
        }
    }

    /** 监看状态变化：更多面板首行的文案与图标随之联动（旧 btnStartStop 的语义） */
    fun onLiveViewState(state: LiveViewState) {
        val label: String
        val icon: Int
        when (state) {
            LiveViewState.RUNNING -> {
                label = "停止监看"; icon = R.drawable.ic_lv_stop
            }
            LiveViewState.STARTING -> {
                label = "启动中…"; icon = R.drawable.ic_lv_stop
            }
            LiveViewState.ERROR -> {
                label = "重试监看"; icon = R.drawable.ic_lv_play
            }
            LiveViewState.STOPPED -> {
                label = "开始监看"; icon = R.drawable.ic_lv_play
            }
        }
        binding.tvLvToggleLabel.text = label
        binding.ivLvToggleIcon.setImageResource(icon)
    }

    // ---------------- 焦点面板（AF 分段 + 跟焦轮） ----------------

    private fun setupFocusPanel() {
        val currentIdx = AF_CODES.indexOf(params.focusMode.value.rawValue).coerceAtLeast(0)
        binding.segAf.setItems(listOf("AF-S", "AF-C", "MF"), currentIdx) { index ->
            params.setFocusMode(AF_CODES[index])
        }
        // 跟焦轮 = 相对驱动：旋转方向/速度映射到 0x9204 的 direction/speed
        binding.dialFocus.onDrive = { direction, speed ->
            live.manualFocusDrive(direction, speed)
        }
        binding.dialFocus.onWheelPosition = { pos ->
            binding.tvDialValue.text = String.format("%.2f", pos)
        }
    }

    // ---------------- 状态流 → 面板内视图 ----------------

    private fun observeParams() {
        scope.launch {
            params.shutterSpeed.collect { p ->
                binding.tvShutterCur.text = p.currentValue.ifBlank { "--" }
                val raws = params.commonShutterSpeeds
                binding.rulerShutter.setItems(
                    raws.map { params.formatShutter(it) },
                    resolvePickerIndex(raws, p.rawValue)
                )
            }
        }
        scope.launch {
            params.aperture.collect { p ->
                binding.tvApertureCur.text = p.currentValue.ifBlank { "--" }
                val options = params.apertureOptions.value
                if (options.isNotEmpty()) {
                    binding.rulerAperture.setItems(
                        options.map { params.formatAperture(it) },
                        resolvePickerIndex(options, p.rawValue)
                    )
                }
            }
        }
        scope.launch {
            params.iso.collect { p ->
                binding.tvIsoCur.text = p.currentValue.ifBlank { "--" }
                val raws = params.commonIsoValues
                binding.rulerIso.setItems(
                    raws.map { it.toString() },
                    resolvePickerIndex(raws, p.rawValue)
                )
            }
        }
        scope.launch {
            params.evCompensation.collect { p ->
                binding.tvEvCur.text = p.currentValue.ifBlank { "--" }
            }
        }
        scope.launch {
            params.whiteBalance.collect { p ->
                binding.tvWbCur.text = p.currentValue.ifBlank { "--" }
                val presets = params.whiteBalancePresets
                if (presets.isNotEmpty()) {
                    binding.rulerWb.setItems(
                        presets.map { it.second },
                        resolvePickerIndex(presets.map { it.first }, p.rawValue)
                    )
                }
            }
        }
        scope.launch {
            params.meteringMode.collect { p ->
                val idx = METERING_CODES.indexOf(p.rawValue).coerceAtLeast(0)
                binding.segMetering.updateSelection(idx)
            }
        }
        scope.launch {
            params.focusMode.collect { p ->
                val idx = AF_CODES.indexOf(p.rawValue).coerceAtLeast(0)
                binding.segAf.updateSelection(idx)
                // 跟焦轮只在 MF 下发包，其余模式置灰（AF 模式下抢驱动会打架）
                binding.dialFocus.driveEnabled = p.rawValue == 1
            }
        }
    }
}

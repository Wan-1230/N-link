package com.nikonlink.app.camera.liveview

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.MainActivity
import com.nikonlink.app.databinding.DialogParamPickerBinding
import com.nikonlink.app.databinding.FragmentLiveviewBinding
import com.nikonlink.app.capture.ShootingState
import com.nikonlink.app.capture.RemoteShootingViewModel
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.camera.params.resolvePickerIndex
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * 全屏实时取景器。
 * 参考相机取景器布局：顶部功能栏、九宫格、触摸对焦、实时参数、快门区、DISP 纯净模式。
 */
@AndroidEntryPoint
class LiveViewFragment : Fragment() {

    companion object {
        private const val EXTRA_AUTO_START = "auto_start"
        private const val FOCUS_INDICATOR_SIZE = 64f

        fun newAutoStart(): LiveViewFragment {
            return LiveViewFragment().apply {
                arguments = Bundle().apply { putBoolean(EXTRA_AUTO_START, true) }
            }
        }
    }

    private var _binding: FragmentLiveviewBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LiveViewViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()
    private val shootingViewModel: RemoteShootingViewModel by viewModels()

    /**
     * 直方图开关状态（优化项 4/修复）。
     * 与遥控页共用同一持久化键（AppSettings.histogramEnabled），
     * 全屏与非全屏来回切换不会改变开关状态，两边按钮互相同步。
     */
    @Inject lateinit var settings: AppSettings

    /** 相机连接判定（USB / WiFi PTP 任一通道连通即视为可远程控制） */
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var usbPtpManager: UsbPtpManager

    /** 模式切换进行中标记：防止重复下发，下发期间入口置灰 */
    private var modeSwitching = false

    private var controlsVisible = true
    private var gridVisible = true
    private var levelVisible = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupControls()
        setupShutter()
        setupParams()
        setupTouchAndScale()
        observeState()
        updateModeEntryState()
        binding.viewGridOverlay.setImageSource(binding.ivLiveView)
        paramsViewModel.readAll()
        shootingViewModel.refreshStatus()
        if (arguments?.getBoolean(EXTRA_AUTO_START, false) == true) {
            viewModel.startLiveView()
        }
        // 模块 2：全屏监看页可见期间开启 0x500E 快轮询（机身拨盘切模式 ≤500ms 同步）
        paramsViewModel.startModeWatch()
    }

    override fun onResume() {
        super.onResume()
        // 全屏页是独立 Activity，标准生命周期即可；回到前台续上快轮询
        paramsViewModel.startModeWatch()
    }

    override fun onPause() {
        // 离开前台停止快轮询，避免后台仍占用 PTP 命令通道
        paramsViewModel.stopModeWatch()
        super.onPause()
    }

    // ---------------- 顶部与辅助控件 ----------------

    private fun setupControls() {
        binding.btnClose.pressEffect()
        binding.btnClose.setOnClickListener { requireActivity().finish() }

        binding.btnAfMode.pressEffect()
        binding.btnAfMode.setOnClickListener { paramsViewModel.cycleFocusMode() }

        // 模式标签可点：监看中远程切换拍摄模式（0x500E）。
        // 仅在监看进行中可用（未监看 / 未连接时置灰，见 updateModeEntryState）。
        binding.tvModeTag.pressEffect()
        binding.tvModeTag.setOnClickListener { showModePicker() }

        binding.btnGridToggle.pressEffect()
        binding.btnGridToggle.setOnClickListener {
            gridVisible = !gridVisible
            binding.viewGridOverlay.setGridVisible(gridVisible)
            binding.viewGridOverlay.visibility = if (gridVisible) View.VISIBLE else View.GONE
        }

        // 优化项 4/修复：全屏页同样提供直方图开关，状态与遥控页共用
        binding.btnHistogram.pressEffect()
        binding.btnHistogram.setOnClickListener {
            val enabled = !settings.histogramEnabled
            settings.histogramEnabled = enabled
            applyHistogramToggle(enabled)
            Toast.makeText(
                requireContext(),
                if (enabled) "已开启亮度直方图" else "已关闭亮度直方图",
                Toast.LENGTH_SHORT
            ).show()
        }
        applyHistogramToggle(settings.histogramEnabled)

        binding.btnMore.pressEffect()
        binding.btnMore.setOnClickListener { showMoreMenu() }

        binding.btnDisp.pressEffect()
        binding.btnDisp.setOnClickListener { toggleControls() }

        binding.btnRotate.pressEffect()
        binding.btnRotate.setOnClickListener {
            val isLandscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requireActivity().requestedOrientation =
                if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        binding.btnLevel.pressEffect()
        binding.btnLevel.setOnClickListener {
            levelVisible = !levelVisible
            binding.viewGridOverlay.setLevelVisible(levelVisible)
        }

        binding.btnStartStop.pressEffect()
        binding.btnStartStop.setOnClickListener {
            if (viewModel.liveViewState.value == LiveViewState.RUNNING) {
                viewModel.stopLiveView()
            } else {
                viewModel.startLiveView()
            }
        }

        binding.btnAlbum.pressEffect()
        binding.btnAlbum.setOnClickListener {
            val intent = Intent(requireContext(), MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_ALBUM)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            requireActivity().finish()
        }
    }

    /**
     * 优化项 4/修复：应用直方图开关状态。
     * 该状态只由用户点击开关改变；全屏/非全屏切换、监看启停均不触碰它
     * （两页共用 AppSettings.histogramEnabled，进出全屏自动对齐）。
     * 关闭时清空柱体，避免重新打开时闪上一轮残留。
     */
    private fun applyHistogramToggle(enabled: Boolean) {
        binding.viewHistogram.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnHistogram.alpha = if (enabled) 1f else 0.55f
        if (!enabled) binding.viewHistogram.clear()
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(requireContext(), binding.btnMore)
        popup.menu.add(0, 1, 0, "开始 / 停止监看")
        popup.menu.add(0, 2, 0, if (gridVisible) "隐藏网格" else "显示网格")
        popup.menu.add(0, 3, 0, "画面放大")
        popup.menu.add(0, 4, 0, "画面缩小")
        popup.menu.add(0, 5, 0, "重置缩放")
        // 模块 3 入口迁移：B 门长曝光入口已移至遥控页「更多动作」按钮（可定时/手动），
        // 全屏监看菜单不再提供，避免同一功能两处入口状态不同步
        popup.menu.add(0, 7, 0, "测光模式")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> binding.btnStartStop.performClick()
                2 -> binding.btnGridToggle.performClick()
                3 -> zoomController?.zoomBy(1.25f)
                4 -> zoomController?.zoomBy(0.8f)
                5 -> zoomController?.reset()
                7 -> showMeteringMenu()
            }
            true
        }
        popup.show()
    }

    private fun showMeteringMenu() {
        val options = arrayOf("矩阵测光", "中央重点", "点测光", "高光重点")
        val codes = listOf(3, 2, 4, 0x8010)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("测光模式")
            .setItems(options) { _, which ->
                paramsViewModel.setMeteringMode(codes[which])
            }
            .show()
    }

    /**
     * 拍摄模式远程切换（0x500E，模块 2：失败显式回调）。
     * 与遥控页共用同一套档位与回读流（单一数据源）。
     */
    /**
     * 拍摄模式远程切换（0x500E，模块 2：失败显式回调 + 下发后回读校验）。
     * 与遥控页共用同一套档位与回读流（单一数据源）：
     * 1) 列表预选当前模式；2) 下发中禁用重复点击并显示 loading；
     * 3) 下发后等 0x500E 快轮询（≤500ms）回读，3 秒内未切过去则明确提示失败，
     *    UI 仍以 exposureProgram 回读流为准（不落本地假状态）。
     */
    private fun showModePicker() {
        // 边界：列表为空 / 切换进行中直接拦截（入口已置灰，这里再兜一层）
        if (modeSwitching) return
        val modes = paramsViewModel.exposureProgramModes
        if (modes.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可切换的拍摄模式", Toast.LENGTH_SHORT).show()
            return
        }
        val current = paramsViewModel.exposureProgram.value
        val checkedIndex = modes.indexOfFirst { it.first == current.rawValue }
        val labels = modes.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("拍摄模式（远程切换）")
            .setMessage("当前: ${current.currentValue.ifBlank { "--" }}")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                if (modeSwitching) return@setSingleChoiceItems
                dialog.dismiss()
                applyExposureProgram(modes[which].first, modes[which].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 下发拍摄模式并做回读校验。
     * 写入被拒 → 提示参数锁定/只读；写入成功但 3 秒内 0x500E 没切过去 → 提示相机未响应，
     * 二者都显式反馈，不把 UI 显示成已切换（显示值由回读流驱动）。
     */
    private fun applyExposureProgram(target: Int, label: String) {
        modeSwitching = true
        updateModeEntryState()
        // 下发中先给一个轻量 loading 提示，避免「点了没反应」的错觉
        Toast.makeText(requireContext(), "正在切换拍摄模式…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ok = paramsViewModel.setExposureProgramResult(target)
                if (!ok) {
                    if (_binding == null) return@launch
                    Toast.makeText(
                        requireContext(),
                        "相机拒绝切换（参数可能已锁定或模式只读），请用机身拨盘调整",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                // 回读校验：0x500E 快轮询（≤500ms）会刷新 exposureProgram 流，
                // 最多等 3 秒确认相机确实切到了目标模式
                val confirmed = withTimeoutOrNull(3000) {
                    paramsViewModel.exposureProgram.first { it.rawValue == target }
                } != null
                if (_binding == null) return@launch
                if (confirmed) {
                    Toast.makeText(requireContext(), "已切换到 $label", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "相机未响应：当前模式下可能不支持远程切换，请确认后用机身拨盘调整",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                modeSwitching = false
                updateModeEntryState()
            }
        }
    }

    /**
     * 根据监看状态 / 切换中标记 / 模式列表是否为空，更新模式入口可用性。
     * 监看未开始、相机未连接、列表为空、切换进行中 → 置灰且点击无效（isEnabled=false）。
     */
    private fun updateModeEntryState() {
        if (_binding == null) return
        val enabled = viewModel.liveViewState.value == LiveViewState.RUNNING
            && !modeSwitching
            && paramsViewModel.exposureProgramModes.isNotEmpty()
        binding.tvModeTag.isEnabled = enabled
        binding.tvModeTag.isClickable = enabled
        binding.tvModeTag.alpha = if (enabled) 1f else 0.5f
    }

    // ---------------- 快门 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        binding.btnShutter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    flashScreen()
                    shootingViewModel.capture()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun flashScreen() {
        binding.viewFlash.visibility = View.VISIBLE
        binding.viewFlash.alpha = 0f
        binding.viewFlash.animate().alpha(0.65f).setDuration(50)
            .withEndAction {
                if (_binding != null) {
                    binding.viewFlash.animate().alpha(0f).setDuration(160)
                        .withEndAction {
                            if (_binding != null) binding.viewFlash.visibility = View.GONE
                        }.start()
                }
            }.start()
    }

    // ---------------- 参数信息栏 ----------------

    private fun setupParams() {
        binding.cellShutter.pressEffect()
        binding.cellShutter.setOnClickListener {
            // 档位表与格式化统一取自 paramsViewModel（唯一真源）
            val rawValues = paramsViewModel.commonShutterSpeeds
            val param = paramsViewModel.shutterSpeed.value
            showParamPicker(
                title = "快门速度",
                displayValues = rawValues.map { paramsViewModel.formatShutter(it) },
                rawValues = rawValues,
                current = param.currentValue,
                currentRaw = param.rawValue,
                // 档位表按曝光时间升序，向上滚 = 更快
                hint = "↑ 更快 / ↓ 更慢",
                onConfirm = { index -> paramsViewModel.setShutterByValue(rawValues[index]) }
            )
        }

        binding.cellAperture.pressEffect()
        binding.cellAperture.setOnClickListener { openAperturePicker() }

        binding.cellIso.pressEffect()
        binding.cellIso.setOnClickListener {
            val rawValues = paramsViewModel.commonIsoValues
            val param = paramsViewModel.iso.value
            showParamPicker(
                title = "ISO",
                displayValues = rawValues.map { it.toString() },
                rawValues = rawValues,
                current = param.currentValue,
                currentRaw = param.rawValue,
                onConfirm = { index -> paramsViewModel.setIsoByValue(rawValues[index]) }
            )
        }

        binding.cellWb.pressEffect()
        binding.cellWb.setOnClickListener {
            val presets = paramsViewModel.whiteBalancePresets
            val param = paramsViewModel.whiteBalance.value
            showParamPicker(
                title = "白平衡",
                displayValues = presets.map { it.second },
                rawValues = presets.map { it.first },
                current = param.currentValue,
                currentRaw = param.rawValue,
                onConfirm = { index -> paramsViewModel.setWhiteBalance(presets[index].first) }
            )
        }

        binding.cellEv.pressEffect()
        binding.cellEv.setOnClickListener {
            Toast.makeText(requireContext(), "EV 请通过相机机内拨盘调整", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开光圈滚轮。
     * 档位与镜头联动：打开时读一次当前焦距（PRD Q3，不进轮询），再按镜头生成可用档位。
     */
    private fun openAperturePicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.refreshApertureOptions()
            if (_binding == null) return@launch
            val options = paramsViewModel.apertureOptions.value
            val param = paramsViewModel.aperture.value
            showParamPicker(
                title = "光圈",
                displayValues = options.map { paramsViewModel.formatAperture(it) },
                rawValues = options,
                current = param.currentValue,
                currentRaw = param.rawValue,
                hint = if (paramsViewModel.apertureRangeFromLens) {
                    "↑ 光圈更大 / ↓ 光圈收小"
                } else {
                    "未获取到镜头信息，使用通用档位"
                },
                onConfirm = { index -> paramsViewModel.setApertureByValue(options[index]) }
            )
        }
    }

    private fun showParamPicker(
        title: String,
        displayValues: List<String>,
        rawValues: List<Int>,
        current: String,
        currentRaw: Int,
        hint: String? = null,
        onConfirm: (Int) -> Unit
    ) {
        if (displayValues.isEmpty() || displayValues.size != rawValues.size) return
        val dialog = BottomSheetDialog(requireContext())
        val pickerBinding = DialogParamPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)
        pickerBinding.tvPickerTitle.text = title
        pickerBinding.tvPickerCurrent.text = buildString {
            append("当前: ").append(current.ifBlank { "--" })
            if (!hint.isNullOrBlank()) append('\n').append(hint)
        }

        val picker = pickerBinding.numberPicker
        picker.minValue = 0
        picker.maxValue = displayValues.size - 1
        picker.displayedValues = displayValues.toTypedArray()
        picker.wrapSelectorWheel = false
        // 用 raw 值定位，不再用显示字符串反查（v0.1.2 两处口径不一致导致恒定位到首项）
        picker.value = resolvePickerIndex(rawValues, currentRaw)

        pickerBinding.btnPickerConfirm.setOnClickListener {
            onConfirm(picker.value)
            dialog.dismiss()
        }
        dialog.show()
    }

    // ---------------- 触摸对焦与缩放（模块 1：imageMatrix 统一变换） ----------------

    private var zoomController: LiveViewZoomController? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndScale() {
        zoomController = LiveViewZoomController(binding.ivLiveView) { x, y ->
            // DISP 纯净模式：单击先唤醒控件，不消耗本次点击做对焦
            if (!controlsVisible) {
                showControls()
            } else {
                handleFocusTap(x, y)
            }
        }

        binding.ivLiveView.setOnTouchListener { _, event ->
            zoomController?.onTouchEvent(event)
            true
        }
    }

    private fun handleFocusTap(tapX: Float, tapY: Float) {
        // 统一换算：fitCenter 黑边剔除 + 双指缩放还原（旧版放大后点击坐标与实际不符）
        val tap = FocusTapMapper.mapToNormalized(binding.ivLiveView, tapX, tapY) ?: return
        viewModel.touchFocus(tap.x, tap.y)
        showFocusIndicator(tapX, tapY)
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val size = dp(FOCUS_INDICATOR_SIZE)
        binding.viewFocusIndicator.animate().cancel()
        binding.viewFocusIndicator.visibility = View.VISIBLE
        binding.viewFocusIndicator.translationX = x - size / 2f
        binding.viewFocusIndicator.translationY = y - size / 2f
        binding.viewFocusIndicator.alpha = 0f
        binding.viewFocusIndicator.scaleX = 0.6f
        binding.viewFocusIndicator.scaleY = 0.6f
        binding.viewFocusIndicator.animate()
            .alpha(1f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(130)
            .withEndAction {
                if (_binding != null) {
                    binding.viewFocusIndicator.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(90)
                        .start()
                }
            }
            .start()
        binding.viewFocusIndicator.postDelayed({
            if (_binding != null) hideFocusIndicator()
        }, 750)
    }

    private fun hideFocusIndicator() {
        if (_binding == null) return
        binding.viewFocusIndicator.animate().cancel()
        binding.viewFocusIndicator.animate().alpha(0f).setDuration(220)
            .withEndAction {
                if (_binding != null) binding.viewFocusIndicator.visibility = View.GONE
            }.start()
    }

    // ---------------- DISP 纯净模式 ----------------

    private fun toggleControls() {
        if (controlsVisible) {
            controlsVisible = false
            fadeOut(binding.topBarScroll, binding.paramsBar, binding.bottomControls)
            fadeOut(binding.btnDisp, binding.rightControls, binding.tvPerformance)
            binding.viewGridOverlay.visibility = View.GONE
            hideFocusIndicator()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controlsVisible = true
        fadeIn(binding.topBarScroll, binding.paramsBar, binding.bottomControls)
        fadeIn(binding.btnDisp, binding.rightControls, binding.tvPerformance)
        binding.viewGridOverlay.visibility = if (gridVisible) View.VISIBLE else View.GONE
    }

    private fun fadeIn(vararg views: View) {
        views.forEach { view ->
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun fadeOut(vararg views: View) {
        views.forEach { view ->
            view.animate().alpha(0f).setDuration(120)
                .withEndAction {
                    if (_binding != null) view.visibility = View.GONE
                }.start()
        }
    }

    // ---------------- 状态订阅 ----------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveViewState.collect { state ->
                binding.btnStartStop.text = when (state) {
                    LiveViewState.RUNNING -> "停止监看"
                    LiveViewState.STARTING -> "启动中"
                    LiveViewState.ERROR -> "重试监看"
                    LiveViewState.STOPPED -> "开始监看"
                }
                // 监看启停同步模式入口可用性（未监看时置灰）
                updateModeEntryState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.errorMessage.collect { message ->
                if (!message.isNullOrBlank()) {
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestFrame.collect { frame ->
                val data = frame.data
                val start = findJpegStart(data)
                val bitmap = BitmapFactory.decodeByteArray(data, start, data.size - start)
                if (bitmap != null) {
                    binding.ivLiveView.setImageBitmap(bitmap)
                    // 模块 1：分辨率变化时重算 fitCenter 基准并保持缩放状态，
                    // 网格/对焦读同一 imageMatrix，自动像素级同步
                    zoomController?.onFrameChanged()
                    binding.viewGridOverlay.invalidate()
                    // 优化项 4/修复：直方图与取景画面同帧刷新；关闭时跳过统计，零开销
                    if (settings.histogramEnabled) binding.viewHistogram.setFrame(bitmap)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.fps, viewModel.latency) { fps, latency -> fps to latency }
                .collect { (fps, latency) ->
                    binding.tvPerformance.text = "$fps FPS · $latency ms"
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.shutterSpeed.collect { param ->
                binding.tvShutterValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.aperture.collect { param ->
                binding.tvApertureValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.iso.collect { param ->
                binding.tvIsoValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.evCompensation.collect { param ->
                binding.tvEvValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.whiteBalance.collect { param ->
                binding.tvWbValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.focusMode.collect { param ->
                binding.btnAfMode.text = compactFocus(param.currentValue)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.exposureProgram.collect { param ->
                // 常驻显示当前模式（用 describeExposureProgram 的显示名，与选择器同口径）
                binding.tvModeTag.text = param.currentValue.ifBlank { "P" }
            }
        }
    }

    private fun compactFocus(full: String): String = when {
        full.contains("AF-C") -> "AF-C"
        full.contains("AF-S") -> "AF-S"
        full.contains("MF") -> "MF"
        else -> full.ifBlank { "AF-S" }
    }

    /** 定位 JPEG 起始标记 0xFF 0xD8（兼容 Nikon 1024 字节帧头） */
    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD8) {
                return i
            }
        }
        return 0
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDestroyView() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.stopLiveView()
        super.onDestroyView()
        _binding = null
    }
}

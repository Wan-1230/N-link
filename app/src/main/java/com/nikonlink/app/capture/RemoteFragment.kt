package com.nikonlink.app.capture

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.DialogParamPickerBinding
import com.nikonlink.app.databinding.FragmentRemoteBinding
import com.nikonlink.app.camera.liveview.LiveViewState
import com.nikonlink.app.camera.liveview.LiveViewActivity
import com.nikonlink.app.camera.liveview.LiveViewViewModel
import com.nikonlink.app.camera.params.CameraParam
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.camera.params.ParamApplyResult
import com.nikonlink.app.camera.params.resolvePickerIndex
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tab3 遥控拍摄（黑白极简）
 * 实时监看 2/3 + 参数滚轮选择器 + 快门户（按压内陷+闪白）
 * 视频模式：录制按钮 + 实心圆点时长标识（灰度）
 */
@AndroidEntryPoint
class RemoteFragment : Fragment() {

    private var _binding: FragmentRemoteBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RemoteShootingViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()
    private val liveViewViewModel: LiveViewViewModel by viewModels()

    /** 优化项 4：直方图开关状态持久化（顺手保持跨页面/重启一致） */
    @Inject lateinit var settings: AppSettings

    private var videoMode = false
    private var recording = false
    private var recordTimerJob: Job? = null
    private val paramCells = mutableMapOf<String, TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRemoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLiveViewArea()
        setupParamRow()
        setupShutter()
        observe()
        // 轻量一次性读取，避免抢占 PTP 通道（P0-1 修复经验）
        viewModel.refreshStatus()
        paramsViewModel.readAll()
    }

    // ---------------- 监看区域 ----------------

    private fun setupLiveViewArea() {
        // 监看画面圆角裁剪
        binding.liveContainer.clipToOutline = true
        binding.liveContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, dp(16f))
            }
        }

        binding.btnStartLv.pressEffect()
        binding.btnStartLv.setOnClickListener {
            liveViewViewModel.startLiveView()
        }

        binding.btnFullscreenLv.pressEffect()
        binding.btnFullscreenLv.setOnClickListener {
            // 避免两个页面同时持有 LiveViewManager scope，先停止当前监看再进入全屏页。
            liveViewViewModel.stopLiveView()
            LiveViewActivity.start(requireContext())
        }

        // 优化项 4：亮度直方图开关
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

        // 点击画面选择对焦点（淡入淡出动效）
        binding.ivLiveView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                liveViewViewModel.liveViewState.value == LiveViewState.RUNNING
            ) {
                val nx = event.x / v.width
                val ny = event.y / v.height
                liveViewViewModel.touchFocus(nx, ny)
                showFocusIndicator(event.x, event.y)
            }
            true
        }
    }

    /**
     * 优化项 4：应用直方图开关状态。
     * 关闭时一并清空已统计的柱体，否则重新打开会先闪一帧上一轮的残留图形。
     * 开关态用图标透明度表达（开启 100% / 关闭 55%），不引入强调色，保持黑白体系。
     */
    private fun applyHistogramToggle(enabled: Boolean) {
        binding.viewHistogram.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnHistogram.alpha = if (enabled) 1f else 0.55f
        if (!enabled) binding.viewHistogram.clear()
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val size = dp(56f)
        binding.viewFocusIndicator.translationX = (x - size / 2).coerceIn(0f, binding.liveContainer.width - size)
        binding.viewFocusIndicator.translationY = (y - size / 2).coerceIn(0f, binding.liveContainer.height - size)
        binding.viewFocusIndicator.animate().cancel()
        binding.viewFocusIndicator.visibility = View.VISIBLE
        binding.viewFocusIndicator.alpha = 0f
        binding.viewFocusIndicator.animate().alpha(1f).setDuration(120).start()
        binding.viewFocusIndicator.postDelayed({
            if (_binding != null) {
                binding.viewFocusIndicator.animate().alpha(0f).setDuration(300)
                    .withEndAction { if (_binding != null) binding.viewFocusIndicator.visibility = View.GONE }
                    .start()
            }
        }, 900)
    }

    // ---------------- 参数行（滚轮选择器） ----------------

    private fun setupParamRow() {
        val params = listOf(
            "模式" to paramsViewModel.exposureProgram,
            "光圈" to paramsViewModel.aperture,
            "快门" to paramsViewModel.shutterSpeed,
            "ISO" to paramsViewModel.iso,
            "白平衡" to paramsViewModel.whiteBalance
        )
        params.forEachIndexed { index, (label, flow) ->
            val cell = buildParamCell(label)
            if (index > 0) {
                (cell.first.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8f).toInt()
            }
            paramCells[label] = cell.second
            binding.paramRow.addView(cell.first)
            cell.first.setOnClickListener { onParamClick(label, flow.value) }

            // 订阅参数值刷新
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { param -> cell.second.text = param.currentValue.ifBlank { "--" } }
            }
        }
    }

    private fun buildParamCell(label: String): Pair<LinearLayout, TextView> {
        val ctx = requireContext()
        val valueView = TextView(ctx).apply {
            text = "--"
            setTextColor(resources.getColor(R.color.text_primary, null))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val labelView = TextView(ctx).apply {
            text = label
            setTextColor(resources.getColor(R.color.text_tertiary, null))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
        }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_info_grid_item)
            setPadding(dp(16f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(6f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(labelView)
            addView(valueView)
        }
        cell.pressEffect()
        return cell to valueView
    }

    private fun onParamClick(label: String, param: CameraParam) {
        when (label) {
            // 光圈档位与镜头联动，打开滚轮前先刷新一次（内部会读一次当前焦距）
            "光圈" -> openAperturePicker(param)
            "快门" -> {
                val rawValues = paramsViewModel.commonShutterSpeeds
                showWheelPicker(
                    title = "快门速度",
                    param = param,
                    displayValues = rawValues.map { paramsViewModel.formatShutter(it) },
                    rawValues = rawValues,
                    // 档位表按曝光时间升序，向上滚 = 更快
                    hint = "↑ 更快 / ↓ 更慢（可手动输入）",
                    manualHintRes = R.string.param_input_hint_shutter,
                    onManualInput = { text -> paramsViewModel.applyShutterInput(text) },
                    onConfirm = { idx -> paramsViewModel.setShutterByValue(rawValues[idx]) }
                )
            }
            "ISO" -> {
                val rawValues = paramsViewModel.commonIsoValues
                showWheelPicker(
                    title = "ISO",
                    param = param,
                    displayValues = rawValues.map { it.toString() },
                    rawValues = rawValues,
                    hint = null,
                    manualHintRes = R.string.param_input_hint_iso,
                    onManualInput = { text -> paramsViewModel.applyIsoInput(text) },
                    onConfirm = { idx -> paramsViewModel.setIsoByValue(rawValues[idx]) }
                )
            }
            "白平衡" -> {
                val presets = paramsViewModel.whiteBalancePresets
                showWheelPicker(
                    "白平衡", param, presets.map { it.second }, presets.map { it.first },
                    onConfirm = { idx -> paramsViewModel.setWhiteBalance(presets[idx].first) }
                )
            }
            "模式" -> MaterialAlertDialogBuilder(requireContext())
                .setTitle("拍摄模式")
                .setMessage("当前模式: ${param.currentValue.ifBlank { "--" }}\n远程切换 P/A/S/M 需要机身支持，请在相机拨盘上切换。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /**
     * 打开光圈滚轮：档位与镜头联动。
     * 打开时读一次当前焦距（PRD Q3，不进轮询），再从 paramsViewModel 暴露的档位流取结果。
     */
    private fun openAperturePicker(param: CameraParam) {
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.refreshApertureOptions()
            if (_binding == null) return@launch
            val options = paramsViewModel.apertureOptions.value
            showWheelPicker(
                title = "光圈",
                param = param,
                displayValues = options.map { paramsViewModel.formatAperture(it) },
                rawValues = options,
                hint = if (paramsViewModel.apertureRangeFromLens) {
                    "↑ 光圈更大 / ↓ 光圈收小（可手动输入）"
                } else {
                    "未获取到镜头信息，使用通用档位（可手动输入）"
                },
                manualHintRes = R.string.param_input_hint_aperture,
                onManualInput = { text -> paramsViewModel.applyApertureInput(text) },
                onConfirm = { idx -> paramsViewModel.setApertureByValue(options[idx]) }
            )
        }
    }

    /**
     * 滚轮选择器底部面板（从下向上滑入，Material 默认行为）。
     *
     * @param manualHintRes 非 0 时显示手动输入行（快门 / 光圈 / ISO 支持，
     *                      白平衡等枚举型参数不传）
     * @param onManualInput 手动输入的应用回调：解析 → 就近匹配 → 下发 → 回告生效值
     */
    private fun showWheelPicker(
        title: String,
        param: CameraParam,
        displayValues: List<String>,
        rawValues: List<Int>,
        hint: String? = null,
        manualHintRes: Int = 0,
        onManualInput: (suspend (String) -> ParamApplyResult)? = null,
        onConfirm: (Int) -> Unit
    ) {
        if (displayValues.isEmpty() || displayValues.size != rawValues.size) return
        val dialog = BottomSheetDialog(requireContext())
        val pickerBinding = DialogParamPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = title
        pickerBinding.tvPickerCurrent.text = buildString {
            append("当前: ").append(param.currentValue.ifBlank { "--" })
            if (!hint.isNullOrBlank()) append('\n').append(hint)
        }

        val picker = pickerBinding.numberPicker
        picker.minValue = 0
        picker.maxValue = displayValues.size - 1
        picker.displayedValues = displayValues.toTypedArray()
        picker.wrapSelectorWheel = false
        // 用 raw 值定位，不再用显示字符串反查（v0.1.2 两处口径不一致导致恒定位到首项）
        picker.value = resolvePickerIndex(rawValues, param.rawValue)

        // —— 手动输入区 ——
        val applyInput = onManualInput
        if (applyInput != null && manualHintRes != 0) {
            pickerBinding.layoutManualInput.visibility = View.VISIBLE
            pickerBinding.tvManualHint.visibility = View.VISIBLE
            pickerBinding.tvManualHint.setText(manualHintRes)
            val submit = {
                val text = pickerBinding.etManualInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    pickerBinding.etManualInput.error = "请输入数值"
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = applyInput(text)
                        if (_binding == null) return@launch
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                        if (result.ok) {
                            // 成功后把滚轮定位到实际生效档位，避免面板与相机口径不一致
                            val idx = resolvePickerIndex(rawValues, currentParamRaw(param.name))
                            if (idx in 0..picker.maxValue) picker.value = idx
                            pickerBinding.etManualInput.text?.clear()
                            pickerBinding.etManualInput.clearFocus()
                            dialog.dismiss()
                        }
                    }
                }
            }
            pickerBinding.btnManualApply.setOnClickListener { submit() }
            pickerBinding.etManualInput.setOnEditorActionListener { _, actionId, _ ->
                // 键盘「完成」等同于点击应用
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    submit(); true
                } else {
                    false
                }
            }
        }

        pickerBinding.btnPickerConfirm.setOnClickListener {
            onConfirm(picker.value)
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 按参数名取当前 raw 值，用于手动输入成功后回正滚轮位置 */
    private fun currentParamRaw(name: String): Int = when (name) {
        "光圈" -> paramsViewModel.aperture.value.rawValue
        "快门速度" -> paramsViewModel.shutterSpeed.value.rawValue
        "ISO" -> paramsViewModel.iso.value.rawValue
        else -> 0
    }

    // ---------------- 快门户 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        // 照片 / 视频模式切换
        binding.btnModePhoto.setOnClickListener { setVideoMode(false) }
        binding.btnModeVideo.setOnClickListener { setVideoMode(true) }

        // 快门：按压内陷 + 闪白反馈
        binding.btnShutter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    onShutterPressed()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }

        // 定时拍摄
        binding.btnTimer.pressEffect()
        binding.btnTimer.setOnClickListener {
            val options = arrayOf("关闭定时", "2 秒", "5 秒", "10 秒")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("定时拍摄")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> viewModel.cancelTimer()
                        1 -> viewModel.startTimerCapture(2)
                        2 -> viewModel.startTimerCapture(5)
                        3 -> viewModel.startTimerCapture(10)
                    }
                }
                .show()
        }

        // 间隔拍摄（仅照片模式可用；视频模式隐藏该按钮）
        binding.btnModeAction.pressEffect()
        binding.btnModeAction.setOnClickListener {
            showIntervalDialog()
        }
    }

    private fun showIntervalDialog() {
        val options = arrayOf("间隔 3s × 30 张", "间隔 5s × 50 张", "间隔 10s × 100 张", "停止间隔拍摄")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("间隔拍摄")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.startInterval(IntervalConfig(3000, 30))
                    1 -> viewModel.startInterval(IntervalConfig(5000, 50))
                    2 -> viewModel.startInterval(IntervalConfig(10000, 100))
                    3 -> viewModel.cancelInterval()
                }
            }
            .show()
    }

    private fun onShutterPressed() {
        if (videoMode) {
            if (!recording) {
                viewModel.startVideo()
            } else {
                viewModel.stopVideo()
            }
            return
        }
        flashScreen()
        viewModel.capture()
    }

    /** 快门触发：画面轻微闪白模拟快门效果 */
    private fun flashScreen() {
        binding.viewFlash.visibility = View.VISIBLE
        binding.viewFlash.alpha = 0f
        binding.viewFlash.animate().alpha(0.75f).setDuration(60)
            .withEndAction {
                if (_binding != null) {
                    binding.viewFlash.animate().alpha(0f).setDuration(180)
                        .withEndAction { if (_binding != null) binding.viewFlash.visibility = View.GONE }
                        .start()
                }
            }.start()
    }

    private fun setVideoMode(video: Boolean) {
        videoMode = video
        binding.btnModePhoto.setBackgroundResource(if (!video) R.drawable.bg_chip_selected else 0)
        binding.btnModeVideo.setBackgroundResource(if (video) R.drawable.bg_chip_selected else 0)
        binding.btnModePhoto.setTextColor(
            resources.getColor(if (!video) R.color.on_primary else R.color.text_primary, null)
        )
        binding.btnModeVideo.setTextColor(
            resources.getColor(if (video) R.color.on_primary else R.color.text_primary, null)
        )
        if (video) {
            binding.ivShutterIcon.setImageResource(R.drawable.ic_record_dot)
            binding.ivShutterIcon.setColorFilter(resources.getColor(R.color.white, null))
            // 视频模式无对应“视频场景”PTP 能力，隐藏该按钮避免空操作
            binding.btnModeAction.visibility = View.GONE
        } else {
            binding.ivShutterIcon.setImageResource(R.drawable.ic_shutter_white)
            binding.ivShutterIcon.clearColorFilter()
            binding.btnModeAction.visibility = View.VISIBLE
            binding.btnModeAction.text = "间隔拍摄"
        }
    }

    // ---------------- 状态订阅 ----------------

    private fun observe() {
        // 监看帧渲染：跳过 Nikon 头部，定位 JPEG SOI 再解码
        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.latestFrame.collect { frame ->
                val data = frame.data
                val start = findJpegStart(data)
                val bitmap = BitmapFactory.decodeByteArray(data, start, data.size - start)
                if (bitmap != null) {
                    binding.ivLiveView.setImageBitmap(bitmap)
                    // 优化项 4：直方图与取景画面同一帧刷新，不存在时延差。
                    // 开关关闭时整段跳过——降采样统计本身也不跑，零额外开销。
                    if (settings.histogramEnabled) binding.viewHistogram.setFrame(bitmap)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.liveViewState.collect { state ->
                binding.btnStartLv.visibility =
                    if (state == LiveViewState.RUNNING) View.GONE else View.VISIBLE
                binding.btnStartLv.text =
                    if (state == LiveViewState.ERROR) "重试监看" else "开始监看"
                binding.tvLvConn.text = when (state) {
                    LiveViewState.RUNNING -> "监看中"
                    LiveViewState.STARTING -> "启动中"
                    LiveViewState.ERROR -> "监看异常"
                    else -> "未监看"
                }
                // 优化项 4/修复：监看启停/进出全屏不自动改变直方图状态——
                // 开关只由用户点击改变，停止期间保留最后一帧的分布，恢复监看后自然续上
            }
        }

        // Fix 真机反馈: 监看启动失败时必须把原因显示出来，而不是无反馈
        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.errorMessage.collect { msg ->
                if (!msg.isNullOrBlank()) {
                    binding.tvRemoteStatus.text = msg
                }
            }
        }

        // 顶部悬浮信息：电量 / 剩余可拍
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.batteryLevel.collect { lv ->
                val show = lv >= 0
                binding.ivLvBattery.visibility = if (show) View.VISIBLE else View.GONE
                binding.tvLvBattery.visibility = if (show) View.VISIBLE else View.GONE
                if (show) binding.tvLvBattery.text = "$lv%"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.remainingShots.collect { n ->
                val show = n >= 0
                binding.ivLvShots.visibility = if (show) View.VISIBLE else View.GONE
                binding.tvLvShots.visibility = if (show) View.VISIBLE else View.GONE
                if (show) binding.tvLvShots.text = "$n 张"
            }
        }

        // 拍摄状态 / 计数
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shootingState.collect { state ->
                binding.tvRemoteStatus.text = when (state) {
                    ShootingState.IDLE -> if (videoMode) "视频就绪" else "拍摄就绪"
                    ShootingState.CAPTURING -> "拍摄中"
                    ShootingState.TIMER_COUNTDOWN -> "倒计时"
                    ShootingState.INTERVAL_SHOOTING -> "间隔拍摄中"
                    ShootingState.BULB_EXPOSING -> "B门曝光中"
                    ShootingState.VIDEO_RECORDING -> "录制中"
                }
                val nowRecording = state == ShootingState.VIDEO_RECORDING
                if (nowRecording != recording) {
                    recording = nowRecording
                    if (nowRecording) startRecordTimer() else stopRecordTimer()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shotCount.collect { n -> binding.tvShotCount.text = "已拍 $n 张" }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.timerCountdown.collect { sec ->
                if (sec > 0) binding.tvRemoteStatus.text = "倒计时 $sec s"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.intervalProgress.collect { p ->
                if (p.totalShots > 0 && p.completedShots < p.totalShots) {
                    binding.tvRemoteStatus.text = "间隔 ${p.completedShots}/${p.totalShots}"
                }
            }
        }

    }

    private fun startRecordTimer() {
        binding.layoutRecordBadge.visibility = View.VISIBLE
        val startAt = System.currentTimeMillis()
        recordTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val sec = ((System.currentTimeMillis() - startAt) / 1000).toInt()
                binding.tvRecordTime.text =
                    String.format("%02d:%02d", sec / 60, sec % 60)
                delay(500)
            }
        }
    }

    private fun stopRecordTimer() {
        recordTimerJob?.cancel()
        recordTimerJob = null
        binding.layoutRecordBadge.visibility = View.GONE
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

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 切离拍摄页停止 LiveView，释放 PTP 通道（防断联）
        if (hidden && liveViewViewModel.liveViewState.value == LiveViewState.RUNNING) {
            liveViewViewModel.stopLiveView()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        liveViewViewModel.stopLiveView()
        recordTimerJob?.cancel()
        _binding = null
    }
}

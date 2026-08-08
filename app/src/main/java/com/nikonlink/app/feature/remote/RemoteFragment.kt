package com.nikonlink.app.feature.remote

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
import com.nikonlink.app.feature.liveview.LiveViewState
import com.nikonlink.app.feature.liveview.LiveViewActivity
import com.nikonlink.app.feature.liveview.LiveViewViewModel
import com.nikonlink.app.feature.settings.CameraParam
import com.nikonlink.app.feature.settings.CameraParamsViewModel
import com.nikonlink.app.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
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
            "光圈" -> showWheelPicker("光圈", param,
                liveViewModel2().commonAperturesDisplay(),
                liveViewModel2().commonApertures
            ) { idx -> paramsViewModel.setApertureByValue(liveViewModel2().commonApertures[idx]) }
            "快门" -> showWheelPicker("快门速度", param,
                liveViewModel2().commonShuttersDisplay(),
                liveViewModel2().commonShutterSpeeds
            ) { idx -> paramsViewModel.setShutterByValue(liveViewModel2().commonShutterSpeeds[idx]) }
            "ISO" -> showWheelPicker("ISO", param,
                liveViewModel2().commonIsoValues.map { it.toString() },
                liveViewModel2().commonIsoValues
            ) { idx -> paramsViewModel.setIsoByValue(liveViewModel2().commonIsoValues[idx]) }
            "白平衡" -> {
                val presets = liveViewModel2().whiteBalancePresets
                showWheelPicker("白平衡", param, presets.map { it.second }, presets.map { it.first }) { idx ->
                    paramsViewModel.setWhiteBalance(presets[idx].first)
                }
            }
            "模式" -> MaterialAlertDialogBuilder(requireContext())
                .setTitle("拍摄模式")
                .setMessage("当前模式: ${param.currentValue.ifBlank { "--" }}\n远程切换 P/A/S/M 需要机身支持，请在相机拨盘上切换。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /** 滚轮选择器底部面板（从下向上滑入，Material 默认行为） */
    private fun <T> showWheelPicker(
        title: String,
        param: CameraParam,
        displayValues: List<String>,
        rawValues: List<T>,
        onConfirm: (Int) -> Unit
    ) {
        if (displayValues.isEmpty()) return
        val dialog = BottomSheetDialog(requireContext())
        val pickerBinding = DialogParamPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = title
        pickerBinding.tvPickerCurrent.text = "当前: ${param.currentValue.ifBlank { "--" }}"

        val picker = pickerBinding.numberPicker
        picker.minValue = 0
        picker.maxValue = displayValues.size - 1
        picker.displayedValues = displayValues.toTypedArray()
        picker.wrapSelectorWheel = false
        // 定位到当前值附近
        val currentIdx = displayValues.indexOfFirst { it == param.currentValue }
        picker.value = if (currentIdx >= 0) currentIdx else 0

        pickerBinding.btnPickerConfirm.setOnClickListener {
            onConfirm(picker.value)
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 参数候选值格式化辅助 */
    private fun liveViewModel2(): ParamCatalog = ParamCatalog(paramsViewModel)

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

        // 照片模式：间隔拍摄；视频模式：视频场景适配
        binding.btnModeAction.pressEffect()
        binding.btnModeAction.setOnClickListener {
            if (videoMode) {
                showVideoSceneDialog()
            } else {
                showIntervalDialog()
            }
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

    private fun showVideoSceneDialog() {
        val scenes = arrayOf("标准", "人像", "风景", "运动", "微距")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("视频场景")
            .setItems(scenes) { _, which ->
                Toast.makeText(
                    requireContext(),
                    "已切换视频场景：${scenes[which]}",
                    Toast.LENGTH_SHORT
                ).show()
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
            binding.btnModeAction.text = "视频场景"
        } else {
            binding.ivShutterIcon.setImageResource(R.drawable.ic_shutter_white)
            binding.ivShutterIcon.clearColorFilter()
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
                if (bitmap != null) binding.ivLiveView.setImageBitmap(bitmap)
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

/**
 * 参数候选值目录：将 manager 的原始数值格式化为滚轮显示文本
 */
private class ParamCatalog(private val vm: CameraParamsViewModel) {

    private val manager
        get() = vm

    val commonApertures: List<Int>
        get() = listOf(140, 180, 200, 280, 350, 400, 560, 800, 1100, 1600, 2200)

    val commonShutterSpeeds: List<Int>
        get() = listOf(10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200,
            250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000)

    val commonIsoValues: List<Int>
        get() = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200)

    val whiteBalancePresets: List<Pair<Int, String>>
        get() = listOf(
            2 to "自动", 0x8016 to "自然光自动适应", 4 to "晴天",
            0x8010 to "阴天", 0x8011 to "背阴", 6 to "白炽灯",
            5 to "荧光灯", 7 to "闪光灯", 0x8012 to "选择色温", 0x8013 to "手动预设"
        )

    fun commonAperturesDisplay(): List<String> =
        commonApertures.map { "f/${formatF(it)}" }

    fun commonShuttersDisplay(): List<String> =
        commonShutterSpeeds.map { "1/$it s" }

    private fun formatF(v: Int): String {
        val f = v / 100f
        return if (f == f.toLong().toFloat()) f.toLong().toString() else String.format("%.1f", f)
    }
}

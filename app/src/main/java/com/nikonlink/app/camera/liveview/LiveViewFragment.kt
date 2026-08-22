package com.nikonlink.app.camera.liveview

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
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
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

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

    private var controlsVisible = true
    private var gridVisible = true
    private var levelVisible = false
    private var aspectLabel = "3:2"
    private var formatLabel = "RAW+JPG"
    private var baseZoom = 1f
    private var scaleDetector: ScaleGestureDetector? = null

    private val aspectOptions = listOf("3:2", "4:3", "1:1", "16:9")
    private val formatOptions = listOf("RAW+JPG", "RAW", "JPG")

    private val apertureValues = listOf(140, 180, 200, 280, 350, 400, 560, 800, 1100, 1600, 2200)
    private val shutterValues = listOf(
        10, 13, 15, 20, 25, 30, 40, 50, 60, 80, 100, 125, 160, 200,
        250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000
    )
    private val isoValues = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200)
    private val wbPresets = listOf(
        2 to "自动", 0x8016 to "自然光自动适应", 4 to "晴天",
        0x8010 to "阴天", 0x8011 to "背阴", 6 to "白炽灯",
        5 to "荧光灯", 7 to "闪光灯", 0x8012 to "选择色温", 0x8013 to "手动预设"
    )

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
        binding.viewGridOverlay.setImageSource(binding.ivLiveView)
        paramsViewModel.readAll()
        shootingViewModel.refreshStatus()
        if (arguments?.getBoolean(EXTRA_AUTO_START, false) == true) {
            viewModel.startLiveView()
        }
    }

    // ---------------- 顶部与辅助控件 ----------------

    private fun setupControls() {
        binding.btnClose.pressEffect()
        binding.btnClose.setOnClickListener { requireActivity().finish() }

        binding.btnAfMode.pressEffect()
        binding.btnAfMode.setOnClickListener { paramsViewModel.cycleFocusMode() }

        binding.btnAspect.pressEffect()
        binding.btnAspect.setOnClickListener {
            val idx = aspectOptions.indexOf(aspectLabel)
            aspectLabel = aspectOptions[(idx + 1).coerceAtLeast(0) % aspectOptions.size]
            binding.btnAspect.text = aspectLabel
        }

        binding.btnFormat.pressEffect()
        binding.btnFormat.setOnClickListener {
            val idx = formatOptions.indexOf(formatLabel)
            formatLabel = formatOptions[(idx + 1).coerceAtLeast(0) % formatOptions.size]
            binding.btnFormat.text = formatLabel
        }

        binding.btnGridToggle.pressEffect()
        binding.btnGridToggle.setOnClickListener {
            gridVisible = !gridVisible
            binding.viewGridOverlay.setGridVisible(gridVisible)
            binding.viewGridOverlay.visibility = if (gridVisible) View.VISIBLE else View.GONE
        }

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

        binding.btnFilter.pressEffect()
        binding.btnFilter.setOnClickListener {
            Toast.makeText(requireContext(), "滤镜效果请在拍摄页调整", Toast.LENGTH_SHORT).show()
        }

        binding.btnRecipe.pressEffect()
        binding.btnRecipe.setOnClickListener {
            Toast.makeText(requireContext(), "配方功能将在设置页开放", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoreMenu() {
        val popup = PopupMenu(requireContext(), binding.btnMore)
        popup.menu.add(0, 1, 0, "开始 / 停止监看")
        popup.menu.add(0, 2, 0, if (gridVisible) "隐藏网格" else "显示网格")
        popup.menu.add(0, 3, 0, "画面放大")
        popup.menu.add(0, 4, 0, "画面缩小")
        popup.menu.add(0, 5, 0, "重置缩放")
        val bulbRunning = shootingViewModel.shootingState.value == ShootingState.BULB_EXPOSING
        popup.menu.add(0, 6, 0, if (bulbRunning) "结束 B 门 / 长曝光" else "B 门 / 长曝光")
        popup.menu.add(0, 7, 0, "测光模式")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> binding.btnStartStop.performClick()
                2 -> binding.btnGridToggle.performClick()
                3 -> {
                    val zoom = (binding.ivLiveView.scaleX * 1.25f).coerceAtMost(5f)
                    binding.ivLiveView.animate().scaleX(zoom).scaleY(zoom).setDuration(120).start()
                }
                4 -> {
                    val zoom = (binding.ivLiveView.scaleX / 1.25f).coerceAtLeast(1f)
                    binding.ivLiveView.animate().scaleX(zoom).scaleY(zoom).setDuration(120).start()
                }
                5 -> {
                    binding.ivLiveView.scaleX = 1f
                    binding.ivLiveView.scaleY = 1f
                }
                6 -> {
                    if (shootingViewModel.shootingState.value == ShootingState.BULB_EXPOSING) {
                        shootingViewModel.bulbStop()
                    } else {
                        shootingViewModel.bulbStart()
                    }
                }
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
            showParamPicker(
                title = "快门速度",
                displayValues = shutterValues.map { "1/$it s" },
                rawValues = shutterValues,
                current = paramsViewModel.shutterSpeed.value.currentValue
            ) { index -> paramsViewModel.setShutterByValue(shutterValues[index]) }
        }

        binding.cellAperture.pressEffect()
        binding.cellAperture.setOnClickListener {
            showParamPicker(
                title = "光圈",
                displayValues = apertureValues.map { formatAperture(it) },
                rawValues = apertureValues,
                current = paramsViewModel.aperture.value.currentValue
            ) { index -> paramsViewModel.setApertureByValue(apertureValues[index]) }
        }

        binding.cellIso.pressEffect()
        binding.cellIso.setOnClickListener {
            showParamPicker(
                title = "ISO",
                displayValues = isoValues.map { it.toString() },
                rawValues = isoValues,
                current = paramsViewModel.iso.value.currentValue
            ) { index -> paramsViewModel.setIsoByValue(isoValues[index]) }
        }

        binding.cellWb.pressEffect()
        binding.cellWb.setOnClickListener {
            showParamPicker(
                title = "白平衡",
                displayValues = wbPresets.map { it.second },
                rawValues = wbPresets.map { it.first },
                current = paramsViewModel.whiteBalance.value.currentValue
            ) { index -> paramsViewModel.setWhiteBalance(wbPresets[index].first) }
        }

        binding.cellEv.pressEffect()
        binding.cellEv.setOnClickListener {
            Toast.makeText(requireContext(), "EV 请通过相机机内拨盘调整", Toast.LENGTH_SHORT).show()
        }
    }

    private fun <T> showParamPicker(
        title: String,
        displayValues: List<String>,
        rawValues: List<T>,
        current: String,
        onConfirm: (Int) -> Unit
    ) {
        if (displayValues.isEmpty() || displayValues.size != rawValues.size) return
        val dialog = BottomSheetDialog(requireContext())
        val pickerBinding = DialogParamPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)
        pickerBinding.tvPickerTitle.text = title
        pickerBinding.tvPickerCurrent.text = "当前: ${current.ifBlank { "--" }}"

        val picker = pickerBinding.numberPicker
        picker.minValue = 0
        picker.maxValue = displayValues.size - 1
        picker.displayedValues = displayValues.toTypedArray()
        picker.wrapSelectorWheel = false
        val currentIdx = displayValues.indexOfFirst { it == current }
        picker.value = if (currentIdx >= 0) currentIdx else 0

        pickerBinding.btnPickerConfirm.setOnClickListener {
            onConfirm(picker.value)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatAperture(valueX100: Int): String {
        val f = valueX100 / 100f
        return if (f == f.toInt().toFloat()) {
            "f/$f"
        } else {
            String.format(Locale.US, "f/%.1f", f)
        }
    }

    // ---------------- 触摸对焦与缩放 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndScale() {
        scaleDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    baseZoom = binding.ivLiveView.scaleX
                    hideFocusIndicator()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoom = (baseZoom * detector.scaleFactor).coerceIn(1f, 5f)
                    binding.ivLiveView.scaleX = zoom
                    binding.ivLiveView.scaleY = zoom
                    return true
                }
            }
        )

        binding.ivLiveView.setOnTouchListener { v, event ->
            scaleDetector?.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (scaleDetector?.isInProgress == true) return@setOnTouchListener true
                if (!controlsVisible) {
                    showControls()
                    return@setOnTouchListener true
                }
                handleFocusTap(event.x, event.y)
            }
            true
        }
    }

    private fun handleFocusTap(tapX: Float, tapY: Float) {
        val rect = displayedImageRect() ?: RectF(0f, 0f, binding.ivLiveView.width.toFloat(), binding.ivLiveView.height.toFloat())
        if (!rect.contains(tapX, tapY)) return

        val nx = ((tapX - rect.left) / rect.width()).coerceIn(0f, 1f)
        val ny = ((tapY - rect.top) / rect.height()).coerceIn(0f, 1f)
        viewModel.touchFocus(nx, ny)
        showFocusIndicator(
            rect.left + nx * rect.width(),
            rect.top + ny * rect.height()
        )
    }

    private fun displayedImageRect(): RectF? {
        val drawable = binding.ivLiveView.drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        val rect = RectF(
            0f, 0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )
        Matrix(binding.ivLiveView.imageMatrix).mapRect(rect)
        return if (rect.width() > 0 && rect.height() > 0) rect else null
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
                    binding.viewGridOverlay.setImageSource(binding.ivLiveView)
                    binding.viewGridOverlay.invalidate()
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
                binding.tvModeTag.text = compactMode(param.currentValue)
            }
        }
    }

    private fun compactFocus(full: String): String = when {
        full.contains("AF-C") -> "AF-C"
        full.contains("AF-S") -> "AF-S"
        full.contains("MF") -> "MF"
        else -> full.ifBlank { "AF-S" }
    }

    private fun compactMode(full: String): String {
        val first = full.substringBefore("(").trim().take(1)
        return first.uppercase().ifBlank { "P" }
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

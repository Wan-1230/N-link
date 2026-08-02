package com.nikonlink.app.feature.remote

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.databinding.FragmentRemoteBinding
import com.nikonlink.app.feature.liveview.LiveViewState
import com.nikonlink.app.feature.liveview.LiveViewViewModel
import com.nikonlink.app.feature.settings.CameraParamsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 拍摄页：Live View 取景、遥控快门与参数快捷调整合并在一个界面。
 */
@AndroidEntryPoint
class RemoteFragment : Fragment() {

    private var _binding: FragmentRemoteBinding? = null
    private val binding get() = _binding!!
    private val viewModel: RemoteShootingViewModel by viewModels()
    private val liveViewViewModel: LiveViewViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()

    private var isBulbExposing = false
    private var sessionFeaturesStarted = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRemoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupShutterButton()
        setupModeButtons()
        setupLiveView()
        setupParams()
        observeState()
        observeLiveView()
        observeParams()
        if (!isHidden) {
            startSessionFeatures()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopSessionFeatures()
        } else if (_binding != null) {
            startSessionFeatures()
        }
    }

    private fun startSessionFeatures() {
        if (sessionFeaturesStarted) return
        sessionFeaturesStarted = true
        viewModel.refreshStatus()
        paramsViewModel.startPolling()
        paramsViewModel.readAll()
        liveViewViewModel.startLiveView()
    }

    private fun stopSessionFeatures() {
        sessionFeaturesStarted = false
        paramsViewModel.stopPolling()
        liveViewViewModel.stopLiveView()
    }

    private fun setupShutterButton() {
        binding.btnShutter.setOnClickListener {
            viewModel.capture()
        }

        binding.btnShutter.setOnLongClickListener {
            if (!isBulbExposing) {
                isBulbExposing = true
                viewModel.bulbStart()
                binding.btnShutter.text = "释放结束曝光"
            } else {
                isBulbExposing = false
                viewModel.bulbStop()
                binding.btnShutter.text = "快门"
            }
            true
        }

        binding.btnFocus.setOnClickListener {
            viewModel.halfPressFocus()
            Toast.makeText(requireContext(), "对焦中...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupModeButtons() {
        binding.btnTimer2s.setOnClickListener { viewModel.startTimerCapture(2) }
        binding.btnTimer5s.setOnClickListener { viewModel.startTimerCapture(5) }
        binding.btnTimer10s.setOnClickListener { viewModel.startTimerCapture(10) }

        binding.btnInterval.setOnClickListener {
            val config = IntervalConfig(intervalMs = 5000, totalShots = 50)
            viewModel.startInterval(config)
            Toast.makeText(requireContext(), "间隔拍摄开始: 5s x 50张", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopInterval.setOnClickListener {
            viewModel.cancelInterval()
        }

        binding.btnVideo.setOnClickListener {
            viewModel.startVideo()
            Toast.makeText(requireContext(), "视频录制开始", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopVideo.setOnClickListener {
            viewModel.stopVideo()
        }
    }

    private fun setupLiveView() {
        binding.btnStartStop.setOnClickListener {
            if (liveViewViewModel.liveViewState.value == LiveViewState.RUNNING) {
                liveViewViewModel.stopLiveView()
            } else {
                liveViewViewModel.startLiveView()
            }
        }

        binding.btnGrid.setOnClickListener {
            liveViewViewModel.cycleGrid()
        }

        binding.btnZoomIn.setOnClickListener { liveViewViewModel.zoomIn() }
        binding.btnZoomOut.setOnClickListener { liveViewViewModel.zoomOut() }
        binding.btnZoomReset.setOnClickListener { liveViewViewModel.resetZoom() }

        binding.btnAfCenter.setOnClickListener {
            liveViewViewModel.autoFocus()
            Toast.makeText(requireContext(), "中心对焦", Toast.LENGTH_SHORT).show()
        }

        binding.ivLiveView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x / v.width
                val y = event.y / v.height
                liveViewViewModel.touchFocus(x, y)
                binding.viewFocusIndicator.x = event.x - 28
                binding.viewFocusIndicator.y = event.y - 28
                binding.viewFocusIndicator.visibility = View.VISIBLE
                binding.viewFocusIndicator.postDelayed({
                    binding.viewFocusIndicator.visibility = View.GONE
                }, 800)
            }
            true
        }
    }

    private fun setupParams() {
        binding.btnQuickApertureDown.setOnClickListener { paramsViewModel.adjustAperture(-1) }
        binding.btnQuickApertureUp.setOnClickListener { paramsViewModel.adjustAperture(1) }
        binding.btnQuickShutterDown.setOnClickListener { paramsViewModel.adjustShutter(-1) }
        binding.btnQuickShutterUp.setOnClickListener { paramsViewModel.adjustShutter(1) }
        binding.btnQuickIsoDown.setOnClickListener { paramsViewModel.adjustIso(-1) }
        binding.btnQuickIsoUp.setOnClickListener { paramsViewModel.adjustIso(1) }
        binding.btnQuickWb.setOnClickListener { paramsViewModel.cycleWhiteBalance() }
        binding.btnQuickAf.setOnClickListener { paramsViewModel.cycleFocusMode() }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shootingState.collect { state ->
                binding.tvShootingState.text = when (state) {
                    ShootingState.IDLE -> "就绪"
                    ShootingState.CAPTURING -> "拍摄中..."
                    ShootingState.TIMER_COUNTDOWN -> "倒计时..."
                    ShootingState.INTERVAL_SHOOTING -> "间隔拍摄中"
                    ShootingState.BULB_EXPOSING -> "B门曝光中"
                    ShootingState.VIDEO_RECORDING -> "录制中"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shotCount.collect { count ->
                binding.tvShotCount.text = "已拍: $count 张"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.timerCountdown.collect { countdown ->
                if (countdown > 0) {
                    binding.tvTimerDisplay.visibility = View.VISIBLE
                    binding.tvTimerDisplay.text = "$countdown"
                } else {
                    binding.tvTimerDisplay.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.bulbExposureTime.collect { ms ->
                if (ms > 0) {
                    binding.tvBulbTime.visibility = View.VISIBLE
                    binding.tvBulbTime.text = String.format("曝光: %.1fs", ms / 1000.0)
                } else {
                    binding.tvBulbTime.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.intervalProgress.collect { progress ->
                if (progress.totalShots > 0) {
                    binding.tvIntervalProgress.visibility = View.VISIBLE
                    binding.tvIntervalProgress.text = "间隔: ${progress.completedShots}/${progress.totalShots}"
                } else {
                    binding.tvIntervalProgress.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.batteryLevel.collect { level ->
                binding.tvBattery.text = if (level >= 0) "电量: $level%" else "电量: --"
            }
        }
    }

    private fun observeLiveView() {
        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.liveViewState.collect { state ->
                binding.btnStartStop.text = when (state) {
                    LiveViewState.RUNNING -> "停止"
                    LiveViewState.STARTING -> "启动中..."
                    LiveViewState.ERROR -> "重试"
                    LiveViewState.STOPPED -> "开始"
                }
                binding.tvLiveStatus.text = when (state) {
                    LiveViewState.RUNNING -> "● LIVE"
                    LiveViewState.STARTING -> "连接中..."
                    LiveViewState.ERROR -> "✕ 错误"
                    LiveViewState.STOPPED -> "○ 已停止"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.latestFrame.collect { frame ->
                val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                if (bitmap != null) {
                    binding.ivLiveView.setImageBitmap(bitmap)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.fps.collect { fps ->
                binding.tvFps.text = "${fps} fps"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.latency.collect { ms ->
                binding.tvLatency.text = "${ms}ms"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.gridOverlay.collect { grid ->
                binding.btnGrid.text = grid.displayName
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.zoomLevel.collect { zoom ->
                binding.ivLiveView.scaleX = zoom
                binding.ivLiveView.scaleY = zoom
                binding.tvZoom.text = String.format("%.1fx", zoom)
            }
        }
    }

    private fun observeParams() {
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.aperture.collect { param ->
                binding.tvQuickAperture.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.shutterSpeed.collect { param ->
                binding.tvQuickShutter.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.iso.collect { param ->
                binding.tvQuickIso.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.whiteBalance.collect { param ->
                binding.btnQuickWb.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.focusMode.collect { param ->
                binding.btnQuickAf.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.paramsLocked.collect { locked ->
                binding.tvQuickLock.text = if (locked) "已锁定" else "未锁定"
            }
        }
    }

    override fun onDestroyView() {
        stopSessionFeatures()
        super.onDestroyView()
        _binding = null
    }
}

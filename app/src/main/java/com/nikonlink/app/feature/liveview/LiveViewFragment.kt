package com.nikonlink.app.feature.liveview

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
import com.nikonlink.app.databinding.FragmentLiveviewBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Live View 实时取景 Fragment
 * PRD 2.3: 实时画面、触摸对焦、构图辅助线、画面缩放
 */
@AndroidEntryPoint
class LiveViewFragment : Fragment() {

    private var _binding: FragmentLiveviewBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LiveViewViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        setupTouchFocus()
        observeState()
    }

    private fun setupControls() {
        binding.btnStartStop.setOnClickListener {
            if (viewModel.liveViewState.value == LiveViewState.RUNNING) {
                viewModel.stopLiveView()
            } else {
                viewModel.startLiveView()
            }
        }

        binding.btnGrid.setOnClickListener {
            viewModel.cycleGrid()
        }

        binding.btnZoomIn.setOnClickListener { viewModel.zoomIn() }
        binding.btnZoomOut.setOnClickListener { viewModel.zoomOut() }
        binding.btnZoomReset.setOnClickListener { viewModel.resetZoom() }

        binding.btnAfCenter.setOnClickListener {
            viewModel.autoFocus()
            Toast.makeText(requireContext(), "中心对焦", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * PRD 2.3: 点击手机屏幕任意位置触发对焦点移动
     */
    private fun setupTouchFocus() {
        binding.ivLiveView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x / v.width
                val y = event.y / v.height
                viewModel.touchFocus(x, y)
                // 显示对焦点指示
                binding.viewFocusIndicator.x = event.x - 30
                binding.viewFocusIndicator.y = event.y - 30
                binding.viewFocusIndicator.visibility = View.VISIBLE
                binding.viewFocusIndicator.postDelayed({
                    binding.viewFocusIndicator.visibility = View.GONE
                }, 800)
            }
            true
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveViewState.collect { state ->
                binding.btnStartStop.text = when (state) {
                    LiveViewState.RUNNING -> "停止 Live View"
                    LiveViewState.STARTING -> "启动中..."
                    LiveViewState.ERROR -> "出错 - 点击重试"
                    LiveViewState.STOPPED -> "开始 Live View"
                }
                binding.tvLiveStatus.text = when (state) {
                    LiveViewState.RUNNING -> "● LIVE"
                    LiveViewState.STARTING -> "连接中..."
                    LiveViewState.ERROR -> "✕ 错误"
                    LiveViewState.STOPPED -> "○ 已停止"
                }
            }
        }

        // 渲染帧
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestFrame.collect { frame ->
                val bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                if (bitmap != null) {
                    binding.ivLiveView.setImageBitmap(bitmap)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.fps.collect { fps ->
                binding.tvFps.text = "${fps} fps"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latency.collect { ms ->
                binding.tvLatency.text = "${ms}ms"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gridOverlay.collect { grid ->
                binding.btnGrid.text = grid.displayName
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.zoomLevel.collect { zoom ->
                binding.ivLiveView.scaleX = zoom
                binding.ivLiveView.scaleY = zoom
                binding.tvZoom.text = String.format("%.1fx", zoom)
            }
        }
    }

    override fun onDestroyView() {
        viewModel.stopLiveView()
        super.onDestroyView()
        _binding = null
    }
}

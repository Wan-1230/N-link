package com.nikonlink.app.feature.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.databinding.FragmentSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 相机参数管理 Fragment
 * PRD 2.4: 曝光三要素、白平衡、对焦模式、拍摄模式、测光模式
 * PRD 2.4 UI: 参数变更实时同步至相机（< 100ms 响应）、参数锁定/解锁
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CameraParamsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeParameters()
        setupActions()
        viewModel.readAll()
    }

    private fun observeParameters() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.aperture.collect { param ->
                binding.tvApertureValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shutterSpeed.collect { param ->
                binding.tvShutterValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.iso.collect { param ->
                binding.tvIsoValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.whiteBalance.collect { param ->
                binding.tvWbValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.focusMode.collect { param ->
                binding.tvAfValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.exposureProgram.collect { param ->
                binding.tvModeValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.meteringMode.collect { param ->
                binding.tvMeteringValue.text = param.currentValue
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.paramsLocked.collect { locked ->
                binding.btnLock.text = if (locked) "已锁定" else "未锁定"
            }
        }
    }

    private fun setupActions() {
        // 刷新参数
        binding.btnRefresh.setOnClickListener {
            viewModel.readAll()
            Toast.makeText(requireContext(), "参数已刷新", Toast.LENGTH_SHORT).show()
        }

        // 锁定/解锁
        binding.btnLock.setOnClickListener {
            viewModel.toggleLock()
        }

        // ISO 快捷调整
        binding.btnIsoUp.setOnClickListener { viewModel.adjustIso(1) }
        binding.btnIsoDown.setOnClickListener { viewModel.adjustIso(-1) }

        // 光圈快捷调整
        binding.btnApertureUp.setOnClickListener { viewModel.adjustAperture(1) }
        binding.btnApertureDown.setOnClickListener { viewModel.adjustAperture(-1) }

        // 快门快捷调整
        binding.btnShutterUp.setOnClickListener { viewModel.adjustShutter(1) }
        binding.btnShutterDown.setOnClickListener { viewModel.adjustShutter(-1) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

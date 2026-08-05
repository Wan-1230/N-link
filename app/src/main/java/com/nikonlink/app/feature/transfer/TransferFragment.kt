package com.nikonlink.app.feature.transfer

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.core.content.ContextCompat
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.R
import com.nikonlink.app.databinding.FragmentTransferBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 照片传输 Fragment（相册风格）
 * PRD 2.1: 浏览相机存储卡照片列表、缩略图预览、选择性下载、队列管理
 * 参考安卓相册悬浮布局：三栏筛选 + 网格实时预览 + 底部悬浮操作栏
 */
@AndroidEntryPoint
class TransferFragment : Fragment() {

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransferViewModel by viewModels()

    private lateinit var gridAdapter: PhotoGridAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGrid()
        setupActions()
        observeState()
    }

    private fun setupGrid() {
        gridAdapter = PhotoGridAdapter { file ->
            viewModel.toggleSelection(file.handle)
        }
        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvPhotos.adapter = gridAdapter
    }

    private fun setupActions() {
        binding.btnFetchPhotos.setOnClickListener { viewModel.fetchPhotos() }
        binding.btnDownloadAll.setOnClickListener { viewModel.downloadAll() }
        binding.btnDownloadSelected.setOnClickListener { viewModel.downloadSelected() }

        binding.btnSelectAll.setOnClickListener {
            if (viewModel.selectedHandles.value.size == viewModel.filteredPhotos.value.size) {
                viewModel.clearSelection()
            } else {
                viewModel.selectAllFiltered()
            }
        }

        binding.btnCancelSelection.setOnClickListener { viewModel.clearSelection() }

        binding.btnPause.setOnClickListener {
            if (viewModel.transferState.value is TransferState.Paused) {
                viewModel.resumeTransfer()
                binding.btnPause.text = "暂停"
                Toast.makeText(requireContext(), "传输已恢复", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.pauseTransfer()
                binding.btnPause.text = "继续"
                Toast.makeText(requireContext(), "传输已暂停", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCancelAll.setOnClickListener {
            viewModel.cancelAll()
            Toast.makeText(requireContext(), "已取消全部传输", Toast.LENGTH_SHORT).show()
        }

        binding.filterGroup.check(R.id.btnFilterAll)
        binding.filterGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setPhotoFilter(
                when (checkedId) {
                    R.id.btnFilterJpg -> PhotoFilter.JPEG
                    R.id.btnFilterRaw -> PhotoFilter.RAW
                    else -> PhotoFilter.ALL
                }
            )
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        // 网格数据：照片 + 选中 + 缩略图
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.filteredPhotos.collect { photos ->
                refreshGrid()
                binding.tvEmpty.visibility = if (photos.isEmpty()) View.VISIBLE else View.GONE
                binding.tvPhotoCount.text = "${photos.size} 文件"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedHandles.collect { selected ->
                refreshGrid()
                updateSelectionTitle()
                binding.btnDownloadSelected.isEnabled = selected.isNotEmpty()
                binding.btnDownloadSelected.alpha = if (selected.isNotEmpty()) 1f else 0.45f
                updateDownloadButtonStyle(selected.isNotEmpty())
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.thumbnails.collect { _ ->
                refreshGrid()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { updateChannelStatus() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbState.collect { updateChannelStatus() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.transferState.collect { state ->
                when (state) {
                    is TransferState.Idle -> {
                        binding.progressDownload.visibility = View.GONE
                        binding.progressDownload.isIndeterminate = false
                        binding.tvTransferStatus.text = "传输空闲"
                        binding.btnPause.text = "暂停"
                    }
                    is TransferState.Downloading -> {
                        // Fix P1-4: 绑定确定进度条，实时显示 0-100%
                        binding.progressDownload.visibility = View.VISIBLE
                        val totalKnown = state.total > 0 && state.total != 0xFFFFFFFFL
                        binding.progressDownload.isIndeterminate = !totalKnown
                        if (totalKnown) {
                            val pct = (state.received * 100 / state.total).toInt().coerceIn(0, 100)
                            binding.progressDownload.progress = pct
                            binding.tvTransferStatus.text = "下载中: ${state.file.fileName} ($pct%)"
                        } else {
                            binding.progressDownload.progress = 0
                            binding.tvTransferStatus.text = "下载中: ${state.file.fileName} (大小未知)"
                        }
                    }
                    is TransferState.Paused -> {
                        binding.tvTransferStatus.text = "已暂停"
                        binding.progressDownload.isIndeterminate = false
                        binding.btnPause.text = "继续"
                    }
                    is TransferState.Completed -> {
                        binding.progressDownload.visibility = View.GONE
                        binding.progressDownload.isIndeterminate = false
                        binding.tvTransferStatus.text = "完成: ${state.file.fileName}"
                        binding.btnPause.text = "暂停"
                    }
                }
            }
        }
    }

    private fun refreshGrid() {
        gridAdapter.submit(
            viewModel.filteredPhotos.value,
            viewModel.selectedHandles.value,
            viewModel.thumbnails.value
        )
    }

    private fun updateSelectionTitle() {
        val count = viewModel.selectedHandles.value.size
        binding.tvSelectionTitle.text = if (count > 0) "已选择 $count 项" else "相机照片"
        val total = viewModel.filteredPhotos.value.size
        binding.btnSelectAll.text = if (count > 0 && count == total) "取消全选" else "全选"
    }

    /** 选中态用琥珀色对比色高亮，未选中恢复为透明底文字按钮。 */
    private fun updateDownloadButtonStyle(selected: Boolean) {
        val bg = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.accent else android.R.color.transparent
        )
        val text = ContextCompat.getColor(
            requireContext(),
            if (selected) R.color.white else R.color.accent
        )
        binding.btnDownloadSelected.backgroundTintList = ColorStateList.valueOf(bg)
        binding.btnDownloadSelected.setTextColor(text)
    }

    private fun updateChannelStatus() {
        val usbConnected = viewModel.usbState.value == UsbConnectionState.CONNECTED
        binding.tvChannelStatus.text = when {
            usbConnected -> "通道: USB 有线"
            else -> when (viewModel.connectionState.value) {
                ConnectionState.FULLY_CONNECTED -> "通道: WiFi 已连接"
                ConnectionState.WIFI_UPGRADING -> "通道: 建立 WiFi 中"
                ConnectionState.BLE_CONNECTED -> "通道: BLE 已连接"
                ConnectionState.CONNECTING -> "通道: 连接中"
                ConnectionState.ERROR_WAITING_RETRY -> "通道: 等待重连"
                ConnectionState.DISCONNECTED -> "通道: 未连接"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

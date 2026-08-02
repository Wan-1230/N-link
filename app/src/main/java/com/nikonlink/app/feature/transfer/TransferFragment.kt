package com.nikonlink.app.feature.transfer

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.databinding.FragmentTransferBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 照片传输 Fragment
 * PRD 2.1: 浏览相机存储卡照片列表、缩略图预览、选择性下载、队列管理
 */
@AndroidEntryPoint
class TransferFragment : Fragment() {

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransferViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActions()
        observeState()
    }

    private fun setupActions() {
        binding.btnFetchPhotos.setOnClickListener {
            viewModel.fetchPhotos()
        }

        binding.btnDownloadAll.setOnClickListener {
            viewModel.downloadAll()
        }

        binding.btnPause.setOnClickListener {
            viewModel.pauseTransfer()
            Toast.makeText(requireContext(), "传输已暂停", Toast.LENGTH_SHORT).show()
        }

        binding.btnResume.setOnClickListener {
            viewModel.resumeTransfer()
            Toast.makeText(requireContext(), "传输已恢复", Toast.LENGTH_SHORT).show()
        }

        binding.btnCancelAll.setOnClickListener {
            viewModel.cancelAll()
            Toast.makeText(requireContext(), "已取消全部传输", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
                binding.btnFetchPhotos.isEnabled = !loading
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.message.collect { msg ->
                if (msg.isNotEmpty()) {
                    binding.tvMessage.text = msg
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                updateChannelStatus()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbState.collect { usbState ->
                updateChannelStatus()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photoList.collect { photos ->
                binding.tvPhotoCount.text = "文件数: ${photos.size}"
                // 显示文件列表（简化为文本列表）
                val fileListText = photos.take(50).joinToString("\n") { file ->
                    "${file.fileName}  (${formatSize(file.size)})"
                }
                binding.tvFileList.text = fileListText.ifEmpty { "点击\"获取照片\"浏览存储卡" }

                // 加载前几张缩略图
                photos.take(3).forEach { viewModel.loadThumbnail(it.handle) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.thumbnails.collect { thumbs ->
                // 显示第一张缩略图作为预览
                thumbs.values.firstOrNull()?.let { data ->
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) {
                        binding.ivPreview.setImageBitmap(bitmap)
                        binding.ivPreview.visibility = View.VISIBLE
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.transferState.collect { state ->
                binding.tvTransferStatus.text = when (state) {
                    is TransferState.Idle -> "传输空闲"
                    is TransferState.Downloading -> {
                        val d = state as TransferState.Downloading
                        val pct = if (d.total > 0) (d.received * 100 / d.total) else 0
                        "下载中: ${d.file.fileName} ($pct%)"
                    }
                    is TransferState.Paused -> "已暂停"
                    is TransferState.Completed -> "完成: ${(state as TransferState.Completed).file.fileName}"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.queue.collect { tasks ->
                val pending = tasks.count { it.status == TransferTaskStatus.PENDING }
                val done = tasks.count { it.status == TransferTaskStatus.COMPLETED }
                binding.tvQueueStatus.text = "队列: $done 完成 / $pending 等待"
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun updateChannelStatus() {
        val usbConnected = viewModel.usbState.value == UsbConnectionState.CONNECTED
        binding.tvChannelStatus.text = when {
            usbConnected -> "通道: USB 有线"
            else -> when (viewModel.connectionState.value) {
                ConnectionState.FULLY_CONNECTED -> "通道: WiFi 已连接"
                ConnectionState.WIFI_UPGRADING -> "通道: 正在建立 WiFi"
                ConnectionState.BLE_CONNECTED -> {
                    if (viewModel.statusMessage.value.contains("连接中断")) {
                        "通道: 连接中断"
                    } else {
                        "通道: BLE 已连接"
                    }
                }
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

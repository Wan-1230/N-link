package com.nikonlink.app.feature.transfer

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.R
import com.nikonlink.app.databinding.FragmentTransferBinding
import com.nikonlink.app.databinding.ItemPhotoBinding
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
    private val photoAdapter = PhotoAdapter()

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

        binding.listPhotos.adapter = photoAdapter
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

        binding.btnSelectAll.setOnClickListener {
            if (viewModel.selectedHandles.value.size == viewModel.filteredPhotos.value.size) {
                viewModel.clearSelection()
            } else {
                viewModel.selectAllFiltered()
            }
        }

        binding.btnDownloadSelected.setOnClickListener {
            viewModel.downloadSelected()
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
            viewModel.filteredPhotos.collect { photos ->
                photoAdapter.items = photos
                photoAdapter.selected = viewModel.selectedHandles.value
                photoAdapter.notifyDataSetChanged()
                binding.tvPhotoCount.text = "照片 ${photos.size} / 已选 ${photoAdapter.selected.size}"
                photos.take(3).forEach { viewModel.loadThumbnail(it.handle) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedHandles.collect { selected ->
                photoAdapter.selected = selected
                photoAdapter.notifyDataSetChanged()
                binding.tvPhotoCount.text = "照片 ${photoAdapter.items.size} / 已选 ${selected.size}"
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

    private inner class PhotoAdapter : BaseAdapter() {
        var items: List<CameraFile> = emptyList()
        var selected: Set<Int> = emptySet()

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): CameraFile = items[position]

        override fun getItemId(position: Int): Long = items[position].handle.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val binding = if (convertView == null) {
                ItemPhotoBinding.inflate(layoutInflater, parent, false)
            } else {
                ItemPhotoBinding.bind(convertView)
            }
            val file = items[position]
            binding.tvFileName.text = file.fileName
            binding.tvFileMeta.text = "${formatSize(file.size)}"
            binding.tvFormatBadge.text = when (file.format) {
                CameraFileFormat.JPEG -> "JPG"
                CameraFileFormat.RAW -> "RAW"
                else -> "照片"
            }
            binding.cbSelect.isChecked = file.handle in selected
            binding.cbSelect.setOnClickListener {
                viewModel.toggleSelection(file.handle)
            }
            binding.root.setOnClickListener {
                viewModel.toggleSelection(file.handle)
            }
            return binding.root
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

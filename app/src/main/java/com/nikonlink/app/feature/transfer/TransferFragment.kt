package com.nikonlink.app.feature.transfer

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.FragmentTransferBinding
import com.nikonlink.app.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Tab2 相机相册（黑白极简）
 * 分类标签 + 3 列网格实时预览 + 长按多选 + 底部悬浮操作栏 + 全屏预览
 */
@AndroidEntryPoint
class TransferFragment : Fragment() {

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransferViewModel by viewModels()

    private lateinit var adapter: PhotoGridAdapter
    private val chipViews = mutableMapOf<PhotoFilter, TextView>()
    private var multiSelectMode = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGrid()
        setupChips()
        setupActions()
        observe()
        viewModel.fetchPhotos()
    }

    private fun setupGrid() {
        adapter = PhotoGridAdapter(
            onItemClick = { file, position ->
                if (multiSelectMode) {
                    viewModel.toggleSelection(file.handle)
                } else {
                    // 进入全屏预览页（右推入转场，由主题 windowAnimationStyle 提供）
                    PreviewActivity.start(requireContext(), file)
                }
            },
            onItemLongClick = { file ->
                if (!multiSelectMode) setMultiSelectMode(true)
                viewModel.toggleSelection(file.handle)
            }
        )
        binding.gridPhotos.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.gridPhotos.adapter = adapter
    }

    /** 分类标签：黑底白字胶囊（选中） / 灰底黑字（未选中） */
    private fun setupChips() {
        PhotoFilter.values().forEach { filter ->
            val chip = TextView(requireContext()).apply {
                text = filter.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                setPadding(dp(16), dp(7), dp(16), dp(7))
                val lp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
                setOnClickListener { viewModel.setPhotoFilter(filter) }
                pressEffect()
            }
            chipViews[filter] = chip
            binding.chipRow.addView(chip)
        }
    }

    private fun renderChips(current: PhotoFilter) {
        chipViews.forEach { (filter, chip) ->
            val selected = filter == current
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.on_primary else R.color.text_primary
                )
            )
        }
    }

    private fun setupActions() {
        binding.btnRefresh.setOnClickListener { viewModel.fetchPhotos() }

        binding.btnMultiSelect.setOnClickListener {
            // Fix 真机反馈: 长按已选中照片后再点「多选」，旧逻辑会直接退出多选并清空选中，
            // 用户感知为「没有反馈」。新逻辑：已处于多选态时，有选中先清选中、保持多选；无选中才退出
            if (!multiSelectMode) {
                setMultiSelectMode(true)
            } else if (viewModel.selectedHandles.value.isNotEmpty()) {
                viewModel.clearSelection()
            } else {
                setMultiSelectMode(false)
            }
        }

        binding.btnSelectAll.setOnClickListener { viewModel.selectAllFiltered() }

        binding.btnDownload.pressEffect()
        binding.btnDownload.setOnClickListener {
            if (viewModel.selectedHandles.value.isEmpty()) {
                viewModel.showMessage("请先选择要下载的照片")
            } else {
                viewModel.downloadSelected()
            }
        }

        binding.btnDelete.setOnClickListener {
            val count = viewModel.selectedHandles.value.size
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除")
                .setMessage("确定要从相机存储卡删除 $count 个文件吗？此操作不可恢复。")
                .setPositiveButton("删除") { _, _ ->
                    viewModel.deleteSelected()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnShare.setOnClickListener {
            viewModel.showMessage("请先下载到手机，再从系统相册分享")
        }
    }

    private fun setMultiSelectMode(enabled: Boolean) {
        multiSelectMode = enabled
        adapter.multiSelectMode = enabled
        binding.btnMultiSelect.text = if (enabled) "取消" else "多选"
        if (!enabled) {
            binding.bottomBar.visibility = View.GONE
            viewModel.clearSelection()
        }
        adapter.notifyDataSetChanged()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photoFilter.collect { renderChips(it) }
        }

        // 网格数据：列表 + 选中 + 缩略图
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.filteredPhotos.collect {
                    adapter.submit(it, viewModel.selectedHandles.value, viewModel.thumbnails.value)
                    binding.layoutEmpty.visibility =
                        if (it.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            launch {
                viewModel.selectedHandles.collect { selected ->
                    adapter.submit(viewModel.filteredPhotos.value, selected, viewModel.thumbnails.value)
                    binding.tvSelectedCount.text = "已选 ${selected.size} 项"
                    if (multiSelectMode) {
                        binding.bottomBar.visibility = View.VISIBLE
                    } else if (selected.isEmpty()) {
                        binding.bottomBar.visibility = View.GONE
                    }
                }
            }
            launch {
                viewModel.thumbnails.collect { thumbs ->
                    adapter.submit(viewModel.filteredPhotos.value, viewModel.selectedHandles.value, thumbs)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.message.collect { msg ->
                if (msg.isNotBlank()) binding.tvMessage.text = msg
            }
        }

        // 下载进度：灰度确定进度条 + 百分比
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.transferState.collect { state ->
                when (state) {
                    is TransferState.Idle -> {
                        binding.progressDownload.visibility = View.GONE
                        binding.progressDownload.isIndeterminate = false
                    }
                    is TransferState.Downloading -> {
                        val totalKnown = state.total > 0 && state.total != 0xFFFFFFFFL
                        binding.progressDownload.visibility = View.VISIBLE
                        binding.progressDownload.isIndeterminate = !totalKnown
                        if (totalKnown) {
                            val pct = (state.received * 100 / state.total).toInt().coerceIn(0, 100)
                            binding.progressDownload.progress = pct
                            binding.tvMessage.text = "下载中 ${state.file.fileName} · $pct%"
                        } else {
                            binding.progressDownload.progress = 0
                            binding.tvMessage.text = "下载中 ${state.file.fileName} · 大小未知"
                        }
                    }
                    is TransferState.Paused -> {
                        binding.tvMessage.text = "传输已暂停"
                        binding.progressDownload.isIndeterminate = false
                    }
                    is TransferState.Completed -> {
                        binding.progressDownload.visibility = View.GONE
                        binding.progressDownload.isIndeterminate = false
                        binding.tvMessage.text = "已完成: ${state.file.fileName}"
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

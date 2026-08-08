package com.nikonlink.app.feature.transfer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
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
    private var lastToastMsg: String? = null

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.setAlbum(AlbumSource.LOCAL)
        } else {
            viewModel.showMessage("未授予照片访问权限，无法显示本地照片")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGrid()
        setupChips()
        setupAlbumTabs()
        setupPullRefresh()
        setupActions()
        observe()
        viewModel.fetchPhotos()
    }

    private fun setupGrid() {
        adapter = PhotoGridAdapter(
            onItemClick = { file, position ->
                if (multiSelectMode) {
                    viewModel.toggleSelection(file.handle)
                } else if (viewModel.activeAlbum.value == AlbumSource.LOCAL) {
                    openLocalFile(file)
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

    private fun openLocalFile(file: CameraFile) {
        val uri = viewModel.localContentUri(file.handle)
        val mime = when (file.format) {
            CameraFileFormat.VIDEO -> "video/*"
            CameraFileFormat.RAW -> "image/x-nikon-nef"
            else -> "image/*"
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            startActivity(Intent.createChooser(intent, "查看本地文件"))
        }.onFailure {
            viewModel.showMessage("没有可打开该文件的应用")
        }
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

    private fun setupAlbumTabs() {
        binding.tabCameraPhotos.pressEffect()
        binding.tabCameraPhotos.setOnClickListener {
            viewModel.setAlbum(AlbumSource.CAMERA)
        }
        binding.tabLocalPhotos.pressEffect()
        binding.tabLocalPhotos.setOnClickListener {
            if (hasMediaPermission()) {
                viewModel.setAlbum(AlbumSource.LOCAL)
            } else {
                requestMediaPermission()
            }
        }
    }

    private fun renderAlbumTabs(source: AlbumSource) {
        val cameraSelected = source == AlbumSource.CAMERA
        binding.tabCameraPhotos.setBackgroundResource(
            if (cameraSelected) R.drawable.bg_chip_selected else 0
        )
        binding.tabLocalPhotos.setBackgroundResource(
            if (cameraSelected) 0 else R.drawable.bg_chip_selected
        )
        binding.tabCameraPhotos.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (cameraSelected) R.color.on_primary else R.color.text_primary
            )
        )
        binding.tabLocalPhotos.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (cameraSelected) R.color.text_primary else R.color.on_primary
            )
        )
        binding.tabCameraPhotos.typeface =
            if (cameraSelected) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        binding.tabLocalPhotos.typeface =
            if (cameraSelected) android.graphics.Typeface.DEFAULT
            else android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun setupPullRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.text_primary)
        )
        // SwipeRefreshLayout 的直接子 View 是 FrameLayout，需要让网格自己决定能否向上滚动
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.gridPhotos.canScrollVertically(-1)
        }
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshActiveAlbum()
        }
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_VIDEO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermissionLauncher.launch(permissions)
    }

    private fun setupActions() {
        binding.btnRefresh.pressEffect()
        binding.btnRefresh.setOnClickListener {
            if (viewModel.activeAlbum.value == AlbumSource.LOCAL && !hasMediaPermission()) {
                requestMediaPermission()
            } else {
                viewModel.refreshActiveAlbum()
            }
        }

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
            val isLocal = viewModel.activeAlbum.value == AlbumSource.LOCAL
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除")
                .setMessage(
                    if (isLocal) "确定要删除手机中的 $count 个本地文件吗？此操作不可恢复。"
                    else "确定要从相机存储卡删除 $count 个文件吗？此操作不可恢复。"
                )
                .setPositiveButton(if (isLocal) "删除本地" else "删除") { _, _ ->
                    viewModel.deleteSelected()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnShare.setOnClickListener {
            if (viewModel.activeAlbum.value == AlbumSource.LOCAL) {
                shareLocalSelected()
            } else {
                viewModel.showMessage("请先下载到手机，再从系统相册分享")
            }
        }
    }

    private fun shareLocalSelected() {
        val uris = viewModel.selectedLocalUris()
        if (uris.isEmpty()) {
            viewModel.showMessage("请先选择要分享的本地文件")
            return
        }
        val intent = Intent(
            if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        ).apply {
            type = "*/*"
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, "分享本地文件"))
    }

    private fun setMultiSelectMode(enabled: Boolean) {
        multiSelectMode = enabled
        adapter.multiSelectMode = enabled
        binding.btnMultiSelect.text = if (enabled) "取消" else "多选"
        if (!enabled) {
            binding.bottomBar.visibility = View.GONE
            viewModel.clearSelection()
        } else {
            renderActionButtons()
        }
        adapter.notifyDataSetChanged()
    }

    private fun renderActionButtons() {
        val isLocal = viewModel.activeAlbum.value == AlbumSource.LOCAL
        binding.btnDownload.visibility = if (isLocal) View.GONE else View.VISIBLE
        binding.btnShare.visibility = if (isLocal) View.VISIBLE else View.VISIBLE
        binding.btnDelete.text = if (isLocal) "删除本地" else "删除"
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photoFilter.collect { renderChips(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeAlbum.collect { source ->
                renderAlbumTabs(source)
                if (multiSelectMode) renderActionButtons()
                binding.tvMessage.text = when (source) {
                    AlbumSource.CAMERA -> "连接相机后查看相册"
                    AlbumSource.LOCAL -> "尚未下载照片到手机"
                }
            }
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
                if (!loading) binding.swipeRefresh.isRefreshing = false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.message.collect { msg ->
                if (msg.isNotBlank()) binding.tvMessage.text = msg
            }
        }

        // 全链路优化: 下载成功/失败/通道切换均用 Toast 反馈，不再静默
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.managerMessage.collect { msg ->
                if (msg.isNotBlank() && msg != lastToastMsg) {
                    lastToastMsg = msg
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 通道状态变化时刷新空态文案，明确告知当前走 USB 还是 WiFi
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.usbState.collect {
                if (viewModel.activeAlbum.value == AlbumSource.CAMERA &&
                    viewModel.photoList.value.isEmpty()
                ) {
                    binding.tvMessage.text = "连接相机后查看相册 · 当前通道: ${viewModel.activeChannel()}"
                }
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

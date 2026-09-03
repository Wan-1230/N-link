package com.nikonlink.app.camera.gallery

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
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
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Tab2 相机相册（黑白极简）
 * 分类标签 + 3 列网格实时预览 + 长按多选 + 底部悬浮操作栏 + 全屏预览
 */
@AndroidEntryPoint
class TransferFragment : Fragment() {

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TransferViewModel by viewModels()

    @Inject
    lateinit var thumbnailCache: ThumbnailCache

    private lateinit var adapter: PhotoGridAdapter
    private val chipViews = mutableMapOf<PhotoFilter, TextView>()
    private var chipNotDownloaded: TextView? = null
    private var multiSelectMode = false
    private var lastToastMsg: String? = null

    /**
     * 排序锚点：切换排序前记下首屏第一项的 handle，
     * 列表重排后把它滚回视野顶部，用户不会「被扔回列表开头」。
     */
    private var scrollAnchorHandle: Int? = null

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
        consumeDeepLink()
    }

    /**
     * F5 深链：传输完成通知的「查看」跳到相册页本地照片源。
     * extra 由 MainActivity 的 open_tab 路由带入，消费后立即移除，
     * 避免之后重建 Fragment（切 Tab 回来/旋转）时重复触发。
     */
    private fun consumeDeepLink() {
        val activity = activity ?: return
        val intent = activity.intent ?: return
        if (!intent.getBooleanExtra(TransferManager.EXTRA_OPEN_GALLERY_LOCAL, false)) return
        intent.removeExtra(TransferManager.EXTRA_OPEN_GALLERY_LOCAL)
        if (hasMediaPermission()) {
            viewModel.setAlbum(AlbumSource.LOCAL)
        } else {
            requestMediaPermission()
        }
    }

    private fun setupGrid() {
        adapter = PhotoGridAdapter(
            cache = thumbnailCache,
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
            },
            onRequestThumb = { file -> viewModel.requestThumbnail(file.handle) },
            // 日期分组标题行的「全选 / 取消全选」：
            // 非多选态先进入多选模式（让底栏与对勾一并出现），再整组切换选中
            onToggleGroupSelection = { handles ->
                if (!multiSelectMode) setMultiSelectMode(true)
                viewModel.toggleGroupSelection(handles)
            }
        )
        binding.gridPhotos.layoutManager = GridLayoutManager(requireContext(), 3).apply {
            // 日期标题行占满整行，照片格子各占 1 列
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.isHeaderAt(position)) 3 else 1
            }
        }
        binding.gridPhotos.adapter = adapter
    }

    /**
     * 是否按日期分组展示。
     *
     * 仅「拍摄时间」排序下生效：时间排序天然按日成块，插标题行不会打乱顺序；
     * 「文件类型」排序下同一天的照片被拆散在 JPG / RAW 各段里，硬分组会出现
     * 重复的日期标题，故保持平铺。「已标记」栏按标记时间排列（与拍摄日期无关），
     * 同样不分组。
     */
    private fun shouldGroupByDate(): Boolean =
        viewModel.activeAlbum.value != AlbumSource.MARKED &&
            viewModel.sort.value.dimension == AlbumSortDimension.CAPTURE_TIME

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

        // F2：「未下载」开关 chip，与类型筛选可叠加；仅相机相册显示
        val notDownloadedChip = TextView(requireContext()).apply {
            text = "未下载"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            setPadding(dp(16), dp(7), dp(16), dp(7))
            val lp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(4)
            layoutParams = lp
            setOnClickListener {
                viewModel.setOnlyNotDownloaded(!viewModel.onlyNotDownloaded.value)
            }
            pressEffect()
        }
        chipNotDownloaded = notDownloadedChip
        binding.chipRow.addView(notDownloadedChip)
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
        renderNotDownloadedChip(viewModel.onlyNotDownloaded.value)
    }

    /** F2：「未下载」chip 选中态渲染；仅相机源可见 */
    private fun renderNotDownloadedChip(enabled: Boolean) {
        val chip = chipNotDownloaded ?: return
        chip.visibility =
            if (viewModel.activeAlbum.value == AlbumSource.CAMERA) View.VISIBLE else View.GONE
        chip.setBackgroundResource(if (enabled) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        chip.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (enabled) R.color.on_primary else R.color.text_primary
            )
        )
    }

    private fun setupAlbumTabs() {
        binding.tabCameraPhotos.pressEffect()
        binding.tabCameraPhotos.setOnClickListener {
            viewModel.setAlbum(AlbumSource.CAMERA)
        }
        // F1「已标记」独立分区：进入即默认多选态（全选 → 批量下载的工作台）
        binding.tabMarkedPhotos.pressEffect()
        binding.tabMarkedPhotos.setOnClickListener {
            viewModel.setAlbum(AlbumSource.MARKED)
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
        val tabs = mapOf(
            AlbumSource.CAMERA to binding.tabCameraPhotos,
            AlbumSource.MARKED to binding.tabMarkedPhotos,
            AlbumSource.LOCAL to binding.tabLocalPhotos
        )
        tabs.forEach { (tabSource, tab) ->
            val selected = tabSource == source
            tab.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else 0)
            tab.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (selected) R.color.on_primary else R.color.text_primary
                )
            )
            tab.typeface =
                if (selected) android.graphics.Typeface.DEFAULT_BOLD
                else android.graphics.Typeface.DEFAULT
        }
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

        // 排序入口：弹出下拉菜单，含「拍摄时间 / 文件类型」×「升序 / 降序」四项。
        // 只改展示顺序，不重新拉取列表；相机相册与本地相册都可用（本地时间取 MediaStore 的 DATE_TAKEN）。
        binding.btnSort.pressEffect()
        binding.btnSort.setOnClickListener { showSortMenu() }

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

        binding.btnSelectAll.setOnClickListener {
            when (viewModel.activeAlbum.value) {
                AlbumSource.MARKED -> viewModel.selectAllMarked()
                else -> viewModel.selectAllFiltered()
            }
        }

        // F1：标记/取消标记切换（选中集全部已标 → 取消；否则 → 打标）
        binding.btnMark.pressEffect()
        binding.btnMark.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.toggleMarkSelection()
                renderActionButtons()
            }
        }

        // F2：已标记栏「跳过已下载」开关
        binding.btnSkipDownloaded.pressEffect()
        binding.btnSkipDownloaded.setOnClickListener {
            viewModel.setSkipDownloadedInMarks(!viewModel.skipDownloadedInMarks.value)
            renderActionButtons()
        }

        binding.btnDownload.pressEffect()
        binding.btnDownload.setOnClickListener {
            if (viewModel.selectedHandles.value.isEmpty()) {
                viewModel.showMessage("请先选择要下载的照片")
                return@setOnClickListener
            }
            // F1 大额保护（ZRelay largeSelectionWarning 口径）：全选超过阈值先二次确认
            if (viewModel.activeAlbum.value == AlbumSource.MARKED &&
                viewModel.selectedHandles.value.size > LARGE_SELECTION_WARNING
            ) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("批量下载")
                    .setMessage(
                        "已选 ${viewModel.selectedHandles.value.size} 张，" +
                            "批量下载原图会占用较多电量与时间，继续吗？"
                    )
                    .setPositiveButton("继续") { _, _ -> viewModel.downloadMarkedSelected() }
                    .setNegativeButton("取消", null)
                    .show()
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
            when (viewModel.activeAlbum.value) {
                // F4：相机源分享走「预览副本」链路（长边 2048，默认剥离 GPS）
                AlbumSource.CAMERA -> viewModel.shareSelectedCameraCopies()
                else -> shareLocalSelected()
            }
        }
    }

    /**
     * 排序下拉菜单（与 LiveViewFragment 的「更多」菜单同一实现：PopupMenu + menu.add）。
     * 当前规则以勾选态标记，选中即 [TransferViewModel.setSort] 并持久化。
     */
    private fun showSortMenu() {
        val current = viewModel.sort.value
        val popup = PopupMenu(requireContext(), binding.btnSort)
        AlbumSort.OPTIONS.forEachIndexed { index, option ->
            popup.menu.add(0, index + 1, 0, option.menuLabel)
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.findItem(AlbumSort.OPTIONS.indexOf(current) + 1)?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            val option = AlbumSort.OPTIONS.getOrNull(item.itemId - 1) ?: return@setOnMenuItemClickListener false
            if (option == current) return@setOnMenuItemClickListener true
            // 先记锚点再改排序：重排后把这张图滚回原来的位置
            captureScrollAnchor()
            viewModel.setSort(option)
            true
        }
        popup.show()
    }

    /**
     * 记录当前首屏第一项作为排序后的滚动锚点。
     *
     * 网格现在是「标题行 + 照片」的扁平序列：首屏第一项是日期标题时，
     * 取它后面那一张照片作锚点（标题行本身不参与排序，不能当锚）。
     */
    private fun captureScrollAnchor() {
        val lm = binding.gridPhotos.layoutManager as? GridLayoutManager ?: return
        val position = lm.findFirstVisibleItemPosition()
        scrollAnchorHandle = adapter.itemAt(position)?.handle ?: adapter.itemAt(position + 1)?.handle
    }

    /**
     * 列表刷新后把锚点项滚回视野顶部。
     *
     * 只在存在待恢复锚点时动作一次并立即清空，后续的缩略图加载、
     * 选中态变化等常规刷新都不受影响。锚点文件被筛选掉时（找不到）不做滚动。
     * 滚动放到 post 里执行，等 DiffUtil 的更新派发完成后再定位。
     * 下标用 [PhotoGridAdapter.indexOfHandle] 换算，因为扁平序列里插了日期标题行。
     */
    private fun restoreScrollAnchor(list: List<CameraFile>) {
        val handle = scrollAnchorHandle ?: return
        scrollAnchorHandle = null
        if (list.none { it.handle == handle }) return
        val index = adapter.indexOfHandle(handle)
        if (index < 0) return
        binding.gridPhotos.post {
            if (_binding == null) return@post
            (binding.gridPhotos.layoutManager as? GridLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
    }

    private fun shareLocalSelected() {
        val uris = viewModel.selectedLocalUris()
        if (uris.isEmpty()) {
            viewModel.showMessage("请先选择要分享的本地文件")
            return
        }
        shareUris(uris, "分享本地文件")
    }

    /** 统一的多文件分享面板（本地分享与 F4 预览副本共用） */
    private fun shareUris(uris: List<Uri>, title: String) {
        if (uris.isEmpty()) return
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
        runCatching {
            startActivity(Intent.createChooser(intent, title))
        }.onFailure {
            viewModel.showMessage("没有可用的分享应用")
        }
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
        when (viewModel.activeAlbum.value) {
            AlbumSource.LOCAL -> {
                binding.btnDownload.visibility = View.GONE
                // 相机端照片未下载前无法直接分享，仅本地相册展示分享入口
                binding.btnShare.visibility = View.VISIBLE
                binding.btnDelete.text = "删除本地"
                binding.btnMark.visibility = View.GONE
                binding.btnSkipDownloaded.visibility = View.GONE
            }

            AlbumSource.MARKED -> {
                // F1：标记栏工作台 —— 下载 + 删除 + 标记切换 + 跳过已下载
                binding.btnDownload.visibility = View.VISIBLE
                binding.btnShare.visibility = View.GONE
                binding.btnDelete.text = "删除"
                binding.btnMark.visibility = View.VISIBLE
                binding.btnSkipDownloaded.visibility = View.VISIBLE
                renderMarkButton()
                renderSkipDownloadedButton()
            }

            AlbumSource.CAMERA -> {
                binding.btnDownload.visibility = View.VISIBLE
                // F4：相机源分享 = 批量生成预览副本
                binding.btnShare.visibility = View.VISIBLE
                binding.btnDelete.text = "删除"
                binding.btnMark.visibility = View.VISIBLE
                binding.btnSkipDownloaded.visibility = View.GONE
                renderMarkButton()
            }
        }
    }

    /** F1：底栏「标记」按钮的文案随选中集状态切换（全部已标 → 「取消标记」） */
    private fun renderMarkButton() {
        val selected = viewModel.selectedHandles.value
        val allMarked = selected.isNotEmpty() && selected.all { it in viewModel.markedHandles.value }
        binding.btnMark.text = if (allMarked) "取消标记" else "标记"
    }

    /** F2：「跳过已下载」开关的选中态渲染 */
    private fun renderSkipDownloadedButton() {
        val on = viewModel.skipDownloadedInMarks.value
        binding.btnSkipDownloaded.text = if (on) "✓ 跳过已下载" else "跳过已下载"
        binding.btnSkipDownloaded.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (on) R.color.on_primary else R.color.text_tertiary
            )
        )
        binding.btnSkipDownloaded.setBackgroundResource(
            if (on) R.drawable.bg_chip_selected else 0
        )
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.photoFilter.collect { renderChips(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.onlyNotDownloaded.collect { renderNotDownloadedChip(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeAlbum.collect { source ->
                renderAlbumTabs(source)
                // F1：「已标记」进入即默认多选态（全选 → 批量下载的工作台）；
                // 离开标记栏回到常规浏览则退出多选，保持既有交互心智
                when (source) {
                    AlbumSource.MARKED -> if (!multiSelectMode) setMultiSelectMode(true)
                    else -> if (multiSelectMode) setMultiSelectMode(false)
                }
                if (multiSelectMode) renderActionButtons()
                // 排序入口仅常规两源可用：「已标记」固定按标记时间倒序（PRD F1）
                binding.btnSort.visibility =
                    if (source == AlbumSource.MARKED) View.GONE else View.VISIBLE
                renderNotDownloadedChip(viewModel.onlyNotDownloaded.value)
                binding.tvMessage.text = when (source) {
                    AlbumSource.CAMERA -> "连接相机后查看相册"
                    AlbumSource.MARKED -> "暂无标记照片：在相机相册长按多选后点「标记」"
                    AlbumSource.LOCAL -> "尚未下载照片到手机"
                }
            }
        }

        // F1：「已标记」栏数量角标（Tab 文案跟随）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.markedRecords.collect { records ->
                binding.tabMarkedPhotos.text =
                    if (records.isEmpty()) "已标记" else "已标记 ${records.size}"
            }
        }

        // 排序按钮文案跟随当前排序（各选项字数一致，切换时不会挤动相邻控件）
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sort.collect { sort ->
                binding.btnSort.text = sort.shortLabel
            }
        }

        // 网格数据：列表 + 选中 + 缩略图 + 标记/已下载角标（F1/F2）
        // 「已标记」源提交 markedDisplayList，其余源提交 filteredPhotos
        viewLifecycleOwner.lifecycleScope.launch {
            fun displayList(): List<CameraFile> =
                if (viewModel.activeAlbum.value == AlbumSource.MARKED) {
                    viewModel.markedDisplayList.value
                } else {
                    viewModel.filteredPhotos.value
                }

            launch {
                viewModel.filteredPhotos.collect { list ->
                    if (viewModel.activeAlbum.value != AlbumSource.MARKED) {
                        adapter.submit(
                            list,
                            viewModel.selectedHandles.value,
                            viewModel.thumbnails.value,
                            viewModel.markedHandles.value,
                            viewModel.downloadedHandles.value,
                            groupByDate = shouldGroupByDate()
                        )
                        binding.layoutEmpty.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                        restoreScrollAnchor(list)
                    }
                }
            }
            launch {
                viewModel.markedDisplayList.collect { list ->
                    if (viewModel.activeAlbum.value == AlbumSource.MARKED) {
                        adapter.submit(
                            list,
                            viewModel.selectedHandles.value,
                            viewModel.thumbnails.value,
                            viewModel.markedHandles.value,
                            viewModel.downloadedHandles.value,
                            groupByDate = shouldGroupByDate()
                        )
                        binding.layoutEmpty.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                        if (list.isEmpty()) {
                            binding.tvMessage.text = if (viewModel.markedRecords.value.isEmpty()) {
                                "暂无标记照片：在相机相册长按多选后点「标记」"
                            } else {
                                "标记的照片都已下载，或相机尚未连接"
                            }
                        }
                    }
                }
            }
            launch {
                viewModel.selectedHandles.collect { selected ->
                    adapter.submit(
                        displayList(),
                        selected,
                        viewModel.thumbnails.value,
                        viewModel.markedHandles.value,
                        viewModel.downloadedHandles.value,
                        groupByDate = shouldGroupByDate()
                    )
                    binding.tvSelectedCount.text = "已选 ${selected.size} 项"
                    if (multiSelectMode) {
                        binding.bottomBar.visibility = View.VISIBLE
                        renderMarkButton()
                    } else if (selected.isEmpty()) {
                        binding.bottomBar.visibility = View.GONE
                    }
                }
            }
            launch {
                viewModel.thumbnails.collect { thumbs ->
                    adapter.submit(
                        displayList(),
                        viewModel.selectedHandles.value,
                        thumbs,
                        viewModel.markedHandles.value,
                        viewModel.downloadedHandles.value,
                        groupByDate = shouldGroupByDate()
                    )
                }
            }
            launch {
                viewModel.markedHandles.collect {
                    adapter.submit(
                        displayList(),
                        viewModel.selectedHandles.value,
                        viewModel.thumbnails.value,
                        it,
                        viewModel.downloadedHandles.value,
                        groupByDate = shouldGroupByDate()
                    )
                }
            }
            launch {
                viewModel.downloadedHandles.collect {
                    adapter.submit(
                        displayList(),
                        viewModel.selectedHandles.value,
                        viewModel.thumbnails.value,
                        viewModel.markedHandles.value,
                        it,
                        groupByDate = shouldGroupByDate()
                    )
                }
            }
            launch {
                viewModel.skipDownloadedInMarks.collect { renderSkipDownloadedButton() }
            }
        }

        // F1 AC-5：标记栏批量下载完成 → 弹「清除这些标记」确认
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.clearMarksPrompt.collect { count ->
                if (count <= 0) return@collect
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("批量下载完成")
                    .setMessage("已下载 $count 张标记照片，是否清除这些标记？")
                    .setPositiveButton("清除") { _, _ -> viewModel.clearDownloadedMarks() }
                    .setNegativeButton("保留", null)
                    .show()
            }
        }

        // F4：批量生成分享副本的进度与完成事件
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shareExportProgress.collect { progress ->
                if (progress == null) return@collect
                val (done, total) = progress
                binding.tvMessage.text = "生成分享副本 $done/$total…"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shareExportDone.collect { result ->
                shareUris(result.uris, "分享照片")
                if (result.failed.isNotEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "${result.failed.size} 个文件生成副本失败",
                        Toast.LENGTH_SHORT
                    ).show()
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

        // 连接成功后自动加载相册。
        // 背景：MainActivity 的四个 Fragment 常驻，切 Tab 只走 hide/show，
        // onViewCreated 里的 fetchPhotos() 全程只跑一次（通常在相机还没连上时），
        // 此前没有任何机制在连接就绪后补一次加载。
        // 这里只负责转发状态，真正的「上升沿只触发一次」判定在 ViewModel 内，
        // 保证切 Tab 回来、配置重建等场景不会重复加载。
        // 手动刷新入口（btnRefresh / 下拉刷新）保持原样不变。
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.cameraReady.collect { ready ->
                viewModel.onCameraReadyChanged(ready)
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
                        val speed = formatSpeed(viewModel.transferSpeedBps.value)
                        if (totalKnown) {
                            val pct = (state.received * 100 / state.total).toInt().coerceIn(0, 100)
                            binding.progressDownload.progress = pct
                            binding.tvMessage.text = "下载中 ${state.file.fileName} · $pct%$speed"
                        } else {
                            binding.progressDownload.progress = 0
                            binding.tvMessage.text = "下载中 ${state.file.fileName} · 大小未知$speed"
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

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val mbPerSec = bytesPerSec / 1024.0 / 1024.0
        return " · ${String.format(Locale.US, "%.1f", mbPerSec)} MB/s"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** F1 大额保护阈值：全选超过该张数时批量下载前二次确认（PRD F1 AC-10） */
        private const val LARGE_SELECTION_WARNING = 200
    }
}

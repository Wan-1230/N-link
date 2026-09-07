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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.FragmentTransferBinding
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** 模块 4.4：长按滑动多选控制器（未激活时事件完全透传） */
    private var dragSelect: DragSelectController? = null

    /** 模块 4.3：用户主动刷新后，数据合并完成时强制回列表顶部 */
    private var pendingScrollToTop = false

    /**
     * 需求 3：关闭「不重复下载已下载照片」后要回到原来的位置。
     * 记的是**照片 handle**（不是 adapter 位置）—— 恢复后列表长度变了，位置会失效，
     * 而 handle 稳定；找不到时退化为回顶部。
     */
    private var pendingRestoreAnchorHandle: Int? = null

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
        observeDownloadStats()
        // holdPages：首进相册页不逐页发射中间态（handle 原始序与倒序展示几乎逆序，
        // DiffUtil 会拖着视口来回跳动），等全量拉取完成后一次性提交排序后的最终列表
        viewModel.fetchPhotos(holdPages = true)
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
                    // 进入全屏预览页（右推入转场，由主题 windowAnimationStyle 提供）。
                    // 传**整组列表 + 起始位置**，预览页才能左右滑动切换上一张/下一张。
                    // 注意不能用回调里的 position——它是含日期分组标题行的 adapter 位置，
                    // 与 uiPhotos 的索引不是一回事；按 handle 反查才是可靠位置。
                    val list = viewModel.uiPhotos.value
                    val startPos = list.indexOfFirst { it.handle == file.handle }.coerceAtLeast(0)
                    PreviewActivity.start(requireContext(), list, startPos)
                }
            },
            onItemLongClick = { file, position ->
                // 模块 4.4：长按进入多选并选中起点，随后不抬手滑动即进入拖动多选
                if (!multiSelectMode) setMultiSelectMode(true)
                viewModel.toggleSelection(file.handle)
                dragSelect?.onDragStart(position)
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
        // 模块 4.4：滑动多选事件接管（未激活时完全透传，不影响滚动/点击）
        dragSelect = DragSelectController(
            recyclerView = binding.gridPhotos,
            itemAt = { position -> adapter.itemAt(position) },
            onRange = { handles, select -> viewModel.setSelectionRange(handles, select) }
        )
        binding.gridPhotos.addOnItemTouchListener(dragSelect!!)
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
        renderSkipDownloadedChip()
    }

    /**
     * 模块 4.2 + 2026-09-08（需求 2）：「不重复下载已下载照片」按钮选中态渲染。
     *
     * 可见性：已标记源（原逻辑）+ **相机照片源**（新增）。本地源仍隐藏——本地页
     * 本身就是已下载集合，没有「跳过已下载」的语义。
     *
     * 选中态按源取自不同开关：已标记 → [TransferViewModel.skipDownloadedInMarks]（默认开），
     * 相机 → [TransferViewModel.onlyNotDownloaded]（默认关，沿用相机页原有逻辑）。
     */
    private fun renderSkipDownloadedChip() {
        val btn = binding.btnSkipDownloaded
        val source = viewModel.activeAlbum.value
        btn.visibility =
            if (source == AlbumSource.MARKED || source == AlbumSource.CAMERA) {
                View.VISIBLE
            } else {
                View.GONE
            }
        val enabled = if (source == AlbumSource.CAMERA) {
            viewModel.onlyNotDownloaded.value
        } else {
            viewModel.skipDownloadedInMarks.value
        }
        if (enabled) {
            // 开启过滤：黑底白字图标，一眼看出开关已拨到「跳过」
            btn.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_chip_selected)
            btn.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.on_primary)
        } else {
            // 关闭：透明背景 + 黑色图标（与 btnRefresh 一致，保留按压涟漪）
            btn.background = skipBtnBorderlessBg
            btn.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_primary)
        }
    }

    /** 「跳过已下载」关闭态背景：复用系统 borderless 按压涟漪，与 btnRefresh 一致 */
    private val skipBtnBorderlessBg: android.graphics.drawable.Drawable? by lazy {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, tv, true
        )
        ContextCompat.getDrawable(requireContext(), tv.resourceId)
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
        var selectedIndex = 0
        tabs.forEach { (tabSource, tab) ->
            val selected = tabSource == source
            if (selected) selectedIndex = tabs.keys.indexOf(tabSource)
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
        // 模块 4.5：选中胶囊滑动到目标 Tab（180ms）；首次渲染直接落位不平移动画。
        // 指示器宽度 = 行宽 / Tab 数（运行时设置），translationX = 序号 × 段宽
        // （FrameLayout 子 View 已落在 padding 内侧，无需再加 paddingLeft）
        val row = binding.tabCameraPhotos.parent as? android.view.ViewGroup ?: return
        fun applyIndicator(retries: Int) {
            if (_binding == null) return
            // MainActivity 四 Fragment 常驻、本页初始 hidden（GONE 不参与布局），
            // onViewCreated 阶段 row.width 必为 0——旧版此时静默放弃，指示器永远
            // 停在全宽黑胶囊（表现为「底部只见 1 个 Tab」）。这里改为有限次重试，
            // 直到页面 show() 后完成首次布局再落位。
            val rowWidth = row.width - row.paddingLeft - row.paddingRight
            if (rowWidth <= 0) {
                if (retries > 0) binding.tabIndicator.postDelayed({ applyIndicator(retries - 1) }, 64)
                return
            }
            val segWidth = rowWidth / tabs.size
            if (binding.tabIndicator.layoutParams.width != segWidth) {
                binding.tabIndicator.layoutParams.width = segWidth
                binding.tabIndicator.requestLayout()
            }
            binding.tabIndicator.animate()
                .translationX(selectedIndex * segWidth.toFloat())
                .setDuration(if (tabIndicatorInitialized) 180L else 0L)
                .start()
            tabIndicatorInitialized = true
        }
        binding.tabIndicator.post { applyIndicator(24) }
    }

    private var tabIndicatorInitialized = false

    /** Tab show/hide 不走 onResume：显示时补一次 Tab 指示器渲染（首帧只有 1 个 Tab 的修复） */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            renderAlbumTabs(viewModel.activeAlbum.value)
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
            // 模块 4.3：用户主动刷新 → 数据合并完成后强制回列表顶部
            pendingScrollToTop = true
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
                // 模块 4.3：手动刷新同样在数据合并完成后回顶部
                pendingScrollToTop = true
                viewModel.refreshActiveAlbum()
            }
        }

        // 模块 4.2（已标记源）+ 2026-09-08 需求 2/3（相机源）：同一按钮、按源切不同开关。
        // 相机源：关闭（即将重新显示被隐藏的照片）前先记锚点，列表重发后回到原位置。
        binding.btnSkipDownloaded.pressEffect()
        binding.btnSkipDownloaded.setOnClickListener {
            if (viewModel.activeAlbum.value == AlbumSource.CAMERA) {
                val willShow = viewModel.onlyNotDownloaded.value
                if (willShow) captureScrollAnchor()
                viewModel.setOnlyNotDownloaded(!willShow)
            } else {
                viewModel.setSkipDownloadedInMarks(!viewModel.skipDownloadedInMarks.value)
            }
        }

        // 排序入口：弹出下拉菜单，含「拍摄时间 / 文件类型」×「升序 / 降序」四项。
        // 只改展示顺序，不重新拉取列表；相机相册与本地相册都可用（本地时间取 MediaStore 的 DATE_TAKEN）。
        binding.btnSort.pressEffect()
        binding.btnSort.setOnClickListener { showSortMenu() }

        binding.btnMultiSelect.pressEffect()
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

        binding.btnSelectAll.pressEffect()
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

        binding.btnDelete.pressEffect()
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
            // 切换排序后始终回列表顶部（而非记录锚点）：排序维度变化时分组结构会重排，
            // 若沿用锚点会把同项推到很靠后、表现为「翻到尾页」。复用 pendingScrollToTop
            // 机制，在最终列表 submit 之后 post{scrollToPosition(0)}，避免「跳底再弹回」。
            pendingScrollToTop = true
            viewModel.setSort(option)
            true
        }
        popup.show()
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
            }

            AlbumSource.MARKED -> {
                // F1：标记栏工作台 —— 下载 + 删除 + 标记切换（跳过已下载已迁至 chip 行）
                binding.btnDownload.visibility = View.VISIBLE
                binding.btnShare.visibility = View.GONE
                binding.btnDelete.text = "删除"
                binding.btnMark.visibility = View.VISIBLE
                renderMarkButton()
            }

            AlbumSource.CAMERA -> {
                binding.btnDownload.visibility = View.VISIBLE
                // F4：相机源分享 = 批量生成预览副本
                binding.btnShare.visibility = View.VISIBLE
                binding.btnDelete.text = "删除"
                binding.btnMark.visibility = View.VISIBLE
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
                renderDownloadStats()
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

        // 网格数据（模块 4.1）：全部收集器只订阅 uiPhotos 单一数据源。
        // 切 Tab 时 _activeAlbum 变化必然触发 uiPhotos 重发，选中态/列表/加载态原子切换，
        // 不再存在「目标 flow 无新值导致界面停留」的竞态；快速连点天然由最后的 emit 收敛。
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                viewModel.uiPhotos.collect { list ->
                    adapter.submit(
                        list,
                        viewModel.selectedHandles.value,
                        viewModel.thumbnails.value,
                        viewModel.markedHandles.value,
                        viewModel.downloadedHandles.value,
                        groupByDate = shouldGroupByDate()
                    )
                    // 模块 4.3：滚动复位必须在「最终（排序后）列表」submit 之后再执行，
                    // 否则会被后续提交再次移动视口（跳底→弹回的两段运动）
                    // 需求 3：关闭隐藏开关后回到关闭前的第一个可见项（找不到则回顶部）
                    pendingRestoreAnchorHandle?.let { anchor ->
                        pendingRestoreAnchorHandle = null
                        binding.gridPhotos.post {
                            if (_binding == null) return@post
                            // indexOfHandle 返回含日期标题行的 adapter 位置
                            val index = adapter.indexOfHandle(anchor)
                            val lm = binding.gridPhotos.layoutManager as? GridLayoutManager
                            if (index >= 0 && lm != null) {
                                lm.scrollToPositionWithOffset(index, 0)
                            } else {
                                binding.gridPhotos.scrollToPosition(0)
                            }
                        }
                    }
                    if (pendingScrollToTop) {
                        pendingScrollToTop = false
                        binding.gridPhotos.post {
                            if (_binding != null) binding.gridPhotos.scrollToPosition(0)
                        }
                    }
                    binding.layoutEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                    if (list.isEmpty()) {
                        // O4：筛选态空结果兜底——「未下载」筛选开着且列表为空，
                        // 但原始列表有货 = 全部下载完了。自动关筛选并提示，
                        // 避免「全下完之后反而看不到任何照片」。
                        if (viewModel.activeAlbum.value == AlbumSource.CAMERA &&
                            viewModel.onlyNotDownloaded.value &&
                            viewModel.photoList.value.isNotEmpty()
                        ) {
                            viewModel.setOnlyNotDownloaded(false)
                            Toast.makeText(
                                requireContext(),
                                "已全部下载，已显示全部照片",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@collect
                        }
                        binding.tvMessage.text = when (viewModel.activeAlbum.value) {
                            AlbumSource.MARKED -> if (viewModel.markedRecords.value.isEmpty()) {
                                "暂无标记照片：在相机相册长按多选后点「标记」"
                            } else {
                                "标记的照片都已下载，或相机尚未连接"
                            }

                            AlbumSource.CAMERA -> "连接相机后查看相册"
                            AlbumSource.LOCAL -> "尚未下载照片到手机"
                        }
                    }
                }
            }
            launch {
                viewModel.selectedHandles.collect { selected ->
                    adapter.submit(
                        viewModel.uiPhotos.value,
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
                // B2：缩略图逐张到达，旧逻辑每来一张就全量 submit（主线程 buildItems + DiffUtil 各 O(n)），
                // 快速滑动时几十张连发会把主线程占满 → 卡住 / 花屏 / 堆叠。
                // 改为：先只重绘可见范围（十几项，廉价），静默 100ms 后再补一次全量 submit 收口。
                var fullSubmitJob: Job? = null
                viewModel.thumbnails.collect { thumbs ->
                    val lm = binding.gridPhotos.layoutManager as? GridLayoutManager
                    val first = lm?.findFirstVisibleItemPosition() ?: -1
                    val last = lm?.findLastVisibleItemPosition() ?: -1
                    if (first >= 0 && last >= first) {
                        adapter.notifyThumbRangeChanged(first, last - first + 1)
                    }
                    fullSubmitJob?.cancel()
                    fullSubmitJob = launch {
                        delay(THUMB_FULL_SUBMIT_DELAY_MS)
                        if (_binding == null) return@launch
                        adapter.submit(
                            viewModel.uiPhotos.value,
                            viewModel.selectedHandles.value,
                            viewModel.thumbnails.value,
                            viewModel.markedHandles.value,
                            viewModel.downloadedHandles.value,
                            groupByDate = shouldGroupByDate()
                        )
                    }
                }
            }
            launch {
                // 渐进式缩略图：高清替换小图时 handle 集合不变，Set 相等不会触发上面的流，
                // 因此单独监听升级计数，只重绘**可见范围**（十几项），避免全量 DiffUtil。
                viewModel.thumbUpgradeTick.collect { tick ->
                    if (tick == 0L) return@collect
                    val lm = binding.gridPhotos.layoutManager as? GridLayoutManager ?: return@collect
                    val first = lm.findFirstVisibleItemPosition()
                    val last = lm.findLastVisibleItemPosition()
                    if (first >= 0 && last >= first) {
                        adapter.notifyItemRangeChanged(first, last - first + 1)
                    }
                }
            }
            launch {
                viewModel.markedHandles.collect {
                    adapter.submit(
                        viewModel.uiPhotos.value,
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
                        viewModel.uiPhotos.value,
                        viewModel.selectedHandles.value,
                        viewModel.thumbnails.value,
                        viewModel.markedHandles.value,
                        it,
                        groupByDate = shouldGroupByDate()
                    )
                }
            }
            launch {
                viewModel.skipDownloadedInMarks.collect { renderSkipDownloadedChip() }
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
                // 仅收起下拉刷新圈；滚动复位挪到 uiPhotos 最终提交之后（见下方 collector）——
                // 旧版在这里滚顶会插在「排序后的最终列表 submit」之前，产生「跳底再弹回」两段运动
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

    /** 需求 3：记下当前第一个可见照片的 handle，供关闭隐藏开关后恢复位置 */
    private fun captureScrollAnchor() {
        val layoutManager = binding.gridPhotos.layoutManager as? GridLayoutManager ?: return
        val start = layoutManager.findFirstVisibleItemPosition()
        if (start == RecyclerView.NO_POSITION) return
        // adapter 位置含日期标题行，标题不是照片（itemAt 返回 null），向后找到第一个照片格
        for (i in start until adapter.itemCount) {
            val file = adapter.itemAt(i) ?: continue
            pendingRestoreAnchorHandle = file.handle
            return
        }
    }

    /**
     * O2：剩余下载进度 —— 「已下载 M/N · 剩余 K 张」。
     * 只在**相机照片源**显示（本地页本就是已下载集合，无剩余概念）；下载中追加整体百分比。
     */
    private fun renderDownloadStats() {
        if (_binding == null) return
        val stats = viewModel.downloadStats.value
        val camera = viewModel.activeAlbum.value == AlbumSource.CAMERA
        val visible = camera && stats.total > 0
        binding.tvDownloadStats.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        // 被「不重复下载已下载照片」隐藏的张数 = 剩余 K，两个功能互相印证
        binding.tvDownloadStats.text = if (viewModel.transferState.value is TransferState.Downloading) {
            "已下载 ${stats.downloaded}/${stats.total} · 剩余 ${stats.remaining} 张 · ${stats.percent}%"
        } else {
            "已下载 ${stats.downloaded}/${stats.total} · 剩余 ${stats.remaining} 张"
        }
    }

    /** 收集剩余下载进度：数值变化与切源都要刷新 */
    private fun observeDownloadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.downloadStats.collect { renderDownloadStats() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.transferState.collect { renderDownloadStats() }
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

        /**
         * B2：缩略图合并刷新窗口。窗口内只重绘可见范围，静默该时长后
         * 再补一次全量 submit 收口（保证选中态/角标等最终一致）。
         */
        private const val THUMB_FULL_SUBMIT_DELAY_MS = 100L
    }
}

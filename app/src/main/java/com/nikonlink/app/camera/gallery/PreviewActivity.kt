package com.nikonlink.app.camera.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nikonlink.app.shared.ui.glass.GlassCoordinator
import com.nikonlink.app.shared.ui.glass.GlassRegistry
import com.nikonlink.app.shared.ui.glass.GlassTokens
import com.nikonlink.app.shared.ui.glass.NlGlass
import com.nikonlink.app.shared.ui.glass.UiFlags
import com.nikonlink.app.shared.ui.glass.applyGlass
import com.nikonlink.app.R
import com.nikonlink.app.camera.data.PhotoMarkRepository
import com.nikonlink.app.databinding.ActivityPreviewBinding
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import timber.log.Timber
import javax.inject.Inject

/**
 * 全屏预览页（二级页面：右推入转场由主题 windowAnimationStyle 提供）
 * 顶部悬浮：返回 / 文件名 / 页码(N/M) / 更多
 * 底部悬浮：标记（F1）/ 下载（原图）/ 拍摄信息（F3 底部抽屉）/ 分享（F4 预览副本）
 *
 * 改造：用 ViewPager2 承载整组照片，左右滑动切换上一张/下一张；每页懒加载大图。
 */
@AndroidEntryPoint
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Preview"

        // 整组照片通过基本类型数组传递（CameraFile 非 Parcelable，且不允许修改其定义）
        private const val EXTRA_HANDLES = "handles"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_SIZES = "sizes"
        private const val EXTRA_FORMAT_CODES = "format_codes"
        private const val EXTRA_STORAGE_IDS = "storage_ids"
        private const val EXTRA_CAPTURE_TIMES = "capture_times"
        private const val EXTRA_POSITION = "position"

        /** 单张入口（兼容 TransferFragment 现有调用）：内部转成只含一张的列表 */
        fun start(context: Context, file: CameraFile) {
            start(context, listOf(file), 0)
        }

        /** 整组入口：传入列表 + 起始位置 */
        fun start(context: Context, files: List<CameraFile>, position: Int) {
            val safePos = position.coerceIn(0, (files.size - 1).coerceAtLeast(0))
            context.startActivity(Intent(context, PreviewActivity::class.java).apply {
                putExtra(EXTRA_HANDLES, files.map { it.handle }.toIntArray())
                putExtra(EXTRA_NAMES, files.map { it.fileName }.toTypedArray())
                putExtra(EXTRA_SIZES, files.map { it.size }.toLongArray())
                putExtra(EXTRA_FORMAT_CODES, files.map { it.formatCode }.toIntArray())
                putExtra(EXTRA_STORAGE_IDS, files.map { it.storageId }.toIntArray())
                putExtra(EXTRA_CAPTURE_TIMES, files.map { it.captureTimeMillis ?: 0L }.toLongArray())
                putExtra(EXTRA_POSITION, safePos)
            })
        }
    }

    @Inject
    lateinit var transferManager: TransferManager

    @Inject
    lateinit var thumbnailCache: ThumbnailCache

    @Inject
    lateinit var photoMarkRepository: PhotoMarkRepository

    @Inject
    lateinit var shareExporter: PreviewShareExporter

    @Inject
    lateinit var settings: AppSettings

    private lateinit var binding: ActivityPreviewBinding

    /** 上下工具栏的背景纹理源（= ViewPager2 里的照片），换页时要作废 */
    private var previewGlass: GlassCoordinator? = null

    /** 整组照片（由 Intent 基本类型数组重建，format 用 classifyFormat 还原） */
    private lateinit var files: List<CameraFile>

    /** 当前展示的照片（随滑动更新，底部栏与信息均以它为准） */
    private var file: CameraFile = CameraFile(0, "", 0, 0, 0)
    private var currentPosition = 0

    /** 每页下载结果缓存：position -> 已下载本地路径（避免滑动后丢失下载态） */
    private val downloadResults = mutableMapOf<Int, String>()

    /** 当前页进度订阅协程（onStart 订阅 / onStop 取消 / 切页重订阅） */
    private var progressJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        files = buildFilesFromIntent()
        if (files.isEmpty()) {
            // 极端兜底：没有任何数据直接关闭，避免空 ViewPager 崩溃
            finish()
            return
        }
        currentPosition = intent.getIntExtra(EXTRA_POSITION, 0).coerceIn(0, files.size - 1)
        file = files[currentPosition]
        applyGlassBars()

        updateTopBar()
        setupViewPager()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDownload.setOnClickListener { download() }

        binding.btnMark.pressEffect()
        binding.btnMark.setOnClickListener { toggleMark() }
        observeMarkState()

        binding.btnExif.setOnClickListener { showInfoSheet() }
        binding.btnShare.setOnClickListener { showSharePanel() }

        binding.btnMore.setOnClickListener {
            NlGlass.dialog(this)
                .setTitle(file.fileName)
                .setItems(arrayOf("下载原图", "查看拍摄信息")) { _, which ->
                    when (which) {
                        0 -> download()
                        1 -> binding.btnExif.performClick()
                    }
                }
                .show()
        }
    }

    // ---------- 进度订阅（按 handle，退出再进入仍显示实时进度） ----------

    override fun onStart() {
        super.onStart()
        // 页面可见时订阅当前文件的下载进度；重新进入（onStop→onStart）会再次订阅并按当前进度渲染
        subscribeProgress()
    }

    override fun onStop() {
        // 页面不可见时取消订阅（释放 Flow 收集），不丢进度——下次进入重新订阅即可
        progressJob?.cancel()
        progressJob = null
        super.onStop()
    }

    /** 订阅当前文件的下载进度；切页 / onStart 都会重订阅，实现「退出再进入仍显示进度」 */
    private fun subscribeProgress() {
        progressJob?.cancel()
        val handle = file.handle
        progressJob = lifecycleScope.launch {
            transferManager.observeDownloadProgress(handle).collect { progress ->
                // 下载改由应用级队列驱动后，进度/完成事件很可能在用户翻到别页之后才到，
                // 因此底部栏只认「仍在显示的那一张」，路径则始终回填到它自己所在的页。
                if (handle != file.handle) return@collect
                renderDownloadProgress(progress, handle)
            }
        }
    }

    /** 按 DownloadProgress 渲染底部下载栏（订阅驱动，单一数据源） */
    private fun renderDownloadProgress(progress: DownloadProgress, handle: Int) {
        when (progress) {
            is DownloadProgress.Downloading -> {
                binding.progressDownload.visibility = View.VISIBLE
                val totalKnown = progress.total > 0 && progress.total != 0xFFFFFFFFL
                binding.progressDownload.isIndeterminate = !totalKnown
                if (totalKnown) {
                    val pct = (progress.received * 100 / progress.total).toInt().coerceIn(0, 100)
                    binding.progressDownload.progress = pct
                    binding.tvDownloadLabel.text = "下载中 $pct%"
                } else {
                    binding.progressDownload.progress = 0
                    binding.tvDownloadLabel.text = "下载中"
                }
                binding.iconDownload.setImageResource(R.drawable.ic_download)
            }

            DownloadProgress.Queued -> {
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.isIndeterminate = true
                binding.iconDownload.setImageResource(R.drawable.ic_download)
                binding.tvDownloadLabel.text = "排队中"
            }

            is DownloadProgress.Completed -> {
                binding.progressDownload.visibility = View.GONE
                binding.progressDownload.isIndeterminate = false
                binding.progressDownload.progress = 100
                binding.iconDownload.setImageResource(R.drawable.ic_check)
                binding.iconDownload.scaleX = 1f
                binding.iconDownload.scaleY = 1f
                binding.tvDownloadLabel.text = "已完成"
                val posOfHandle = files.indexOfFirst { it.handle == handle }
                if (posOfHandle >= 0) downloadResults[posOfHandle] = progress.localPath
            }

            is DownloadProgress.Failed -> {
                binding.progressDownload.visibility = View.GONE
                binding.iconDownload.setImageResource(R.drawable.ic_download)
                binding.tvDownloadLabel.text = "重试"
            }

            DownloadProgress.NotQueued -> {
                binding.progressDownload.visibility = View.GONE
                binding.progressDownload.isIndeterminate = false
                binding.progressDownload.progress = 0
                binding.iconDownload.setImageResource(R.drawable.ic_download)
                binding.iconDownload.scaleX = 1f
                binding.iconDownload.scaleY = 1f
                binding.tvDownloadLabel.text = "下载"
            }
        }
    }

    // ---------- 列表重建与 ViewPager ----------

    private fun buildFilesFromIntent(): List<CameraFile> {
        val handles = intent.getIntArrayExtra(EXTRA_HANDLES) ?: intArrayOf()
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: arrayOf()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: longArrayOf()
        val formatCodes = intent.getIntArrayExtra(EXTRA_FORMAT_CODES) ?: intArrayOf()
        val storageIds = intent.getIntArrayExtra(EXTRA_STORAGE_IDS) ?: intArrayOf()
        val captureTimes = intent.getLongArrayExtra(EXTRA_CAPTURE_TIMES) ?: longArrayOf()
        return handles.indices.map { i ->
            val name = names.getOrNull(i) ?: ""
            val fc = formatCodes.getOrNull(i) ?: 0
            CameraFile(
                handle = handles.getOrNull(i) ?: 0,
                fileName = name,
                size = sizes.getOrNull(i) ?: 0,
                formatCode = fc,
                storageId = storageIds.getOrNull(i) ?: 0,
                format = classifyFormat(fc, name),
                captureTimeMillis = captureTimes.getOrNull(i)?.takeIf { it > 0 }
            )
        }
    }

    private fun setupViewPager() {
        binding.vpPreview.adapter = PreviewPagerAdapter()
        // 仅预载相邻 1 页，滑到才真正加载大图，避免一次性加载全部撑爆内存
        binding.vpPreview.offscreenPageLimit = 1
        binding.vpPreview.setCurrentItem(currentPosition, false)
        binding.vpPreview.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                file = files[position]
                updateTopBar()
                // 换页 = 玻璃背后的整张图都变了，纹理必须作废重采
                previewGlass?.invalidate()
                refreshMarkState()   // 切页立即刷新标记态（不等待 mark 变化事件）
                subscribeProgress()  // 切到新文件 → 重订阅其下载进度，进度条跟随切换
            }
        })
    }

    /**
     * 预览页上下工具栏玻璃化（PRD §5.3）。
     *
     * 两面都是**贴边通栏**，所以半径取 0 —— 贴屏幕边的圆角看起来像漏涂。
     * 层级感交给 tint + 模糊 + 内顶高光，这正是 iOS 半透明导航栏的做法。
     * 背后是 ViewPager2 里的照片，所以能拿到真折射；白图顶上来时由
     * [GlassSurfaceDrawable] 的对比度自适应兜底（PRD §9.3）。
     */
    private fun applyGlassBars() {
        val bars = listOf(binding.previewTopBar, binding.previewBottomBar)
        if (!UiFlags.glassEnabled(this)) {
            previewGlass = null
            bars.forEach {
                it.setBackgroundColor(ContextCompat.getColor(this, R.color.liveview_scrim))
                it.elevation = 0f
                GlassRegistry.unregister(it)
            }
            return
        }
        val coord = GlassCoordinator.attach(binding.vpPreview).also { previewGlass = it }
        // 翻页时背景整张换掉，采集要跟上；静止时不必高频
        coord.minRefreshMs = 120L
        bars.forEach { bar ->
            bar.applyGlass(coord) { GlassTokens.hud(it.context).copy(radiusPx = 0f) }
        }
    }

    private fun updateTopBar() {
        binding.tvFileName.text = file.fileName
        binding.tvPage.text = "${currentPosition + 1} / ${files.size}"
    }

    /** ViewPager2 每页：代码构造 ImageView + 进度 + 失败提示（不新增 XML 布局文件） */
    private class PageViewHolder(val pv: PageViews) : RecyclerView.ViewHolder(pv.root)

    private data class PageViews(
        val root: FrameLayout,
        val iv: ImageView,
        val progress: ProgressBar,
        val error: TextView
    )

    private fun createPageViews(): PageViews {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "影像预览"
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.white))
            layoutParams = FrameLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER }
        }
        val error = TextView(this).apply {
            setTextColor(getColor(R.color.white))
            textSize = 13f
            text = "预览加载失败"
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(iv)
        root.addView(progress)
        root.addView(error)
        return PageViews(root, iv, progress, error)
    }

    private inner class PreviewPagerAdapter : RecyclerView.Adapter<PageViewHolder>() {
        override fun getItemCount(): Int = files.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder =
            PageViewHolder(createPageViews())

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val f = files[position]
            holder.pv.error.visibility = View.GONE
            holder.pv.progress.visibility = View.VISIBLE
            holder.pv.iv.setImageBitmap(null)
            loadInto(f, holder, position)
        }
    }

    /**
     * 单页懒加载：内存 → 磁盘缓存 → PTP（与网格页共享缓存）。
     * 用 bindingAdapterPosition 校验，避免 ViewHolder 被回收复用到其他页后旧图串页。
     */
    private fun loadInto(f: CameraFile, holder: PageViewHolder, position: Int) {
        lifecycleScope.launch {
            var bitmap = withContext(Dispatchers.IO) { thumbnailCache.get(f.handle) }
            if (bitmap == null) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { transferManager.fetchThumbnail(f.handle) }.getOrNull()
                }
                if (bytes != null) {
                    bitmap = withContext(Dispatchers.IO) { thumbnailCache.putBytes(f.handle, bytes) }
                }
            }
            if (holder.bindingAdapterPosition != position) return@launch // 已被复用，丢弃结果
            if (bitmap != null) {
                holder.pv.iv.setImageBitmap(bitmap)
                holder.pv.progress.visibility = View.GONE
            } else {
                holder.pv.progress.visibility = View.GONE
                holder.pv.error.visibility = View.VISIBLE
            }
        }
    }

    // ---------- F1：标记 ----------

    /** 监听标记变化（实时反映当前页标记态） */
    private fun observeMarkState() {
        lifecycleScope.launch {
            photoMarkRepository.observeAll().collect {
                val marked = withContext(Dispatchers.Default) {
                    photoMarkRepository.isMarked(file)
                }
                binding.tvMarkLabel.text = if (marked) "已标记" else "标记"
                binding.iconMark.alpha = if (marked) 1f else 0.55f
            }
        }
    }

    /** 切页时立即刷新标记态（不依赖 mark 变化事件） */
    private fun refreshMarkState() {
        lifecycleScope.launch {
            val marked = photoMarkRepository.isMarked(file)
            binding.tvMarkLabel.text = if (marked) "已标记" else "标记"
            binding.iconMark.alpha = if (marked) 1f else 0.55f
        }
    }

    private fun toggleMark() {
        lifecycleScope.launch {
            val marked = photoMarkRepository.isMarked(file)
            runCatching {
                if (marked) {
                    photoMarkRepository.unmark(listOf(file))
                } else {
                    photoMarkRepository.mark(listOf(file))
                    if (settings.markAutoDownload && transferManager.hasActiveSession()) {
                        transferManager.enqueue(listOf(file))
                    }
                }
            }.onFailure { e ->
                Timber.tag(TAG).w(e, "Toggle mark failed")
            }
        }
    }

    // ---------- F3：拍摄信息（底部抽屉 + 复制全部） ----------

    private fun showInfoSheet() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { collectInfo() }
            val sheet = NlGlass.sheet(this@PreviewActivity)
            val container = android.widget.LinearLayout(this@PreviewActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(20))
            }
            val title = android.widget.TextView(this@PreviewActivity).apply {
                text = "拍摄信息"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            container.addView(title)

            val body = android.widget.TextView(this@PreviewActivity).apply {
                text = info.text
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextColor(getColor(R.color.text_primary))
                setPadding(0, dp(12), 0, dp(12))
                setTextIsSelectable(true)
            }
            container.addView(body)

            val copyBtn = android.widget.TextView(this@PreviewActivity).apply {
                text = "复制全部"
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip_selected)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setTextColor(getColor(R.color.on_primary))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                pressEffect()
                setOnClickListener {
                    val clipboard = getSystemService(
                        ClipboardManager::class.java
                    )
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("N-Link 拍摄信息", info.text)
                    )
                    android.widget.Toast.makeText(
                        this@PreviewActivity, "已复制拍摄信息", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    sheet.dismiss()
                }
            }
            val copyWrap = android.widget.FrameLayout(this@PreviewActivity)
            container.addView(copyWrap)
            copyWrap.addView(copyBtn)
            sheet.setContentView(container)
            sheet.show()
        }
    }

    private data class InfoText(val text: String)

    /**
     * 数据源分级（PRD F3）：ObjectInfo 基本区恒可显示；参数区先试缩略图缓存磁盘文件
     * 的内嵌 EXIF，再试已归档原图；都拿不到按「—」兜底，不报错。
     */
    private fun collectInfo(): InfoText {
        val base = buildString {
            append("文件名: ").append(file.fileName).append('\n')
            append("格式: ").append(file.format.name).append('\n')
            append("大小: ").append(formatSize(file.size)).append('\n')
            file.captureTimeMillis?.let {
                append(
                    "拍摄时间: ").append(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        .format(java.util.Date(it))
                ).append('\n')
            }
        }
        val exifSources = sequence<ExifInterface?> {
            val thumbFile = runCatching { thumbnailCache.diskFile(file.handle) }.getOrNull()
            yield(thumbFile?.let { path ->
                runCatching { java.io.FileInputStream(path).use { ExifInterface(it) } }.getOrNull()
            })
            val localPath = downloadResults[currentPosition]
            if (localPath != null) {
                yield(runCatching {
                    contentResolver.openInputStream(Uri.parse(localPath))
                        ?.use { stream -> ExifInterface(stream) }
                }.getOrNull())
            }
        }
        for (exif in exifSources) {
            if (exif == null) continue
            val fNumber = rational(exif.getAttribute(ExifInterface.TAG_F_NUMBER))
            val exposure = rational(exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME))
            val focal = rational(exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH))
            val hasParams = fNumber != null || exposure != null ||
                exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) != null
            if (!hasParams) continue

            fun tag(name: String): String = exif.getAttribute(name)?.ifBlank { null } ?: "—"
            val detail = buildString {
                append("光圈: ").append(fNumber?.let { "f/${String.format("%.1f", it)}" } ?: "—").append('\n')
                append("快门: ").append(
                    exposure?.let { formatExposure(it) } ?: "—"
                ).append('\n')
                append("ISO: ").append(tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)).append('\n')
                append("焦距: ").append(
                    focal?.let { "${if (it == it.toLong().toDouble()) it.toLong() else String.format("%.0f", it)} mm" } ?: "—"
                ).append('\n')
                append("机身: ").append(tag(ExifInterface.TAG_MODEL)).append('\n')
                append("镜头: ").append(tag(ExifInterface.TAG_LENS_MODEL)).append('\n')
            }
            return InfoText(base + "\n" + detail)
        }
        return InfoText(
            base + "\n光圈 / 快门 / ISO 等参数将在下载原图后完整展示。"
        )
    }

    /** EXIF 快门口径：与 ShutterCatalog 的 1s 分界规则一致（<1s 显示分数，≥1s 显示秒） */
    private fun formatExposure(seconds: Double): String =
        if (seconds < 1) "1/${(1 / seconds).roundToInt()} s" else "$seconds s"

    private fun rational(v: String?): Double? {
        val parts = v?.split("/") ?: return null
        val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return if (den == 0.0) null else num / den
    }

    // ---------- F4：分享（预览副本 / 原图） ----------

    private fun showSharePanel() {
        val copySupported = shareExporter.supportsPreviewCopy(file)
        val options = buildList {
            add(
                if (copySupported) "分享预览副本（长边 2048）"
                else "分享预览副本（${file.format.name} 暂不支持，需先下载原图）"
            )
            add("分享原图")
        }
        NlGlass.dialog(this)
            .setTitle("分享")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (copySupported) sharePreviewCopy() else {
                        android.widget.Toast.makeText(
                            this, "RAW/视频副本依赖预览通道，请先下载原图后分享",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }

                    else -> shareOriginal()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sharePreviewCopy() {
        binding.progressDownload.visibility = View.VISIBLE
        binding.progressDownload.isIndeterminate = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                shareExporter.export(listOf(file)) { _, _ -> }
            }
            binding.progressDownload.visibility = View.GONE
            binding.progressDownload.isIndeterminate = false
            val uri = result.uris.firstOrNull()
            if (uri == null) {
                android.widget.Toast.makeText(
                    this@PreviewActivity, "副本生成失败：${result.failed.firstOrNull() ?: "未知原因"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            shareUris(listOf(uri), "分享预览副本")
        }
    }

    private fun shareOriginal() {
        lifecycleScope.launch {
            val alreadyDownloaded = downloadResults.containsKey(currentPosition)
            if (!alreadyDownloaded) {
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.isIndeterminate = true
            }
            val uri = withContext(Dispatchers.IO) {
                shareExporter.exportOriginalUri(file, downloadResults[currentPosition])
            }
            binding.progressDownload.visibility = View.GONE
            binding.progressDownload.isIndeterminate = false
            if (uri == null) {
                android.widget.Toast.makeText(
                    this@PreviewActivity, "相机未连接或下载失败，无法分享原图",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            shareUris(listOf(uri), "分享原图")
        }
    }

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
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, title))
        }.onFailure {
            android.widget.Toast.makeText(this, "没有可用的分享应用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 下载（按当前页维护状态） ----------

    /**
     * 下载当前页原图 —— 交给 TransferManager 的应用级队列，不再绑在本页生命周期上。
     *
     * 旧实现在 `lifecycleScope` 里直接调 `downloadPhoto()`：退出预览 → 协程取消 →
     * 传输被就地掐断，半截临时文件也没人续，只能重新进入这张、再点一次下载才保存成功。
     * 入队后传输挂在 ConnectionService 的 scope 上，切页 / 退后台 / 关掉预览页都继续跑完，
     * 按钮态与进度由 [TransferManager.observeDownloadProgress] 单一数据源驱动
     * （见 [subscribeProgress] / [renderDownloadProgress]）。
     */
    private fun download() {
        val pos = currentPosition
        if (downloadResults.containsKey(pos)) return // 已下载，避免重复
        if (!transferManager.hasActiveSession()) {
            android.widget.Toast.makeText(this, "相机未连接，无法下载", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressDownload.visibility = View.VISIBLE
        binding.progressDownload.isIndeterminate = true
        binding.tvDownloadLabel.text = "排队中"
        transferManager.enqueue(listOf(file))
    }

    /** 兼容旧逻辑：下载状态（当前页），主要由 downloadResults 维护 */
    private fun dp(value: Int): Int =
        android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}

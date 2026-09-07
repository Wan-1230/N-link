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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.camera.data.PhotoMarkRepository
import com.nikonlink.app.databinding.ActivityPreviewBinding
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
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

    /** 整组照片（由 Intent 基本类型数组重建，format 用 classifyFormat 还原） */
    private lateinit var files: List<CameraFile>

    /** 当前展示的照片（随滑动更新，底部栏与信息均以它为准） */
    private var file: CameraFile = CameraFile(0, "", 0, 0, 0)
    private var currentPosition = 0

    /** 每页下载结果缓存：position -> 已下载本地路径（避免滑动后丢失下载态） */
    private val downloadResults = mutableMapOf<Int, String>()

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
            MaterialAlertDialogBuilder(this)
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
                refreshMarkState()   // 切页立即刷新标记态（不等待 mark 变化事件）
                syncDownloadUi(position)
            }
        })
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
            val sheet = BottomSheetDialog(this@PreviewActivity)
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
        MaterialAlertDialogBuilder(this)
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

    private fun download() {
        val pos = currentPosition
        if (downloadResults.containsKey(pos)) return // 已下载，避免重复
        binding.progressDownload.visibility = View.VISIBLE
        binding.tvDownloadLabel.text = "下载中"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                transferManager.downloadPhoto(
                    file = file,
                    onProgress = { received, total ->
                        runOnUiThread {
                            val totalKnown = total > 0 && total != 0xFFFFFFFFL
                            binding.progressDownload.isIndeterminate = !totalKnown
                            if (totalKnown) {
                                binding.progressDownload.progress =
                                    (received * 100 / total).toInt().coerceIn(0, 100)
                                binding.tvDownloadLabel.text =
                                    "下载中 ${binding.progressDownload.progress}%"
                            } else {
                                binding.progressDownload.progress = 0
                                binding.tvDownloadLabel.text = "下载中"
                            }
                        }
                    }
                )
            }
            when (result) {
                is TransferResult.Success -> {
                    // 仅当仍停留在同一页时才更新底部栏（切走则交由 syncDownloadUi 还原）
                    downloadResults[pos] = result.path
                    if (pos == currentPosition) {
                        binding.progressDownload.isIndeterminate = false
                        binding.progressDownload.progress = 100
                        binding.iconDownload.setImageResource(R.drawable.ic_check)
                        binding.iconDownload.scaleX = 0.5f
                        binding.iconDownload.scaleY = 0.5f
                        binding.iconDownload.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
                        binding.tvDownloadLabel.text = "已完成"
                    }
                }

                is TransferResult.Failed -> {
                    if (pos == currentPosition) {
                        binding.progressDownload.visibility = View.GONE
                        binding.tvDownloadLabel.text = "重试"
                        Timber.tag(TAG).w("Download failed: ${result.reason}")
                    }
                }

                is TransferResult.Cancelled -> {
                    if (pos == currentPosition) {
                        binding.progressDownload.visibility = View.GONE
                    }
                }
            }
        }
    }

    /** 切页时根据下载缓存同步底部下载栏（已下载显示完成，否则复位） */
    private fun syncDownloadUi(position: Int) {
        val path = downloadResults[position]
        if (path != null) {
            binding.progressDownload.visibility = View.GONE
            binding.progressDownload.isIndeterminate = false
            binding.progressDownload.progress = 100
            binding.iconDownload.setImageResource(R.drawable.ic_check)
            binding.iconDownload.scaleX = 1f
            binding.iconDownload.scaleY = 1f
            binding.tvDownloadLabel.text = "已完成"
        } else {
            binding.progressDownload.visibility = View.GONE
            binding.progressDownload.isIndeterminate = false
            binding.progressDownload.progress = 0
            binding.iconDownload.setImageResource(R.drawable.ic_download)
            binding.iconDownload.scaleX = 1f
            binding.iconDownload.scaleY = 1f
            binding.tvDownloadLabel.text = "下载"
        }
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

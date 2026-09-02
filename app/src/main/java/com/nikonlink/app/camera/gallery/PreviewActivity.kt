package com.nikonlink.app.camera.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
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
 * 顶部悬浮：返回 / 文件名 / 更多
 * 底部悬浮：标记（F1）/ 下载（原图）/ 拍摄信息（F3 底部抽屉）/ 分享（F4 预览副本）
 */
@AndroidEntryPoint
class PreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Preview"
        private const val EXTRA_HANDLE = "handle"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_FORMAT_CODE = "format_code"
        private const val EXTRA_STORAGE_ID = "storage_id"
        private const val EXTRA_CAPTURE_TIME = "capture_time"

        fun start(context: Context, file: CameraFile) {
            context.startActivity(Intent(context, PreviewActivity::class.java).apply {
                putExtra(EXTRA_HANDLE, file.handle)
                putExtra(EXTRA_NAME, file.fileName)
                putExtra(EXTRA_SIZE, file.size)
                putExtra(EXTRA_FORMAT_CODE, file.formatCode)
                putExtra(EXTRA_STORAGE_ID, file.storageId)
                putExtra(EXTRA_CAPTURE_TIME, file.captureTimeMillis ?: 0L)
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
    private lateinit var file: CameraFile
    private var downloaded = false
    private var downloadedPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        file = CameraFile(
            handle = intent.getIntExtra(EXTRA_HANDLE, 0),
            fileName = intent.getStringExtra(EXTRA_NAME) ?: "",
            size = intent.getLongExtra(EXTRA_SIZE, 0),
            formatCode = intent.getIntExtra(EXTRA_FORMAT_CODE, 0),
            storageId = intent.getIntExtra(EXTRA_STORAGE_ID, 0),
            format = classifyFormat(
                intent.getIntExtra(EXTRA_FORMAT_CODE, 0),
                intent.getStringExtra(EXTRA_NAME) ?: ""
            ),
            captureTimeMillis = intent.getLongExtra(EXTRA_CAPTURE_TIME, 0L).takeIf { it > 0 }
        )
        binding.tvFileName.text = file.fileName

        loadPreview()

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

    // ---------- F1：标记 ----------

    private fun observeMarkState() {
        lifecycleScope.launch {
            photoMarkRepository.observeAll().collect { marks ->
                val marked = withContext(Dispatchers.Default) {
                    marks.any { it.objectHandle == file.handle && it.fileName == file.fileName }
                }
                binding.tvMarkLabel.text = if (marked) "已标记" else "标记"
                binding.iconMark.alpha = if (marked) 1f else 0.55f
            }
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
                    // F1 可选增强：标记后自动入队（设置开关默认关）
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
            // 1) 缩略图磁盘缓存（Nikon 缩略图为内嵌 JPEG，部分机身带参数 EXIF）
            val thumbFile = runCatching { thumbnailCache.diskFile(file.handle) }.getOrNull()
            yield(thumbFile?.let { path ->
                runCatching { java.io.FileInputStream(path).use { ExifInterface(it) } }.getOrNull()
            })
            // 2) 已归档原图（完整 EXIF；ExifInterface 读取支持流，无需落盘中转）
            if (downloadedPath != null) {
                yield(runCatching {
                    contentResolver.openInputStream(Uri.parse(downloadedPath))
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
            if (!downloaded) {
                binding.progressDownload.visibility = View.VISIBLE
                binding.progressDownload.isIndeterminate = true
            }
            val uri = withContext(Dispatchers.IO) {
                shareExporter.exportOriginalUri(file, downloadedPath)
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

    /** 用缩略图先行预览：内存 → 磁盘缓存 → PTP（与网格页共享缓存，不再重复拉取） */
    private fun loadPreview() {
        lifecycleScope.launch {
            var bitmap = withContext(Dispatchers.IO) { thumbnailCache.get(file.handle) }
            if (bitmap == null) {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { transferManager.fetchThumbnail(file.handle) }.getOrNull()
                }
                if (bytes != null) {
                    bitmap = thumbnailCache.putBytes(file.handle, bytes)
                }
            }
            if (bitmap != null) {
                binding.ivPreview.setImageBitmap(bitmap)
                binding.progressPreview.visibility = View.GONE
            }
        }
    }

    private fun download() {
        if (downloaded) return
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
                    downloaded = true
                    downloadedPath = result.path
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress = 100
                    // 下载完成对勾收敛动画
                    binding.iconDownload.setImageResource(R.drawable.ic_check)
                    binding.iconDownload.scaleX = 0.5f
                    binding.iconDownload.scaleY = 0.5f
                    binding.iconDownload.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
                    binding.tvDownloadLabel.text = "已完成"
                }

                is TransferResult.Failed -> {
                    binding.progressDownload.visibility = View.GONE
                    binding.tvDownloadLabel.text = "重试"
                    Timber.tag(TAG).w("Download failed: ${result.reason}")
                }

                is TransferResult.Cancelled -> {
                    binding.progressDownload.visibility = View.GONE
                }
            }
        }
    }

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

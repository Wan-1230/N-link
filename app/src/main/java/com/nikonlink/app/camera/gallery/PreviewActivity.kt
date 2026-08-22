package com.nikonlink.app.camera.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ActivityPreviewBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import timber.log.Timber
import javax.inject.Inject

/**
 * 全屏预览页（二级页面：右推入转场由主题提供）
 * 顶部悬浮：返回 / 文件名 / 更多
 * 底部悬浮：下载（原图）/ EXIF / 分享
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

        fun start(context: Context, file: CameraFile) {
            context.startActivity(Intent(context, PreviewActivity::class.java).apply {
                putExtra(EXTRA_HANDLE, file.handle)
                putExtra(EXTRA_NAME, file.fileName)
                putExtra(EXTRA_SIZE, file.size)
                putExtra(EXTRA_FORMAT_CODE, file.formatCode)
                putExtra(EXTRA_STORAGE_ID, file.storageId)
            })
        }
    }

    @Inject
    lateinit var transferManager: TransferManager

    @Inject
    lateinit var thumbnailCache: ThumbnailCache

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
            )
        )
        binding.tvFileName.text = file.fileName

        loadPreview()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnDownload.setOnClickListener { download() }

        binding.btnExif.setOnClickListener {
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { buildExifText() }
                MaterialAlertDialogBuilder(this@PreviewActivity)
                    .setTitle("拍摄信息")
                    .setMessage(info)
                    .setPositiveButton("确定", null)
                    .show()
            }
        }

        binding.btnShare.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("分享")
                .setMessage(if (downloaded) "已保存到手机相册，请从系统相册分享。" else "请先下载原图，再从系统相册分享。")
                .setPositiveButton("确定", null)
                .show()
        }

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

    /** 拍摄信息：已下载时从保存文件解析 EXIF（光圈/快门/ISO/焦距/时间），未下载提示先下载 */
    private fun buildExifText(): String {
        val path = downloadedPath
        val base = buildString {
            append("文件名: ").append(file.fileName).append('\n')
            append("格式: ").append(file.format.name).append('\n')
            append("大小: ").append(formatSize(file.size)).append('\n')
        }
        if (path == null) {
            return base + "\n光圈 / 快门 / ISO / 焦距 / 拍摄时间等 EXIF 参数将在下载原图后完整解析。"
        }
        return try {
            val exif = contentResolver.openInputStream(Uri.parse(path))?.use {
                ExifInterface(it)
            } ?: return base + "\n无法打开文件。"
            fun tag(name: String): String = exif.getAttribute(name)?.ifBlank { "--" } ?: "--"
            fun rational(v: String?): Double? {
                val parts = v?.split("/") ?: return null
                val num = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
                val den = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
                return if (den == 0.0) null else num / den
            }
            val fNumber = rational(tag(ExifInterface.TAG_F_NUMBER))
            val exposure = rational(tag(ExifInterface.TAG_EXPOSURE_TIME))
            val focal = rational(tag(ExifInterface.TAG_FOCAL_LENGTH))
            base + buildString {
                append("像素: ").append(tag(ExifInterface.TAG_IMAGE_WIDTH)).append(" × ")
                    .append(tag(ExifInterface.TAG_IMAGE_LENGTH)).append('\n')
                append("光圈: ").append(fNumber?.let { "f/${String.format("%.1f", it)}" } ?: "--").append('\n')
                append("快门: ").append(exposure?.let { if (it < 1) "1/${(1 / it).roundToInt()} s" else "${it} s" } ?: "--").append('\n')
                append("ISO: ").append(tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)).append('\n')
                append("焦距: ").append(focal?.let { "${if (it == it.toLong().toDouble()) it.toLong() else String.format("%.0f", it)} mm" } ?: "--").append('\n')
                append("拍摄时间: ").append(tag(ExifInterface.TAG_DATETIME_ORIGINAL)).append('\n')
                append("机身: ").append(tag(ExifInterface.TAG_MODEL)).append('\n')
                append("镜头: ").append(tag(ExifInterface.TAG_LENS_MODEL)).append('\n')
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "EXIF read failed")
            base + "\nEXIF 信息解析失败（RAW 文件暂不支持机内 EXIF 展示）"
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
                binding.progressPreview.visibility = android.view.View.GONE
            }
        }
    }

    private fun download() {
        if (downloaded) return
        binding.progressDownload.visibility = android.view.View.VISIBLE
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
                    binding.progressDownload.visibility = android.view.View.GONE
                    binding.tvDownloadLabel.text = "重试"
                    Timber.tag(TAG).w("Download failed: ${result.reason}")
                }
                is TransferResult.Cancelled -> {
                    binding.progressDownload.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}

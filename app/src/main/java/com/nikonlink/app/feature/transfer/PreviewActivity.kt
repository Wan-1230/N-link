package com.nikonlink.app.feature.transfer

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ActivityPreviewBinding
import com.nikonlink.app.feature.edit.EditActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * 全屏预览页（二级页面：右推入转场由主题提供）
 * 顶部悬浮：返回 / 文件名 / 更多
 * 底部悬浮：下载（原图）/ AI 修图（PRD 5.1 入口①②）/ EXIF / 分享
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
        private const val EXTRA_IS_LOCAL = "is_local"

        /**
         * @param isLocal true 表示本地照片（storageId 存放 MediaStore id），
         *                false 表示相机存储卡照片
         */
        fun start(context: Context, file: CameraFile, isLocal: Boolean = false) {
            context.startActivity(Intent(context, PreviewActivity::class.java).apply {
                putExtra(EXTRA_HANDLE, file.handle)
                putExtra(EXTRA_NAME, file.fileName)
                putExtra(EXTRA_SIZE, file.size)
                putExtra(EXTRA_FORMAT_CODE, file.formatCode)
                putExtra(EXTRA_STORAGE_ID, file.storageId)
                putExtra(EXTRA_IS_LOCAL, isLocal)
            })
        }
    }

    @Inject
    lateinit var transferManager: TransferManager

    private lateinit var binding: ActivityPreviewBinding
    private lateinit var file: CameraFile
    private var isLocal = false
    private var downloaded = false

    /** 已下载原图的 MediaStore Uri（修图入口②复用，避免重复下载） */
    private var savedUri: Uri? = null

    /** 「下载并修图」：下载完成后自动进入编辑器 */
    private var editAfterDownload = false
    private var isDownloading = false

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
        isLocal = intent.getBooleanExtra(EXTRA_IS_LOCAL, false)
        binding.tvFileName.text = file.fileName

        loadPreview()
        setupEditEntry()

        binding.btnBack.setOnClickListener { finish() }

        binding.btnDownload.setOnClickListener { download() }

        binding.btnExif.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("拍摄信息")
                .setMessage(buildString {
                    append("文件名: ").append(file.fileName).append('\n')
                    append("格式: ").append(file.format.name).append('\n')
                    append("大小: ").append(formatSize(file.size)).append('\n')
                    append("\n光圈 / 快门 / ISO / 焦距 / 拍摄时间等 EXIF 参数将在下载原图后完整解析。")
                })
                .setPositiveButton("确定", null)
                .show()
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
                .setItems(arrayOf("下载原图", "AI 修图", "查看拍摄信息", "画质选择（原图/压缩）")) { _, which ->
                    when (which) {
                        0 -> download()
                        1 -> startEdit()
                        2 -> binding.btnExif.performClick()
                        3 -> MaterialAlertDialogBuilder(this)
                            .setTitle("下载画质")
                            .setItems(arrayOf("原图", "压缩")) { _, w ->
                                Timber.tag(TAG).d("Quality choice: $w")
                                download()
                            }
                            .show()
                    }
                }
                .show()
        }
    }

    /**
     * AI 修图入口（PRD 5.1）：
     * - 入口① 本地照片直接进编辑器
     * - 入口② 相机照片未下载时自动下载后进编辑器（文案「下载并修图」）
     * - 仅 JPEG/PNG 可修图，RAW/视频置灰提示（PRD 5.1 入口可见性规则）
     */
    private fun setupEditEntry() {
        val editable = file.format == CameraFileFormat.JPEG
        if (!editable) {
            binding.btnEdit.alpha = 0.4f
        }
        if (isLocal) {
            binding.btnDownload.visibility = android.view.View.GONE
        } else if (!downloaded) {
            binding.tvEditLabel.text = "下载并修图"
        }
        binding.btnEdit.setOnClickListener { startEdit() }
    }

    private fun startEdit() {
        if (file.format != CameraFileFormat.JPEG) {
            MaterialAlertDialogBuilder(this)
                .setTitle("暂不支持该格式")
                .setMessage("AI 修图目前支持 JPG/PNG 图片，RAW 与视频将在后续版本支持。")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        if (isLocal) {
            // 入口①：本地照片 storageId 存的是 MediaStore id（见 TransferViewModel）
            val uri = Uri.withAppendedPath(
                MediaStore.Files.getContentUri("external"),
                file.storageId.toString()
            )
            EditActivity.start(this, uri, file.fileName)
            return
        }
        // 入口②：相机照片需先下载原图
        val uri = savedUri
        if (uri != null) {
            EditActivity.start(this, uri, file.fileName)
            return
        }
        if (isDownloading) {
            editAfterDownload = true
            return
        }
        editAfterDownload = true
        download()
    }

    /** 用缩略图先行预览；本地照片走系统 loadThumbnail */
    private fun loadPreview() {
        if (isLocal) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val uri = Uri.withAppendedPath(
                            MediaStore.Files.getContentUri("external"),
                            file.storageId.toString()
                        )
                        contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
                    }.getOrNull()
                }
                if (bmp != null) {
                    binding.ivPreview.setImageBitmap(bmp)
                    binding.progressPreview.visibility = android.view.View.GONE
                }
            }
            return
        }
        lifecycleScope.launch {
            val thumb = withContext(Dispatchers.IO) {
                runCatching { transferManager.fetchThumbnail(file.handle) }.getOrNull()
            }
            if (thumb != null) {
                val bmp = BitmapFactory.decodeByteArray(thumb, 0, thumb.size)
                if (bmp != null) {
                    binding.ivPreview.setImageBitmap(bmp)
                    binding.progressPreview.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun download() {
        if (downloaded || isDownloading) return
        isDownloading = true
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
                    isDownloading = false
                    savedUri = runCatching { Uri.parse(result.path) }.getOrNull()
                    binding.progressDownload.isIndeterminate = false
                    binding.progressDownload.progress = 100
                    // 下载完成对勾收敛动画
                    binding.iconDownload.setImageResource(R.drawable.ic_check)
                    binding.iconDownload.scaleX = 0.5f
                    binding.iconDownload.scaleY = 0.5f
                    binding.iconDownload.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
                    binding.tvDownloadLabel.text = "已完成"
                    binding.tvEditLabel.text = "修图"
                    // 「下载并修图」：下载成功后自动进入编辑器（PRD 入口②）
                    if (editAfterDownload) {
                        editAfterDownload = false
                        savedUri?.let {
                            EditActivity.start(this@PreviewActivity, it, file.fileName)
                        }
                    }
                }
                is TransferResult.Failed -> {
                    isDownloading = false
                    editAfterDownload = false
                    binding.progressDownload.visibility = android.view.View.GONE
                    binding.tvDownloadLabel.text = "重试"
                    Timber.tag(TAG).w("Download failed: ${result.reason}")
                }
                is TransferResult.Cancelled -> {
                    isDownloading = false
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

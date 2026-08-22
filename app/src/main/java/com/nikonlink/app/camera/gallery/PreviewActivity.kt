package com.nikonlink.app.camera.gallery

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ActivityPreviewBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private lateinit var binding: ActivityPreviewBinding
    private lateinit var file: CameraFile
    private var downloaded = false

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
                .setItems(arrayOf("下载原图", "查看拍摄信息", "画质选择（原图/压缩）")) { _, which ->
                    when (which) {
                        0 -> download()
                        1 -> binding.btnExif.performClick()
                        2 -> MaterialAlertDialogBuilder(this)
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

    /** 用缩略图先行预览 */
    private fun loadPreview() {
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

package com.nikonlink.app.settings

import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.nikonlink.app.R
import com.nikonlink.app.databinding.ActivitySupportBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 打赏支持页（PRD v0.2.0 主题 F / F7）
 *
 * 定位与红线：
 * - N-Link 为开源免费项目，本页只是「自愿请开发者喝杯咖啡」的入口；
 * - 文案不得出现「解锁 / 付费 / 会员 / 配额 / 权益」——打赏不附带任何回报（与竞品的
 *   配额付费体系形成差异，开源项目现阶段不做付费墙）；
 * - 纯静态页面：二维码打包进 APK（drawable-nodpi），无网络请求、不引入支付 SDK。
 */
@AndroidEntryPoint
class SupportActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Support"
        private const val GITHUB_URL = "https://github.com/Wan-1230/N-link"
        private const val QR_FILE_NAME = "n-link-donate-wechat.png"
    }

    private lateinit var binding: ActivitySupportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySupportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnZoomQr.setOnClickListener { showZoomDialog() }
        binding.btnSaveQr.setOnClickListener { saveQrImage() }
        binding.btnShareQr.setOnClickListener { shareQrImage() }
        binding.btnGithub.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            }.onFailure {
                Toast.makeText(this, "未找到可打开网页的应用", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 全屏放大二维码（方便另一台手机扫码） */
    private fun showZoomDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.ic_donate_wechat_qr)
            adjustViewBounds = true
        }
        imageView.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(
            imageView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }

    /** 二维码位图（从打包资源解码，保存/分享共用同一份） */
    private suspend fun loadQrBitmap(): Bitmap = withContext(Dispatchers.IO) {
        BitmapFactory.decodeResource(resources, R.drawable.ic_donate_wechat_qr)
    }

    /** 把二维码写到 cache/share 下的临时文件，供保存与分享复用 */
    private suspend fun materializeQrFile(): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val file = File(dir, QR_FILE_NAME)
        if (!file.isFile || file.length() == 0L) {
            loadQrBitmap().compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(file))
        }
        file
    }

    /** 保存二维码到系统相册（Pictures/N-Link），供用户用别的设备扫码 */
    private fun saveQrImage() {
        lifecycleScope.launch {
            runCatching {
                val bitmap = loadQrBitmap()
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, QR_FILE_NAME)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/N-Link"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore insert failed")
                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }.onSuccess {
                Toast.makeText(this@SupportActivity, "已保存到相册 Pictures/N-Link", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Timber.w(e, "Save QR failed")
                Toast.makeText(this@SupportActivity, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 分享二维码图（FileProvider，仅暴露 cache/share 子目录） */
    private fun shareQrImage() {
        lifecycleScope.launch {
            runCatching {
                val file = materializeQrFile()
                val uri = FileProvider.getUriForFile(
                    this@SupportActivity,
                    "${packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享收款码"))
            }.onFailure { e ->
                Timber.w(e, "Share QR failed")
                Toast.makeText(this@SupportActivity, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

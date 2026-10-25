package com.nikonlink.app.camera.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.nikonlink.app.R
import com.nikonlink.app.camera.gallery.CameraFileFormat
import com.nikonlink.app.camera.gallery.PreviewShareExporter
import com.nikonlink.app.camera.gallery.TransferManager
import com.nikonlink.app.databinding.ActivityShareBinding
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.NlFeedback
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * 独立分享页（相机相册 / 本地相册 / 大图预览共用）。
 *
 * 设计要点：
 * - 页面先打开，URI 准备放在本页生命周期内完成；不再依赖相册页的一次性 SharedFlow 事件。
 * - 相机源支持「预览副本 / 原图」两种口径；本地源直接分享原图。
 * - 相机已断线时优先使用传输历史里的本地副本，不再一律报"相机未连接"。
 * - 每个目标应用都用显式 Component 启动，并给所有 URI 建 ClipData；
 *   旧实现只给 EXTRA_STREAM + 单个 FLAG，多图/部分接收方拿不到读权限，表现为"分享没反应"。
 */
@AndroidEntryPoint
class ShareActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TOKEN = "share_token"

        /** 常见聊天/社交优先排前面；不在列表里的按名称排。 */
        private val PREFERRED_TARGETS = listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.tim",
            "com.sina.weibo",
            "com.alibaba.android.rimet",
            "com.xingin.xhs",
            "com.ss.android.ugc.aweme"
        )

        fun start(context: Context, token: String) {
            context.startActivity(
                Intent(context, ShareActivity::class.java).putExtra(EXTRA_TOKEN, token)
            )
        }
    }

    private enum class Mode { PREVIEW, ORIGINAL }

    private data class Target(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val activityName: String
    )

    private sealed interface Pending {
        data class Package(val target: Target) : Pending
        data object More : Pending
    }

    private data class PrepareOutcome(
        val uris: List<Uri>,
        val skipped: List<String>,
        val failed: List<String>,
        val failures: List<String>
    )

    @Inject
    lateinit var draftStore: ShareDraftStore

    @Inject
    lateinit var shareExporter: PreviewShareExporter

    @Inject
    lateinit var transferManager: TransferManager

    @Inject
    lateinit var settings: AppSettings

    private lateinit var binding: ActivityShareBinding
    private lateinit var draft: ShareDraft
    private var mode = Mode.PREVIEW
    private var prepareJob: Job? = null
    private var preparing = false
    private var preparedUris: List<Uri>? = null
    private var preparedMode: Mode? = null
    private var pending: Pending? = null
    private var targets: List<Target> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val token = intent.getStringExtra(EXTRA_TOKEN)
        val loaded = token?.let { draftStore.take(it) }
        if (loaded == null || loaded.items.isEmpty()) {
            finish()
            return
        }
        draft = loaded
        mode = if (draft.hasCameraItems && draft.items.any { it.format == CameraFileFormat.JPEG }) {
            Mode.PREVIEW
        } else {
            Mode.ORIGINAL
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.tvTitle.text = "分享"
        binding.tvSubtitle.text = "共 ${draft.count} 项 · " +
            if (draft.origin == ShareDraft.Origin.CAMERA) "相机相册" else "手机相册"

        setupQualityChips()
        renderThumbnails()
        renderTargets()
        updateQualityUi()
        startPrepare()

        binding.btnMore.setOnClickListener { requestShare(null) }
    }

    override fun onDestroy() {
        prepareJob?.cancel()
        super.onDestroy()
    }

    // ==================== 发送方式 ====================

    private fun setupQualityChips() {
        val canPreview = draft.hasCameraItems && draft.items.any {
            it.format == CameraFileFormat.JPEG
        }
        binding.groupQuality.visibility = if (canPreview || draft.hasCameraItems) View.VISIBLE else View.GONE
        binding.chipPreview.isEnabled = canPreview
        binding.chipPreview.alpha = if (canPreview) 1f else 0.5f
        binding.chipPreview.setOnClickListener {
            if (canPreview) switchMode(Mode.PREVIEW)
        }
        binding.chipOriginal.setOnClickListener { switchMode(Mode.ORIGINAL) }
        if (!canPreview) mode = Mode.ORIGINAL
    }

    private fun switchMode(next: Mode) {
        if (mode == next) return
        mode = next
        pending = null
        updateQualityUi()
        renderTargets()
        startPrepare()
    }

    private fun updateQualityUi() {
        val preview = mode == Mode.PREVIEW
        setChipSelected(binding.chipPreview, preview)
        setChipSelected(binding.chipOriginal, !preview)
        binding.tvQualityNote.text = when {
            preview && settings.shareKeepGps ->
                "预览副本：长边 2048 · 保留位置信息（设置中已开启「分享副本保留 GPS」）"
            preview ->
                "预览副本：长边 2048 · 去除位置信息，仅保留拍摄参数（可在设置中改为保留）"
            else ->
                "原图：完整分辨率与原始 EXIF。若相机已断线，只能分享已经下载到手机的照片。"
        }
    }

    private fun setChipSelected(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip
        )
        chip.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.text_primary)
        )
    }

    // ==================== 目标应用 ====================

    private fun renderTargets() {
        val grid = binding.targetGrid
        grid.removeAllViews()
        val mime = mimeType()
        val action = if (draft.count > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        val probe = Intent(action).setType(mime)
        val infos = runCatching {
            packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())

        targets = infos
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                Target(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = info.loadIcon(packageManager),
                    activityName = info.activityInfo.name
                )
            }
            .sortedWith(compareBy({ preferredRank(it.packageName) }, { it.label }))

        binding.tvTargetEmpty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        targets.forEach { target ->
            val tile = buildTargetTile(target)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(0, 0, 0, dp(12))
            }
            grid.addView(tile, params)
        }
    }

    private fun buildTargetTile(target: Target): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@ShareActivity, R.drawable.bg_card)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }
        val icon = ImageView(this).apply {
            setImageDrawable(target.icon)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = target.label
        }
        val label = TextView(this).apply {
            text = target.label
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(this@ShareActivity, R.color.text_secondary))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        tile.addView(icon)
        tile.addView(label)
        tile.setOnClickListener { requestShare(target) }
        return tile
    }

    private fun preferredRank(pkg: String): Int {
        val index = PREFERRED_TARGETS.indexOf(pkg)
        return if (index >= 0) index else PREFERRED_TARGETS.size
    }

    // ==================== 准备 URI ====================

    private fun startPrepare() {
        prepareJob?.cancel()
        preparedUris = null
        preparedMode = null
        preparing = true
        binding.prepareBox.visibility = View.VISIBLE
        binding.progressPrepare.max = draft.count.coerceAtLeast(1)
        binding.progressPrepare.progress = 0
        binding.tvPrepare.text = "正在准备照片…"
        binding.tvNote.visibility = View.GONE

        prepareJob = lifecycleScope.launch {
            val outcome = prepareAll()
            if (!isActive) return@launch
            preparing = false
            binding.prepareBox.visibility = View.GONE
            preparedUris = outcome.uris.takeIf { it.isNotEmpty() }
            preparedMode = mode
            renderShareNote(outcome)
            if (preparedUris != null) {
                renderThumbnails(preparedUris)
            }
            val queued = pending
            pending = null
            if (preparedUris != null && queued != null) {
                when (queued) {
                    is Pending.Package -> launchShare(queued.target)
                    is Pending.More -> launchShare(null)
                }
            }
        }
    }

    private suspend fun prepareAll(): PrepareOutcome {
        val uris = mutableListOf<Uri>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val failures = mutableListOf<String>()

        draft.items.forEachIndexed { index, item ->
            binding.tvPrepare.text = "正在准备 ${index + 1}/${draft.count} · ${item.fileName}"
            try {
                if (mode == Mode.PREVIEW) {
                    if (item.format != CameraFileFormat.JPEG) {
                        skipped.add(item.fileName)
                        return@forEachIndexed
                    }
                    val local = resolveLocalUri(item)
                    val result = if (local != null) {
                        shareExporter.exportPreviewFromUris(listOf(local to item.fileName)) { _, _ -> }
                    } else {
                        val file = item.cameraFile
                            ?: throw IllegalStateException("没有可用的原图")
                        if (!transferManager.hasActiveSession()) {
                            throw IllegalStateException("尚未下载且相机未连接")
                        }
                        shareExporter.export(listOf(file)) { _, _ -> }
                    }
                    val uri = result.uris.firstOrNull()
                        ?: throw IllegalStateException(result.failed.firstOrNull() ?: "副本生成失败")
                    uris.add(uri)
                } else {
                    val local = resolveLocalUri(item)
                    val uri = when {
                        local != null && item.cameraFile != null ->
                            shareExporter.exportOriginalUri(item.cameraFile, local.toString())
                        local != null -> local
                        item.cameraFile != null && transferManager.hasActiveSession() ->
                            shareExporter.exportOriginalUri(item.cameraFile, null)
                        else -> null
                    } ?: throw IllegalStateException("尚未下载且相机未连接")
                    uris.add(uri)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 切换发送方式/退出页面时，旧任务必须真正停下，不能把取消当成单张失败继续跑
                throw e
            } catch (e: Exception) {
                Timber.tag("ShareActivity").w(e, "Prepare failed: ${item.fileName}")
                failed.add(item.fileName)
                e.message?.takeIf { it.isNotBlank() }?.let { if (it !in failures) failures.add(it) }
            }
            binding.progressPrepare.progress = index + 1
        }
        return PrepareOutcome(uris, skipped, failed, failures)
    }

    private suspend fun resolveLocalUri(item: ShareDraft.ShareItem): Uri? {
        item.localUri?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        val cameraFile = item.cameraFile ?: return null
        val path = transferManager.completedLocalPath(cameraFile.handle) ?: return null
        return if (path.contains("://")) Uri.parse(path) else Uri.fromFile(File(path))
    }

    private fun renderShareNote(outcome: PrepareOutcome) {
        val lines = mutableListOf<String>()
        if (outcome.uris.isNotEmpty()) {
            lines.add("已准备 ${outcome.uris.size} 项")
        } else {
            lines.add("没有可分享的内容")
        }
        if (outcome.skipped.isNotEmpty()) {
            lines.add("跳过 ${outcome.skipped.size} 个 RAW/视频（预览副本仅支持 JPEG）")
        }
        if (outcome.failed.isNotEmpty()) {
            val reason = outcome.failures.firstOrNull()
            lines.add(
                buildString {
                    append("${outcome.failed.size} 项未准备成功")
                    if (reason != null) append("：$reason")
                }
            )
        }
        binding.tvNote.text = lines.joinToString("\n")
        binding.tvNote.visibility = View.VISIBLE
    }

    // ==================== 分享动作 ====================

    private fun requestShare(target: Target?) {
        val ready = preparedUris
        if (ready != null && preparedMode == mode) {
            if (target != null) launchShare(target) else launchShare(null)
            return
        }
        pending = if (target != null) Pending.Package(target) else Pending.More
        NlFeedback.show(this, "正在准备照片，完成后会自动打开分享")
        if (!preparing) startPrepare()
    }

    private fun launchShare(target: Target?) {
        val uris = preparedUris ?: return
        val multiple = uris.size > 1
        val intent = Intent(
            if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND
        ).apply {
            type = mimeType()
            if (multiple) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            } else {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("shared", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        if (target != null) {
            intent.setClassName(target.packageName, target.activityName)
        }
        val launch = if (target != null) intent else Intent.createChooser(intent, "分享").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(launch) }
            .onSuccess { finish() }
            .onFailure { e ->
                Timber.tag("ShareActivity").w(e, "Launch share target failed")
                binding.tvNote.visibility = View.VISIBLE
                binding.tvNote.text = "目标应用无法接收这些文件；可换一个应用，或用「更多分享方式」"
            }
    }

    private fun mimeType(): String = when {
        // 预览副本只产出 JPEG；RAW/视频在预览模式会被跳过，不能因为草稿里有它们就把面板类型降成 */*
        mode == Mode.PREVIEW -> "image/jpeg"
        draft.items.all { it.format == CameraFileFormat.JPEG } -> "image/jpeg"
        draft.items.all { it.format == CameraFileFormat.VIDEO } -> "video/*"
        else -> "*/*"
    }

    // ==================== 缩略图 ====================

    private fun renderThumbnails(prepared: List<Uri>? = null) {
        val row = binding.previewRow
        row.removeAllViews()
        val items = draft.items
        val shown = minOf(6, items.size)
        repeat(shown) { index ->
            val image = ShapeableImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    marginEnd = dp(6)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, dp(12).toFloat())
                    .build()
                setBackgroundColor(ContextCompat.getColor(this@ShareActivity, R.color.chip_unselected))
                setImageResource(R.drawable.ic_nav_camera)
                contentDescription = items[index].fileName
            }
            row.addView(image)
            val source = prepared?.getOrNull(index) ?: items[index].localUri?.let(Uri::parse)
            if (source != null) loadThumbnail(source, image)
        }
        if (items.size > shown) {
            val more = TextView(this).apply {
                text = "+${items.size - shown}"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@ShareActivity, R.color.text_secondary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(64))
            }
            row.addView(more)
        }
    }

    private fun loadThumbnail(uri: Uri, imageView: ImageView) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(uri) }
            if (bitmap != null && !isFinishing) imageView.setImageBitmap(bitmap)
        }
    }

    private fun decodeSampled(uri: Uri, targetPx: Int = 192): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun openStream(uri: Uri): InputStream? =
        if (uri.scheme == "file") {
            uri.path?.let { File(it).inputStream() }
        } else {
            contentResolver.openInputStream(uri)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

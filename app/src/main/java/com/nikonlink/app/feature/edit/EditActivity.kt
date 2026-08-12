package com.nikonlink.app.feature.edit

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.core.imaging.EditParams
import com.nikonlink.app.core.imaging.FilterDef
import com.nikonlink.app.core.imaging.FilterLibrary
import com.nikonlink.app.core.imaging.ai.AiEnhancer
import com.nikonlink.app.core.imaging.model.ModelCapability
import com.nikonlink.app.core.imaging.model.ModelRegistry
import com.nikonlink.app.data.local.EditPresetEntity
import com.nikonlink.app.databinding.ActivityEditBinding
import com.nikonlink.app.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AI 修图编辑器页面（PRD 5.3）
 *
 * M1 范围：画布缩放 + 基础调节五项 + 前后对比（长按/切换）+ 撤销重做 +
 * 另存导出（画质选择）+ 未保存退出拦截（PRD 4.7）。
 * AI 一键增强/滤镜在 M2/M3 接入。
 */
@AndroidEntryPoint
class EditActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context, uri: Uri, name: String) {
            context.startActivity(Intent(context, EditActivity::class.java).apply {
                putExtra(EditViewModel.EXTRA_SOURCE_URI, uri.toString())
                putExtra(EditViewModel.EXTRA_SOURCE_NAME, name)
            })
        }
    }

    /** 基础调节参数定义（M1 五项 + M4 清晰度/色彩增强，PRD 4.3） */
    private data class ParamDef(
        val label: String,
        val get: (EditParams) -> Int,
        val set: (EditParams, Int) -> EditParams,
        /** 滑杆下限：双向参数 -100，单向参数（清晰度/色彩增强）0 */
        val min: Int = EditParams.RANGE_MIN
    )

    private val paramDefs = listOf(
        ParamDef("亮度", { it.brightness }, { p, v -> p.copy(brightness = v) }),
        ParamDef("对比度", { it.contrast }, { p, v -> p.copy(contrast = v) }),
        ParamDef("色温", { it.temperature }, { p, v -> p.copy(temperature = v) }),
        ParamDef("高光", { it.highlights }, { p, v -> p.copy(highlights = v) }),
        ParamDef("阴影", { it.shadows }, { p, v -> p.copy(shadows = v) }),
        ParamDef("清晰度", { it.clarity }, { p, v -> p.copy(clarity = v) }, min = 0),
        ParamDef("色彩增强", { it.vibrance }, { p, v -> p.copy(vibrance = v) }, min = 0),
        ParamDef("降噪", { it.denoise }, { p, v -> p.copy(denoise = v) }, min = 0)
    )

    private val viewModel: EditViewModel by viewModels()
    private lateinit var binding: ActivityEditBinding

    /** 模型注册中心：细节重建等模型能力的可用性查询（PRD 8.5 降级链） */
    @Inject
    lateinit var modelRegistry: ModelRegistry

    /** 底部工具分类（PRD 5.3）：一键/场景/细节/滤镜 */
    private enum class EditTab { ONE_TAP, SCENE, DETAIL, FILTER }

    private val chipViews = mutableListOf<TextView>()
    /** 滤镜缩略图条目: id → (图标, 名称标签) */
    private val filterItemViews = mutableMapOf<String, Pair<ImageView, TextView>>()
    private var selectedParam = 0
    private var currentTab = EditTab.ONE_TAP
    private var longPressCompare = false
    /** 分屏对比模式（PRD 4.6: 左右分屏，分割线可拖动） */
    private var splitMode = false
    private var splitDividerX = 0f
    private var selectedFormat = 0
    /** 保存方式：0=另存为新图（默认，PRD 4.7），1=覆盖原图 */
    private var saveMode = 0
    private var finishAfterSave = true

    /** SAF 覆盖授权回调（PRD 4.7: 拒绝时降级另存） */
    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.completeSafOverwrite(result.resultCode == RESULT_OK) {
            handleExportResult(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvFileName.text = viewModel.sourceName
        setupTabs()
        setupEnhancePanel()
        setupScenePanel()
        setupFilterPanel()
        setupPresetRow()
        setupChips()
        setupToolbar()
        setupSlider()
        setupCanvas()
        setupBackIntercept()
        observe()
    }

    private fun setupTabs() {
        binding.tvTabOneTap.setOnClickListener { selectTab(EditTab.ONE_TAP) }
        binding.tvTabScene.setOnClickListener { selectTab(EditTab.SCENE) }
        binding.tvTabDetail.setOnClickListener { selectTab(EditTab.DETAIL) }
        binding.tvTabFilter.setOnClickListener { selectTab(EditTab.FILTER) }
        binding.tvTabOneTap.pressEffect()
        binding.tvTabScene.pressEffect()
        binding.tvTabDetail.pressEffect()
        binding.tvTabFilter.pressEffect()
        selectTab(currentTab)
    }

    private fun selectTab(tab: EditTab) {
        currentTab = tab
        binding.enhancePanel.visibility = if (tab == EditTab.ONE_TAP) View.VISIBLE else View.GONE
        binding.scenePanel.visibility = if (tab == EditTab.SCENE) View.VISIBLE else View.GONE
        binding.detailPanel.visibility = if (tab == EditTab.DETAIL) View.VISIBLE else View.GONE
        binding.filterPanel.visibility = if (tab == EditTab.FILTER) View.VISIBLE else View.GONE
        renderTabChip(binding.tvTabOneTap, tab == EditTab.ONE_TAP)
        renderTabChip(binding.tvTabScene, tab == EditTab.SCENE)
        renderTabChip(binding.tvTabDetail, tab == EditTab.DETAIL)
        renderTabChip(binding.tvTabFilter, tab == EditTab.FILTER)
    }

    private fun renderTabChip(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
        view.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.text_primary)
        )
    }

    /** 一键增强面板（PRD 4.1 + M2 处理中/失败态反馈） */
    private fun setupEnhancePanel() {
        binding.btnEnhance.pressEffect()
        binding.btnEnhance.setOnClickListener { viewModel.applyAutoEnhance() }
        binding.btnRepair.pressEffect()
        binding.btnRepair.setOnClickListener { viewModel.applyOneTapRepair() }
        binding.seekEnhance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.tvEnhanceStrength.text = progress.toString()
                viewModel.setEnhanceStrength(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun renderEnhanceState(state: EnhanceUiState) {
        val analyzing = state is EnhanceUiState.Analyzing
        when (state) {
            is EnhanceUiState.Idle -> {
                binding.progressEnhance.visibility = View.GONE
                binding.layoutEnhanceStrength.visibility = View.GONE
                binding.btnEnhance.isEnabled = true
                binding.btnRepair.isEnabled = true
                binding.btnEnhance.text = "AI 自动增强"
                binding.tvEnhanceStatus.text = "一键优化曝光 / 白平衡 / 对比度"
            }
            is EnhanceUiState.Analyzing -> {
                binding.progressEnhance.visibility = View.VISIBLE
                binding.btnEnhance.isEnabled = false
                binding.btnRepair.isEnabled = false
                binding.btnEnhance.text = "分析中…"
                binding.tvEnhanceStatus.text = "正在分析照片…"
            }
            is EnhanceUiState.Ready -> {
                binding.progressEnhance.visibility = View.GONE
                binding.layoutEnhanceStrength.visibility = View.VISIBLE
                binding.btnEnhance.isEnabled = true
                binding.btnRepair.isEnabled = true
                binding.btnEnhance.text = "重新分析"
                binding.tvEnhanceStatus.text =
                    if (state.suggestion.kind == AiEnhancer.EnhanceKind.REPAIR) {
                        "已修复：${state.suggestion.issues.joinToString("、")} · 长按画布对比原图"
                    } else {
                        "已增强 · 长按画布对比原图"
                    }
            }
            is EnhanceUiState.Failed -> {
                binding.progressEnhance.visibility = View.GONE
                binding.layoutEnhanceStrength.visibility = View.GONE
                binding.btnEnhance.isEnabled = true
                binding.btnRepair.isEnabled = true
                binding.btnEnhance.text = "重试"
                binding.tvEnhanceStatus.text = state.reason
            }
        }
        // 场景面板同步（与一键共用叠加层，PRD 4.2）
        binding.progressScene.visibility = if (analyzing) View.VISIBLE else View.GONE
        binding.btnPortrait.isEnabled = !analyzing
        binding.btnLandscape.isEnabled = !analyzing
        when (state) {
            is EnhanceUiState.Ready -> {
                val isScene = state.suggestion.kind == AiEnhancer.EnhanceKind.PORTRAIT ||
                    state.suggestion.kind == AiEnhancer.EnhanceKind.LANDSCAPE
                binding.layoutSceneStrength.visibility =
                    if (isScene) View.VISIBLE else View.GONE
                binding.tvSceneStatus.text = if (isScene) {
                    val label = if (state.suggestion.kind == AiEnhancer.EnhanceKind.PORTRAIT) "人像" else "风光"
                    val note = state.suggestion.issues.firstOrNull()?.let { " · $it" }.orEmpty()
                    "${label}优化已应用$note"
                } else {
                    "人像：肤色/曝光优化 · 风光：通透度/暗部细节"
                }
            }
            is EnhanceUiState.Analyzing -> {
                binding.layoutSceneStrength.visibility = View.GONE
                binding.tvSceneStatus.text = "正在分析场景…"
            }
            else -> {
                binding.layoutSceneStrength.visibility = View.GONE
                binding.tvSceneStatus.text = "人像：肤色/曝光优化 · 风光：通透度/暗部细节"
            }
        }
    }

    /** 滤镜面板（PRD 4.5）：缩略图横向列表 + 强度滑杆 */
    private fun setupFilterPanel() {
        FilterLibrary.ALL.forEach { def ->
            val thumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                    marginEnd = dp(4)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(
                    ContextCompat.getColor(this@EditActivity, R.color.surface_variant)
                )
            }
            val label = TextView(this).apply {
                text = def.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    dp(64), android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(10)
                layoutParams = lp
                addView(thumb)
                addView(label)
                setOnClickListener { viewModel.selectFilter(def.id) }
                pressEffect()
            }
            filterItemViews[def.id] = thumb to label
            binding.filterRow.addView(item)
        }

        binding.seekFilter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.tvFilterStrength.text = progress.toString()
                viewModel.setFilterStrength(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    /** 滤镜选中态渲染：黑白规范下用字重/颜色区分，不用彩色描边 */
    private fun renderFilterSelection(selected: FilterDef) {
        filterItemViews.forEach { (id, pair) ->
            val isSelected = id == selected.id
            pair.second.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isSelected) R.color.text_primary else R.color.text_tertiary
                )
            )
            pair.second.typeface = if (isSelected) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            pair.first.alpha = if (isSelected) 1f else 0.8f
        }
        binding.layoutFilterStrength.visibility =
            if (selected.id == FilterLibrary.ORIGINAL.id) View.GONE else View.VISIBLE
    }

    /** 场景面板（PRD 4.2 人像/风光优化） */
    private fun setupScenePanel() {
        binding.btnPortrait.pressEffect()
        binding.btnPortrait.setOnClickListener {
            viewModel.applyScene(AiEnhancer.EnhanceKind.PORTRAIT)
        }
        binding.btnLandscape.pressEffect()
        binding.btnLandscape.setOnClickListener {
            viewModel.applyScene(AiEnhancer.EnhanceKind.LANDSCAPE)
        }
        binding.seekScene.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.tvSceneStrength.text = progress.toString()
                viewModel.setEnhanceStrength(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    /** 自定义预设行（PRD 4.5 P1）：首项固定「+ 存预设」 */
    private fun setupPresetRow() {
        lifecycleScope.launch {
            viewModel.presets.collect { renderPresetChips(it) }
        }
    }

    private fun renderPresetChips(presets: List<EditPresetEntity>) {
        binding.presetRow.removeAllViews()

        // 「+ 存预设」入口
        binding.presetRow.addView(newPresetChip("+ 存预设").apply {
            setOnClickListener { showSavePresetDialog() }
        })

        presets.forEach { preset ->
            val chip = newPresetChip(preset.name)
            chip.setOnClickListener { viewModel.applyPreset(preset) }
            chip.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("删除预设")
                    .setMessage("确定删除预设「${preset.name}」？")
                    .setPositiveButton("删除") { _, _ -> viewModel.deletePreset(preset.id) }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            binding.presetRow.addView(chip)
        }
    }

    private fun newPresetChip(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(ContextCompat.getColor(this@EditActivity, R.color.text_primary))
            val lp = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
            pressEffect()
        }
    }

    private fun showSavePresetDialog() {
        val input = EditText(this).apply {
            hint = "预设名称"
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("保存当前效果为预设")
            .setMessage("将保存当前调节参数与滤镜组合。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                viewModel.saveCurrentAsPreset(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupChips() {
        paramDefs.forEachIndexed { index, def ->
            val chip = TextView(this).apply {
                text = def.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                setPadding(dp(16), dp(7), dp(16), dp(7))
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(8)
                layoutParams = lp
                setOnClickListener {
                    selectedParam = index
                    renderChips()
                    syncSliderToSelected(viewModel.params.value)
                }
                pressEffect()
            }
            chipViews.add(chip)
            binding.chipRow.addView(chip)
        }

        // 细节重建入口（PRD 4.3 P1）：Real-ESRGAN 模型包就绪后启用；未发布时降级提示（PRD 8.5）
        val restoreChip = TextView(this).apply {
            text = "细节重建"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            setPadding(dp(16), dp(7), dp(16), dp(7))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(8)
            layoutParams = lp
            setTextColor(ContextCompat.getColor(this@EditActivity, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_chip)
            setOnClickListener { onDetailRestoreClick() }
            pressEffect()
        }
        binding.chipRow.addView(restoreChip)
        renderChips()
    }

    /** 细节重建（PRD 4.3 P1）：模型插槽已就绪，推理接线待模型发布后启用 */
    private fun onDetailRestoreClick() {
        if (modelRegistry.isAvailable(ModelCapability.DETAIL_RESTORE)) {
            // TODO(模型发布): Real-ESRGAN 分块推理 + 强度滑杆（PRD 8.4/9.1 精细渲染流程）
            Toast.makeText(this, "细节重建组件已就绪，推理流程将在模型发布版本启用", Toast.LENGTH_SHORT).show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle("细节重建")
                .setMessage("高清修图组件（Real-ESRGAN，约 17MB）尚未发布。发布后首次使用时按需下载，可用于软焦/低分辨率照片的 AI 细节重建。")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun renderChips() {
        chipViews.forEachIndexed { index, chip ->
            val selected = index == selectedParam
            chip.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip)
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.on_primary else R.color.text_primary
                )
            )
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnRedo.setOnClickListener { viewModel.redo() }

        // 对比切换：点击触发分屏对比（PRD 4.6），长按对比在画布上
        binding.btnCompare.setOnClickListener {
            if (splitMode) exitSplit() else enterSplit()
        }

        binding.btnSave.setOnClickListener { showSaveDialog() }
        binding.btnReset.setOnClickListener {
            viewModel.resetAll()
            Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
        }
        binding.btnBack.pressEffect()
        binding.btnUndo.pressEffect()
        binding.btnRedo.pressEffect()
        binding.btnCompare.pressEffect()
        binding.btnSave.pressEffect()
        binding.btnReset.pressEffect()
    }

    private fun setupSlider() {
        binding.seekParam.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val def = paramDefs[selectedParam]
                val value = (progress + def.min).coerceIn(def.min, EditParams.RANGE_MAX)
                binding.tvParamValue.text = formatValue(value)
                viewModel.onParamChanging { def.set(it, value) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                viewModel.onParamCommitted()
            }
        })
        syncSliderToSelected(viewModel.params.value)
    }

    private fun setupCanvas() {
        binding.ivCanvas.onLongPressStart = {
            longPressCompare = true
            updateCanvas()
        }
        binding.ivCanvas.onLongPressEnd = {
            longPressCompare = false
            updateCanvas()
        }
        // 分屏对比：拖动分割线调整原图/效果图分界（PRD 4.6）
        @SuppressLint("ClickableViewAccessibility")
        val splitTouch = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE -> {
                    splitDividerX = event.x
                    applySplitClip()
                }
            }
            true
        }
        binding.layoutCompareSplit.setOnTouchListener(splitTouch)
    }

    private fun enterSplit() {
        val original = viewModel.originalBitmap.value ?: return
        splitMode = true
        binding.ivSplitOriginal.setImageBitmap(original)
        // PRD 4.6 缩放同步：分屏原图复用画布当前矩阵，与效果图保持同一缩放/平移位置
        binding.ivSplitOriginal.scaleType = ImageView.ScaleType.MATRIX
        binding.ivSplitOriginal.imageMatrix = binding.ivCanvas.currentMatrix()
        binding.layoutCompareSplit.visibility = View.VISIBLE
        if (splitDividerX == 0f) {
            splitDividerX = binding.layoutCompareSplit.width / 2f
        }
        applySplitClip()
        binding.tvCompareBadge.text = "分屏对比 · 左原图"
        binding.tvCompareBadge.visibility = View.VISIBLE
        binding.btnCompare.alpha = 0.5f
    }

    private fun exitSplit() {
        splitMode = false
        binding.layoutCompareSplit.visibility = View.GONE
        binding.tvCompareBadge.text = "原图"
        binding.tvCompareBadge.visibility = View.GONE
        binding.btnCompare.alpha = 1f
        updateCanvas()
    }

    private fun applySplitClip() {
        val w = binding.layoutCompareSplit.width
        val h = binding.layoutCompareSplit.height
        if (w == 0 || h == 0) return
        val x = splitDividerX.coerceIn(0f, w.toFloat()).toInt()
        binding.ivSplitOriginal.clipBounds = android.graphics.Rect(0, 0, x, h)
        binding.splitDivider.translationX = x.toFloat()
    }

    /** PRD 4.7 未保存退出拦截：保存 / 不保存退出 / 取消 */
    private fun setupBackIntercept() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!viewModel.hasUnsavedChanges) {
                    finish()
                    return
                }
                MaterialAlertDialogBuilder(this@EditActivity)
                    .setTitle("未保存的修改")
                    .setMessage("当前修图参数尚未保存，是否保存后退出？")
                    .setPositiveButton("保存") { _, _ -> showSaveDialog(finishOnSuccess = true) }
                    .setNegativeButton("不保存退出") { _, _ -> finish() }
                    .setNeutralButton("取消", null)
                    .show()
            }
        })
    }

    /** 保存对话框第一步：选择保存方式（PRD 5.4） */
    private fun showSaveDialog(finishOnSuccess: Boolean = true) {
        finishAfterSave = finishOnSuccess
        MaterialAlertDialogBuilder(this)
            .setTitle("保存修图结果")
            .setMessage("另存不会触碰原图；覆盖会先自动备份原图至 NikonLink/.backup 隐藏目录。")
            .setSingleChoiceItems(
                arrayOf("另存为新图（推荐）", "覆盖原图"),
                saveMode
            ) { _, which -> saveMode = which }
            .setPositiveButton("下一步") { _, _ -> showFormatDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存对话框第二步：选择导出画质（PRD 4.7） */
    private fun showFormatDialog() {
        val formats = ExportFormat.values()
        MaterialAlertDialogBuilder(this)
            .setTitle("导出画质")
            .setSingleChoiceItems(
                formats.map { it.label }.toTypedArray(),
                selectedFormat
            ) { _, which -> selectedFormat = which }
            .setPositiveButton("保存") { _, _ ->
                if (saveMode == 1) confirmOverwrite(formats[selectedFormat])
                else startExport(formats[selectedFormat])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 覆盖保存二次确认（PRD 4.7: 强制确认，默认项永远是另存） */
    private fun confirmOverwrite(format: ExportFormat) {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认覆盖")
            .setMessage("原图将被修图结果替换（原图已自动备份至隐藏目录）。确定覆盖？")
            .setPositiveButton("覆盖") { _, _ -> startOverwrite(format) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startExport(format: ExportFormat) {
        binding.overlayExport.visibility = View.VISIBLE
        viewModel.saveToGallery(format) { handleExportResult(it) }
    }

    private fun startOverwrite(format: ExportFormat) {
        binding.overlayExport.visibility = View.VISIBLE
        viewModel.saveOverwrite(
            format,
            onNeedSaf = { sender ->
                safLauncher.launch(IntentSenderRequest.Builder(sender).build())
            },
            onResult = { handleExportResult(it) }
        )
    }

    private fun handleExportResult(result: Result<Uri>) {
        binding.overlayExport.visibility = View.GONE
        result.onSuccess {
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            if (finishAfterSave) finish()
        }.onFailure { e ->
            Toast.makeText(this, e.message ?: "保存失败", Toast.LENGTH_LONG).show()
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            viewModel.previewBitmap.collect { updateCanvas() }
        }
        lifecycleScope.launch {
            viewModel.originalBitmap.collect { updateCanvas() }
        }
        lifecycleScope.launch {
            viewModel.params.collect { params ->
                syncSliderToSelected(params)
            }
        }
        lifecycleScope.launch {
            viewModel.canUndo.collect { can ->
                binding.btnUndo.isEnabled = can
                binding.btnUndo.alpha = if (can) 1f else 0.3f
            }
        }
        lifecycleScope.launch {
            viewModel.canRedo.collect { can ->
                binding.btnRedo.isEnabled = can
                binding.btnRedo.alpha = if (can) 1f else 0.3f
            }
        }
        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressLoading.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.isExporting.collect { exporting ->
                binding.overlayExport.visibility = if (exporting) View.VISIBLE else View.GONE
            }
        }
        lifecycleScope.launch {
            viewModel.message.collect { msg ->
                if (msg.isNotBlank()) {
                    Toast.makeText(this@EditActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
        lifecycleScope.launch {
            viewModel.enhanceState.collect { renderEnhanceState(it) }
        }
        lifecycleScope.launch {
            viewModel.enhanceStrength.collect { strength ->
                binding.seekEnhance.progress = strength
                binding.tvEnhanceStrength.text = strength.toString()
                binding.seekScene.progress = strength
                binding.tvSceneStrength.text = strength.toString()
            }
        }
        lifecycleScope.launch {
            viewModel.filterThumbnails.collect { thumbs ->
                thumbs.forEach { (id, bitmap) ->
                    filterItemViews[id]?.first?.setImageBitmap(bitmap)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.selectedFilter.collect { renderFilterSelection(it) }
        }
        lifecycleScope.launch {
            viewModel.filterStrength.collect { strength ->
                binding.seekFilter.progress = strength
                binding.tvFilterStrength.text = strength.toString()
            }
        }
    }

    /** 画布内容：对比态显示原图，否则显示参数渲染结果 */
    private fun updateCanvas() {
        val bitmap = if (longPressCompare) {
            viewModel.originalBitmap.value
        } else {
            viewModel.previewBitmap.value
        }
        if (bitmap != null && !bitmap.isRecycled) {
            binding.ivCanvas.setImageBitmap(bitmap)
        }
        binding.tvCompareBadge.visibility = if (longPressCompare) View.VISIBLE else View.GONE
        // 对比按钮高亮提示当前处于常显对比态
        binding.btnCompare.alpha = if (splitMode) 0.5f else 1f
    }

    private fun syncSliderToSelected(params: EditParams) {
        val def = paramDefs[selectedParam]
        val value = def.get(params)
        binding.tvParamLabel.text = def.label
        binding.tvParamValue.text = formatValue(value)
        binding.seekParam.max = EditParams.RANGE_MAX - def.min
        binding.seekParam.progress = value - def.min
    }

    private fun formatValue(value: Int): String = if (value > 0) "+$value" else "$value"

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}

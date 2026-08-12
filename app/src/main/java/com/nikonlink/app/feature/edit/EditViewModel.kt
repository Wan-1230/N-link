package com.nikonlink.app.feature.edit

import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.app.core.imaging.EditEngine
import com.nikonlink.app.core.imaging.EditHistory
import com.nikonlink.app.core.imaging.EditMath
import com.nikonlink.app.core.imaging.EditParams
import com.nikonlink.app.core.imaging.FilterDef
import com.nikonlink.app.core.imaging.FilterLibrary
import com.nikonlink.app.core.imaging.ImageDecoders
import com.nikonlink.app.core.imaging.ai.AiEnhancer
import com.nikonlink.app.core.imaging.ai.EnhanceMath
import com.nikonlink.app.data.local.EditPresetEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * AI 修图编辑器 ViewModel（PRD 4.8 编辑状态管理）
 *
 * - 参数化编辑：所有效果以 EditParams 描述，预览层实时合成
 * - 撤销/重做：EditHistory 参数快照栈（≥20 步）
 * - 导出：全分辨率一次性渲染（PRD 4.8），绑定 viewModelScope 支持页面销毁取消
 *
 * 日志来源: EditVM 标签输出参数变更/导出结果；渲染耗时由 EditEngine 输出。
 */
@HiltViewModel
class EditViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val aiEnhancer: AiEnhancer,
    private val presetManager: EditPresetManager
) : ViewModel() {

    companion object {
        private const val TAG = "EditVM"
        /** 预览层长边上限（PRD 9.1: ≤1080p 保证调节延迟 <100ms） */
        const val PREVIEW_MAX_EDGE = 1080
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_SOURCE_NAME = "source_name"
    }

    private val sourceUri: Uri? = savedStateHandle.get<String>(EXTRA_SOURCE_URI)?.let(Uri::parse)
    val sourceName: String = savedStateHandle.get<String>(EXTRA_SOURCE_NAME) ?: ""

    private val history = EditHistory()

    /** 原图降采样位图（对比用，不可被参数管线污染） */
    private var sourceBitmap: Bitmap? = null

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap: StateFlow<Bitmap?> = _originalBitmap.asStateFlow()

    private val _params = MutableStateFlow(EditParams())
    val params: StateFlow<EditParams> = _params.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** AI 自动增强状态机（PRD 4.1 + M2 处理中/失败态反馈） */
    private val _enhanceState = MutableStateFlow<EnhanceUiState>(EnhanceUiState.Idle)
    val enhanceState: StateFlow<EnhanceUiState> = _enhanceState.asStateFlow()

    /** 增强强度 0-100（PRD 4.1: 0=原图，100=完整增强） */
    private val _enhanceStrength = MutableStateFlow(100)
    val enhanceStrength: StateFlow<Int> = _enhanceStrength.asStateFlow()

    /** 当前滤镜（PRD 4.5） */
    private val _selectedFilter = MutableStateFlow(FilterLibrary.ORIGINAL)
    val selectedFilter: StateFlow<FilterDef> = _selectedFilter.asStateFlow()

    /** 滤镜强度 0-100（PRD 4.5 滤镜强度） */
    private val _filterStrength = MutableStateFlow(100)
    val filterStrength: StateFlow<Int> = _filterStrength.asStateFlow()

    /** 滤镜缩略图: id → 基于当前照片实时渲染（PRD 4.5 全部缩略图 <1.5s） */
    private val _filterThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val filterThumbnails: StateFlow<Map<String, Bitmap>> = _filterThumbnails.asStateFlow()

    /** 原图真实尺寸（展示用） */
    private val _originalSize = MutableStateFlow<android.util.Size?>(null)
    val originalSize: StateFlow<android.util.Size?> = _originalSize.asStateFlow()

    private var renderJob: Job? = null

    val hasUnsavedChanges: Boolean
        get() = !_params.value.isDefault ||
            (_enhanceState.value is EnhanceUiState.Ready && _enhanceStrength.value > 0) ||
            (_selectedFilter.value.id != FilterLibrary.ORIGINAL.id && _filterStrength.value > 0)

    init {
        if (sourceUri == null) {
            _isLoading.value = false
            _message.value = "图片加载失败：缺少来源"
            Timber.tag(TAG).w("EditViewModel init without source uri")
        } else {
            loadSource(sourceUri)
        }
    }

    private fun loadSource(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                _originalSize.value = ImageDecoders.querySize(context.contentResolver, uri)
                ImageDecoders.decodeDownsampled(context.contentResolver, uri, PREVIEW_MAX_EDGE)
            }
            if (bitmap == null) {
                _isLoading.value = false
                _message.value = "图片解码失败，请返回重试"
                Timber.tag(TAG).e("Failed to decode source: $uri")
                return@launch
            }
            sourceBitmap = bitmap
            _originalBitmap.value = bitmap
            _previewBitmap.value = bitmap
            _isLoading.value = false
            generateFilterThumbnails(bitmap)
            Timber.tag(TAG).i(
                "Source loaded: ${bitmap.width}x${bitmap.height} (original ${_originalSize.value})"
            )
        }
    }

    /**
     * 滑杆拖动中的连续更新：只重渲染不入历史（保证流畅）。
     */
    fun onParamChanging(transform: (EditParams) -> EditParams) {
        val next = transform(_params.value)
        if (next == _params.value) return
        _params.value = next
        renderMerged()
    }

    /**
     * 滑杆松手：提交一次历史快照（可撤销）。
     */
    fun onParamCommitted() {
        history.push(_params.value)
        syncHistoryState()
        Timber.tag(TAG).d("Committed: ${_params.value}")
    }

    fun undo() {
        val prev = history.undo() ?: return
        _params.value = prev
        renderMerged()
        syncHistoryState()
    }

    fun redo() {
        val next = history.redo() ?: return
        _params.value = next
        renderMerged()
        syncHistoryState()
    }

    fun resetAll() {
        val reset = history.reset()
        _params.value = reset
        renderMerged()
        syncHistoryState()
    }

    /**
     * AI 自动增强（PRD 4.1）：分析原图预览层 → 给出参数建议 → 叠加到渲染管线。
     * 模型未就绪时自动走规则引擎降级链（AiEnhancer 内部处理）。
     */
    fun applyAutoEnhance() {
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _message.value = "图片尚未就绪，请稍后重试"
            return
        }
        if (_enhanceState.value is EnhanceUiState.Analyzing) return
        _enhanceState.value = EnhanceUiState.Analyzing
        viewModelScope.launch {
            val result = runCatching { aiEnhancer.analyze(bitmap) }
            result.onSuccess { suggestion ->
                _enhanceStrength.value = 100
                _enhanceState.value = EnhanceUiState.Ready(suggestion)
                renderMerged()
                Timber.tag(TAG).i("Enhance ready, source=${suggestion.source}")
            }.onFailure { e ->
                _enhanceState.value = EnhanceUiState.Failed(e.message ?: "增强失败")
                _message.value = "增强失败：${e.message}"
                Timber.tag(TAG).w(e, "Enhance failed")
            }
        }
    }

    /** 增强强度滑杆（0=原图，100=完整增强） */
    fun setEnhanceStrength(value: Int) {
        val next = value.coerceIn(0, 100)
        if (next == _enhanceStrength.value) return
        _enhanceStrength.value = next
        renderMerged()
    }

    /**
     * 一键修复（PRD 4.4）：与自动增强共用叠加层（UI 为两个独立按钮，
     * 引擎为不同策略集），后点击者覆盖前者。
     */
    fun applyOneTapRepair() {
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _message.value = "图片尚未就绪，请稍后重试"
            return
        }
        if (_enhanceState.value is EnhanceUiState.Analyzing) return
        _enhanceState.value = EnhanceUiState.Analyzing
        viewModelScope.launch {
            val result = runCatching { aiEnhancer.analyzeRepair(bitmap) }
            result.onSuccess { suggestion ->
                _enhanceStrength.value = 100
                _enhanceState.value = EnhanceUiState.Ready(suggestion)
                renderMerged()
                Timber.tag(TAG).i("Repair ready, issues=${suggestion.issues}")
            }.onFailure { e ->
                _enhanceState.value = EnhanceUiState.Failed(e.message ?: "修复失败")
                _message.value = "修复失败：${e.message}"
                Timber.tag(TAG).w(e, "Repair failed")
            }
        }
    }

    /** 选择滤镜（PRD 4.5） */
    fun selectFilter(id: String) {
        val def = FilterLibrary.byId(id) ?: return
        if (def == _selectedFilter.value) return
        _selectedFilter.value = def
        renderMerged()
        Timber.tag(TAG).d("Filter selected: $id")
    }

    /** 场景优化（PRD 4.2 人像/风光）：与一键增强共用叠加层，后点击者覆盖 */
    fun applyScene(kind: AiEnhancer.EnhanceKind) {
        val bitmap = _originalBitmap.value
        if (bitmap == null) {
            _message.value = "图片尚未就绪，请稍后重试"
            return
        }
        if (_enhanceState.value is EnhanceUiState.Analyzing) return
        _enhanceState.value = EnhanceUiState.Analyzing
        viewModelScope.launch {
            val result = runCatching { aiEnhancer.analyzeScene(bitmap, kind) }
            result.onSuccess { suggestion ->
                _enhanceStrength.value = 100
                _enhanceState.value = EnhanceUiState.Ready(suggestion)
                renderMerged()
                Timber.tag(TAG).i("Scene ready: $kind")
            }.onFailure { e ->
                _enhanceState.value = EnhanceUiState.Failed(e.message ?: "场景优化失败")
                _message.value = "场景优化失败：${e.message}"
                Timber.tag(TAG).w(e, "Scene($kind) failed")
            }
        }
    }

    // ---- 自定义预设（PRD 4.5 P1: 滤镜 + 细节参数组合，Room 本地存储） ----

    /** 预设列表（UI 层直接 collect） */
    val presets = presetManager.presets

    /** 将当前参数 + 滤镜存为预设 */
    fun saveCurrentAsPreset(name: String) {
        val snapshot = _params.value
        val filterId = _selectedFilter.value.id
        val filterStrength = _filterStrength.value
        viewModelScope.launch {
            presetManager.save(name, snapshot, filterId, filterStrength)
            _message.value = "预设已保存"
        }
    }

    /** 应用预设：参数入历史栈（可撤销），滤镜同步切换 */
    fun applyPreset(preset: EditPresetEntity) {
        val params = EditPresetManager.parseParams(preset.paramsJson)
        if (params == null) {
            _message.value = "预设已损坏，请删除后重建"
            return
        }
        history.push(params)
        _params.value = params
        _selectedFilter.value = FilterLibrary.byId(preset.filterId) ?: FilterLibrary.ORIGINAL
        _filterStrength.value = preset.filterStrength.coerceIn(0, 100)
        syncHistoryState()
        renderMerged()
        _message.value = "已应用预设：${preset.name}"
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch { presetManager.delete(id) }
    }

    /** 滤镜强度滑杆（0=原图，100=完整滤镜） */
    fun setFilterStrength(value: Int) {
        val next = value.coerceIn(0, 100)
        if (next == _filterStrength.value) return
        _filterStrength.value = next
        renderMerged()
    }

    /**
     * 滤镜缩略图生成（PRD 4.5: 降采样图实时渲染，10 款总耗时 <1.5s）。
     * 基于预览层源图缩小到长边 384px，逐款渲染后一次性发布。
     */
    private fun generateFilterThumbnails(source: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            val thumbEdge = 384
            val longEdge = maxOf(source.width, source.height)
            val base = if (longEdge <= thumbEdge) {
                source
            } else {
                val scale = thumbEdge.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
            val result = HashMap<String, Bitmap>(FilterLibrary.ALL.size)
            FilterLibrary.ALL.forEach { def ->
                if (!isActive) return@launch
                val thumb = base.copy(Bitmap.Config.ARGB_8888, true)
                EditEngine.applyFilterInPlace(thumb, def, 100)
                result[def.id] = thumb
            }
            _filterThumbnails.value = result
            Timber.tag(TAG).i(
                "Filter thumbnails x${result.size} in ${System.currentTimeMillis() - start}ms"
            )
        }
    }

    /**
     * 合并参数：手动调节 + 按强度缩放后的增强建议。
     * 撤销/重做仅跟踪手动参数；增强作为独立叠加层（PRD 4.8 历史栈说明）。
     */
    private fun mergedParams(): EditParams {
        val base = _params.value
        val ready = _enhanceState.value as? EnhanceUiState.Ready ?: return base
        val scaled = EnhanceMath.scale(ready.suggestion.params, _enhanceStrength.value)
        return EditParams(
            brightness = EditParams.clamp(base.brightness + scaled.brightness),
            contrast = EditParams.clamp(base.contrast + scaled.contrast),
            temperature = EditParams.clamp(base.temperature + scaled.temperature),
            highlights = EditParams.clamp(base.highlights + scaled.highlights),
            shadows = EditParams.clamp(base.shadows + scaled.shadows),
            // 清晰度/色彩增强/降噪为手动项，增强建议不产生（PRD 4.3）
            clarity = EditParams.clampPositive(base.clarity),
            vibrance = EditParams.clampPositive(base.vibrance),
            denoise = EditParams.clampPositive(base.denoise)
        )
    }

    private fun syncHistoryState() {
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
    }

    private fun renderMerged() {
        renderPreview(mergedParams())
    }

    private fun renderPreview(params: EditParams) {
        val source = sourceBitmap ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            var output = EditEngine.renderPreview(source, params)
            // 滤镜叠加在参数调节之后（管线顺序: 增强/调节 → 滤镜）
            val filter = _selectedFilter.value
            val strength = _filterStrength.value
            if (strength > 0 && filter.id != FilterLibrary.ORIGINAL.id) {
                if (output === source) output = source.copy(Bitmap.Config.ARGB_8888, true)
                EditEngine.applyFilterInPlace(output, filter, strength)
            }
            if (isActive) {
                val previous = _previewBitmap.value
                _previewBitmap.value = output
                // 回收上一帧（不回收原图与当前帧）
                if (previous != null && previous !== source && previous !== output && !previous.isRecycled) {
                    previous.recycle()
                }
            }
        }
    }

    /**
     * 保存导出（PRD 4.7 另存为新图 + EXIF 保留）
     * @param format 导出画质
     * @param onResult 成功返回新文件 Uri；失败返回中文化原因
     */
    fun saveToGallery(
        format: ExportFormat,
        onResult: (Result<Uri>) -> Unit
    ) {
        val uri = sourceUri
        if (uri == null) {
            onResult(Result.failure(IllegalStateException("图片加载失败：缺少来源")))
            return
        }
        if (_isExporting.value) return
        _isExporting.value = true
        val paramsSnapshot = mergedParams()
        val filterSnapshot = _selectedFilter.value
        val filterStrengthSnapshot = _filterStrength.value

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    var full = ImageDecoders.decodeFull(context.contentResolver, uri)
                        ?: throw IllegalStateException("原图解码失败或内存不足")
                    // 快速导出：先缩到长边上限再应用管线（PRD 4.7 兜底路径）
                    if (format.maxEdge > 0) {
                        val longEdge = maxOf(full.width, full.height)
                        if (longEdge > format.maxEdge) {
                            val scale = format.maxEdge.toFloat() / longEdge
                            val scaled = Bitmap.createScaledBitmap(
                                full,
                                (full.width * scale).toInt().coerceAtLeast(1),
                                (full.height * scale).toInt().coerceAtLeast(1),
                                true
                            )
                            full.recycle()
                            full = scaled
                        }
                    }
                    try {
                        EditEngine.applyInPlace(full, paramsSnapshot)
                        EditEngine.applyFilterInPlace(full, filterSnapshot, filterStrengthSnapshot)
                        EditExporter.saveAsNewImage(
                            context = context,
                            bitmap = full,
                            sourceUri = uri,
                            sourceName = sourceName,
                            format = format
                        )
                    } finally {
                        full.recycle()
                    }
                }
            }
            _isExporting.value = false
            result.onSuccess {
                // 保存成功后视为已同步，清空编辑状态（PRD: 返回相册自动刷新）
                history.clear()
                syncHistoryState()
                _message.value = "已保存"
                Timber.tag(TAG).i("Exported: $it format=$format")
            }.onFailure {
                _message.value = "保存失败：${it.message}"
                Timber.tag(TAG).e(it, "Export failed")
            }
            onResult(result)
        }
    }

    /** 待 SAF 授权完成后继续的覆盖保存 */
    private var pendingOverwriteFormat: ExportFormat? = null

    /**
     * 覆盖保存（PRD 4.7 P1）：备份 → 全分辨率渲染 → 覆盖原文件。
     * 非本 App 所有文件抛 SAF 授权请求，由 UI 层发起系统弹窗。
     */
    fun saveOverwrite(
        format: ExportFormat,
        onNeedSaf: (IntentSender) -> Unit,
        onResult: (Result<Uri>) -> Unit
    ) {
        val uri = sourceUri
        if (uri == null) {
            onResult(Result.failure(IllegalStateException("图片加载失败：缺少来源")))
            return
        }
        if (_isExporting.value) return
        _isExporting.value = true
        val paramsSnapshot = mergedParams()
        val filterSnapshot = _selectedFilter.value
        val filterStrengthSnapshot = _filterStrength.value

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val full = ImageDecoders.decodeFull(context.contentResolver, uri)
                        ?: throw IllegalStateException("原图解码失败或内存不足")
                    try {
                        EditEngine.applyInPlace(full, paramsSnapshot)
                        EditEngine.applyFilterInPlace(full, filterSnapshot, filterStrengthSnapshot)
                        EditExporter.overwriteOriginal(context, uri, full, format)
                    } finally {
                        full.recycle()
                    }
                    uri
                }
            }
            val error = result.exceptionOrNull()
            if (error is EditExporter.SafAuthorizationRequiredException) {
                pendingOverwriteFormat = format
                _isExporting.value = false
                onNeedSaf(error.intentSender)
                return@launch
            }
            _isExporting.value = false
            handleOverwriteSuccess(result)
            if (result.isFailure) {
                _message.value = "保存失败：${error?.message}"
                Timber.tag(TAG).e(error, "Overwrite failed")
            }
            onResult(result)
        }
    }

    /**
     * SAF 授权结果回调（PRD 4.7: 用户拒绝时降级为另存）。
     */
    fun completeSafOverwrite(granted: Boolean, onResult: (Result<Uri>) -> Unit) {
        val format = pendingOverwriteFormat ?: return
        pendingOverwriteFormat = null
        if (!granted) {
            _message.value = "未获得授权，已改为另存为新图"
            saveToGallery(format, onResult)
            return
        }
        val uri = sourceUri ?: return
        if (_isExporting.value) return
        _isExporting.value = true
        val paramsSnapshot = mergedParams()
        val filterSnapshot = _selectedFilter.value
        val filterStrengthSnapshot = _filterStrength.value

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val full = ImageDecoders.decodeFull(context.contentResolver, uri)
                        ?: throw IllegalStateException("原图解码失败或内存不足")
                    try {
                        EditEngine.applyInPlace(full, paramsSnapshot)
                        EditEngine.applyFilterInPlace(full, filterSnapshot, filterStrengthSnapshot)
                        // 备份已在首次尝试时完成，授权后直接覆盖
                        EditExporter.overwriteOriginal(context, uri, full, format, skipBackup = true)
                    } finally {
                        full.recycle()
                    }
                    uri
                }
            }
            _isExporting.value = false
            handleOverwriteSuccess(result)
            if (result.isFailure) {
                _message.value = "保存失败：${result.exceptionOrNull()?.message}"
            }
            onResult(result)
        }
    }

    /** 覆盖成功：原图即修图结果，重置全部编辑状态避免二次叠加 */
    private fun handleOverwriteSuccess(result: Result<Uri>) {
        if (result.isFailure) return
        history.clear()
        syncHistoryState()
        _params.value = EditParams()
        _enhanceState.value = EnhanceUiState.Idle
        _selectedFilter.value = FilterLibrary.ORIGINAL
        sourceBitmap?.let { src ->
            _originalBitmap.value = src
            _previewBitmap.value = src
        }
        _message.value = "已覆盖保存（原图已备份）"
        Timber.tag(TAG).i("Overwrite saved: ${result.getOrNull()}")
    }

    override fun onCleared() {
        super.onCleared()
        renderJob?.cancel()
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        sourceBitmap = null
        val preview = _previewBitmap.value
        if (preview != null && preview !== sourceBitmap && !preview.isRecycled) {
            preview.recycle()
        }
        _filterThumbnails.value.values.forEach { thumb ->
            if (!thumb.isRecycled) thumb.recycle()
        }
        _filterThumbnails.value = emptyMap()
    }
}

/**
 * AI 增强 UI 状态（PRD M2: 处理中/失败态全套反馈）
 */
sealed class EnhanceUiState {
    data object Idle : EnhanceUiState()
    data object Analyzing : EnhanceUiState()
    data class Ready(val suggestion: AiEnhancer.EnhanceSuggestion) : EnhanceUiState()
    data class Failed(val reason: String) : EnhanceUiState()
}

/**
 * 导出画质（PRD 4.7）
 * QUICK_4K: 快速导出（长边 4K，P1 兜底路径）——大模型精细渲染耗时过长时的替代选项，
 * 模型档接入后价值更明显；当前传统算法管线中作为小图快导选项保留。
 */
enum class ExportFormat(val label: String, val quality: Int, val maxEdge: Int = 0) {
    JPEG_HIGH("高质量 JPEG", 95),
    PNG_LOSSLESS("PNG 无损", 100),
    QUICK_4K("快速导出（长边 4K）", 92, maxEdge = 4096)
}

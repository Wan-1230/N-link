package com.nikonlink.app.camera.params

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import javax.inject.Inject

/**
 * 相机参数管理 ViewModel
 * PRD 2.4: 曝光三要素实时读取与调整、参数锁定/解锁
 */
@HiltViewModel
class CameraParamsViewModel @Inject constructor(
    private val paramManager: CameraParameterManager
) : ViewModel() {

    val aperture: StateFlow<CameraParam> = paramManager.aperture
    val shutterSpeed: StateFlow<CameraParam> = paramManager.shutterSpeed
    val iso: StateFlow<CameraParam> = paramManager.iso
    val evCompensation: StateFlow<CameraParam> = paramManager.evCompensation
    val whiteBalance: StateFlow<CameraParam> = paramManager.whiteBalance
    val focusMode: StateFlow<CameraParam> = paramManager.focusMode
    val exposureProgram: StateFlow<CameraParam> = paramManager.exposureProgram
    val meteringMode: StateFlow<CameraParam> = paramManager.meteringMode
    val paramsLocked: StateFlow<Boolean> = paramManager.paramsLocked
    val cameraInfo: StateFlow<CameraInfo> = paramManager.cameraInfo

    // ==================== 档位真源（PRD 3.4 S1） ====================
    // 所有档位表只在 CameraParameterManager / ShutterCatalog / ApertureCatalog 维护一份，
    // 拍摄页与全屏监看页都从这里取，不再各自硬编码。

    /** 快门档位 raw 值（1/10000s），**升序 = 曝光时间由短到长 = index 0 最快，lastIndex 最慢** */
    val commonShutterSpeeds: List<Int> get() = ShutterCatalog.VALUES

    /** ISO 档位 */
    val commonIsoValues: List<Int> get() = paramManager.commonIsoValues

    /** 白平衡预设：PTP 枚举值 → 显示名 */
    val whiteBalancePresets: List<Pair<Int, String>> get() = paramManager.whiteBalancePresets

    /** 可远程切换的拍摄模式（0x500E 值 → 标签） */
    val exposureProgramModes: List<Pair<Int, String>> get() = paramManager.exposureProgramModes

    /** 设置拍摄模式（远程切换 P/S/A/M/Auto，机身不支持时以回读值为准） */
    fun setExposureProgram(mode: Int) {
        viewModelScope.launch { paramManager.setExposureProgram(mode) }
    }

    /**
     * 远程切换拍摄模式并回传结果（模块 2：失败显式回调）。
     * false = 机身拒绝（0x500E 只读 / 拨盘机型不可远程切）——调用方需给出可操作提示，
     * UI 展示值仍以 [exposureProgram] 回读流为准（单一数据源，不落本地假状态）。
     */
    suspend fun setExposureProgramResult(mode: Int): Boolean =
        withContext(Dispatchers.IO) { paramManager.setExposureProgram(mode) }

    /**
     * 写入后**主动回读设备**确认模式是否真的切换（v1.3.0 需求 4）。
     *
     * 必要性：0x500E 写入返回 OK 只说明相机接受了指令，部分机型会静默忽略；
     * 旧实现用 `exposureProgram` 状态流判定，而该流可能还停在上一次缓存/初始默认值上，
     * 会出现"提示已切换、相机其实没动"的假成功。
     *
     * @return 命中 target 返回 target；否则返回相机最后上报的实际值（读不到为 null）
     */
    suspend fun confirmExposureProgram(
        target: Int,
        attempts: Int = 4,
        intervalMs: Long = 400L
    ): Int? = withContext(Dispatchers.IO) {
        paramManager.confirmExposureProgram(target, attempts, intervalMs)
    }

    /** 本机是否已判定为「不支持从 App 切换拍摄模式」（会话级，重连后复位） */
    val modeSwitchUnsupported: StateFlow<Boolean> get() = paramManager.modeSwitchUnsupported

    /** 标记本机不支持远程切换（由监看页在"写入 OK 但模式未变"后调用） */
    fun markModeSwitchUnsupported() = paramManager.markModeSwitchUnsupported()

    /** 相机侧模式的中文描述（用于把回读到的实际值写进提示文案） */
    fun describeExposureProgram(value: Int): String = paramManager.describeExposureProgram(value)

    /**
     * 模式快轮询开关（模块 2：机身侧切模式 ≤500ms 同步）。
     * 拍摄页 / 全屏监看页可见期间开启，隐藏或销毁时停止。
     */
    fun startModeWatch(intervalMs: Long = 500L) {
        paramManager.startModeWatch(intervalMs)
    }

    fun stopModeWatch() {
        paramManager.stopModeWatch()
    }

    /** 快门显示文本（与相机回读值同一口径，见 [CameraParameterManager.formatShutter]） */
    fun formatShutter(rawX10000: Int): String = paramManager.formatShutter(rawX10000)

    /** 光圈显示文本：f 值 ×100 → "f/2.8" */
    fun formatAperture(fStopX100: Int): String = paramManager.formatAperture(fStopX100)

    private val _apertureOptions = MutableStateFlow(ApertureCatalog.FALLBACK)

    /**
     * 当前镜头可用光圈档位（f 值 ×100，升序 = 大光圈 → 小光圈）。
     * 打开光圈滚轮前先调用 [refreshApertureOptions] 刷新，再从这里读取。
     */
    val apertureOptions: StateFlow<List<Int>> = _apertureOptions.asStateFlow()

    /** 光圈档位是否来自镜头信息；false 时 UI 需提示"未获取到镜头信息，使用通用档位" */
    val apertureRangeFromLens: Boolean get() = paramManager.isLensApertureRangeKnown

    /**
     * 重新生成光圈档位并写入 [apertureOptions]。
     *
     * 按 PRD Q3 的决定：当前焦距（`PROP_FOCAL_LENGTH`）只在**打开光圈滚轮时读一次**，
     * 不进 `readAllParameters()` 的 2 秒轮询，避免增加 PTP 通道压力。
     */
    suspend fun refreshApertureOptions() = withContext(Dispatchers.IO) {
        if (paramManager.isLensApertureRangeKnown) {
            paramManager.readCurrentFocalLengthMm()
        }
        val options = paramManager.apertureOptions()
        _apertureOptions.value = options
        options
    }

    init {
        paramManager.start(viewModelScope)
    }

    fun startPolling(intervalMs: Long = 2000L) {
        paramManager.startPolling(intervalMs)
    }

    fun stopPolling() {
        paramManager.stopPolling()
    }

    fun readAll() {
        viewModelScope.launch { paramManager.readAllParameters() }
    }

    /** 快门次数查询失败后手动重试 */
    fun retryShutterCountQuery() {
        paramManager.retryShutterCountQuery()
    }

    fun toggleLock() {
        paramManager.toggleLock()
    }

    /**
     * 调整 ISO（按预设列表步进）
     * @param direction +1 升一档, -1 降一档
     */
    fun adjustIso(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.iso.value.rawValue
            val list = paramManager.commonIsoValues
            val newIdx = stepIndex(current, list, direction)
            paramManager.setIso(list[newIdx])
        }
    }

    /**
     * 调整光圈（按档位步进）
     *
     * 档位表按 f 值升序（大光圈 → 小光圈）。
     * @param direction **> 0 向"f 值变大 / 光圈收小"步进；< 0 向"f 值变小 / 光圈放大"步进**
     */
    fun adjustAperture(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.aperture.value.rawValue
            val list = paramManager.apertureOptions()
            val newIdx = stepIndex(current, list, direction)
            paramManager.setAperture(list[newIdx])
        }
    }

    /**
     * 调整快门速度（按档位步进）
     *
     * 档位表按**曝光时间升序**排列（index 0 最快，lastIndex 最慢）。
     * @param direction **> 0 向"曝光变长 / 快门变慢"步进；< 0 向"曝光变短 / 快门变快"步进**
     */
    fun adjustShutter(direction: Int) {
        viewModelScope.launch {
            val current = paramManager.shutterSpeed.value.rawValue
            val list = paramManager.commonShutterSpeeds
            val newIdx = stepIndex(current, list, direction)
            paramManager.setShutterSpeed(list[newIdx])
        }
    }

    private fun stepIndex(currentRaw: Int, values: List<Int>, direction: Int): Int {
        if (values.isEmpty()) return 0
        val idx = values.indexOfFirst { it >= currentRaw }
        val base = if (idx == -1) values.lastIndex else idx
        return (base + direction).coerceIn(0, values.lastIndex)
    }

    fun setWhiteBalance(mode: Int) {
        viewModelScope.launch { paramManager.setWhiteBalance(mode) }
    }

    /** 滚轮选择器：直接按值设定光圈 / 快门 / ISO */
    fun setApertureByValue(fStopX100: Int) {
        viewModelScope.launch { paramManager.setAperture(fStopX100) }
    }

    fun setShutterByValue(exposureTime: Int) {
        viewModelScope.launch { paramManager.setShutterSpeed(exposureTime) }
    }

    /**
     * 切到 B 门档（B 门 / 长曝光的参数化入口，滚轮「B门」项调用）。
     * 与 [setShutterByValue] 分离：0xFFFFFFFF 会被档位钳位吞掉，必须走专用写入路径。
     * @return false = 写入被拒（0x500D 只读 / 非 M·S 档），由 UI 给可操作提示
     */
    suspend fun setShutterBulb(): Boolean = withContext(Dispatchers.IO) {
        paramManager.setShutterBulb()
    }

    fun setIsoByValue(iso: Int) {
        viewModelScope.launch { paramManager.setIso(iso) }
    }

    // ==================== 手动输入（就近匹配 + 生效值提示） ====================

    /**
     * 手动输入快门速度。
     *
     * 输入值若不在档位表中，自动切到**对数距离最近**的档位，并在 [ParamApplyResult.message]
     * 里回告实际生效值；写完后回读相机，机身实际值与下发值不一致时一并提示。
     */
    suspend fun applyShutterInput(text: String): ParamApplyResult = withContext(Dispatchers.IO) {
        val seconds = ShutterCatalog.parseSeconds(text)
            ?: return@withContext ParamApplyResult(
                false, "输入无效，可填 1/250、250、0.6 或 30s（1/8000 – 30s）"
            )
        // 优先按标称名精确命中（1/4000 等高速档的 raw 是取整值，只能按名字命中），
        // 命中不了再按曝光时间就近匹配（按标称秒数对数距离，规避高速档取整误差）
        val labelHit = ShutterCatalog.matchByLabel(text)
        val target = labelHit ?: ShutterCatalog.matchBySeconds(seconds)
        val snapped = labelHit == null && (seconds * 10000).roundToInt() != target
        if (!paramManager.setShutterSpeed(target)) {
            return@withContext ParamApplyResult(false, "参数已锁定，请先解锁后再调整")
        }
        val applied = paramManager.shutterSpeed.value
        val label = ShutterCatalog.format(target)
        when {
            // 机身回读与下发不一致：以机身为准回告，避免界面显示假值
            applied.rawValue > 0 && applied.rawValue != target ->
                ParamApplyResult(true, "已下发 $label，相机实际为 ${applied.currentValue}")
            snapped ->
                ParamApplyResult(true, "无此档位，已切到最接近的 $label")
            else ->
                ParamApplyResult(true, "已设为 $label")
        }
    }

    /** 手动输入光圈（f 值），档位与当前镜头联动，越界自动取最近合法档。 */
    suspend fun applyApertureInput(text: String): ParamApplyResult = withContext(Dispatchers.IO) {
        val fStopX100 = ApertureCatalog.parseFStop(text)
            ?: return@withContext ParamApplyResult(
                false, "输入无效，可填 f/2.8、F8 或 8（f/1.0 – f/64）"
            )
        val options = paramManager.apertureOptions()
        val target = ApertureCatalog.clampToNearest(fStopX100, options)
        if (!paramManager.setAperture(target)) {
            return@withContext ParamApplyResult(false, "参数已锁定，请先解锁后再调整")
        }
        val applied = paramManager.aperture.value
        val label = ApertureCatalog.format(target)
        when {
            applied.rawValue > 0 && applied.rawValue != target ->
                ParamApplyResult(true, "已下发 $label，相机实际为 ${applied.currentValue}")
            target != fStopX100 ->
                ParamApplyResult(true, "该镜头无此档位，已切到最接近的 $label")
            else ->
                ParamApplyResult(true, "已设为 $label")
        }
    }

    /** 手动输入 ISO，不在档位表时按对数距离就近匹配。 */
    suspend fun applyIsoInput(text: String): ParamApplyResult = withContext(Dispatchers.IO) {
        val isoValue = IsoCatalog.parse(text)
            ?: return@withContext ParamApplyResult(
                false, "输入无效，可填 800、ISO 6400 或 auto（50 – 819200）"
            )
        val options = paramManager.commonIsoValues
        val target = IsoCatalog.matchNearest(isoValue, options)
        if (!paramManager.setIso(target)) {
            return@withContext ParamApplyResult(false, "参数已锁定，请先解锁后再调整")
        }
        val applied = paramManager.iso.value
        val label = if (target == IsoCatalog.AUTO_ISO_RAW) "Auto" else "$target"
        when {
            applied.rawValue > 0 && applied.rawValue != target ->
                ParamApplyResult(true, "已下发 $label，相机实际为 ${applied.currentValue}")
            target != isoValue ->
                ParamApplyResult(true, "无此档位，已切到最接近的 ISO $label")
            else ->
                ParamApplyResult(true, "已设为 ISO $label")
        }
    }

    /** 测光模式循环：矩阵 → 中央重点 → 点测光 → 高光重点 */
    fun cycleMeteringMode() {
        viewModelScope.launch {
            val codes = listOf(3, 2, 4, 0x8010)
            val current = paramManager.meteringMode.value.rawValue
            val idx = codes.indexOf(current)
            paramManager.setMeteringMode(codes[(idx + 1).coerceAtLeast(0) % codes.size])
        }
    }

    fun setFocusMode(mode: Int) {
        viewModelScope.launch { paramManager.setFocusMode(mode) }
    }

    fun setMeteringMode(mode: Int) {
        viewModelScope.launch { paramManager.setMeteringMode(mode) }
    }

    fun cycleWhiteBalance() {
        viewModelScope.launch {
            val presets = paramManager.whiteBalancePresets
            if (presets.isEmpty()) return@launch
            val current = paramManager.whiteBalance.value.rawValue
            val idx = presets.indexOfFirst { it.first == current }
            val next = presets[(idx + 1).coerceAtLeast(0) % presets.size]
            paramManager.setWhiteBalance(next.first)
        }
    }

    fun cycleFocusMode() {
        viewModelScope.launch {
            val modes = listOf(0x8010, 0x8011, 1)
            val current = paramManager.focusMode.value.rawValue
            val idx = modes.indexOf(current)
            paramManager.setFocusMode(modes[(idx + 1).coerceAtLeast(0) % modes.size])
        }
    }
}

/**
 * 手动输入的应用结果。
 *
 * @param ok 是否成功下发（false 表示输入非法或参数被锁定，界面不应关闭输入面板）
 * @param message 给用户的提示：非法原因 / 就近匹配后的档位 / 相机实际生效值
 */
data class ParamApplyResult(
    val ok: Boolean,
    val message: String
)

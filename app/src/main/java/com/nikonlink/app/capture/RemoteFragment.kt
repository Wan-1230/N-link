package com.nikonlink.app.capture

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.R
import com.nikonlink.app.databinding.DialogParamPickerBinding
import com.nikonlink.app.databinding.FragmentRemoteBinding
import com.nikonlink.app.camera.liveview.FocusTapMapper
import com.nikonlink.app.camera.liveview.LiveViewState
import com.nikonlink.app.camera.liveview.LiveViewActivity
import com.nikonlink.app.camera.liveview.LiveViewViewModel
import com.nikonlink.app.camera.params.CameraParam
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.camera.params.ParamApplyResult
import com.nikonlink.app.camera.params.resolvePickerIndex
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tab3 遥控拍摄（黑白极简）
 * 实时监看 2/3 + 参数滚轮选择器 + 快门户（按压内陷+闪白）
 * 视频模式：录制按钮 + 实心圆点时长标识（灰度）
 */
@AndroidEntryPoint
class RemoteFragment : Fragment() {

    private var _binding: FragmentRemoteBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RemoteShootingViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()
    private val liveViewViewModel: LiveViewViewModel by viewModels()

    /** 优化项 4：直方图开关状态持久化（顺手保持跨页面/重启一致） */
    @Inject lateinit var settings: AppSettings

    /** 相机连接判定（USB / WiFi PTP 任一通道连通即视为可远程控制） */
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var usbPtpManager: UsbPtpManager

    private var videoMode = false
    private var recording = false
    private var recordTimerJob: Job? = null
    private val paramCells = mutableMapOf<String, TextView>()

    /** 参数单元格容器（PRD D3：B 门曝光中统一禁用，点击与按压反馈一并关闭） */
    private val paramCellContainers = mutableListOf<LinearLayout>()

    /** 「模式」参数单元格引用（用于按连接状态置灰） */
    private var modeCell: LinearLayout? = null

    /** B 门曝光中标记（PRD D3，与 applyBulbLock 同步） */
    private var bulbBusy = false

    /** 模式切换进行中标记：防止重复下发，下发期间入口置灰 */
    private var modeSwitching = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRemoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLiveViewArea()
        setupParamRow()
        setupShutter()
        observe()
        // 初始按当前连接状态置灰「模式」入口（未连接时不可点）
        updateModeCellState()
        // 轻量一次性读取，避免抢占 PTP 通道（P0-1 修复经验）
        viewModel.refreshStatus()
        paramsViewModel.readAll()
        // 模块 2：页面可见期间开启 0x500E 快轮询，机身拨盘切模式 ≤500ms 同步到 UI
        paramsViewModel.startModeWatch()
        setupDisplayModeChip()
    }

    /**
     * 画面模式下拉（v1.0.2 从长按菜单迁至顶部）。
     * 收起态只显示当前模式名；展开的菜单项带括号说明：
     * 联动画面（相机屏与手机同显）/ 遥控画面（相机屏熄·显示已连接字样）。
     */
    private fun setupDisplayModeChip() {
        renderDisplayModeChip()
        binding.chipDisplayMode.pressEffect()
        binding.chipDisplayMode.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            val options = listOf(
                "联动画面（相机屏与手机同显）" to false,
                "遥控画面（相机屏熄·显示已连接字样）" to true
            )
            options.forEachIndexed { index, (label, remote) ->
                popup.menu.add(0, index + 1, 0, label).isChecked =
                    (settings.remoteDisplayMode == AppSettings.DISPLAY_MODE_REMOTE) == remote
            }
            popup.menu.setGroupCheckable(0, true, true)
            popup.setOnMenuItemClickListener { item ->
                val remote = options.getOrNull(item.itemId - 1)?.second
                    ?: return@setOnMenuItemClickListener false
                val changed = (settings.remoteDisplayMode == AppSettings.DISPLAY_MODE_REMOTE) != remote
                settings.remoteDisplayMode =
                    if (remote) AppSettings.DISPLAY_MODE_REMOTE else AppSettings.DISPLAY_MODE_LINKED
                renderDisplayModeChip()
                if (changed) {
                    viewModel.setCameraDisplayMode(remote)
                    Toast.makeText(
                        requireContext(),
                        if (remote) "正在进入遥控模式：相机屏将熄灭并显示「已连接到智能设备」"
                        else "正在恢复联动模式：相机屏将重新显示画面",
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
            popup.show()
        }
    }

    /** 收起态标签：只显示模式名（不带括号说明） */
    private fun renderDisplayModeChip() {
        val remote = settings.remoteDisplayMode == AppSettings.DISPLAY_MODE_REMOTE
        binding.chipDisplayMode.text =
            if (remote) "遥控画面 ▾" else "联动画面 ▾"
    }

    /** 模块 2：Tab 隐藏/显示与模式快轮询联动（MainActivity 四 Fragment 常驻，hide/show 不走 onPause） */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            paramsViewModel.stopModeWatch()
            // 切离拍摄页停止 LiveView，释放 PTP 通道（防断联）——与旧 onHiddenChanged 行为一致
            if (liveViewViewModel.liveViewState.value == LiveViewState.RUNNING) {
                liveViewViewModel.stopLiveView()
            }
        } else {
            paramsViewModel.startModeWatch()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        paramsViewModel.stopModeWatch()
        liveViewViewModel.stopLiveView()
        recordTimerJob?.cancel()
        _binding = null
    }

    // ---------------- 监看区域 ----------------

    private fun setupLiveViewArea() {
        // 监看画面圆角裁剪
        binding.liveContainer.clipToOutline = true
        binding.liveContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, dp(16f))
            }
        }

        binding.btnStartLv.pressEffect()
        binding.btnStartLv.setOnClickListener {
            liveViewViewModel.startLiveView()
        }

        binding.btnFullscreenLv.pressEffect()
        binding.btnFullscreenLv.setOnClickListener {
            // 直接进入全屏，**不停当前监看**：遥控页与全屏页共用同一 LiveViewManager
            // 单例，startLiveView 已幂等（RUNNING 直接返回），帧循环无缝延续。
            // 旧流程先 stop 再让全屏页 start，两个协程的 EndLiveView/StartLiveView
            // 在 commandMutex 上的到达顺序不保证，End 晚到会把刚开的监看关掉，
            // 表现为「进全屏后画面异常」。
            LiveViewActivity.start(requireContext())
        }

        // 优化项 4：亮度直方图开关
        binding.btnHistogram.pressEffect()
        binding.btnHistogram.setOnClickListener {
            val enabled = !settings.histogramEnabled
            settings.histogramEnabled = enabled
            applyHistogramToggle(enabled)
            Toast.makeText(
                requireContext(),
                if (enabled) "已开启亮度直方图" else "已关闭亮度直方图",
                Toast.LENGTH_SHORT
            ).show()
        }
        applyHistogramToggle(settings.histogramEnabled)

        // 点击画面选择对焦点（淡入淡出动效）。
        // Fix: 旧版直接 event.x/view.width 归一化，把 fitCenter 黑边算进坐标，
        // 实际对焦点与点击位置系统性偏移；统一走 FocusTapMapper（含缩放还原）
        binding.ivLiveView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                liveViewViewModel.liveViewState.value == LiveViewState.RUNNING
            ) {
                val tap = FocusTapMapper.mapToNormalized(binding.ivLiveView, event.x, event.y)
                if (tap != null) {
                    liveViewViewModel.touchFocus(tap.x, tap.y)
                    showFocusIndicator(event.x, event.y)
                }
            }
            true
        }
    }

    /**
     * 优化项 4：应用直方图开关状态。
     * 关闭时一并清空已统计的柱体，否则重新打开会先闪一帧上一轮的残留图形。
     * 开关态用图标透明度表达（开启 100% / 关闭 55%），不引入强调色，保持黑白体系。
     */
    private fun applyHistogramToggle(enabled: Boolean) {
        binding.viewHistogram.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.btnHistogram.alpha = if (enabled) 1f else 0.55f
        if (!enabled) binding.viewHistogram.clear()
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val size = dp(56f)
        binding.viewFocusIndicator.translationX = (x - size / 2).coerceIn(0f, binding.liveContainer.width - size)
        binding.viewFocusIndicator.translationY = (y - size / 2).coerceIn(0f, binding.liveContainer.height - size)
        binding.viewFocusIndicator.animate().cancel()
        binding.viewFocusIndicator.visibility = View.VISIBLE
        binding.viewFocusIndicator.alpha = 0f
        binding.viewFocusIndicator.animate().alpha(1f).setDuration(120).start()
        binding.viewFocusIndicator.postDelayed({
            if (_binding != null) {
                binding.viewFocusIndicator.animate().alpha(0f).setDuration(300)
                    .withEndAction { if (_binding != null) binding.viewFocusIndicator.visibility = View.GONE }
                    .start()
            }
        }, 900)
    }

    // ---------------- 参数行（滚轮选择器） ----------------

    private fun setupParamRow() {
        val params = listOf(
            "模式" to paramsViewModel.exposureProgram,
            "光圈" to paramsViewModel.aperture,
            "快门" to paramsViewModel.shutterSpeed,
            "ISO" to paramsViewModel.iso,
            "白平衡" to paramsViewModel.whiteBalance
        )
        params.forEachIndexed { index, (label, flow) ->
            val cell = buildParamCell(label)
            if (index > 0) {
                (cell.first.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8f).toInt()
            }
            paramCells[label] = cell.second
            paramCellContainers.add(cell.first)
            if (label == "模式") modeCell = cell.first
            binding.paramRow.addView(cell.first)
            cell.first.setOnClickListener { onParamClick(label, flow.value) }

            // 订阅参数值刷新
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { param -> cell.second.text = param.currentValue.ifBlank { "--" } }
            }
        }
    }

    private fun buildParamCell(label: String): Pair<LinearLayout, TextView> {
        val ctx = requireContext()
        val valueView = TextView(ctx).apply {
            text = "--"
            setTextColor(resources.getColor(R.color.text_primary, null))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val labelView = TextView(ctx).apply {
            text = label
            setTextColor(resources.getColor(R.color.text_tertiary, null))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
        }
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_info_grid_item)
            setPadding(dp(16f).toInt(), dp(6f).toInt(), dp(16f).toInt(), dp(6f).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(labelView)
            addView(valueView)
        }
        cell.pressEffect()
        return cell to valueView
    }

    private fun onParamClick(label: String, param: CameraParam) {
        when (label) {
            // 光圈档位与镜头联动，打开滚轮前先刷新一次（内部会读一次当前焦距）
            "光圈" -> openAperturePicker(param)
            "快门" -> {
                // B 门作为独立档追加在 30s 之后（digiCamControl/ZRelay「Bulb/Time」同款模型）；
                // raw 0xFFFFFFFF 会被常规档位钳位吞掉，确认时走 setShutterBulb 专用路径
                val bulbRaw = BulbPolicy.SHUTTER_BULB_RAW
                val rawValues = paramsViewModel.commonShutterSpeeds + bulbRaw
                showWheelPicker(
                    title = "快门速度",
                    param = param,
                    displayValues = rawValues.map { raw ->
                        if (raw == bulbRaw) "B门" else paramsViewModel.formatShutter(raw)
                    },
                    rawValues = rawValues,
                    // 档位表按曝光时间升序，向上滚 = 更快
                    hint = "↑ 更快 / ↓ 更慢（可手动输入，末档为 B门）",
                    manualHintRes = R.string.param_input_hint_shutter,
                    onManualInput = { text -> paramsViewModel.applyShutterInput(text) },
                    onConfirm = { idx ->
                        val raw = rawValues.getOrNull(idx) ?: return@showWheelPicker
                        if (raw == bulbRaw) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val ok = paramsViewModel.setShutterBulb()
                                if (_binding != null) {
                                    Toast.makeText(
                                        requireContext(),
                                        if (ok) "已切到 B 门档，可在「更多动作」开始曝光"
                                        else "切换 B 门被拒：请将相机拨盘切到 M 档且快门可调",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } else {
                            paramsViewModel.setShutterByValue(raw)
                        }
                    }
                )
            }
            "ISO" -> {
                val rawValues = paramsViewModel.commonIsoValues
                showWheelPicker(
                    title = "ISO",
                    param = param,
                    displayValues = rawValues.map { it.toString() },
                    rawValues = rawValues,
                    hint = null,
                    manualHintRes = R.string.param_input_hint_iso,
                    onManualInput = { text -> paramsViewModel.applyIsoInput(text) },
                    onConfirm = { idx -> paramsViewModel.setIsoByValue(rawValues[idx]) }
                )
            }
            "白平衡" -> {
                val presets = paramsViewModel.whiteBalancePresets
                showWheelPicker(
                    "白平衡", param, presets.map { it.second }, presets.map { it.first },
                    onConfirm = { idx -> paramsViewModel.setWhiteBalance(presets[idx].first) }
                )
            }
            "模式" -> showModePicker()
        }
    }

    /**
     * 拍摄模式远程切换（0x500E，模块 2 + v1.3.0 需求 4 强化）。
     * 与全屏监看页共用同一套档位与回读流（单一数据源）：列表预选当前模式、
     * 下发中禁用重复点击并显示 loading、下发后**主动回读设备**确认模式是否真的改变，
     * 三种结果分别给出明确提示（成功 / 相机接受但未变 / 读取失败），
     * 绝不把 UI 显示成"已切换"而相机没动。
     */
    private fun showModePicker() {
        // 边界：未连接 / 切换进行中直接拦截（入口已置灰，这里再兜一层）
        if (!isCameraPtpConnected()) {
            Toast.makeText(requireContext(), "相机未连接，无法切换拍摄模式", Toast.LENGTH_SHORT).show()
            return
        }
        if (modeSwitching) return
        // v1.3.0 需求 4：本机已被现场验证为「不支持远程切换」→ 直接给结论，
        // 不再让用户反复点选却毫无反馈
        if (paramsViewModel.modeSwitchUnsupported.value) {
            Toast.makeText(
                requireContext(),
                "该机型不支持从 App 切换拍摄模式（相机接受指令但不改变模式），请用机身拨盘",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val modes = paramsViewModel.exposureProgramModes
        if (modes.isEmpty()) {
            Toast.makeText(requireContext(), "暂无可切换的拍摄模式", Toast.LENGTH_SHORT).show()
            return
        }
        val current = paramsViewModel.exposureProgram.value
        val checkedIndex = modes.indexOfFirst { it.first == current.rawValue }
        val labels = modes.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("拍摄模式（远程切换）")
            .setMessage("当前: ${current.currentValue.ifBlank { "--" }}")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                if (modeSwitching) return@setSingleChoiceItems
                dialog.dismiss()
                applyExposureProgram(modes[which].first, modes[which].second)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 下发拍摄模式并做回读校验（与全屏监看页逻辑一致）。
     * 写入被拒 → 提示参数锁定/只读；写入成功但 3 秒内 0x500E 没切过去 → 提示相机未响应，
     * 二者都显式反馈，不把 UI 显示成已切换（显示值由回读流驱动）。
     */
    private fun applyExposureProgram(target: Int, label: String) {
        modeSwitching = true
        updateModeCellState()
        Toast.makeText(requireContext(), "正在切换拍摄模式…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ok = paramsViewModel.setExposureProgramResult(target)
                if (!ok) {
                    if (_binding == null) return@launch
                    Toast.makeText(
                        requireContext(),
                        "相机拒绝切换（参数可能已锁定或模式只读），请用机身拨盘调整",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                // v1.3.0 需求 4：不再用状态流判定，改为**主动回读设备**确认。
                // 旧实现只看 exposureProgram 流，若它停在缓存/初始值上，就会出现
                // 「提示已切换、相机其实没动」的假成功（用户反馈的"只有字样没效果"）。
                val actual = paramsViewModel.confirmExposureProgram(target)
                if (_binding == null) return@launch
                when {
                    actual == target ->
                        Toast.makeText(requireContext(), "已切换到 $label", Toast.LENGTH_SHORT).show()

                    actual != null -> {
                        // 相机接受了指令但模式没变 → 判定本机不支持，后续点击直接给结论
                        paramsViewModel.markModeSwitchUnsupported()
                        Toast.makeText(
                            requireContext(),
                            "相机接受了指令但模式未改变（当前："
                                + paramsViewModel.describeExposureProgram(actual)
                                + "）。该机型不支持从 App 切换拍摄模式，请用机身拨盘调整",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    else -> Toast.makeText(
                        requireContext(),
                        "无法读取相机当前模式，请确认连接后重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                modeSwitching = false
                updateModeCellState()
            }
        }
    }

    /** 相机 PTP 通道是否可用（USB 或 WiFi PTP 任一连通） */
    private fun isCameraPtpConnected(): Boolean =
        usbPtpManager.isConnected() || connectionManager.getPtpSession().isConnected()

    /**
     * 根据连接状态 / B 门锁定 / 切换中标记 / 模式列表是否为空，更新「模式」入口可用性。
     * 未连接、B 门曝光中、切换进行中、列表为空 → 置灰且点击无效。
     */
    private fun updateModeCellState() {
        val cell = modeCell ?: return
        val enabled = isCameraPtpConnected() && !bulbBusy && !modeSwitching
            && paramsViewModel.exposureProgramModes.isNotEmpty()
        cell.isEnabled = enabled
        cell.isClickable = enabled
        cell.alpha = if (enabled) 1f else 0.4f
    }

    /**
     * 打开光圈滚轮：档位与镜头联动。
     * 打开时读一次当前焦距（PRD Q3，不进轮询），再从 paramsViewModel 暴露的档位流取结果。
     */
    private fun openAperturePicker(param: CameraParam) {
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.refreshApertureOptions()
            if (_binding == null) return@launch
            val options = paramsViewModel.apertureOptions.value
            showWheelPicker(
                title = "光圈",
                param = param,
                displayValues = options.map { paramsViewModel.formatAperture(it) },
                rawValues = options,
                hint = if (paramsViewModel.apertureRangeFromLens) {
                    "↑ 光圈更大 / ↓ 光圈收小（可手动输入）"
                } else {
                    "未获取到镜头信息，使用通用档位（可手动输入）"
                },
                manualHintRes = R.string.param_input_hint_aperture,
                onManualInput = { text -> paramsViewModel.applyApertureInput(text) },
                onConfirm = { idx -> paramsViewModel.setApertureByValue(options[idx]) }
            )
        }
    }

    /**
     * 滚轮选择器底部面板（从下向上滑入，Material 默认行为）。
     *
     * @param manualHintRes 非 0 时显示手动输入行（快门 / 光圈 / ISO 支持，
     *                      白平衡等枚举型参数不传）
     * @param onManualInput 手动输入的应用回调：解析 → 就近匹配 → 下发 → 回告生效值
     */
    private fun showWheelPicker(
        title: String,
        param: CameraParam,
        displayValues: List<String>,
        rawValues: List<Int>,
        hint: String? = null,
        manualHintRes: Int = 0,
        onManualInput: (suspend (String) -> ParamApplyResult)? = null,
        onConfirm: (Int) -> Unit
    ) {
        if (displayValues.isEmpty() || displayValues.size != rawValues.size) return
        val dialog = BottomSheetDialog(requireContext())
        val pickerBinding = DialogParamPickerBinding.inflate(layoutInflater)
        dialog.setContentView(pickerBinding.root)

        pickerBinding.tvPickerTitle.text = title
        pickerBinding.tvPickerCurrent.text = buildString {
            append("当前: ").append(param.currentValue.ifBlank { "--" })
            if (!hint.isNullOrBlank()) append('\n').append(hint)
        }

        val picker = pickerBinding.numberPicker
        picker.minValue = 0
        picker.maxValue = displayValues.size - 1
        picker.displayedValues = displayValues.toTypedArray()
        picker.wrapSelectorWheel = false
        // 用 raw 值定位，不再用显示字符串反查（v0.1.2 两处口径不一致导致恒定位到首项）
        picker.value = resolvePickerIndex(rawValues, param.rawValue)

        // —— 手动输入区 ——
        val applyInput = onManualInput
        if (applyInput != null && manualHintRes != 0) {
            pickerBinding.layoutManualInput.visibility = View.VISIBLE
            pickerBinding.tvManualHint.visibility = View.VISIBLE
            pickerBinding.tvManualHint.setText(manualHintRes)
            val submit = {
                val text = pickerBinding.etManualInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    pickerBinding.etManualInput.error = "请输入数值"
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = applyInput(text)
                        if (_binding == null) return@launch
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                        if (result.ok) {
                            // 成功后把滚轮定位到实际生效档位，避免面板与相机口径不一致
                            val idx = resolvePickerIndex(rawValues, currentParamRaw(param.name))
                            if (idx in 0..picker.maxValue) picker.value = idx
                            pickerBinding.etManualInput.text?.clear()
                            pickerBinding.etManualInput.clearFocus()
                            dialog.dismiss()
                        }
                    }
                }
            }
            pickerBinding.btnManualApply.setOnClickListener { submit() }
            pickerBinding.etManualInput.setOnEditorActionListener { _, actionId, _ ->
                // 键盘「完成」等同于点击应用
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    submit(); true
                } else {
                    false
                }
            }
        }

        pickerBinding.btnPickerConfirm.setOnClickListener {
            onConfirm(picker.value)
            dialog.dismiss()
        }
        dialog.show()
    }

    /** 按参数名取当前 raw 值，用于手动输入成功后回正滚轮位置（label 与 setupParamRow 一致） */
    private fun currentParamRaw(name: String): Int = when (name) {
        "光圈" -> paramsViewModel.aperture.value.rawValue
        "快门" -> paramsViewModel.shutterSpeed.value.rawValue
        "ISO" -> paramsViewModel.iso.value.rawValue
        else -> 0
    }

    // ---------------- 快门户 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        // 照片 / 视频模式切换
        binding.btnModePhoto.setOnClickListener { setVideoMode(false) }
        binding.btnModeVideo.setOnClickListener { setVideoMode(true) }

        // 快门：按压内陷 + 闪白反馈
        binding.btnShutter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    onShutterPressed()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    true
                }
                else -> false
            }
        }

        // 定时拍摄
        binding.btnTimer.pressEffect()
        binding.btnTimer.setOnClickListener {
            val options = arrayOf("关闭定时", "2 秒", "5 秒", "10 秒")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("定时拍摄")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> viewModel.cancelTimer()
                        1 -> viewModel.startTimerCapture(2)
                        2 -> viewModel.startTimerCapture(5)
                        3 -> viewModel.startTimerCapture(10)
                    }
                }
                .show()
        }

        // 模块 3 分体按钮：主体单击 = 执行当前动作；右侧箭头 = 弹下拉切换模式
        // （间隔拍摄 / B 门长曝光，勾选当前并记住选择）。
        // 旧「长按弹菜单 + 教程气泡」已删除；全屏监看页「⋮」菜单的 B 门入口
        // 已移除，统一收敛到本按钮。
        binding.btnModeAction.pressEffect()
        binding.btnModeAction.setOnClickListener { executeCurrentAction() }
        binding.btnModeActionArrow.pressEffect()
        binding.btnModeActionArrow.setOnClickListener { showActionModeMenu() }
        renderActionModeLabel()
    }

    /** 按当前选中的动作执行：间隔拍摄 → 既有对话框流程（保持不变）；B 门 → 开始/结束 */
    private fun executeCurrentAction() {
        when (settings.remoteActionMode) {
            AppSettings.ACTION_BULB -> executeBulbAction()
            else -> showIntervalDialog()
        }
    }

    /**
     * B 门动作：曝光中 → 结束（可提前取消定时）；未曝光 → 弹时长选择（定时模式为主）。
     */
    private fun executeBulbAction() {
        if (viewModel.shootingState.value == ShootingState.BULB_EXPOSING) {
            viewLifecycleOwner.lifecycleScope.launch { viewModel.bulbStop() }
            return
        }
        val presets = listOf("手动开始 / 结束" to 0) +
            listOf(15, 30, 60, 120, 300).map {
                "${if (it >= 60) "${it / 60} 分" else "$it 秒"}（自动收门）" to it
            } +
            listOf("切换为「间隔拍摄」" to -1)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("B 门 / 长曝光（上次 ${settings.bulbDurationSeconds} 秒）")
            .setItems(presets.map { it.first }.toTypedArray()) { _, which ->
                val seconds = presets[which].second
                when {
                    seconds == -1 -> switchActionMode(AppSettings.ACTION_INTERVAL)
                    seconds <= 0 -> viewLifecycleOwner.lifecycleScope.launch { viewModel.bulbStart() }
                    else -> {
                        settings.bulbDurationSeconds = seconds
                        viewModel.bulbStartTimed(seconds)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 模式下拉菜单：点主体按钮右侧的箭头弹出，选中即切换模式
     * （按钮文案即时刷新，参数面板随下次执行进入对应流程），当前模式带勾选。
     */
    private fun showActionModeMenu() {
        val popup = PopupMenu(requireContext(), binding.btnModeActionArrow)
        val items = listOf(
            "间隔拍摄" to AppSettings.ACTION_INTERVAL,
            "B 门长曝光" to AppSettings.ACTION_BULB
        )
        items.forEachIndexed { index, (label, mode) ->
            popup.menu.add(0, index + 1, 0, label).isChecked = settings.remoteActionMode == mode
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            items.getOrNull(item.itemId - 1)?.second?.let { switchActionMode(it) }
            true
        }
        popup.show()
    }

    /** 按钮文案随当前动作切换（模块 3：图标/文案随之切换） */
    private fun renderActionModeLabel() {
        binding.btnModeAction.text = if (settings.remoteActionMode == AppSettings.ACTION_BULB) {
            "B门长曝光"
        } else {
            "间隔拍摄"
        }
    }

    private fun showIntervalDialog() {
        val options = arrayOf(
            "间隔 3s × 30 张", "间隔 5s × 50 张", "间隔 10s × 100 张",
            "停止间隔拍摄",
            "切换为「B 门长曝光」"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("间隔拍摄")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.startInterval(IntervalConfig(3000, 30))
                    1 -> viewModel.startInterval(IntervalConfig(5000, 50))
                    2 -> viewModel.startInterval(IntervalConfig(10000, 100))
                    3 -> viewModel.cancelInterval()
                    4 -> switchActionMode(AppSettings.ACTION_BULB)
                }
            }
            .show()
    }

    /** 切换「更多动作」当前动作并即时反馈（按钮弹出的模式下拉 = 显式切换入口） */
    private fun switchActionMode(mode: String) {
        if (mode == settings.remoteActionMode) return
        settings.remoteActionMode = mode
        renderActionModeLabel()
        Toast.makeText(
            requireContext(),
            if (mode == AppSettings.ACTION_BULB) "已切换为「B 门长曝光」"
            else "已切换为「间隔拍摄」",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun onShutterPressed() {
        // PRD D3：B 门曝光中快门按钮语义 = 结束曝光（计时继续，其余参数控件已禁用）
        if (viewModel.shootingState.value == ShootingState.BULB_EXPOSING) {
            viewLifecycleOwner.lifecycleScope.launch { viewModel.bulbStop() }
            return
        }
        if (videoMode) {
            if (!recording) {
                viewModel.startVideo()
            } else {
                viewModel.stopVideo()
            }
            return
        }
        flashScreen()
        viewModel.capture()
    }

    /** 快门触发：画面轻微闪白模拟快门效果 */
    private fun flashScreen() {
        binding.viewFlash.visibility = View.VISIBLE
        binding.viewFlash.alpha = 0f
        binding.viewFlash.animate().alpha(0.75f).setDuration(60)
            .withEndAction {
                if (_binding != null) {
                    binding.viewFlash.animate().alpha(0f).setDuration(180)
                        .withEndAction { if (_binding != null) binding.viewFlash.visibility = View.GONE }
                        .start()
                }
            }.start()
    }

    private fun setVideoMode(video: Boolean) {
        videoMode = video
        binding.btnModePhoto.setBackgroundResource(if (!video) R.drawable.bg_chip_selected else 0)
        binding.btnModeVideo.setBackgroundResource(if (video) R.drawable.bg_chip_selected else 0)
        binding.btnModePhoto.setTextColor(
            resources.getColor(if (!video) R.color.on_primary else R.color.text_primary, null)
        )
        binding.btnModeVideo.setTextColor(
            resources.getColor(if (video) R.color.on_primary else R.color.text_primary, null)
        )
        if (video) {
            binding.ivShutterIcon.setImageResource(R.drawable.ic_record_dot)
            binding.ivShutterIcon.setColorFilter(resources.getColor(R.color.white, null))
            // 视频模式无对应“视频场景”PTP 能力，隐藏该按钮避免空操作；
            // 右侧下拉箭头与主体同属分体按钮，一并隐藏（2026-09-07 反馈）
            binding.btnModeAction.visibility = View.GONE
            binding.btnModeActionArrow.visibility = View.GONE
        } else {
            binding.ivShutterIcon.setImageResource(R.drawable.ic_shutter_white)
            binding.ivShutterIcon.clearColorFilter()
            binding.btnModeAction.visibility = View.VISIBLE
            binding.btnModeActionArrow.visibility = View.VISIBLE
            renderActionModeLabel()
        }
    }

    // ---------------- 状态订阅 ----------------

    private fun observe() {
        // 监看帧渲染：跳过 Nikon 头部，定位 JPEG SOI 再解码
        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.latestFrame.collect { frame ->
                val data = frame.data
                val start = findJpegStart(data)
                val bitmap = BitmapFactory.decodeByteArray(data, start, data.size - start)
                if (bitmap != null) {
                    binding.ivLiveView.setImageBitmap(bitmap)
                    // 优化项 4：直方图与取景画面同一帧刷新，不存在时延差。
                    // 开关关闭时整段跳过——降采样统计本身也不跑，零额外开销。
                    if (settings.histogramEnabled) binding.viewHistogram.setFrame(bitmap)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.liveViewState.collect { state ->
                binding.btnStartLv.visibility =
                    if (state == LiveViewState.RUNNING) View.GONE else View.VISIBLE
                binding.btnStartLv.text =
                    if (state == LiveViewState.ERROR) "重试监看" else "开始监看"
                binding.tvLvConn.text = when (state) {
                    LiveViewState.RUNNING -> "监看中"
                    LiveViewState.STARTING -> "启动中"
                    LiveViewState.ERROR -> "监看异常"
                    else -> "未监看"
                }
                // 优化项 4/修复：监看启停/进出全屏不自动改变直方图状态——
                // 开关只由用户点击改变，停止期间保留最后一帧的分布，恢复监看后自然续上
            }
        }

        // Fix 真机反馈: 监看启动失败时必须把原因显示出来，而不是无反馈
        viewLifecycleOwner.lifecycleScope.launch {
            liveViewViewModel.errorMessage.collect { msg ->
                if (!msg.isNullOrBlank()) {
                    binding.tvRemoteStatus.text = msg
                }
            }
        }

        // 拍摄动作结果提示（录像失败原因等）：旧版静默失败表现为「按了没反应」
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shootingMessage.collect { msg ->
                if (!msg.isNullOrBlank()) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    viewModel.consumeMessage()
                }
            }
        }

        // 顶部悬浮信息：电量 / 剩余可拍
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.batteryLevel.collect { lv ->
                val show = lv >= 0
                binding.ivLvBattery.visibility = if (show) View.VISIBLE else View.GONE
                binding.tvLvBattery.visibility = if (show) View.VISIBLE else View.GONE
                if (show) binding.tvLvBattery.text = "$lv%"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.remainingShots.collect { n ->
                val show = n >= 0
                binding.ivLvShots.visibility = if (show) View.VISIBLE else View.GONE
                binding.tvLvShots.visibility = if (show) View.VISIBLE else View.GONE
                if (show) binding.tvLvShots.text = "$n 张"
            }
        }

        // 拍摄状态 / 计数
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shootingState.collect { state ->
                binding.tvRemoteStatus.text = when (state) {
                    ShootingState.IDLE -> if (videoMode) "视频就绪" else "拍摄就绪"
                    ShootingState.CAPTURING -> "拍摄中"
                    ShootingState.TIMER_COUNTDOWN -> "倒计时"
                    ShootingState.INTERVAL_SHOOTING -> "间隔拍摄中"
                    ShootingState.BULB_EXPOSING -> "B门曝光中"
                    ShootingState.VIDEO_PREPARING -> "正在启动监看…"
                    ShootingState.VIDEO_RECORDING -> "录制中"
                }
                // PRD D3：B 门曝光中禁用参数控件与其它拍摄入口，快门按钮转为「结束曝光」
                val bulbBusy = state == ShootingState.BULB_EXPOSING
                applyBulbLock(bulbBusy)
                val nowRecording = state == ShootingState.VIDEO_RECORDING
                if (nowRecording != recording) {
                    recording = nowRecording
                    if (nowRecording) startRecordTimer() else stopRecordTimer()
                }
            }
        }

        // B 门曝光计时（模块 3）：定时模式显示剩余时长（可提前收门），手动模式显示已用时长
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.bulbExposureTime.collect { ms ->
                if (viewModel.shootingState.value == ShootingState.BULB_EXPOSING) {
                    binding.tvRemoteStatus.text = when (val duration = viewModel.bulbDurationMs.value) {
                        null -> "B门曝光中 · 已用 ${formatClock(ms)}"
                        else -> "B门曝光中 · 剩余 ${formatClock((duration - ms).coerceAtLeast(0))}（可提前收门）"
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.shotCount.collect { n -> binding.tvShotCount.text = "已拍 $n 张" }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.timerCountdown.collect { sec ->
                if (sec > 0) binding.tvRemoteStatus.text = "倒计时 $sec s"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.intervalProgress.collect { p ->
                if (p.totalShots > 0 && p.completedShots < p.totalShots) {
                    binding.tvRemoteStatus.text =
                        if (p.failedShots > 0) "间隔 ${p.completedShots}/${p.totalShots}（失败 ${p.failedShots}）"
                        else "间隔 ${p.completedShots}/${p.totalShots}"
                }
            }
        }

        // 连接状态联动「模式」入口可用性（USB / WiFi PTP 任一通道变化都重新评估）
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.connectionState.collect { updateModeCellState() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            usbPtpManager.usbState.collect { updateModeCellState() }
        }

    }

    /** PRD D3：B 门曝光中禁用参数滚轮入口与其它拍摄动作（半透明 + 点击无效） */
    private fun applyBulbLock(busy: Boolean) {
        bulbBusy = busy
        paramCellContainers.forEach { cell ->
            cell.isEnabled = !busy
            cell.isClickable = !busy
            cell.alpha = if (busy) 0.4f else 1f
        }
        listOf(binding.btnTimer, binding.btnModeAction, binding.btnModePhoto, binding.btnModeVideo)
            .forEach { view ->
                view.isEnabled = !busy
                view.isClickable = !busy
                view.alpha = if (busy) 0.4f else 1f
            }
        // B 门锁定变化影响「模式」入口可用性（连接/切换态不变，仅叠加 busy）
        updateModeCellState()
        if (!busy && _binding != null) {
            renderActionModeLabel()
        }
    }

    private fun startRecordTimer() {        binding.layoutRecordBadge.visibility = View.VISIBLE
        val startAt = System.currentTimeMillis()
        recordTimerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val sec = ((System.currentTimeMillis() - startAt) / 1000).toInt()
                binding.tvRecordTime.text =
                    String.format("%02d:%02d", sec / 60, sec % 60)
                delay(500)
            }
        }
    }

    private fun stopRecordTimer() {
        recordTimerJob?.cancel()
        recordTimerJob = null
        binding.layoutRecordBadge.visibility = View.GONE
    }

    /** 定位 JPEG 起始标记 0xFF 0xD8（兼容 Nikon 1024 字节帧头） */
    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i].toInt() and 0xFF == 0xFF && data[i + 1].toInt() and 0xFF == 0xD8) {
                return i
            }
        }
        return 0
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    /** mm:ss 时钟格式（B 门剩余/已用时长） */
    private fun formatClock(ms: Long): String {
        val sec = ms / 1000
        return String.format("%02d:%02d", sec / 60, sec % 60)
    }
}

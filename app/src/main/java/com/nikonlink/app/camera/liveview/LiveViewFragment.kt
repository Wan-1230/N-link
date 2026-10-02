package com.nikonlink.app.camera.liveview

import com.nikonlink.app.shared.ui.NlFeedback
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.shared.ui.glass.NlGlass
import com.nikonlink.app.shared.ui.glass.UiFlags
import com.nikonlink.app.MainActivity
import com.nikonlink.app.R
import com.nikonlink.app.camera.liveview.panels.LiveViewPanelsController
import com.nikonlink.app.databinding.FragmentLiveviewBinding
import com.nikonlink.app.capture.RemoteShootingViewModel
import com.nikonlink.app.camera.params.CameraParamsViewModel
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

/**
 * 全屏实时取景器（v2.7 重构 · 飓风相机参考范式）。
 *
 * 三层结构：取景层（预览/网格/水平仪/伪彩/对焦框）→ 信息层（顶栏 + 悬浮暗卡）
 * → 控制层（参数快览条 + 双页工具栏 + 快门行），参数选择走底部 sheet 刻度尺。
 * 能力清单与重构前逐条对齐：见交付核对表，Manager/PTP 层零改动。
 */
@AndroidEntryPoint
class LiveViewFragment : Fragment() {

    companion object {
        private const val EXTRA_AUTO_START = "auto_start"
        private const val FOCUS_INDICATOR_SIZE = 64f

        /** 悬浮卡距屏幕顶部的基准距离（顶栏高度 + 一点间隙），实际值再叠加挖孔安全区 */
        private const val CARD_STACK_TOP_DP = 60f

        /** 横屏无操作自动收起控件的延迟 */
        private const val AUTO_HIDE_DELAY_MS = 3500L

        fun newAutoStart(): LiveViewFragment {
            return LiveViewFragment().apply {
                arguments = Bundle().apply { putBoolean(EXTRA_AUTO_START, true) }
            }
        }
    }

    private var _binding: FragmentLiveviewBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LiveViewViewModel by viewModels()
    private val paramsViewModel: CameraParamsViewModel by viewModels()
    private val shootingViewModel: RemoteShootingViewModel by viewModels()

    /**
     * 直方图开关状态。与遥控页共用同一持久化键（AppSettings.histogramEnabled），
     * 全屏与非全屏来回切换不会改变开关状态，两边入口互相同步。
     */
    @Inject lateinit var settings: AppSettings

    /** 相机连接判定（USB / WiFi PTP 任一通道连通即视为可远程控制） */
    @Inject lateinit var connectionManager: ConnectionManager
    @Inject lateinit var usbPtpManager: UsbPtpManager

    /** 模式切换进行中标记：防止重复下发，下发期间入口置灰 */
    private var modeSwitching = false

    /** 伪彩闸门（UiFlags.TONE_TOOLS）是否开着：回退开关，不是运行时偏好 */
    private var toneAvailable = false

    private val toneOn: Boolean get() = settings.pseudoColorEnabled && toneAvailable
    private val waveformOn: Boolean get() = settings.waveformEnabled && toneAvailable

    private var controlsVisible = true
    private var gridVisible = true
    private var levelVisible = false

    private lateinit var panels: LiveViewPanelsController
    private var zoomController: LiveViewZoomController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 顺序契约：applyGlassHud 会取 panels.panelRoots()，控制器必须先于玻璃接线构造
        panels = LiveViewPanelsController(
            binding = binding,
            params = paramsViewModel,
            live = viewModel,
            scope = viewLifecycleOwner.lifecycleScope,
            context = requireContext(),
            zoomProvider = { zoomController },
        )
        panels.setup()
        // 面板与工具栏/参数条互斥：面板展开时让出位置，快门行不受影响
        panels.onOpenStateChanged = { open -> applyPanelMode(open) }
        setupControls()
        setupShutter()
        setupTouchAndScale()
        observeState()
        updateModeEntryState()
        applyWindowInsets()
        applyCompactWidthPolicy()
        binding.viewGridOverlay.setImageSource(binding.ivLiveView)
        paramsViewModel.readAll()
        shootingViewModel.refreshStatus()
        if (arguments?.getBoolean(EXTRA_AUTO_START, false) == true) {
            viewModel.startLiveView()
        }
        // 模块 2：全屏监看页可见期间开启 0x500E 快轮询（机身拨盘切模式 ≤500ms 同步）
        paramsViewModel.startModeWatch()
    }

    override fun onResume() {
        super.onResume()
        paramsViewModel.startModeWatch()
        resetAutoHide()
    }

    override fun onPause() {
        // 离开前台停止快轮询，避免后台仍占用 PTP 命令通道
        paramsViewModel.stopModeWatch()
        _binding?.root?.removeCallbacks(autoHideControls)
        super.onPause()
    }

    // ---------------- 顶栏 / 工具栏 / 快门行 ----------------

    // 液态玻璃 HUD 已整体下线（v2.7.2）：取景画面上叠半透明模糊会跟伪彩 / 网格抢辨识度，
    // 高光折射在纯黑取景上发塑料感。现在全部用 XML drawable 的实体深灰面板
    // （bg_lv_chip / bg_lv_card / bg_lv_sheet），层次靠色阶与 1dp 描边，不再做运行时模糊。
    // XML 里背景已直接写死，这里不再需要任何运行时材质切换。

    private fun setupControls() {
        binding.btnClose.pressEffect()
        binding.btnClose.setOnClickListener { requireActivity().finish() }

        // AF 模式芯片：保留旧版「点击循环 AF-S/AF-C/MF」语义；面板内分段可直选
        binding.btnAfMode.pressEffect()
        binding.btnAfMode.setOnClickListener { paramsViewModel.cycleFocusMode() }

        // 模式标签可点：监看中远程切换拍摄模式（0x500E）
        binding.tvModeTag.pressEffect()
        binding.tvModeTag.setOnClickListener { showModePicker() }

        // 监看会话胶囊 + 居中状态卡主按钮：旧 btnStartStop 的启停语义
        binding.tvLvState.pressEffect()
        binding.tvLvState.setOnClickListener { toggleLiveView() }
        binding.btnStateAction.pressEffect()
        binding.btnStateAction.setOnClickListener { toggleLiveView() }

        binding.btnAlbum.pressEffect()
        binding.btnAlbum.setOnClickListener {
            val intent = Intent(requireContext(), MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_ALBUM)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            requireActivity().finish()
        }

        binding.btnRotate.pressEffect()
        binding.btnRotate.setOnClickListener {
            val isLandscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requireActivity().requestedOrientation =
                if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        // 工具栏第一页：五个面板入口
        listOf(
            binding.toolFocus, binding.toolExposure, binding.toolWb,
            binding.toolMetering, binding.toolMore
        ).forEach { it.pressEffect() }
        binding.toolFocus.setOnClickListener { panels.toggle(binding.panelFocus) }
        binding.toolExposure.setOnClickListener { panels.openExposure() }
        binding.toolWb.setOnClickListener { panels.toggle(binding.panelWb) }
        binding.toolMetering.setOnClickListener { panels.toggle(binding.panelMetering) }
        binding.toolMore.setOnClickListener { panels.toggle(binding.panelMore) }
        binding.toolExposure.setState(null, true)
        binding.toolWb.setState(null, true)
        binding.toolMetering.setState(null, true)
        binding.toolMore.setState(null, true)

        // 工具栏第二页：六个开关态条目
        listOf(
            binding.toolGrid, binding.toolLevel, binding.toolHistogram,
            binding.toolPseudo, binding.toolWaveform, binding.toolDisp
        ).forEach { it.pressEffect() }
        binding.toolGrid.setOnClickListener {
            gridVisible = !gridVisible
            binding.viewGridOverlay.setGridVisible(gridVisible)
            // 伪彩与网格线同在这一层：只看 gridVisible 会让"关掉网格"顺手把伪彩也带走
            binding.viewGridOverlay.visibility =
                if (gridVisible || toneOn) View.VISIBLE else View.GONE
            updateToolStates()
        }
        binding.toolLevel.setOnClickListener {
            levelVisible = !levelVisible
            binding.viewGridOverlay.setLevelVisible(levelVisible)
            updateToolStates()
        }
        binding.toolHistogram.setOnClickListener {
            val enabled = !settings.histogramEnabled
            settings.histogramEnabled = enabled
            applyHistogramToggle(enabled)
            NlFeedback.show(requireContext(), if (enabled) "已开启亮度直方图" else "已关闭亮度直方图")
        }
        // v2.5 FR-13 伪彩/波形：闸门关着时条目根本不出现（与旧顶栏按钮同契约）
        toneAvailable = UiFlags.toneToolsEnabled(requireContext())
        binding.toolPseudo.visibility = if (toneAvailable) View.VISIBLE else View.GONE
        binding.toolWaveform.visibility = if (toneAvailable) View.VISIBLE else View.GONE
        if (toneAvailable) {
            binding.toolPseudo.setOnClickListener {
                val enabled = !settings.pseudoColorEnabled
                settings.pseudoColorEnabled = enabled
                applyPseudoColorToggle(enabled)
                NlFeedback.show(
                    requireContext(),
                    if (enabled) getString(R.string.pseudo_color_note) else "已关闭伪彩"
                )
            }
            binding.toolWaveform.setOnClickListener {
                val enabled = !settings.waveformEnabled
                settings.waveformEnabled = enabled
                applyWaveformToggle(enabled)
                NlFeedback.show(
                    requireContext(),
                    if (enabled) getString(R.string.waveform_note) else "已关闭 RGB 波形"
                )
            }
        }
        // 顶栏「显示辅助」入口：六个显示类开关（网格/水平仪/直方图/伪彩/波形/DISP）收在这里。
        // 原先放在工具栏第二页，翻页时 5 项 / 6 项列宽会跳变，且翻页本身是额外的交互负担。
        binding.btnOverlays.pressEffect()
        binding.btnOverlays.setOnClickListener { panels.toggle(binding.panelOverlays) }

        binding.toolDisp.setOnClickListener { toggleControls() }

        applyHistogramToggle(settings.histogramEnabled)
        applyPseudoColorToggle(settings.pseudoColorEnabled)
        applyWaveformToggle(settings.waveformEnabled)
        updateToolStates()
    }

    private fun toggleLiveView() {
        if (viewModel.liveViewState.value == LiveViewState.RUNNING) {
            viewModel.stopLiveView()
        } else {
            viewModel.startLiveView()
        }
    }

    /** 工具栏角标/置灰态统一刷新（ON 角标 = 功能开启，参考图 067 范式） */
    private fun updateToolStates() {
        binding.toolGrid.setState(if (gridVisible) "ON" else null, gridVisible)
        binding.toolLevel.setState(if (levelVisible) "ON" else null, levelVisible)
        binding.toolHistogram.setState(
            if (settings.histogramEnabled) "ON" else null, settings.histogramEnabled
        )
        binding.toolPseudo.setState(if (toneOn) "ON" else null, toneOn)
        binding.toolWaveform.setState(if (waveformOn) "ON" else null, waveformOn)
        binding.toolDisp.setState(null, controlsVisible)
    }

    private fun applyHistogramToggle(enabled: Boolean) {
        binding.cardHistogram.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) binding.viewHistogram.clear()
        updateToolStatesIfReady()
    }

    private fun applyPseudoColorToggle(enabled: Boolean) {
        val on = enabled && toneAvailable
        binding.viewGridOverlay.setToneVisible(on)
        binding.viewGridOverlay.visibility =
            if (gridVisible || on) View.VISIBLE else View.GONE
        updateToolStatesIfReady()
    }

    private fun applyWaveformToggle(enabled: Boolean) {
        val on = enabled && toneAvailable
        binding.cardWaveform.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) binding.viewWaveform.clear()
        updateToolStatesIfReady()
    }

    /** setupControls 早期调用时工具条目状态刷新（binding 已就绪即可） */
    private fun updateToolStatesIfReady() {
        if (_binding == null) return
        updateToolStates()
    }

    // ---------------- 面板 / 工具栏互斥 ----------------

    /**
     * 面板展开时参数条与工具栏一起让位，快门行留在最底部。
     *
     * 上一版把 panelHost 挂在根 FrameLayout 末尾且 gravity=bottom，z 序高于整个
     * bottomCluster，面板一开就把工具栏和快门按钮整片盖住。现在面板是控制簇内部的
     * 兄弟节点，做的是「占位替换」而不是「覆盖」，快门因此永远可点。
     */
    private fun applyPanelMode(panelOpen: Boolean) {
        val show = if (panelOpen) View.GONE else View.VISIBLE
        binding.paramsBar.visibility = show
        binding.toolbarArea.visibility = show
        // 状态卡与参数面板同占画面中下部，同时可见必然叠住（截图实锤过），面板优先
        if (panelOpen) {
            binding.cardLvState.visibility = View.GONE
        } else {
            applyLvCard(viewModel.liveViewState.value)
        }
    }

    // ---------------- 横屏自动隐藏 ----------------

    /**
     * 横屏专属：监看运行中 3.5 秒无触摸就收起顶栏 + 底簇，画面回到全屏；
     * 碰一下取景画面立即唤回并重新计时。竖屏控件不挡画面主体，保持常显。
     * 与 DISP 手动纯净模式共用 controlsVisible 状态，逻辑互不打架。
     */
    private val autoHideControls = Runnable {
        if (_binding != null && isLandscape() && controlsVisible) toggleControls()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun resetAutoHide() {
        if (_binding == null) return
        binding.root.removeCallbacks(autoHideControls)
        if (isLandscape() && controlsVisible
            && viewModel.liveViewState.value == LiveViewState.RUNNING
        ) {
            binding.root.postDelayed(autoHideControls, AUTO_HIDE_DELAY_MS)
        }
    }

    // ---------------- 系统栏 / 挖孔 / 手势区避让 ----------------

    /**
     * 顶栏与右上角悬浮卡避开状态栏和挖孔，快门行避开手势导航条。
     *
     * 这一页是全屏沉浸（systemBars 已隐藏），但隐藏的是「栏」不是「区域」：
     * 手势导航条仍占着底部一条、挖孔仍切掉屏幕一角。不避让就会出现快门按钮被手势条
     * 压住、横屏时关闭按钮被挖孔吃掉这类只在特定机型复现的问题。
     */
    private fun applyWindowInsets() {
        val root = binding.root
        val safeGap = dp(8f).toInt()
        val hPad = resources.getDimensionPixelSize(R.dimen.lv_bar_h_padding)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            if (_binding == null) return@setOnApplyWindowInsetsListener insets
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = max(cutout.top, bars.top)
            val bottom = max(cutout.bottom, bars.bottom)
            // 横屏时挖孔在短边（左/右），只避让上下会切掉顶栏最外侧的按钮
            val start = max(cutout.left, bars.left)
            val end = max(cutout.right, bars.right)

            binding.topBar.setPadding(
                hPad + start,
                top + safeGap,
                hPad + end,
                binding.topBar.paddingBottom
            )
            // 悬浮卡要再往下让开顶栏自身的高度，否则会压在模式芯片上
            binding.infoCards.setPadding(
                hPad,
                top + dp(CARD_STACK_TOP_DP).toInt(),
                hPad + end,
                binding.infoCards.paddingBottom
            )
            binding.bottomCluster.setPadding(
                start,
                binding.bottomCluster.paddingTop,
                end,
                bottom + safeGap
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 窄屏（&lt;360dp）隐藏帧率/延迟读数，避免顶栏五项横向挤出屏幕。
     * 它是连接质量反馈而不是拍照必需信息，让位是合理的降级。
     */
    private fun applyCompactWidthPolicy() {
        val widthDp = resources.configuration.screenWidthDp
        // 横屏空间全留给画面，帧率读数一律不显示；竖屏再按宽度决定
        val show = !isLandscape() && widthDp >= 360
        binding.tvPerformance.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------- 快门 ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        binding.btnShutter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    flashScreen()
                    shootingViewModel.capture()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun flashScreen() {
        binding.viewFlash.visibility = View.VISIBLE
        binding.viewFlash.alpha = 0f
        binding.viewFlash.animate().alpha(0.65f).setDuration(50)
            .withEndAction {
                if (_binding != null) {
                    binding.viewFlash.animate().alpha(0f).setDuration(160)
                        .withEndAction {
                            if (_binding != null) binding.viewFlash.visibility = View.GONE
                        }.start()
                }
            }.start()
    }

    // ---------------- 触摸对焦与缩放（模块 1：imageMatrix 统一变换） ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAndScale() {
        zoomController = LiveViewZoomController(binding.ivLiveView) { x, y ->
            when {
                // DISP 纯净模式：单击先唤醒控件，不消耗本次点击做对焦
                !controlsVisible -> showControls()
                // 面板开着：第一次点击只收面板，避免误触对焦
                panels.isAnyOpen() -> panels.closeAll()
                else -> handleFocusTap(x, y)
            }
        }

        binding.ivLiveView.setOnTouchListener { _, event ->
            // 任何触摸都刷新横屏自动隐藏计时：正在操作画面 = 可能还要看控件
            resetAutoHide()
            zoomController?.onTouchEvent(event)
            true
        }
    }

    private fun handleFocusTap(tapX: Float, tapY: Float) {
        // 统一换算：fitCenter 黑边剔除 + 双指缩放还原（旧版放大后点击坐标与实际不符）
        val tap = FocusTapMapper.mapToNormalized(binding.ivLiveView, tapX, tapY) ?: return
        viewModel.touchFocus(tap.x, tap.y)
        showFocusIndicator(tapX, tapY)
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        val size = dp(FOCUS_INDICATOR_SIZE)
        binding.viewFocusIndicator.animate().cancel()
        binding.viewFocusIndicator.visibility = View.VISIBLE
        binding.viewFocusIndicator.translationX = x - size / 2f
        binding.viewFocusIndicator.translationY = y - size / 2f
        binding.viewFocusIndicator.alpha = 0f
        binding.viewFocusIndicator.scaleX = 0.6f
        binding.viewFocusIndicator.scaleY = 0.6f
        binding.viewFocusIndicator.animate()
            .alpha(1f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(130)
            .withEndAction {
                if (_binding != null) {
                    binding.viewFocusIndicator.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(90)
                        .start()
                }
            }
            .start()
        binding.viewFocusIndicator.postDelayed({
            if (_binding != null) hideFocusIndicator()
        }, 750)
    }

    private fun hideFocusIndicator() {
        if (_binding == null) return
        binding.viewFocusIndicator.animate().cancel()
        binding.viewFocusIndicator.animate().alpha(0f).setDuration(220)
            .withEndAction {
                if (_binding != null) binding.viewFocusIndicator.visibility = View.GONE
            }.start()
    }

    // ---------------- DISP 纯净模式 ----------------

    private fun toggleControls() {
        if (controlsVisible) {
            controlsVisible = false
            panels.closeAll()
            fadeOut(binding.topBar, binding.bottomCluster, binding.cardLvState)
            fadeOut(binding.cardHistogram, binding.cardWaveform)
            binding.viewGridOverlay.visibility = View.GONE
            hideFocusIndicator()
        } else {
            showControls()
        }
        updateToolStates()
    }

    private fun showControls() {
        controlsVisible = true
        fadeIn(binding.topBar, binding.bottomCluster)
        binding.viewGridOverlay.visibility =
            if (gridVisible || toneOn) View.VISIBLE else View.GONE
        applyHistogramToggle(settings.histogramEnabled)
        applyWaveformToggle(settings.waveformEnabled)
        applyLvCard(viewModel.liveViewState.value)
        resetAutoHide()
    }

    private fun fadeIn(vararg views: View) {
        views.forEach { view ->
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(150).start()
        }
    }

    private fun fadeOut(vararg views: View) {
        views.forEach { view ->
            view.animate().alpha(0f).setDuration(120)
                .withEndAction {
                    if (_binding != null) view.visibility = View.GONE
                }.start()
        }
    }

    // ---------------- 拍摄模式远程切换（0x500E） ----------------

    /**
     * 与遥控页共用同一套档位与回读流（单一数据源）：
     * 1) 列表预选当前模式；2) 下发中禁用重复点击；
     * 3) 下发后等 0x500E 快轮询（≤500ms）回读，3 秒内未切过去则明确提示失败，
     *    UI 仍以 exposureProgram 回读流为准（不落本地假状态）。
     */
    private fun showModePicker() {
        if (modeSwitching) return
        // v1.3.0 需求 4：本机已被验证为「不支持远程切换」→ 直接给结论
        if (paramsViewModel.modeSwitchUnsupported.value) {
            NlFeedback.show(requireContext(), "该机型不支持从 App 切换拍摄模式（相机接受指令但不改变模式），请用机身拨盘", long = true)
            return
        }
        val modes = paramsViewModel.exposureProgramModes
        if (modes.isEmpty()) {
            NlFeedback.show(requireContext(), "暂无可切换的拍摄模式")
            return
        }
        val current = paramsViewModel.exposureProgram.value
        val checkedIndex = modes.indexOfFirst { it.first == current.rawValue }
        val labels = modes.map { it.second }.toTypedArray()
        NlGlass.dialog(requireContext())
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

    /** 下发拍摄模式并做回读校验：写入被拒 / 写入成功但模式未变 都显式反馈 */
    private fun applyExposureProgram(target: Int, label: String) {
        modeSwitching = true
        updateModeEntryState()
        NlFeedback.show(requireContext(), "正在切换拍摄模式…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ok = paramsViewModel.setExposureProgramResult(target)
                if (!ok) {
                    if (_binding == null) return@launch
                    NlFeedback.show(requireContext(), "相机拒绝切换（参数可能已锁定或模式只读），请用机身拨盘调整", long = true)
                    return@launch
                }
                val actual = paramsViewModel.confirmExposureProgram(target)
                if (_binding == null) return@launch
                when {
                    actual == target ->
                        NlFeedback.show(requireContext(), "已切换到 $label")

                    actual != null -> {
                        paramsViewModel.markModeSwitchUnsupported()
                        NlFeedback.show(requireContext(), "相机接受了指令但模式未改变（当前："
                                + paramsViewModel.describeExposureProgram(actual)
                                + "）。该机型不支持从 App 切换拍摄模式，请用机身拨盘调整", long = true)
                    }

                    else -> NlFeedback.show(requireContext(), "无法读取相机当前模式，请确认连接后重试", long = true)
                }
            } finally {
                modeSwitching = false
                updateModeEntryState()
            }
        }
    }

    /** 监看未开始 / 切换中 / 模式列表为空 → 模式入口置灰且点击无效 */
    private fun updateModeEntryState() {
        if (_binding == null) return
        val enabled = viewModel.liveViewState.value == LiveViewState.RUNNING
            && !modeSwitching
            && paramsViewModel.exposureProgramModes.isNotEmpty()
        binding.tvModeTag.isEnabled = enabled
        binding.tvModeTag.isClickable = enabled
        binding.tvModeTag.alpha = if (enabled) 1f else 0.5f
    }

    // ---------------- 状态订阅 ----------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.liveViewState.collect { state ->
                val capsuleText: String
                val capsuleBg: Int
                when (state) {
                    LiveViewState.RUNNING -> {
                        capsuleText = "监看中"; capsuleBg = R.drawable.bg_lv_state_ok
                    }
                    LiveViewState.STARTING -> {
                        capsuleText = "启动中"; capsuleBg = R.drawable.bg_lv_state_bad
                    }
                    LiveViewState.ERROR -> {
                        capsuleText = "重试"; capsuleBg = R.drawable.bg_lv_state_bad
                    }
                    LiveViewState.STOPPED -> {
                        capsuleText = "未监看"; capsuleBg = R.drawable.bg_lv_state_bad
                    }
                }
                binding.tvLvState.text = capsuleText
                binding.tvLvState.setBackgroundResource(capsuleBg)
                applyLvCard(state)
                panels.onLiveViewState(state)
                // 监看启停同步模式入口可用性（未监看时置灰）
                updateModeEntryState()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.errorMessage.collect { message ->
                if (!message.isNullOrBlank()) {
                    NlFeedback.show(requireContext(), message)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.latestFrame.collect { frame ->
                val data = frame.data
                val start = findJpegStart(data)
                val bitmap = BitmapFactory.decodeByteArray(data, start, data.size - start)
                if (bitmap != null) {
                    binding.ivLiveView.setImageBitmap(bitmap)
                    // 模块 1：分辨率变化时重算 fitCenter 基准并保持缩放状态
                    zoomController?.onFrameChanged()
                    binding.viewGridOverlay.invalidate()
                    if (settings.histogramEnabled) binding.viewHistogram.setFrame(bitmap)
                    if (toneOn) binding.viewGridOverlay.setToneFrame(bitmap)
                    if (waveformOn) binding.viewWaveform.setFrame(bitmap)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.fps, viewModel.latency) { fps, latency -> fps to latency }
                .collect { (fps, latency) ->
                    binding.tvPerformance.text = "$fps FPS · $latency ms"
                }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.shutterSpeed.collect { param ->
                binding.tvShutterValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.aperture.collect { param ->
                binding.tvApertureValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.iso.collect { param ->
                binding.tvIsoValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.evCompensation.collect { param ->
                binding.tvEvValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.whiteBalance.collect { param ->
                binding.tvWbValue.text = param.currentValue.ifBlank { "--" }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.focusMode.collect { param ->
                val compact = compactFocus(param.currentValue)
                binding.btnAfMode.text = compact
                // 焦点工具角标：自动系 = A，手动 = M（参考图角标范式）
                binding.toolFocus.setState(if (compact == "MF") "M" else "A", true)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            paramsViewModel.exposureProgram.collect { param ->
                // 从 v2.6.5 移植：单字母胶囊看上去只是个状态标签，没人会去点它，
                // 「拍摄模式（远程切换）」等于不存在。带「模式」二字 + 下拉箭头后可点性一眼可见
                // （与遥控页「联动画面 ▾」同一套读法）。
                binding.tvModeTag.text = "模式 ${param.currentValue.ifBlank { "P" }} ▾"
            }
        }
    }

    /** 居中状态卡：未监看/启动中/异常时给主行动入口；RUNNING 或 DISP 隐藏时不出现 */
    private fun applyLvCard(state: LiveViewState) {
        if (_binding == null) return
        if (state == LiveViewState.RUNNING || !controlsVisible || panels.isAnyOpen()) {
            binding.cardLvState.visibility = View.GONE
            return
        }
        binding.cardLvState.animate().cancel()
        binding.cardLvState.visibility = View.VISIBLE
        // DISP 纯净模式走的是 fadeOut，alpha 被留在 0 后直接 GONE。这里必须显式恢复，
        // 否则「隐藏控件 → 再显示」之后状态卡是一块占着位置但全透明的空白
        binding.cardLvState.alpha = 1f
        when (state) {
            LiveViewState.STOPPED -> {
                binding.tvStateTitle.text = "未开始监看"
                binding.tvStateDesc.text = "开始后可用触摸对焦、缩放与参数回读"
                binding.btnStateAction.text = "开始监看"
                binding.btnStateAction.isEnabled = true
            }
            LiveViewState.STARTING -> {
                binding.tvStateTitle.text = "启动中…"
                binding.tvStateDesc.text = "正在与相机建立监看会话"
                binding.btnStateAction.text = "启动中"
                binding.btnStateAction.isEnabled = false
            }
            else -> {
                binding.tvStateTitle.text = "监看异常"
                binding.tvStateDesc.text = "会话中断，可重试"
                binding.btnStateAction.text = "重试监看"
                binding.btnStateAction.isEnabled = true
            }
        }
        // 上移半个底簇高度：卡片默认居中，横屏底簇一高就压在参数条 / 快门上（截图实锤）
        binding.cardLvState.post {
            if (_binding != null) {
                binding.cardLvState.translationY = -binding.bottomCluster.height / 2f
            }
        }
    }

    private fun compactFocus(full: String): String = when {
        full.contains("AF-C") -> "AF-C"
        full.contains("AF-S") -> "AF-S"
        full.contains("MF") -> "MF"
        else -> full.ifBlank { "AF-S" }
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDestroyView() {
        _binding?.root?.removeCallbacks(autoHideControls)
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewModel.stopLiveView()
        super.onDestroyView()
        _binding = null
    }
}

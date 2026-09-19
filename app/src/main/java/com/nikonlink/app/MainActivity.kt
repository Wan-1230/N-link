package com.nikonlink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.nikonlink.app.databinding.ActivityMainBinding
import com.nikonlink.app.device.DashboardFragment
import com.nikonlink.app.capture.RemoteFragment
import com.nikonlink.app.settings.SettingsFragment
import com.nikonlink.app.camera.gallery.TransferFragment
import com.nikonlink.app.device.service.ConnectionService
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.ui.glass.GlassCoordinator
import com.nikonlink.app.shared.ui.glass.GlassInsetAware
import com.nikonlink.app.shared.ui.glass.GlassRegistry
import com.nikonlink.app.shared.ui.glass.GlassTokens
import com.nikonlink.app.shared.ui.glass.UiFlags
import com.nikonlink.app.shared.ui.glass.applyGlass
import com.nikonlink.app.shared.ui.pressEffect
import com.nikonlink.app.shared.update.UpdateChecker
import com.nikonlink.app.shared.update.UpdatePrompt
import com.nikonlink.app.shared.update.UpdateResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 主 Activity — 黑白极简四 Tab 框架
 * Tab1 设备 / Tab2 相册 / Tab3 拍摄 / Tab4 设置
 * 转场规范：Tab 切换淡入淡出 + 10px 位移，0.25s ease-out
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val TAB_HOME = 0
        const val TAB_ALBUM = 1
        const val TAB_REMOTE = 2
        const val TAB_SETTINGS = 3
        const val EXTRA_OPEN_TAB = "open_tab"

        /** 双击退出的时间窗：2 秒内再次按返回键才退出，超时重新计时 */
        private const val BACK_EXIT_INTERVAL_MS = 2_000L

        /** v2.2 基线：dock 不悬浮时的高度，「经典外观」要逐值还原（AC-12） */
        private const val DOCK_BASELINE_HEIGHT_DP = 60

        /** 无操作多久后收起 dock */
        private const val DOCK_IDLE_HIDE_MS = 3_800L
        private const val DOCK_REVEAL_MS = 260L
        private const val DOCK_HIDE_MS = 200L

        /** 判定"点一下"而非"滑动"的阈值 */
        private const val TAP_MAX_MS = 220L
        private const val TAP_SLOP_DP = 14f

        /** 启动自动检查更新：每进程只跑一次（旋转/重建 Activity 不重跑） */
        @Volatile
        private var autoCheckStarted = false
    }

    private lateinit var binding: ActivityMainBinding

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var eventLogger: AppEventLogger

    /** 上一次按下返回键的时间戳；0 表示当前不在「待退出」窗口内 */
    private var lastBackPressedAt = 0L

    // ---- dock 悬浮与自动隐藏（PRD §4 dock 项、§7.2 insets）----

    /** 状态栏 / 底部导航条的 insets，edge-to-edge 后必须自己算 */
    private var statusInset = 0
    private var navInset = 0

    /** dock 当前是否被隐藏 */
    private var dockHidden = false

    /** 只有可滚动的页面（设备 / 相册 / 设置）才允许自动隐藏 */
    private var dockAutoHideAllowed = false

    /** dock 完全下沉时的位移量（同时用作"遮住内容的高度"） */
    private var dockHideOffset = 0f

    private val hideDockRunnable = Runnable {
        if (dockAutoHideAllowed && UiFlags.glassEnabled(this)) hideDock()
    }

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    private val dashboardFragment = DashboardFragment()
    private val transferFragment = TransferFragment()
    private val remoteFragment = RemoteFragment()
    private val settingsFragment = SettingsFragment()
    private var activeFragment: Fragment = dashboardFragment
    private var currentTab = TAB_HOME

    private lateinit var tabViews: List<View>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabLabels: List<TextView>
    private val tabFragments: List<Fragment>
        get() = listOf(dashboardFragment, transferFragment, remoteFragment, settingsFragment)

    /** 线性图标（未选中） */
    @DrawableRes
    private val iconsLine = listOf(
        R.drawable.ic_nav_home, R.drawable.ic_nav_album,
        R.drawable.ic_nav_camera, R.drawable.ic_nav_settings
    )

    /** 实心图标（选中） */
    @DrawableRes
    private val iconsFilled = listOf(
        R.drawable.ic_nav_home_filled, R.drawable.ic_nav_album_filled,
        R.drawable.ic_nav_camera_filled, R.drawable.ic_nav_settings_filled
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            onPermissionsGranted()
        } else {
            Timber.tag(TAG).w("Some permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 逐窗 edge-to-edge：让玻璃能铺到状态栏与手势条底下，也才有"内容从 dock 底下滚过"。
        // 其他 Activity（预览/监看/打赏）保持 themes.xml 的 opt-out，不在这次范围内。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFragments()
        setupBottomNav()
        applyDock()
        applyWindowInsets()
        UiFlags.observe(this) { applyDock() }
        setupBackExit()
        handleOpenTab(intent)
        checkPermissionsAndStart()
        maybeAutoCheckUpdate()
    }

    override fun onDestroy() {
        UiFlags.unobserve(this)
        super.onDestroy()
    }

    /**
     * 手动分发 insets（edge-to-edge）。
     *
     * 只给根布局挂监听、自己算状态栏/导航条高度，不改 themes.xml 的全局 opt-out，
     * 这样 SupportActivity 等页面不受影响（PRD 风险 R5：去 opt-out 会引发全局回归）。
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            statusInset = bars.top
            navInset = bars.bottom
            applyDock()
            insets
        }
    }

    /**
     * dock 的材质 + 几何 + 悬浮偏移（PRD §4 dock 项、§7.2）。
     *
     * 关玻璃时**逐值还原** v2.2 基线：容器不再有负 margin、根布局自己垫 navInset
     * （等效于原来的 fitsSystemWindows）、恢复不透明底与 1px 分割线 ——
     * 回退闸门要求逐像素相等（AC-12），只换 background 是不够的。
     */
    private fun applyDock() {
        val glass = UiFlags.glassEnabled(this)
        val bar = binding.bottomBar
        val barLp = bar.layoutParams as android.widget.LinearLayout.LayoutParams
        val boxLp = binding.fragmentContainer.layoutParams as android.widget.LinearLayout.LayoutParams

        val dockH = resources.getDimensionPixelSize(R.dimen.glass_dock_height)
        val side = resources.getDimensionPixelSize(R.dimen.glass_dock_inset_h)
        // dock 底部内缩要避开系统手势条，否则悬浮玻璃会被手势区切一半
        val bottom = resources.getDimensionPixelSize(R.dimen.glass_dock_inset_bottom) + navInset

        val statusLp = binding.statusGlass.layoutParams
        statusLp.height = statusInset
        binding.statusGlass.layoutParams = statusLp
        if (glass) {
            binding.statusGlass.applyGlass { GlassTokens.statusStrip(it.context) }
        } else {
            GlassRegistry.unregister(binding.statusGlass)
            binding.statusGlass.setBackgroundColor(ContextCompat.getColor(this, R.color.background))
        }

        if (glass) {
            barLp.height = dockH
            barLp.marginStart = side
            barLp.marginEnd = side
            barLp.bottomMargin = bottom
            bar.layoutParams = barLp
            // 负 margin：容器向下多占 dock 那一条，dock 随后盖在内容之上 → 真悬浮 + 实时模糊
            boxLp.bottomMargin = -(dockH + bottom)
            binding.fragmentContainer.layoutParams = boxLp
            binding.root.updatePadding(bottom = 0)
            // dock 压在内容之上，把容器当背景纹理源 → 真透景 + 真折射
            bar.applyGlass(GlassCoordinator.attach(binding.fragmentContainer)) {
                GlassTokens.dock(it.context)
            }
            // 分割线的分隔职责交给玻璃的四条边信息
            binding.navDivider.visibility = View.GONE
            dockHideOffset = (dockH + bottom + bar.paddingBottom).toFloat()
            // 重涂后按当前隐藏态复位
            animateDock(if (dockHidden) dockHideOffset else 0f, animated = false)
        } else {
            barLp.height = DOCK_BASELINE_HEIGHT_DP.dpToPx()
            barLp.marginStart = 0
            barLp.marginEnd = 0
            barLp.bottomMargin = 0
            bar.layoutParams = barLp
            boxLp.bottomMargin = 0
            binding.fragmentContainer.layoutParams = boxLp
            binding.root.updatePadding(bottom = navInset)
            bar.background = null
            bar.setBackgroundColor(ContextCompat.getColor(this, R.color.nav_background))
            binding.navDivider.visibility = View.VISIBLE
            dockHideOffset = 0f
            animateDock(0f, animated = false)
            dockHidden = false
        }
        // 通知当前页重算底部内衬（内容要能滚出 dock 那一条）
        contentInsetsChanged()
    }

    private fun contentInsetsChanged() {
        tabFragments.forEach { (it as? GlassInsetAware)?.onDockSpaceChanged(dockSpace()) }
    }

    /** dock 实际遮住内容的高度，页面给滚动容器加 paddingBottom 用 */
    fun dockSpace(): Int =
        if (UiFlags.glassEnabled(this)) dockHideOffset.toInt() else 0

    // ---------------- dock 自动隐藏（滚动方向 + 无操作超时）----------------

    /**
     * 页面滚动时上报方向：dy &gt; 0 表示内容向上推进（往下浏览）→ dock 下沉让位；
     * dy &lt; 0 表示往回看 → dock 回弹。对齐 iOS 大标题/工具栏的手势习惯。
     */
    fun reportContentScroll(dy: Int) {
        if (dy == 0 || !dockAutoHideAllowed || !UiFlags.glassEnabled(this)) return
        if (dy > 0) hideDock() else showDock()
    }

    /** 由 [switchToTab] 驱动：拍摄页是固定布局、不可滚动，不允许自动隐藏 */
    private fun setDockAutoHide(allowed: Boolean) {
        dockAutoHideAllowed = allowed && UiFlags.glassEnabled(this)
        if (!dockAutoHideAllowed) showDock()
        restartIdleTimer()
    }

    private fun restartIdleTimer() {
        binding.root.removeCallbacks(hideDockRunnable)
        if (dockAutoHideAllowed) {
            binding.root.postDelayed(hideDockRunnable, DOCK_IDLE_HIDE_MS)
        }
    }

    private fun showDock(animated: Boolean = true) = animateDock(0f, animated)

    private fun hideDock(animated: Boolean = true) {
        if (dockHideOffset > 0f) animateDock(dockHideOffset.toFloat(), animated)
    }

    private fun animateDock(to: Float, animated: Boolean) {
        val bar = binding.bottomBar
        dockHidden = to != 0f
        val motion = animated && UiFlags.motionEnabled(this)
        bar.animate().cancel()
        if (!motion) {
            bar.translationY = to
            return
        }
        bar.animate()
            .translationY(to)
            .setDuration(if (to == 0f) DOCK_REVEAL_MS else DOCK_HIDE_MS)
            // 回弹用轻微过冲（PRD §6.1 SPRING_DOCK 的手感），下沉用减速
            .setInterpolator(if (to == 0f) OvershootInterpolator(3.2f) else DecelerateInterpolator())
            .start()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downAt = SystemClock.uptimeMillis()
                restartIdleTimer()
            }
            MotionEvent.ACTION_UP -> {
                // 只认"点一下"（没有明显位移、且短于滑动判定时间）→ dock 回弹。
                // 不能用 onUserInteraction：它对整个手势的每个 MOVE 都触发，
                // 会把"上滑隐藏"立刻抵消掉。
                if (isTap(ev)) showDock()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isTap(up: MotionEvent): Boolean {
        if (!dockHidden || !dockAutoHideAllowed) return false
        val dt = SystemClock.uptimeMillis() - downAt
        val dist = Math.hypot((up.x - downX).toDouble(), (up.y - downY).toDouble())
        return dt < TAP_MAX_MS &&
            dist < TAP_SLOP_DP.toDouble() * resources.displayMetrics.density.toDouble()
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    /**
     * 双击返回退出：首次按返回键提示「再按一次退出」，2 秒内再次按下才真正退出，
     * 超过间隔重新计时（下一次按键又被当作首次）。
     *
     * 用 [OnBackPressedDispatcher] 而不是重写已废弃的 [onBackPressed]，
     * 后续 Fragment 若注册自己的返回回调也不会互相覆盖。
     */
    private fun setupBackExit() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAt > BACK_EXIT_INTERVAL_MS) {
                    lastBackPressedAt = now
                    Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
                    return
                }
                lastBackPressedAt = 0L
                finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenTab(intent)
    }

    private fun handleOpenTab(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_OPEN_TAB, -1) ?: return
        if (tab in 0..3) switchToTab(tab)
    }

    private fun setupFragments() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragmentContainer, settingsFragment, "settings").hide(settingsFragment)
            add(R.id.fragmentContainer, remoteFragment, "remote").hide(remoteFragment)
            add(R.id.fragmentContainer, transferFragment, "transfer").hide(transferFragment)
            add(R.id.fragmentContainer, dashboardFragment, "dashboard")
        }.commit()
    }

    private fun setupBottomNav() {
        tabViews = listOf(binding.tabDashboard, binding.tabTransfer, binding.tabRemote, binding.tabSettings)
        tabIcons = listOf(binding.iconDashboard, binding.iconTransfer, binding.iconRemote, binding.iconSettings)
        tabLabels = listOf(binding.labelDashboard, binding.labelTransfer, binding.labelRemote, binding.labelSettings)

        tabViews.forEachIndexed { index, view ->
            view.pressEffect()
            view.setOnClickListener { switchToTab(index) }
        }
        applyTabStyle(currentTab)
    }

    /** 供 Fragment 快捷入口跳转 Tab */
    fun switchToTab(index: Int) {
        if (index != currentTab) {
            switchFragment(tabFragments[index])
            currentTab = index
        }
        applyTabStyle(index)
        // 拍摄页是固定布局（监看 + 参数区），没有"滚动让位"这回事 → 不自动隐藏
        setDockAutoHide(index != TAB_REMOTE)
        // 切页时背景内容整体换掉，玻璃纹理要作废重采
        (binding.fragmentContainer.getTag(R.id.glass_coordinator)
            as? GlassCoordinator)?.invalidate()
    }

    /** 选中态：实心图标 + 加粗文字；未选中：线性图标 + 常规字重 */
    private fun applyTabStyle(active: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.nav_active_icon)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive_icon)
        for (i in tabViews.indices) {
            val selected = i == active
            tabIcons[i].setImageResource(if (selected) iconsFilled[i] else iconsLine[i])
            tabIcons[i].setColorFilter(if (selected) activeColor else inactiveColor)
            tabLabels[i].setTextColor(if (selected) activeColor else inactiveColor)
            tabLabels[i].typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun switchFragment(target: Fragment) {
        if (target == activeFragment) return
        supportFragmentManager.beginTransaction().apply {
            // Tab 切换：淡入淡出 + 10px 轻微位移，0.25s ease-out
            setCustomAnimations(R.anim.tab_enter, R.anim.tab_exit)
            hide(activeFragment)
            show(target)
        }.commit()
        activeFragment = target
    }

    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun onPermissionsGranted() {
        requestBatteryOptimizationExemption()
        startConnectionService()
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e: Exception) {
                Timber.tag(TAG).w("Cannot request battery optimization: ${e.message}")
            }
        }
    }

    private fun startConnectionService() {
        ContextCompat.startForegroundService(this, Intent(this, ConnectionService::class.java))
        Timber.tag(TAG).i("ConnectionService started")
    }

    /**
     * 启动自动检查更新（PRD 夸克网盘更新通道 §4.5）。
     *
     * - 每进程一次：onCreate 触发，与主界面加载并行，不阻塞首帧
     * - 有新版本 → 等主界面 resumed 后弹更新弹窗（UpdatePrompt 内置防重入，
     *   与设置页手动检查互不叠加）；已是最新 → 静默；失败 → 只记日志不打扰
     */
    private fun maybeAutoCheckUpdate() {
        if (autoCheckStarted) return
        autoCheckStarted = true
        eventLogger.event("update_check", "action" to "auto_start")
        lifecycleScope.launch {
            val result = updateChecker.check(BuildConfig.VERSION_NAME)
            if (isFinishing || isDestroyed) return@launch
            when (result) {
                is UpdateResult.Available -> {
                    eventLogger.event(
                        "update_check",
                        "action" to "auto_available",
                        "tag" to result.versionLabel
                    )
                    // 检查完成时通常早已 resumed；withResumed 仅防御离线短路等极快返回
                    lifecycle.withResumed {
                        UpdatePrompt.showIfNotShowing(this@MainActivity, eventLogger, result)
                    }
                }

                UpdateResult.UpToDate -> eventLogger.event(
                    "update_check",
                    "action" to "auto_up_to_date",
                    "current" to BuildConfig.VERSION_NAME
                )

                is UpdateResult.Failed -> eventLogger.event(
                    "update_check",
                    "action" to "auto_failed",
                    "reason" to result.reason.name
                )
            }
        }
    }
}

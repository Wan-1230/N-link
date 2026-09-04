package com.nikonlink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFragments()
        setupBottomNav()
        setupBackExit()
        handleOpenTab(intent)
        checkPermissionsAndStart()
        maybeAutoCheckUpdate()
    }

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

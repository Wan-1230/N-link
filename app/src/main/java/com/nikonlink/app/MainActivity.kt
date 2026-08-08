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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.nikonlink.app.databinding.ActivityMainBinding
import com.nikonlink.app.feature.dashboard.DashboardFragment
import com.nikonlink.app.feature.remote.RemoteFragment
import com.nikonlink.app.feature.settings.SettingsFragment
import com.nikonlink.app.feature.transfer.TransferFragment
import com.nikonlink.app.service.ConnectionService
import com.nikonlink.app.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

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
    }

    private lateinit var binding: ActivityMainBinding

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
        handleOpenTab(intent)
        checkPermissionsAndStart()
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
}

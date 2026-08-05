package com.nikonlink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.nikonlink.app.databinding.ActivityMainBinding
import com.nikonlink.app.feature.dashboard.DashboardFragment
import com.nikonlink.app.feature.remote.RemoteFragment
import com.nikonlink.app.feature.settings.SettingsFragment
import com.nikonlink.app.feature.transfer.TransferFragment
import com.nikonlink.app.service.ConnectionService
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * 主 Activity - 底部导航切换模块
 * PRD: 连接 / 传输 / 拍摄 / 参数
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding

    private val dashboardFragment = DashboardFragment()
    private val transferFragment = TransferFragment()
    private val remoteFragment = RemoteFragment()
    private val settingsFragment = SettingsFragment()
    private var activeFragment: Fragment = dashboardFragment

    // 任务7: 底部 Tab 配置
    private var tabWidth = 0f
    private var currentTab = 0
    private lateinit var tabViews: List<View>
    private lateinit var tabIcons: List<ImageView>
    private lateinit var tabLabels: List<TextView>
    private val tabFragments: List<Fragment> get() = listOf(dashboardFragment, transferFragment, remoteFragment, settingsFragment)

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
        checkPermissionsAndStart()
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
            view.setOnClickListener { selectTab(index) }
        }

        // 布局完成后初始化药丸宽度与位置
        binding.bottomBar.post {
            tabWidth = binding.tabRow.width / 4f
            val inset = tabWidth * 0.12f
            val lp = binding.tabPill.layoutParams
            lp.width = (tabWidth - inset * 2).toInt()
            binding.tabPill.layoutParams = lp
            binding.tabPill.translationX = currentTab * tabWidth + inset
            applyTabColors(currentTab)
        }
        applyTabColors(currentTab)
    }

    private fun selectTab(index: Int) {
        if (index == currentTab) return
        switchFragment(tabFragments[index])

        // 任务7: 药丸指示器平滑跟随动画
        val inset = tabWidth * 0.12f
        binding.tabPill.animate()
            .translationX(index * tabWidth + inset)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()

        currentTab = index
        applyTabColors(index)
    }

    /** 任务7: 激活 Tab 用对比色（白）高亮，未激活用灰 */
    private fun applyTabColors(active: Int) {
        val activeColor = ContextCompat.getColor(this, R.color.on_dark_card)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive_icon)
        for (i in tabViews.indices) {
            val color = if (i == active) activeColor else inactiveColor
            tabIcons[i].setColorFilter(color)
            tabLabels[i].setTextColor(color)
        }
    }

    private fun switchFragment(target: Fragment) {
        if (target == activeFragment) return
        supportFragmentManager.beginTransaction().apply {
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

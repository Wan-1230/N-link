package com.nikonlink.app.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.BuildConfig
import com.nikonlink.app.databinding.FragmentSettingsBinding
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Tab4 设置与更多（分组列表式布局 · 黑白开关）
 * 传输设置 / 相机设置 / 通用设置 / 帮助与反馈
 *
 * 功能整改: 移除无实际逻辑的入口（账号/固件更新/语言/RAW处理/GPS同步），
 * 落地画质/保存路径/连接偏好/5GHz优先/自动下载设置项（AppSettings 读写一体），
 * 意见反馈改为系统邮件意图，通用设置新增「导出日志」（AppEventLogger 链路日志）。
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var eventLogger: AppEventLogger

    private val logExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val packed = eventLogger.packLogsForExport() ?: return@registerForActivityResult
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                packed.inputStream().use { it.copyTo(out) }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("导出成功")
                .setMessage("日志已保存，可在查看详情或提交反馈时附上。")
                .setPositiveButton("确定", null)
                .show()
        }.onFailure { e ->
            Timber.w(e, "Export log failed")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("导出失败")
                .setMessage("日志导出失败：${e.message}")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoreState()
        setupRows()
    }

    private fun restoreState() {
        binding.switchAutoDownload.isChecked = settings.autoDownload
        binding.switchWifi5G.isChecked = settings.preferWifi5GHz
        binding.tvQualityValue.text = settings.downloadQuality
        binding.tvSavePathValue.text = settings.savePath
        binding.tvConnPrefValue.text = settings.connectionPreference
        binding.tvThemeValue.text = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            AppCompatDelegate.MODE_NIGHT_YES -> "深色"
            else -> "跟随系统"
        }
        binding.tvCacheValue.text = formatCacheSize(requireContext())
    }

    private fun setupRows() {
        // 传输设置
        binding.rowQuality.pressEffect()
        binding.rowQuality.setOnClickListener {
            singleChoice(
                "下载画质",
                arrayOf(AppSettings.QUALITY_ORIGINAL, AppSettings.QUALITY_COMPRESSED),
                settings.downloadQuality
            ) {
                settings.downloadQuality = it
                binding.tvQualityValue.text = it
            }
        }

        binding.rowSavePath.pressEffect()
        binding.rowSavePath.setOnClickListener {
            singleChoice(
                "默认保存路径",
                arrayOf(AppSettings.SAVE_PATH_DCIM, AppSettings.SAVE_PATH_DOWNLOAD),
                settings.savePath
            ) {
                settings.savePath = it
                binding.tvSavePathValue.text = it
            }
        }

        binding.switchAutoDownload.setOnCheckedChangeListener { _, checked ->
            settings.autoDownload = checked
            eventLogger.event("setting", "key" to "auto_download", "value" to checked)
        }

        // 相机设置
        binding.rowConnPref.pressEffect()
        binding.rowConnPref.setOnClickListener {
            singleChoice(
                "连接偏好",
                arrayOf(AppSettings.CONN_PREF_USB, AppSettings.CONN_PREF_WIFI),
                settings.connectionPreference
            ) {
                settings.connectionPreference = it
                binding.tvConnPrefValue.text = it
            }
        }

        // 5GHz 优先：API≥30 生效；相机 AP 不支持 5GHz 时自动回退 2.4GHz
        binding.switchWifi5G.setOnCheckedChangeListener { _, checked ->
            settings.preferWifi5GHz = checked
            eventLogger.event("setting", "key" to "wifi_band_5g_prefer", "value" to checked)
        }

        // 通用设置
        binding.rowTheme.pressEffect()
        binding.rowTheme.setOnClickListener {
            val options = arrayOf("跟随系统", "浅色", "深色")
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("深浅色模式")
                .setItems(options) { _, which ->
                    AppCompatDelegate.setDefaultNightMode(
                        when (which) {
                            1 -> AppCompatDelegate.MODE_NIGHT_NO
                            2 -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                    binding.tvThemeValue.text = options[which]
                }
                .show()
        }

        binding.rowClearCache.pressEffect()
        binding.rowClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清除缓存")
                .setMessage("将清除缩略图缓存与临时下载文件，不会影响已保存的照片。")
                .setPositiveButton("清除") { _, _ ->
                    runCatching { requireContext().cacheDir.deleteRecursively() }
                    binding.tvCacheValue.text = "0 MB"
                    eventLogger.event("setting", "key" to "clear_cache")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.rowExportLog.pressEffect()
        binding.rowExportLog.setOnClickListener {
            eventLogger.event("setting", "key" to "export_log")
            logExportLauncher.launch("n-link_logs_${System.currentTimeMillis()}.txt")
        }

        binding.rowAbout.pressEffect()
        binding.rowAbout.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("关于 N-Link")
                .setMessage(
                    "版本 ${BuildConfig.VERSION_NAME}\n\n为尼康 Z 系列微单打造的第三方连接应用：" +
                        "永不断联的双通道连接、高速传输、遥控拍摄与实时监看。"
                )
                .setPositiveButton("确定", null)
                .show()
        }

        // 帮助与反馈
        binding.rowTutorial.pressEffect()
        binding.rowTutorial.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("使用教程")
                .setMessage(
                    "1. 在「设备」页连接相机（WiFi / USB）\n" +
                        "2. 在「相册」页浏览并下载照片\n" +
                        "3. 在「拍摄」页遥控快门与监看"
                )
                .setPositiveButton("确定", null)
                .show()
        }
        binding.rowFaq.pressEffect()
        binding.rowFaq.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("常见问题")
                .setMessage(
                    "Q: 连接后相机无反应？\n" +
                        "A: 请确认相机 WiFi 模式为「连接至智能设备」，且手机与相机在同一网络。\n\n" +
                        "Q: USB 连接失败？\n" +
                        "A: 请将相机 USB 模式设为 MTP/PTP，并授权 App 的 USB 访问权限。"
                )
                .setPositiveButton("确定", null)
                .show()
        }
        binding.rowFeedback.pressEffect()
        binding.rowFeedback.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("feedback@nikonlink.app"))
                putExtra(Intent.EXTRA_SUBJECT, "N-Link 意见反馈 v${BuildConfig.VERSION_NAME}")
            }
            runCatching {
                startActivity(Intent.createChooser(intent, "反馈方式"))
            }.onFailure {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("提示")
                    .setMessage("未找到可用的邮件应用，请通过应用商店评价反馈。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun singleChoice(title: String, options: Array<String>, current: String, onPick: (String) -> Unit) {
        val checkedIdx = options.indexOf(current).coerceAtLeast(0)
        var selection = checkedIdx
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, checkedIdx) { _, which -> selection = which }
            .setPositiveButton("确定") { _, _ -> onPick(options[selection]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatCacheSize(context: Context): String {
        val size = context.cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        return when {
            size >= 1024 * 1024 -> "${size / 1024 / 1024} MB"
            size >= 1024 -> "${size / 1024} KB"
            else -> "0 MB"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
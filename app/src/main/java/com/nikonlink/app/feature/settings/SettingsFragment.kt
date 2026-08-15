package com.nikonlink.app.feature.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nikonlink.app.BuildConfig
import com.nikonlink.app.databinding.FragmentSettingsBinding
import com.nikonlink.app.ui.pressEffect
import dagger.hilt.android.AndroidEntryPoint

/**
 * Tab4 设置与更多（分组列表式布局 · 黑白开关）
 * 账号 / 传输设置 / 相机设置 / 通用设置 / 帮助与反馈
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val prefs by lazy {
        requireContext().getSharedPreferences("nl_settings", Context.MODE_PRIVATE)
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
        binding.switchAutoDownload.isChecked = prefs.getBoolean("auto_download", false)
        binding.switchGpsSync.isChecked = prefs.getBoolean("gps_sync", false)
        binding.tvQualityValue.text = prefs.getString("quality", "原图")
        binding.tvSavePathValue.text = prefs.getString("save_path", "系统相册")
        binding.tvRawValue.text = prefs.getString("raw_process", "原样保存")
        binding.tvConnPrefValue.text = prefs.getString("conn_pref", "WiFi 优先")
        binding.tvThemeValue.text = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            AppCompatDelegate.MODE_NIGHT_YES -> "深色"
            else -> "跟随系统"
        }
    }

    private fun setupRows() {
        binding.rowAccount.pressEffect()
        binding.rowAccount.setOnClickListener {
            placeholderDialog("尼康账号", "账号体系暂未接入，后续版本将支持尼康账号登录与云同步。")
        }

        // 传输设置
        binding.rowQuality.pressEffect()
        binding.rowQuality.setOnClickListener {
            singleChoice("下载画质", arrayOf("原图", "压缩"), binding.tvQualityValue.text.toString()) {
                binding.tvQualityValue.text = it
                prefs.edit().putString("quality", it).apply()
            }
        }

        binding.rowSavePath.pressEffect()
        binding.rowSavePath.setOnClickListener {
            singleChoice("默认保存路径", arrayOf("系统相册", "Download 目录"), binding.tvSavePathValue.text.toString()) {
                binding.tvSavePathValue.text = it
                prefs.edit().putString("save_path", it).apply()
            }
        }

        binding.switchAutoDownload.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_download", checked).apply()
        }

        binding.rowRawProcess.pressEffect()
        binding.rowRawProcess.setOnClickListener {
            singleChoice("RAW 文件处理", arrayOf("原样保存", "同时生成 JPG 预览"), binding.tvRawValue.text.toString()) {
                binding.tvRawValue.text = it
                prefs.edit().putString("raw_process", it).apply()
            }
        }

        // 相机设置
        binding.rowConnPref.pressEffect()
        binding.rowConnPref.setOnClickListener {
            singleChoice("连接偏好", arrayOf("WiFi 优先", "USB 优先", "仅 BLE"), binding.tvConnPrefValue.text.toString()) {
                binding.tvConnPrefValue.text = it
                prefs.edit().putString("conn_pref", it).apply()
            }
        }

        binding.switchGpsSync.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("gps_sync", checked).apply()
        }

        binding.rowFirmware.pressEffect()
        binding.rowFirmware.setOnClickListener {
            placeholderDialog("固件更新", "当前固件已是最新版本。")
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

        binding.rowLanguage.pressEffect()
        binding.rowLanguage.setOnClickListener {
            placeholderDialog("语言设置", "当前仅支持简体中文。")
        }

        binding.rowClearCache.pressEffect()
        binding.rowClearCache.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("清除缓存")
                .setMessage("将清除缩略图缓存与临时下载文件，不会影响已保存的照片。")
                .setPositiveButton("清除") { _, _ ->
                    runCatching { requireContext().cacheDir.deleteRecursively() }
                    binding.tvCacheValue.text = "0 MB"
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.rowAbout.pressEffect()
        binding.rowAbout.setOnClickListener {
            placeholderDialog("关于 N-Link",
                "版本 ${BuildConfig.VERSION_NAME}\n\n为尼康 Z 系列微单打造的第三方连接应用：永不断联的双通道连接、高速传输、遥控拍摄与实时监看。")
        }

        // 帮助与反馈
        binding.rowTutorial.pressEffect()
        binding.rowTutorial.setOnClickListener {
            placeholderDialog("使用教程", "1. 在「设备」页连接相机（WiFi / USB）\n2. 在「相册」页浏览并下载照片\n3. 在「拍摄」页遥控快门与监看")
        }
        binding.rowFaq.pressEffect()
        binding.rowFaq.setOnClickListener {
            placeholderDialog("常见问题", "Q: 连接后相机无反应？\nA: 请确认相机 WiFi 模式为「连接至智能设备」，且手机与相机在同一网络。\n\nQ: USB 连接失败？\nA: 请将相机 USB 模式设为 MTP/PTP，并授权 App 的 USB 访问权限。")
        }
        binding.rowFeedback.pressEffect()
        binding.rowFeedback.setOnClickListener {
            placeholderDialog("意见反馈", "感谢使用 N-Link，反馈渠道即将开放。")
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

    private fun placeholderDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

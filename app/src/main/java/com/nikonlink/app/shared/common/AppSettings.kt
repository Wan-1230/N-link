package com.nikonlink.app.shared.common

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用设置统一入口（SharedPreferences 封装）。
 *
 * 原实现中设置页只写不读（下载画质/保存路径/连接偏好等均不生效），
 * 现收敛到单一入口供 SettingsFragment 与各业务层共同读写。
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "nl_settings"

        const val QUALITY_ORIGINAL = "原图"
        const val QUALITY_COMPRESSED = "压缩"

        const val SAVE_PATH_DCIM = "系统相册"
        const val SAVE_PATH_DOWNLOAD = "Download 目录"

        const val CONN_PREF_USB = "USB 优先"
        const val CONN_PREF_WIFI = "WiFi 优先"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 下载画质：原图 / 压缩（仅对 JPG 重编码，RAW 原样） */
    var downloadQuality: String
        get() = prefs.getString("quality", QUALITY_ORIGINAL) ?: QUALITY_ORIGINAL
        set(value) = prefs.edit().putString("quality", value).apply()

    /** 默认保存路径：系统相册（DCIM/N-Link）或 Download 目录（Download/N-Link） */
    var savePath: String
        get() = prefs.getString("save_path", SAVE_PATH_DCIM) ?: SAVE_PATH_DCIM
        set(value) = prefs.edit().putString("save_path", value).apply()

    /** 连接偏好：USB 优先（默认，性能最稳）/ WiFi 优先 */
    var connectionPreference: String
        get() = prefs.getString("conn_pref", CONN_PREF_USB) ?: CONN_PREF_USB
        set(value) = prefs.edit().putString("conn_pref", value).apply()

    /** 5GHz 优先：相机 AP 双频时优先连接 5GHz，失败自动回退 2.4GHz（API ≥ 30 生效） */
    var preferWifi5GHz: Boolean
        get() = prefs.getBoolean("wifi_band_5g_prefer", false)
        set(value) = prefs.edit().putBoolean("wifi_band_5g_prefer", value).apply()

    /** 自动下载：相机拍摄新照片后自动同步到手机（需连接就绪） */
    var autoDownload: Boolean
        get() = prefs.getBoolean("auto_download", false)
        set(value) = prefs.edit().putBoolean("auto_download", value).apply()

    /**
     * 相册排序：true = 最新拍摄在前，false = 最早拍摄在前。
     *
     * 默认 true —— 相机相册此前沿用 PTP 返回的对象句柄顺序（旧→新），
     * 新拍的照片要翻到列表最底部才看得到。
     * 仅作用于「相机照片」；「本地照片」由 MediaStore 按 DATE_ADDED 倒序返回，不参与此设置。
     */
    var albumSortNewestFirst: Boolean
        get() = prefs.getBoolean("album_sort_newest_first", true)
        set(value) = prefs.edit().putBoolean("album_sort_newest_first", value).apply()
}
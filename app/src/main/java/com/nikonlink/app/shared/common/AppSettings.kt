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

        /** 「更多动作」按钮的动作模式（模块 3）：间隔拍摄 / B 门长曝光 */
        const val ACTION_INTERVAL = "interval"
        const val ACTION_BULB = "bulb"

        /** 画面模式：联动（相机屏与手机同时显示） */
        const val DISPLAY_MODE_LINKED = "linked"

        /** 画面模式：遥控（手机监看，相机屏熄并显示「已连接到智能设备」） */
        const val DISPLAY_MODE_REMOTE = "remote"

        /** WiFi STA 子模式：相机与手机连接同一 WiFi（默认） */
        const val STA_MODE_SAME_WIFI = "same_wifi"

        /** WiFi STA 子模式：相机连接手机热点（ZDROP 的 PHONE_HOTSPOT 模式） */
        const val STA_MODE_PHONE_HOTSPOT = "phone_hotspot"
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

    /**
     * 亮度直方图开关（优化项 4）：遥控拍摄界面是否显示实时直方图。
     * 默认关闭——直方图是进阶工具，默认铺开会遮挡取景画面。
     */
    var histogramEnabled: Boolean
        get() = prefs.getBoolean("histogram_enabled", false)
        set(value) = prefs.edit().putBoolean("histogram_enabled", value).apply()

    /** 自动下载：相机拍摄新照片后自动同步到手机（需连接就绪） */
    var autoDownload: Boolean
        get() = prefs.getBoolean("auto_download", false)
        set(value) = prefs.edit().putBoolean("auto_download", value).apply()

    /**
     * 相册排序维度（存 AlbumSortDimension 的枚举名）。
     *
     * 以字符串而非枚举存储，是为了让 shared 层不反向依赖 feature 层的排序模型；
     * 读写转换由相册 ViewModel 负责，取到非法值时回退到默认规则
     * （默认「拍摄时间倒序」，即新拍的排最前）。
     */
    var albumSortDimension: String?
        get() = prefs.getString("album_sort_dimension", null)
        set(value) = prefs.edit().putString("album_sort_dimension", value).apply()

    /** 相册排序方向（存 AlbumSortDirection 的枚举名），取值约定同上 */
    var albumSortDirection: String?
        get() = prefs.getString("album_sort_direction", null)
        set(value) = prefs.edit().putString("album_sort_direction", value).apply()

    /**
     * 标记后自动入队下载原图（F1 可选增强，默认关）。
     * 关闭时标记只做「清单」，进「已标记」栏手动批量收片。
     */
    var markAutoDownload: Boolean
        get() = prefs.getBoolean("mark_auto_download", false)
        set(value) = prefs.edit().putBoolean("mark_auto_download", value).apply()

    /**
     * 分享预览副本保留 GPS（F4，默认关）。
     * 默认剥离 GPS 只保留拍摄参数，避免发图暴露位置；开启后按原样保留全部 EXIF。
     */
    var shareKeepGps: Boolean
        get() = prefs.getBoolean("share_keep_gps", false)
        set(value) = prefs.edit().putBoolean("share_keep_gps", value).apply()

    /**
     * 拍摄页「更多动作」按钮的当前动作（模块 3）。
     * 单击按钮弹出模式下拉切换并执行；记住用户上次选择，默认间隔拍摄。
     */
    var remoteActionMode: String
        get() = prefs.getString("remote_action_mode", ACTION_INTERVAL) ?: ACTION_INTERVAL
        set(value) = prefs.edit().putString("remote_action_mode", value).apply()

    /** B 门默认曝光时长（秒），定时模式使用；记住上次选择 */
    var bulbDurationSeconds: Int
        get() = prefs.getInt("bulb_duration_seconds", 30)
        set(value) = prefs.edit().putInt("bulb_duration_seconds", value).apply()

    /**
     * 画面模式（连接策略，v1.0.2 用户提议的两档化）。
     * 联动为默认；遥控模式 = 0x90C2(1) 机身控制模式，B 门等远程操作的推荐模式。
     */
    var remoteDisplayMode: String
        get() = prefs.getString("remote_display_mode", DISPLAY_MODE_LINKED) ?: DISPLAY_MODE_LINKED
        set(value) = prefs.edit().putString("remote_display_mode", value).apply()

    /** WiFi STA 子模式（v1.0.2 拆分：两种模式的发现/路由策略不同） */
    var staSubMode: String
        get() = prefs.getString("sta_sub_mode", STA_MODE_SAME_WIFI) ?: STA_MODE_SAME_WIFI
        set(value) = prefs.edit().putString("sta_sub_mode", value).apply()
}
package com.nikonlink.app.shared.common

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * AppSettings 读写一致性测试（设置页与业务层共用同一入口）。
 */
class AppSettingsTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        val context = mockk<Context>()
        every { context.getSharedPreferences("nl_settings", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        // relaxed mock 对 String 返回空串而非 null，需显式按“未存储”行为 stub
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getBoolean(any(), any()) } returns false
        // Int 未存储时返回调用方给的默认值（SharedPreferences 真实语义）
        every { prefs.getInt(any(), any()) } answers { secondArg() }
        settings = AppSettings(context)
    }

    @Test
    fun `defaults are stable`() {
        assertEquals(AppSettings.QUALITY_ORIGINAL, settings.downloadQuality)
        assertEquals(AppSettings.SAVE_PATH_DCIM, settings.savePath)
        assertEquals(AppSettings.CONN_PREF_USB, settings.connectionPreference)
        assertFalse(settings.preferWifi5GHz)
        assertFalse(settings.autoDownload)
        // F1/F4 新增开关的默认态：全部关闭（PRD 约定）
        assertFalse(settings.markAutoDownload)
        assertFalse(settings.shareKeepGps)
        // 模块 3：「更多动作」默认间隔拍摄（既有流程不因入口迁移改变），B 门默认 30s
        assertEquals(AppSettings.ACTION_INTERVAL, settings.remoteActionMode)
        assertEquals(30, settings.bulbDurationSeconds)
    }

    @Test
    fun `writes persist through same keys`() {
        settings.downloadQuality = AppSettings.QUALITY_COMPRESSED
        settings.savePath = AppSettings.SAVE_PATH_DOWNLOAD
        settings.connectionPreference = AppSettings.CONN_PREF_WIFI
        settings.preferWifi5GHz = true
        settings.autoDownload = true
        settings.markAutoDownload = true
        settings.shareKeepGps = true
        settings.remoteActionMode = AppSettings.ACTION_BULB
        settings.bulbDurationSeconds = 60

        verify { editor.putString("quality", AppSettings.QUALITY_COMPRESSED) }
        verify { editor.putString("save_path", AppSettings.SAVE_PATH_DOWNLOAD) }
        verify { editor.putString("conn_pref", AppSettings.CONN_PREF_WIFI) }
        verify { editor.putBoolean("wifi_band_5g_prefer", true) }
        verify { editor.putBoolean("auto_download", true) }
        verify { editor.putBoolean("mark_auto_download", true) }
        verify { editor.putBoolean("share_keep_gps", true) }
        verify { editor.putString("remote_action_mode", AppSettings.ACTION_BULB) }
        verify { editor.putInt("bulb_duration_seconds", 60) }
    }
}
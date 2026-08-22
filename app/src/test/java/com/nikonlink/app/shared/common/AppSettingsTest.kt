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
        settings = AppSettings(context)
    }

    @Test
    fun `defaults are stable`() {
        assertEquals(AppSettings.QUALITY_ORIGINAL, settings.downloadQuality)
        assertEquals(AppSettings.SAVE_PATH_DCIM, settings.savePath)
        assertEquals(AppSettings.CONN_PREF_USB, settings.connectionPreference)
        assertFalse(settings.preferWifi5GHz)
        assertFalse(settings.autoDownload)
    }

    @Test
    fun `writes persist through same keys`() {
        settings.downloadQuality = AppSettings.QUALITY_COMPRESSED
        settings.savePath = AppSettings.SAVE_PATH_DOWNLOAD
        settings.connectionPreference = AppSettings.CONN_PREF_WIFI
        settings.preferWifi5GHz = true
        settings.autoDownload = true

        verify { editor.putString("quality", AppSettings.QUALITY_COMPRESSED) }
        verify { editor.putString("save_path", AppSettings.SAVE_PATH_DOWNLOAD) }
        verify { editor.putString("conn_pref", AppSettings.CONN_PREF_WIFI) }
        verify { editor.putBoolean("wifi_band_5g_prefer", true) }
        verify { editor.putBoolean("auto_download", true) }
    }
}
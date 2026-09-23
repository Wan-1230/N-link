package com.nikonlink.app.shared.privacy

import android.content.Context
import android.content.SharedPreferences
import com.nikonlink.app.shared.common.AppSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** FR-08c/d：数据交接授权与诊断字段声明。 */
class DataHandoffTest {

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
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getBoolean(any(), any()) } answers { secondArg() }
        settings = AppSettings(context)
    }

    @Test
    fun `云端解析沿用 v2_4 的旧键，重构不让已授权用户重答一遍`() {
        assertEquals("shutter_cloud_consent", KnownHandoffs.ShutterCloudAnalysis.consentKey())

        settings.shutterCloudConsent = true
        verify { editor.putBoolean("shutter_cloud_consent", true) }

        settings.shutterCloudConsent = false
        verify { editor.putBoolean("shutter_cloud_consent", false) }
    }

    @Test
    fun `未登记的交接用默认键名`() {
        val h = DataHandoff("rules_fetch", "example.com", "机型与 ROM 家族", "取兼容规则", "每次启动")
        assertEquals("handoff_consent_rules_fetch", h.consentKey())
    }

    @Test
    fun `默认一律未授权`() {
        assertFalse(settings.dataHandoffs().first { it.first == KnownHandoffs.ShutterCloudAnalysis }.second)
    }

    @Test
    fun `声明里逐项写清包含什么、不包含什么`() {
        val text = DiagnosticsDisclosure.text(
            listOf(KnownHandoffs.ShutterCloudAnalysis to true)
        )
        assertTrue(text.contains("文件名、对象句柄、字节数"))
        assertTrue(text.contains("IP 地址与端口"))
        assertTrue(text.contains("GPS 坐标、WiFi SSID"))
        assertTrue(text.contains("crash.log"))
        assertTrue(text.contains("shutter_cloud_analysis"))
        assertTrue(text.contains("consent: granted"))
        assertTrue(text.contains("digeeker.com"))
    }

    @Test
    fun `未授权也要如实列出来，而不是假装这条交接不存在`() {
        val text = DiagnosticsDisclosure.text(
            listOf(KnownHandoffs.ShutterCloudAnalysis to false)
        )
        assertTrue(text.contains("consent: not-granted"))
    }

    @Test
    fun `没有任何交接时给出明确的无，而不是留空行`() {
        val text = DiagnosticsDisclosure.text(emptyList())
        assertTrue(text.contains("（无）"))
    }
}

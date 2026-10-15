package com.nikonlink.app.device.ptp

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PtpIdentityStoreTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    @Volatile private var saved: String? = null

    init {
        every { context.getSharedPreferences("ptp_identity", Context.MODE_PRIVATE) } returns prefs
        every { prefs.getString("ptp_ip_client_guid", null) } answers { saved }
        every { prefs.edit() } returns editor
        every { editor.putString("ptp_ip_client_guid", any()) } answers {
            saved = secondArg()
            editor
        }
        every { editor.apply() } just Runs
    }

    @Test
    fun `有效历史GUID保持不变不重新注册身份`() {
        saved = "42".repeat(16)
        assertArrayEquals(ByteArray(16) { 0x42 }, PtpIdentityStore(context).clientGuid)
        verify(exactly = 0) { prefs.edit() }
    }

    @Test
    fun `损坏十六进制缓存可恢复而不是启动崩溃`() {
        saved = "z".repeat(32)
        val identity = PtpIdentityStore(context)
        assertEquals(16, identity.clientGuid.size)
        assertArrayEquals(identity.clientGuid, PtpIdentityStore(context).clientGuid)
    }

    @Test
    fun `并发首次读取只生成一个稳定GUID`() {
        val identity = PtpIdentityStore(context)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map { executor.submit<ByteArray> { identity.clientGuid } }
                .map { it.get(2, TimeUnit.SECONDS) }
            results.forEach { assertArrayEquals(results.first(), it) }
            verify(exactly = 1) { prefs.edit() }
        } finally {
            executor.shutdownNow()
        }
    }
}

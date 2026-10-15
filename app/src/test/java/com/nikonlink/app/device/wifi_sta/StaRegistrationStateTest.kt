package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.content.SharedPreferences
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.connect.ConnectionStateMachine
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class StaRegistrationStateTest {
    @Test
    fun `握手拒绝码三不能新增或覆盖已有注册记录`() {
        for (registered in listOf(false, true)) {
            val prefs = mockk<SharedPreferences>()
            every { prefs.getBoolean(any(), any()) } returns registered
            val context = mockk<Context>()
            every { context.getSharedPreferences(any(), any()) } returns prefs
            val reasons = MutableStateFlow<Int?>(null)
            val ptp = mockk<PtpSessionManager>()
            every { ptp.lastInitFailReason } returns reasons
            val flags = mockk<ConnFlags>()
            every { flags.isEnabled(any()) } returns true
            val manager = ConnectionManager(
                context, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                ptp, mockk(relaxed = true), ConnectionStateMachine(),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), flags,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                ConnectionManager::class.java.getDeclaredField("scope")
                    .apply { isAccessible = true }.set(manager, scope)
                ConnectionManager::class.java.getDeclaredMethod("observeHostRegistrationHint")
                    .apply { isAccessible = true }.invoke(manager)
                reasons.value = PtpConstants.INIT_FAIL_HOST_ALREADY_CONNECTED
                assertEquals(registered, manager.staHostRegistered.value)
                assertEquals(!registered, manager.staRegisterNeeded.value)
                assertTrue(manager.statusMessage.value.contains("不能确认注册成功"))
                verify(exactly = 0) { prefs.edit() }
            } finally {
                scope.cancel()
            }
        }
    }
}

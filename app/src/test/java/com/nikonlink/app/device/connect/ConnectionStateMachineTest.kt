package com.nikonlink.app.device.connect

import com.nikonlink.app.device.model.ConnectionEvent
import com.nikonlink.app.device.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 连接状态机单测 —— 2026-10-12「断连自动处理」修复的回归钉。
 *
 * 修复内容：WiFi 直连设备会话丢失改走 HeartbeatTimeout → ERROR_WAITING_RETRY；
 * ERROR_WAITING_RETRY 收到不可恢复 ErrorOccurred（重试预算耗尽）时必须落
 * DISCONNECTED，否则状态永久卡在「等待重连」，用户只能手动点「断开连接」复位。
 */
class ConnectionStateMachineTest {

    private lateinit var machine: ConnectionStateMachine
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        machine = ConnectionStateMachine()
        // Unconfined：发射后收集者在同一线程立即恢复，断言无需跨线程等待
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        machine.start(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun dispatch(event: ConnectionEvent) {
        machine.dispatch(event)
        // 让收集者协程跑完这一个事件（Unconfined 下通常是同步的，兜底让出时间片）
        Thread.sleep(20)
    }

    @Test
    fun `wifi session loss moves to error-waiting-retry not ble-connected`() {
        dispatch(ConnectionEvent.WifiConnected)
        assertEquals(ConnectionState.FULLY_CONNECTED, machine.state.value)

        // WiFi 直连设备（无 BLE）会话丢失：心跳超时语义，不再假落 BLE_CONNECTED
        dispatch(ConnectionEvent.HeartbeatTimeout)
        assertEquals(ConnectionState.ERROR_WAITING_RETRY, machine.state.value)
    }

    @Test
    fun `non-recoverable error settles to disconnected automatically`() {
        dispatch(ConnectionEvent.WifiConnected)
        dispatch(ConnectionEvent.HeartbeatTimeout)
        assertEquals(ConnectionState.ERROR_WAITING_RETRY, machine.state.value)

        // 重试预算耗尽（不可恢复）：必须自动落 DISCONNECTED，无需用户手动断开
        dispatch(ConnectionEvent.ErrorOccurred("自动重试已达上限", recoverable = false))
        assertEquals(ConnectionState.DISCONNECTED, machine.state.value)
    }

    @Test
    fun `recoverable error keeps waiting-retry state`() {
        dispatch(ConnectionEvent.WifiConnected)
        dispatch(ConnectionEvent.HeartbeatTimeout)

        // 可恢复错误：保持等待重连（回归：行为不因本次修复改变）
        dispatch(ConnectionEvent.ErrorOccurred("暂时连不上", recoverable = true))
        assertEquals(ConnectionState.ERROR_WAITING_RETRY, machine.state.value)
    }

    @Test
    fun `reconnect success after session loss returns to fully connected`() {
        dispatch(ConnectionEvent.WifiConnected)
        dispatch(ConnectionEvent.HeartbeatTimeout)
        assertEquals(ConnectionState.ERROR_WAITING_RETRY, machine.state.value)

        // 自动重连成功：回到已连接（掉线回连功能不受影响）
        dispatch(ConnectionEvent.RetryTriggered)
        assertEquals(ConnectionState.CONNECTING, machine.state.value)
        dispatch(ConnectionEvent.WifiConnected)
        assertEquals(ConnectionState.FULLY_CONNECTED, machine.state.value)
    }

    @Test
    fun `dual channel wifi drop still falls back to ble-connected`() {
        dispatch(ConnectionEvent.WifiConnected)

        // BLE+WiFi 双通道设备 WiFi 掉线的既定行为不变（BLE 仍在线）
        dispatch(ConnectionEvent.WifiDisconnected)
        assertEquals(ConnectionState.BLE_CONNECTED, machine.state.value)
    }
}

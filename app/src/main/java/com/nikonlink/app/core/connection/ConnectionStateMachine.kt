package com.nikonlink.app.core.connection

import com.nikonlink.app.core.common.ConnectionEvent
import com.nikonlink.app.core.common.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接状态机 - N-Link 核心差异化组件
 *
 * PRD 3.3 智能重连策略:
 * - 指数退避：初始 1s，倍增因子 2，上限 30s
 * - 状态流转：Disconnected → Connecting → BLE_Connected → WiFi_Upgrading → Fully_Connected
 * - 永不放弃重连
 *
 * PRD 3.5 断联自动恢复:
 * - WiFi 断开 BLE 正常 → BLE 发送 WiFi 重连指令
 * - BLE 断开 WiFi 正常 → 自动重新扫描 BLE
 * - 双通道均断开 → 全量重连（指数退避）
 */
@Singleton
class ConnectionStateMachine @Inject constructor() {

    companion object {
        private const val TAG = "ConnectionSM"
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val RETRY_MULTIPLIER = 2
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _retryCount = MutableStateFlow(0)
    val retryCount: StateFlow<Int> = _retryCount.asStateFlow()

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 16)

    private var retryJob: Job? = null
    private var currentRetryDelay = INITIAL_RETRY_DELAY_MS
    private var scope: CoroutineScope? = null

    /** 状态变更回调 */
    private val stateListeners = mutableListOf<(ConnectionState, ConnectionState) -> Unit>()

    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            _events.collect { event -> handleEvent(event) }
        }
        Timber.tag(TAG).i("State machine started")
    }

    fun stop() {
        retryJob?.cancel()
        scope = null
        Timber.tag(TAG).i("State machine stopped")
    }

    fun dispatch(event: ConnectionEvent) {
        _events.tryEmit(event)
    }

    fun addStateListener(listener: (oldState: ConnectionState, newState: ConnectionState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (oldState: ConnectionState, newState: ConnectionState) -> Unit) {
        stateListeners.remove(listener)
    }

    private suspend fun handleEvent(event: ConnectionEvent) {
        val oldState = _state.value
        Timber.tag(TAG).d("Event: $event | Current state: $oldState")

        val newState = transition(oldState, event)

        if (newState != oldState) {
            _state.value = newState
            stateListeners.forEach { it(oldState, newState) }
            Timber.tag(TAG).i("State transition: $oldState → $newState")
            onStateEntered(newState)
        }
    }

    /**
     * 状态转换逻辑
     */
    private fun transition(current: ConnectionState, event: ConnectionEvent): ConnectionState {
        return when (current) {
            ConnectionState.DISCONNECTED -> when (event) {
                is ConnectionEvent.StartConnect -> ConnectionState.CONNECTING
                is ConnectionEvent.RetryTriggered -> ConnectionState.CONNECTING
                is ConnectionEvent.BleConnected -> ConnectionState.BLE_CONNECTED
                is ConnectionEvent.WifiConnected -> ConnectionState.FULLY_CONNECTED
                else -> current
            }

            ConnectionState.CONNECTING -> when (event) {
                is ConnectionEvent.BleConnected -> {
                    resetRetry()
                    ConnectionState.BLE_CONNECTED
                }
                is ConnectionEvent.WifiConnected -> {
                    resetRetry()
                    ConnectionState.FULLY_CONNECTED
                }
                is ConnectionEvent.BleDisconnected -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                is ConnectionEvent.ErrorOccurred -> {
                    if (event.recoverable) {
                        scheduleRetry()
                        ConnectionState.ERROR_WAITING_RETRY
                    } else {
                        ConnectionState.DISCONNECTED
                    }
                }
                is ConnectionEvent.CameraShutdown -> ConnectionState.DISCONNECTED
                is ConnectionEvent.HeartbeatTimeout -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                else -> current
            }

            ConnectionState.BLE_CONNECTED -> when (event) {
                is ConnectionEvent.WifiUpgradeRequested -> ConnectionState.WIFI_UPGRADING
                is ConnectionEvent.WifiConnected -> {
                    resetRetry()
                    ConnectionState.FULLY_CONNECTED
                }
                is ConnectionEvent.BleDisconnected -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                is ConnectionEvent.CameraShutdown -> ConnectionState.DISCONNECTED
                is ConnectionEvent.HeartbeatTimeout -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                is ConnectionEvent.ErrorOccurred -> {
                    if (event.recoverable) {
                        current
                    } else {
                        ConnectionState.DISCONNECTED
                    }
                }
                else -> current
            }

            ConnectionState.WIFI_UPGRADING -> when (event) {
                is ConnectionEvent.WifiConnected -> {
                    resetRetry()
                    ConnectionState.FULLY_CONNECTED
                }
                is ConnectionEvent.WifiDisconnected -> ConnectionState.BLE_CONNECTED
                is ConnectionEvent.BleDisconnected -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                is ConnectionEvent.ErrorOccurred -> ConnectionState.BLE_CONNECTED
                is ConnectionEvent.CameraShutdown -> ConnectionState.DISCONNECTED
                else -> current
            }

            ConnectionState.FULLY_CONNECTED -> when (event) {
                is ConnectionEvent.WifiDisconnected -> ConnectionState.BLE_CONNECTED
                is ConnectionEvent.BleDisconnected -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                is ConnectionEvent.CameraShutdown -> ConnectionState.DISCONNECTED
                is ConnectionEvent.HeartbeatTimeout -> {
                    scheduleRetry()
                    ConnectionState.ERROR_WAITING_RETRY
                }
                else -> current
            }

            ConnectionState.ERROR_WAITING_RETRY -> when (event) {
                is ConnectionEvent.RetryTriggered -> ConnectionState.CONNECTING
                is ConnectionEvent.BleConnected -> {
                    resetRetry()
                    ConnectionState.BLE_CONNECTED
                }
                is ConnectionEvent.WifiConnected -> {
                    resetRetry()
                    ConnectionState.FULLY_CONNECTED
                }
                is ConnectionEvent.CameraShutdown -> {
                    resetRetry()
                    ConnectionState.DISCONNECTED
                }
                else -> current
            }
        }
    }

    /**
     * 进入新状态时的副作用
     */
    private suspend fun onStateEntered(state: ConnectionState) {
        when (state) {
            ConnectionState.FULLY_CONNECTED -> {
                resetRetry()
                Timber.tag(TAG).i("✓ Fully connected - dual channel active")
            }
            ConnectionState.BLE_CONNECTED -> {
                Timber.tag(TAG).i("✓ BLE connected - heartbeat active")
            }
            ConnectionState.ERROR_WAITING_RETRY -> {
                Timber.tag(TAG).w("⚠ Waiting for retry in ${currentRetryDelay}ms")
            }
            ConnectionState.DISCONNECTED -> {
                resetRetry()
            }
            else -> {}
        }
    }

    /**
     * PRD 3.3: 指数退避重连调度
     * 初始 1s，倍增因子 2，上限 30s，永不放弃
     */
    private fun scheduleRetry() {
        retryJob?.cancel()
        val delay = currentRetryDelay
        _retryCount.value++

        retryJob = scope?.launch {
            Timber.tag(TAG).d("Scheduling retry #${_retryCount.value} in ${delay}ms")
            delay(delay)
            dispatch(ConnectionEvent.RetryTriggered)
        }

        // 指数退避：翻倍但不超过上限
        currentRetryDelay = (currentRetryDelay * RETRY_MULTIPLIER).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun resetRetry() {
        retryJob?.cancel()
        currentRetryDelay = INITIAL_RETRY_DELAY_MS
        _retryCount.value = 0
    }
}

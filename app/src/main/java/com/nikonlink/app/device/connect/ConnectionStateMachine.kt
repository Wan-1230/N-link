package com.nikonlink.app.device.connect

import com.nikonlink.app.device.model.ConnectionEvent
import com.nikonlink.app.device.model.ConnectionState
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

        /**
         * 一轮连接允许多少次自动重试（v2.2 / PRD §4.2、G6）。
         *
         * 旧策略「永不放弃」在相机热点上是反效果：AOSP 的 network selection 在多次
         * 认证/关联失败后会把该 AP 逐步禁用（DISABLED_WRONG_PASSWORD /
         * DISABLED_NO_INTERNET_ACCESS / PERMANENTLY_DISABLED，Android 10+ 应用侧读不到），
         * 表现就是「前几次还能连，越试越连不上，去设置里忽略网络才好」。
         * 超限后停下来把原因和处置办法交给用户，不再空转。
         */
        private const val RETRY_BUDGET = 8
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

    /**
     * **事件级**回调：任何事件都会通知，**包括不引起状态变化的**（v1.3.2 STA 反馈修复）。
     *
     * 为什么需要：状态回调只在 `newState != oldState` 时触发，而「连不上相机」这类失败
     * 往往在状态还没推进到 CONNECTING 时就发生（例如 WiFi 没连、相机不可达），
     * 于是 ErrorOccurred 被静默丢掉，UI 一直停在旧文案 —— 用户看到的就是
     * 「点了连接没有任何反馈」。失败原因必须与状态变化解耦。
     */
    private val eventListeners = mutableListOf<(ConnectionEvent) -> Unit>()

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

    /** 注册事件级监听（失败原因可见化的入口，见 [eventListeners] 说明） */
    fun addEventListener(listener: (ConnectionEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (ConnectionEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    private suspend fun handleEvent(event: ConnectionEvent) {
        val oldState = _state.value
        Timber.tag(TAG).d("Event: $event | Current state: $oldState")

        // 事件级通知先发：即使下面不产生状态迁移，UI 也能拿到失败原因
        eventListeners.forEach { listener ->
            runCatching { listener(event) }
                .onFailure { Timber.tag(TAG).w(it, "Event listener failed: $event") }
        }

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
    /** 重试预算是否生效（由 ConnectionManager 按 ConnFlags 设定，关则退回无限退避）。 */
    @Volatile
    var budgetEnabled: Boolean = true

    /** 预算耗尽时回调（写漏斗 + 换文案），在独立线程上调用。 */
    @Volatile
    var onBudgetExhausted: ((Int) -> Unit)? = null

    private fun scheduleRetry() {
        retryJob?.cancel()
        val delay = currentRetryDelay
        _retryCount.value++

        if (budgetEnabled && _retryCount.value > RETRY_BUDGET) {
            Timber.tag(TAG).w("Retry budget exhausted after $RETRY_BUDGET attempts, stopping auto-retry")
            onBudgetExhausted?.invoke(RETRY_BUDGET)
            dispatch(
                ConnectionEvent.ErrorOccurred(
                    "自动重试已达上限（$RETRY_BUDGET 次）。继续重试会被系统拉黑这个热点，" +
                        "请先在系统 WLAN 设置里「忽略」相机网络，再按指引重新连接",
                    recoverable = false
                )
            )
            return
        }

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

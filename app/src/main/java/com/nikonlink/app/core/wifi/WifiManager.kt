package com.nikonlink.app.core.wifi

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.core.ble.WifiCredential
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WiFi 通道管理器
 *
 * PRD 3.1: WiFi 通道（高速数据）
 * - 照片传输
 * - Live View 视频流
 * - 遥控指令
 * - 参数读写
 *
 * PRD 3.5: 断联自动恢复
 * - WiFi 断开，BLE 正常 → 通过 BLE 发送 WiFi 重连指令，3s 内恢复
 * - 优先使用 Infrastructure 模式（相机作为 AP）
 */
@Singleton
class WifiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WifiManager"
        private const val CONNECT_TIMEOUT_MS = 15000L
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)

    private var activeNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var globalNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var scope: CoroutineScope? = null

    private val _wifiState = MutableStateFlow(WifiChannelState.DISCONNECTED)
    val wifiState: StateFlow<WifiChannelState> = _wifiState.asStateFlow()

    private val _networkAvailable = MutableSharedFlow<Network>(extraBufferCapacity = 1)
    val networkAvailable: SharedFlow<Network> = _networkAvailable.asSharedFlow()

    private val _networkLost = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val networkLost: SharedFlow<Unit> = _networkLost.asSharedFlow()

    private val _wifiRssi = MutableStateFlow(0)
    val wifiRssi: StateFlow<Int> = _wifiRssi.asStateFlow()

    private var currentCredential: WifiCredential? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        registerNetworkCallback()
        Timber.tag(TAG).i("WifiManager started")
    }

    fun stop() {
        unregisterNetworkCallback()
        disconnect()
        scope = null
        Timber.tag(TAG).i("WifiManager stopped")
    }

    /**
     * 连接到相机 WiFi AP
     * PRD 7.1: 优先使用 Infrastructure 模式（相机作为 AP）
     */
    suspend fun connectToCamera(credential: WifiCredential): Boolean {
        if (isConnected()) return true
        currentCredential = credential
        _wifiState.value = WifiChannelState.CONNECTING

        return withContext(Dispatchers.Main) {
            try {
                val builder = WifiNetworkSpecifier.Builder().setSsid(credential.ssid)
                if (credential.password.isNotEmpty()) {
                    builder.setWpa2Passphrase(credential.password)
                }
                val specifier = builder.build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()

                val result = CompletableDeferred<Boolean>()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (result.isCompleted) return
                        Timber.tag(TAG).i("WiFi network available: $network")
                        activeNetwork = network
                        // 将此网络绑定为进程默认（用于 PTP/IP 通信）
                        connectivityManager.bindProcessToNetwork(network)
                        _wifiState.value = WifiChannelState.CONNECTED
                        _networkAvailable.tryEmit(network)
                        result.complete(true)
                    }

                    override fun onUnavailable() {
                        if (result.isCompleted) return
                        Timber.tag(TAG).w("WiFi network unavailable")
                        _wifiState.value = WifiChannelState.DISCONNECTED
                        result.complete(false)
                    }

                    override fun onLost(network: Network) {
                        Timber.tag(TAG).w("WiFi network lost")
                        if (network == activeNetwork) {
                            activeNetwork = null
                            _wifiState.value = WifiChannelState.DISCONNECTED
                            _networkLost.tryEmit(Unit)
                        }
                    }
                }

                connectivityManager.requestNetwork(request, callback)
                this@WifiManager.networkCallback = callback

                // 等待连接结果（带超时）
                val outcome = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    result.await()
                } ?: run {
                    connectivityManager.unregisterNetworkCallback(callback)
                    if (this@WifiManager.networkCallback === callback) {
                        this@WifiManager.networkCallback = null
                    }
                    _wifiState.value = WifiChannelState.DISCONNECTED
                    false
                }
                outcome
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "WiFi connection failed")
                _wifiState.value = WifiChannelState.DISCONNECTED
                false
            }
        }
    }

    /**
     * 断开 WiFi 连接
     */
    fun disconnect() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        connectivityManager.bindProcessToNetwork(null)
        activeNetwork = null
        _wifiState.value = WifiChannelState.DISCONNECTED
    }

    /**
     * 尝试重连 WiFi
     * PRD 3.5: WiFi 断开时自动重连
     */
    suspend fun reconnect(): Boolean {
        if (isConnected()) return true
        val credential = currentCredential ?: return false
        Timber.tag(TAG).i("Attempting WiFi reconnect to ${credential.ssid}")
        return connectToCamera(credential)
    }

    fun isConnected(): Boolean = _wifiState.value == WifiChannelState.CONNECTED

    fun getActiveNetwork(): Network? = activeNetwork

    /**
     * 注册全局网络状态监听
     * PRD 3.3: 网络感知 - 监听 ConnectivityManager，WiFi 恢复时立即触发重连
     */
    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Timber.tag(TAG).d("WiFi available (global)")
            }

            override fun onLost(network: Network) {
                if (network == activeNetwork) {
                    Timber.tag(TAG).w("Active WiFi lost")
                    _wifiState.value = WifiChannelState.DISCONNECTED
                    _networkLost.tryEmit(Unit)
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val signalStrength = capabilities.signalStrength
                if (signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                    _wifiRssi.value = signalStrength
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            globalNetworkCallback = callback
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to register network callback")
        }
    }

    private fun unregisterNetworkCallback() {
        globalNetworkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
        globalNetworkCallback = null
    }
}

/**
 * WiFi 通道子状态
 */
enum class WifiChannelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

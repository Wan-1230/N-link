package com.nikonlink.app.device.wifi_ap

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.device.ble.WifiCredential
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
        private const val SCAN_SETTLE_MS = 1500L

        /** 将 WiFi 频率（MHz）映射为可读频段标签。 */
        fun bandLabel(frequencyMhz: Int): String = when {
            frequencyMhz in 2400..2495 -> "2.4 GHz"
            frequencyMhz in 4900..5895 -> "5 GHz"
            frequencyMhz >= 5925 -> "6 GHz"
            else -> "未知"
        }
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

    private val _wifiFrequencyMhz = MutableStateFlow(0)
    val wifiFrequencyMhz: StateFlow<Int> = _wifiFrequencyMhz.asStateFlow()

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
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
                        scope?.launch { refreshWifiBand() }
                        result.complete(true)
                    }

                    override fun onUnavailable() {
                        if (result.isCompleted) return
                        Timber.tag(TAG).w("WiFi network unavailable")
                        _wifiState.value = WifiChannelState.DISCONNECTED
                        _wifiFrequencyMhz.value = 0
                        result.complete(false)
                    }

                    override fun onLost(network: Network) {
                        Timber.tag(TAG).w("WiFi network lost")
                        if (network == activeNetwork) {
                            activeNetwork = null
                            _wifiState.value = WifiChannelState.DISCONNECTED
                            _wifiFrequencyMhz.value = 0
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
                    _wifiFrequencyMhz.value = 0
                    false
                }
                outcome
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "WiFi connection failed")
                _wifiState.value = WifiChannelState.DISCONNECTED
                _wifiFrequencyMhz.value = 0
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
        _wifiFrequencyMhz.value = 0
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
     * 刷新当前 WiFi 连接的频段（尽力而为）。
     * 先读主 WiFi 连接的 frequency（STA 模式），失败后按已知 SSID 扫描匹配（AP 模式）。
     * 无法确定时保持 0，UI 显示「未知」。
     */
    suspend fun refreshWifiBand() {
        if (!isConnected()) {
            _wifiFrequencyMhz.value = 0
            return
        }

        val connectionFreq = withContext(Dispatchers.IO) {
            runCatching { wifiManager.connectionInfo?.frequency ?: 0 }.getOrDefault(0)
        }
        if (connectionFreq > 0) {
            applyFrequency(connectionFreq)
            return
        }

        val targetSsid = currentCredential?.ssid.orEmpty()
        if (targetSsid.isEmpty()) {
            _wifiFrequencyMhz.value = 0
            return
        }

        val scanFreq = withContext(Dispatchers.IO) {
            runCatching { wifiManager.startScan() }
            delay(SCAN_SETTLE_MS)
            runCatching {
                wifiManager.scanResults
                    .firstOrNull { it.SSID == targetSsid && it.frequency > 0 }
                    ?.frequency ?: 0
            }.getOrDefault(0)
        }
        applyFrequency(scanFreq)
    }

    private fun applyFrequency(frequencyMhz: Int) {
        _wifiFrequencyMhz.value = frequencyMhz
        Timber.tag(TAG).i("WiFi band resolved: %s (%d MHz)", bandLabel(frequencyMhz), frequencyMhz)
    }

    /**
     * 只读地返回当前 WiFi 网络，不改变进程默认网络。
     * STA 扫描阶段需要把 mDNS/探测 Socket 显式绑定到 WiFi，
     * 但不应在纯扫描时副作用式地修改 bindProcessToNetwork。
     */
    fun currentWifiNetwork(): Network? {
        return connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getNetworkCapabilities(candidate)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * STA 模式下手机和相机处于同一路由器 WiFi。
     * 把进程默认网络绑定到当前 WiFi，确保 PTP/IP Socket 不会走到蜂窝网。
     */
    fun bindToActiveWifi(): Network? {
        val network = connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getNetworkCapabilities(candidate)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        activeNetwork = network
        connectivityManager.bindProcessToNetwork(network)
        Timber.tag(TAG).i("Bound process to active WiFi network $network")
        return network
    }

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

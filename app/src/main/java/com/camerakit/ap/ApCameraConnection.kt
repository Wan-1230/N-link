package com.camerakit.ap

import android.content.Context
import com.camerakit.api.CameraConnection
import com.camerakit.api.CameraEndpoint
import com.camerakit.api.CameraError
import com.camerakit.api.CameraEvent
import com.camerakit.api.CameraSession
import com.camerakit.api.CameraState
import com.camerakit.api.ConnectionMethod
import com.camerakit.discovery.ApEndpointResolver
import com.camerakit.net.NetworkBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AP 连接（整体替换版）：网络绑定 + [ApPtpClient] 引擎（开源 PTP/IP 实现整体移植）。
 *
 * 连接编排忠实参考实现：每次握手随机 GUID、事件通道无额外等待、
 * OpenSession → GetDeviceInfo 即连成。失败**直接自动重试**（无冷却门）。
 * STA / USB 不经由本类，互不影响。
 */
class ApCameraConnection internal constructor(
    private val context: Context,
    private val log: (String) -> Unit = {},
    /**
     * 建链前的套接字调优（闸门 `ConnFlags.AP_LV_SOCKET_TUNING`）。
     * 只影响 TCP 缓冲/keepAlive 三个 socket 选项，不碰握手、GUID、重试与网络绑定。
     */
    tuneSockets: Boolean = true,
    /** 取帧零拷贝（闸门 `ConnFlags.AP_LV_ZEROCOPY`）。 */
    zeroCopyFrames: Boolean = true,
) : CameraConnection {

    private companion object {
        const val CONNECT_ATTEMPTS = 3
        const val RETRY_PAUSE_MS = 1_000L
        const val NET_STABLE_WAIT_MS = 1_200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectMutex = Mutex()

    val engine: ApEngineBridge = ApEngineBridge(
        ApPtpClient(
            socketFactory = { host -> currentSocketFactory(host) },
            tuneSockets = tuneSockets,
            zeroCopyFrames = zeroCopyFrames,
        ),
        log,
    )

    private val _state = MutableStateFlow<CameraState>(CameraState.Disconnected)
    override val state: StateFlow<CameraState> = _state

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CameraEvent> = _events

    private var networkBinder: NetworkBinder? = null
    private var socketFactoryProvider: ((String) -> java.net.Socket)? = null
    private var currentEndpoint: CameraEndpoint? = null
    private var lostWatcher: kotlinx.coroutines.Job? = null

    private fun currentSocketFactory(host: String): java.net.Socket =
        socketFactoryProvider?.invoke(host) ?: java.net.Socket()

    // ---------------- 发现 ----------------

    override suspend fun discover(method: ConnectionMethod): List<CameraEndpoint> {
        val ep = when (method) {
            is ConnectionMethod.WifiAp -> CameraEndpoint("Nikon AP", method.host, method.port, "ap")
            is ConnectionMethod.WifiSta -> method.endpoint ?: CameraEndpoint("Nikon AP", "192.168.1.1", 15740, "ap")
            is ConnectionMethod.Usb -> throw CameraError.NoCameraFound("AP 引擎不处理 USB")
        }
        return listOf(ep)
    }

    // ---------------- 连接（失败直接自动重试） ----------------

    override suspend fun connect(
        method: ConnectionMethod,
        endpoint: CameraEndpoint?,
        autoPair: Boolean,
    ) = connectMutex.withLock {
        disconnectInternal(emitState = false)
        val ep = endpoint ?: CameraEndpoint(
            "Nikon Z Camera (AP)",
            if (method is ConnectionMethod.WifiAp) method.host else "192.168.1.1",
            if (method is ConnectionMethod.WifiAp) method.port else 15740,
            "ap",
        )
        currentEndpoint = ep

        _state.value = CameraState.Connecting("等待到相机的网络路由…")
        if (!ApEndpointResolver.awaitLocalRoute(ep.host, totalTimeoutMs = 8_000)) {
            if (!ApEndpointResolver.awaitLocalRoute(ep.host, totalTimeoutMs = 2_500)) {
                throw CameraError.NoCameraFound(
                    "未发现到 ${ep.host} 的网络路由。请确认手机已连接相机 Wi-Fi（AP 模式）后重试。",
                )
            }
        }

        val binder = NetworkBinder(context, ep.host, log).also { networkBinder = it }
        val factory = binder.cameraSocketFactory()
        socketFactoryProvider = { factory.createSocket() }

        // 网络绑定生效等待（Android 侧，非相机协议）
        _state.value = CameraState.Connecting("等待网络稳定…")
        delay(NET_STABLE_WAIT_MS)

        var lastError: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            _state.value = CameraState.Connecting("PTP/IP 连接（${attempt + 1}/$CONNECT_ATTEMPTS）")
            val result = engine.client.connect(ep.host, ep.port)
            result.onSuccess {
                onConnected(ep)
                return@withLock
            }.onFailure { e ->
                lastError = e
                log("AP 连接失败（第 ${attempt + 1}/$CONNECT_ATTEMPTS 次）：${e.message}；直接重试")
                if (attempt < CONNECT_ATTEMPTS - 1) {
                    // 清场重建绑定：旧 Network 被系统回收后复用会 EPERM
                    engine.disconnect()
                    networkBinder?.release()
                    networkBinder = null
                    delay(RETRY_PAUSE_MS)
                    val nb = NetworkBinder(context, ep.host, log).also { networkBinder = it }
                    val f = nb.cameraSocketFactory()
                    socketFactoryProvider = { f.createSocket() }
                }
            }
        }
        val err = CameraError.TransportLost(
            "AP 连接失败：${lastError?.message ?: "无响应"}",
            lastError,
        )
        _state.value = CameraState.Failed(err)
        throw err
    }

    private suspend fun onConnected(ep: CameraEndpoint) {
        val info = engine.getDeviceInfo()
        _state.value = CameraState.Connected(ep, info)
        log("已连接 ${info.model} via $ep")
        startLostWatcher()
    }

    /** 引擎断链观察：connected 翻 false 即上报传输丢失（参考实现断链语义）。 */
    private fun startLostWatcher() {
        lostWatcher?.cancel()
        lostWatcher = scope.launch {
            engine.connected.collect { connected ->
                if (!connected && _state.value is CameraState.Connected) {
                    val reason = engine.lastError ?: "相机连接已断开"
                    _events.tryEmit(CameraEvent.TransportLost(reason))
                    _state.value = CameraState.Failed(CameraError.TransportLost(reason))
                }
            }
        }
    }

    // ---------------- 会话 ----------------

    override fun session(): CameraSession? = if (engine.isConnected) engine else null

    override fun requireSession(): CameraSession =
        if (engine.isConnected) engine else throw CameraError.NotConnected()

    /** AP 引擎桥（监看 / 遥控 / 参数 / 相册的命令面）。 */
    fun bridge(): ApEngineBridge? = if (engine.isConnected) engine else null

    // ---------------- 主机配对（PC 主机注册） ----------------

    override suspend fun pair() {
        val ep = currentEndpoint ?: throw CameraError.NotConnected()
        _state.value = CameraState.Connecting("建立 PC 主机注册会话")
        _events.tryEmit(CameraEvent.PairingProgress("正在建立 PC 主机注册会话…"))
        val prepare = runCatching {
            val r = engine.rawTransact(0x952B)
            if (!r.isOk) throw CameraError.PairingRejected("注册预备被拒（0x${r.code.toString(16)}）")
            decodeHostRegPrepare(r.dataIn)
        }.getOrElse {
            throw CameraError.PairingRejected("主机注册握手被拒绝：${it.message}")
        }
        _state.value = CameraState.AwaitingHostConfirm(prepare)
        _events.tryEmit(CameraEvent.PairingProgress("请在相机的主机确认界面确认本机。"))
        delay(8_500)
        var lastError: Exception? = null
        repeat(3) { attempt ->
            val r = runCatching { engine.rawTransact(0x935A, listOf(0x2001)) }.getOrNull()
            if (r?.isOk == true) {
                _events.tryEmit(CameraEvent.PairingProgress("PC 主机注册完成"))
                _state.value = CameraState.Connected(ep, engine.getDeviceInfo())
                return
            }
            lastError = IllegalStateException("确认响应 0x${r?.code?.toString(16) ?: "无响应"}")
            if (attempt < 2) delay(1_500)
        }
        val err = CameraError.PairingRejected("相机未确认主机注册：${lastError?.message}")
        _state.value = CameraState.Failed(err)
        throw err
    }

    private fun decodeHostRegPrepare(data: ByteArray?): String {
        if (data == null || data.size <= 8) return "空"
        val body = data.copyOfRange(8, data.size)
        val utf16 = buildString {
            var off = 0
            while (off + 1 < body.size) {
                val ch = ((body[off].toInt() and 0xFF) or ((body[off + 1].toInt() and 0xFF) shl 8)).toChar()
                if (ch == 0.toChar()) break
                append(ch)
                off += 2
            }
        }
        val printable = utf16.count { it.code in 0x20..0x7E || it.code > 0xA0 }
        return if (utf16.isNotEmpty() && printable >= utf16.length * 0.7) {
            utf16
        } else {
            body.joinToString("") { "%02x".format(it) }.take(64).ifEmpty { "空" }
        }
    }

    override suspend fun registerStaHost(endpoint: CameraEndpoint?) {
        throw CameraError.PairingRejected("STA 主机注册请走 STA 链路（AP 引擎不处理）")
    }

    // ---------------- 断开 ----------------

    override fun disconnect() {
        disconnectInternal(emitState = true)
    }

    private fun disconnectInternal(emitState: Boolean) {
        lostWatcher?.cancel()
        lostWatcher = null
        engine.disconnect()
        networkBinder?.release()
        networkBinder = null
        socketFactoryProvider = null
        currentEndpoint = null
        if (emitState) _state.value = CameraState.Disconnected
    }
}

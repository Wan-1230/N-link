package com.camerakit.demo

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.camerakit.api.CameraConnection
import com.camerakit.api.CameraError
import com.camerakit.api.CameraEvent
import com.camerakit.api.CameraState
import com.camerakit.api.ConnectionMethod
import com.camerakit.client.CameraConnectionFactory
import com.camerakit.usb.UsbCameraDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 精简测试页：状态卡 + 三通道连接按钮 + 测试操作 + 日志。
 * 全部操作走 [CameraConnection] 公共 API。
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var client: CameraConnection
    private lateinit var stateLabel: TextView
    private lateinit var infoLabel: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先初始化 UI 再创建 client：client 构造期会同步回调日志
        buildUi()
        client = CameraConnectionFactory.create(this) { msg -> runOnUiThread { log(msg) } }
        registerUsbReceiver()
        observeClient()
        log("启动完成 · 三通道：USB / Wi-Fi AP / Wi-Fi STA")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbReceiver) }
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(dp(16), dp(28), dp(16), dp(12))
        }

        root.addView(TextView(this).apply {
            text = "Nikon CameraKit"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
        })
        root.addView(TextView(this).apply {
            text = "相机连接模块 · 真机验证"
            textSize = 12f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, 0, dp(12))
        })

        // 状态卡
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        stateLabel = TextView(this).apply {
            text = "未连接"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
        }
        infoLabel = TextView(this).apply {
            text = "等待连接…"
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(stateLabel)
        card.addView(infoLabel)
        root.addView(card, matchWrap().apply { topMargin = dp(4) })

        // 连接区
        root.addView(sectionTitle("连接方式"))
        root.addView(row3(
            bigButton("USB", Color.parseColor("#1565C0")) { onConnect(ConnectionMethod.Usb) },
            bigButton("Wi-Fi AP", Color.parseColor("#2E7D32")) { onConnect(ConnectionMethod.WifiAp()) },
            bigButton("Wi-Fi STA", Color.parseColor("#6A1B9A")) { onConnect(ConnectionMethod.WifiSta()) },
        ))

        // 测试区
        root.addView(sectionTitle("测试操作"))
        root.addView(row4(
            smallButton("读取测试") { doAsync { readTest() } },
            smallButton("配对") { doAsync { client.pair() } },
            smallButton("断开") { client.disconnect(); log("已断开") },
            smallButton("清空日志") { logView.text = "" },
        ))

        // 日志区
        root.addView(sectionTitle("日志"))
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ).apply { topMargin = dp(4) }
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        logScroll.addView(logView)
        root.addView(logScroll)
        setContentView(root)
    }

    private fun onConnect(method: ConnectionMethod) {
        doAsync {
            client.connect(method)
            // 连接成功后自动做一次读取验证
            readTest()
        }
    }

    // ---------------- 业务 ----------------

    private suspend fun readTest() {
        val s = client.requireSession()
        val info = s.getDeviceInfo()
        log("设备：${info.manufacturer} ${info.model} ${info.deviceVersion}")

        val storages = s.listStorages()
        log("存储卡：${storages.joinToString { "${it.volumeLabel} ${it.freeSpace / 1024 / 1024}MB" }}")
        val storage = storages.firstOrNull() ?: run { log("（无存储卡）"); return }

        val handles = s.listObjectHandles(storage.storageId)
        log("对象数：${handles.size}")
        val handle = handles.firstOrNull() ?: run { log("（卡内无文件）"); return }

        val obj = s.getObjectInfo(handle)
        log("首个对象：${obj.filename} ${obj.compressedSize}B ${obj.imageWidth}x${obj.imageHeight}")

        val thumb = s.getThumbnail(handle)
        log("缩略图：${thumb.size}B ✓")

        val want = minOf(256 * 1024L, obj.compressedSize).toInt().coerceAtLeast(1)
        val head = s.readRange(handle, 0, want)
        log("分段读：${head.size}/${want}B ✓")
    }

    // ---------------- 客户端观察 ----------------

    private fun observeClient() {
        scope.launch {
            client.state.collect { st ->
                val (title, color, info) = describe(st)
                stateLabel.text = title
                stateLabel.setTextColor(Color.parseColor(color))
                infoLabel.text = info
                when (st) {
                    is CameraState.Failed -> log("失败：${st.error.message}")
                    is CameraState.AwaitingHostConfirm -> log("请在相机的主机确认界面确认本机（预备：${st.prepare}）")
                    else -> Unit
                }
            }
        }
        scope.launch {
            client.events.collect { ev -> log("· ${describeEvent(ev)}") }
        }
    }

    private fun describe(st: CameraState): Triple<String, String, String> = when (st) {
        is CameraState.Disconnected -> Triple("未连接", "#1565C0", "选择下方连接方式开始测试")
        is CameraState.Discovering -> Triple("发现中…", "#EF6C00", "正在搜索相机")
        is CameraState.Connecting -> Triple("连接中", "#EF6C00", st.phase)
        is CameraState.AwaitingHostConfirm -> Triple("等待相机确认", "#EF6C00", "请在相机「主机确认」界面确认本机")
        is CameraState.Connected -> Triple(
            "已连接 ${st.deviceInfo.model}",
            "#2E7D32",
            "${st.deviceInfo.manufacturer} · ${st.deviceInfo.deviceVersion} · ${st.endpoint.host}:${st.endpoint.port}",
        )
        is CameraState.Recovering -> Triple("自动恢复中", "#EF6C00", st.reason)
        is CameraState.Failed -> Triple("连接失败", "#C62828", st.error.message ?: "")
    }

    private fun describeEvent(ev: CameraEvent): String = when (ev) {
        is CameraEvent.KeepaliveFailed -> "保活临时失败：${ev.detail}"
        is CameraEvent.TransportLost -> "传输丢失：${ev.detail}"
        is CameraEvent.SessionRecovered -> "会话已恢复：${ev.detail}"
        is CameraEvent.SessionTokenChanged -> "会话令牌变更"
        is CameraEvent.WirelessDisconnected -> "无线断开：${ev.reason}"
        is CameraEvent.PairingProgress -> ev.text
    }

    // ---------------- UI 工具 ----------------

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#888888"))
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun bigButton(text: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun row3(a: Button, b: Button, c: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(a, b, c).forEach {
                addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            }
        }

    private fun row4(a: Button, b: Button, c: Button, d: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(a, b, c, d).forEach {
                addView(it, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
            }
        }

    private fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 工具 ----------------

    private fun doAsync(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CameraError) {
                runOnUiThread { log("错误：${e.message}") }
            } catch (e: Exception) {
                runOnUiThread { log("异常：${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    private fun log(msg: String) {
        if (!::logView.isInitialized) return
        logView.append("$msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------- USB 广播 ----------------

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> log("USB 设备已插入")
                UsbManager.ACTION_USB_DEVICE_DETACHED -> log("USB 设备已拔出")
                UsbCameraDiscovery.ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        log("USB 授权成功：${device?.productName ?: ""}，开始连接…")
                        onConnect(ConnectionMethod.Usb)
                    } else {
                        log("USB 授权被拒绝")
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbCameraDiscovery.ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }
}

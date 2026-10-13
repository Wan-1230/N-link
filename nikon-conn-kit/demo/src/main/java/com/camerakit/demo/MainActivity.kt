package com.camerakit.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.camerakit.api.CameraConnection
import com.camerakit.api.CameraEndpoint
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
        ensureDiscoveryPermissions()
        client = CameraConnectionFactory.create(this) { msg -> runOnUiThread { log(msg) } }
        registerUsbReceiver()
        observeClient()
        log("启动完成 · 三通道：USB / Wi-Fi AP / Wi-Fi STA")
    }

    /**
     * mDNS 发现所需运行时权限（Android 13+ 为「附近设备」，此前为定位）。
     * 未授权时部分 ROM 的 NSD 表现为「发现已启动却零回调」——不报错、也没结果。
     */
    private fun ensureDiscoveryPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1)
        }
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

        // STA 主机注册：相机停在主机配置向导时独立注册（无需先连接），注册成功自动续连
        root.addView(bigButton(
            "STA 主机注册（相机停在主机配置向导时用）",
            Color.parseColor("#00695C"),
        ) {
            doAsync {
                client.registerStaHost()
                log("PC 主机注册完成，自动发起 STA 连接…")
                connectWithRetry(ConnectionMethod.WifiSta())
                readTest()
            }
        }, matchWrap().apply { topMargin = dp(6) })

        // 测试区
        root.addView(sectionTitle("测试操作"))
        root.addView(row4(
            smallButton("读取测试") { doAsync { readTest() } },
            smallButton("配对") { doAsync { client.pair() } },
            smallButton("断开") { client.disconnect(); log("已断开") },
            smallButton("清空日志") { logView.text = "" },
        ))
        root.addView(
            row2(
                smallButton("复制日志") { copyLog() },
                smallButton("STA 手动IP") { showManualIpDialog() },
            ),
            matchWrap().apply { topMargin = dp(6) },
        )

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
            connectWithRetry(method)
            // 连接成功后自动做一次读取验证
            readTest()
        }
    }

    /** STA 连接带一次自动重试：注册/建档后相机服务可能仍在重启，首次失败多为瞬时。 */
    private suspend fun connectWithRetry(method: ConnectionMethod) {
        try {
            client.connect(method)
        } catch (e: Exception) {
            if (method !is ConnectionMethod.WifiSta) throw e
            log("首次连接失败（${e.message}），5s 后自动重试一次…")
            kotlinx.coroutines.delay(5_000)
            client.connect(method)
        }
    }

    // ---------------- 业务 ----------------

    private suspend fun readTest() {
        val s = client.requireSession()
        val info = s.getDeviceInfo()
        log("[业务] 设备：${info.manufacturer} ${info.model} ${info.deviceVersion} ops=${info.operationsSupported.size}")

        val storages = s.listStorages()
        log("[业务] 存储卡：${storages.joinToString { "${it.volumeLabel} ${it.freeSpace / 1024 / 1024}MB" }}")
        val storage = storages.firstOrNull() ?: run { log("[业务] （无存储卡）"); return }

        val handles = s.listObjectHandles(storage.storageId)
        log("[业务] 对象数：${handles.size}")
        // 列表首个常是 DCIM 目录（association，无尺寸）：跳过目录类对象，取第一个真实文件
        val obj = handles.firstNotNullOfOrNull { h ->
            runCatching { s.getObjectInfo(h) }.getOrNull()?.takeIf { it.compressedSize > 0 }
        }
        if (obj == null) {
            log("[业务] （卡内无可用文件，或全为目录对象）")
            return
        }
        log("[业务] 首个文件：${obj.filename} ${obj.compressedSize}B ${obj.imageWidth}x${obj.imageHeight}")

        runCatching { s.getThumbnail(obj.handle) }
            .onSuccess { log("[业务] 缩略图：${it.size}B ✓") }
            .onFailure { log("[业务] 缩略图失败（${it.message}），跳过") }

        val want = minOf(256 * 1024L, obj.compressedSize).toInt().coerceAtLeast(1)
        val head = s.readRange(obj.handle, 0, want)
        log("[业务] 分段读：${head.size}/${want}B ✓")
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

    private fun row2(a: Button, b: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(a, b).forEach {
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

    private val logTimeFormat =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    private fun log(msg: String) {
        if (!::logView.isInitialized) return
        // 每行带毫秒时间戳：真机排查要靠时间差判断卡在哪一段（发现/握手/注册/保活）
        val line = "${logTimeFormat.format(java.util.Date())} $msg"
        // 业务协程在 IO 线程，视图只能主线程碰：统一投回主线程再追加
        runOnUiThread {
            logView.append("$line\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** 复制全部日志到剪贴板（反馈问题时直接粘贴发出）。 */
    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CameraKit 日志", logView.text))
        log("已复制全部日志到剪贴板")
    }

    /**
     * STA 手动 IP 连接（基准实现 实证同款兜底）：mDNS 发现不到相机时
     * 直接按 IP 连接，端口固定 15740。
     */
    private fun showManualIpDialog() {
        val input = EditText(this).apply {
            hint = "相机 IP（如 192.168.1.35）"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("STA 手动 IP 连接")
            .setMessage("mDNS 发现不到相机时的兜底：手机与相机同一网络后，输入相机 IP 直连（端口 15740）")
            .setView(input)
            .setPositiveButton("连接") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isEmpty()) return@setPositiveButton
                log("手动 IP 连接：$ip:15740")
                doAsync {
                    client.connect(
                        ConnectionMethod.WifiSta(
                            CameraEndpoint("手动输入", ip, 15740, "manual"),
                        ),
                    )
                    readTest()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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

package com.nikonlink.app.camera.liveview

import android.graphics.BitmapFactory
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.metrics.BaselineMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live View 实时取景管理器
 *
 * PRD 2.3 实时取景监看:
 * - 实时画面：相机 Live View 画面实时传输至手机屏幕 (P0)
 * - 触摸对焦：点击手机屏幕任意位置触发对焦点移动 (P0)
 * - 低延迟：端到端延迟目标 < 200ms (P0)
 * - 构图辅助：三分线、黄金分割、对角线、自定义网格 (P1)
 * - 直方图：实时 RGB 直方图 (P1)
 * - 画面放大：双指缩放 / 双击放大至 100% (P1)
 *
 * PRD 5.1 性能指标:
 * - 分辨率：最低 720p，推荐 1080p
 * - 帧率：≥ 30fps
 * - 延迟：< 200ms (WiFi 5GHz)
 * - 编码：H.264 / MJPEG
 */
@Singleton
class LiveViewManager @Inject constructor(
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val apConnection: com.nikonlink.app.device.ap.ApConnectionService,
    private val apLinkLocks: com.nikonlink.app.device.ap.ApLinkLocks,
    private val connFlags: com.nikonlink.app.device.connect.ConnFlags,
    private val eventLogger: AppEventLogger,
    private val baselineMetrics: BaselineMetrics
) {
    companion object {
        private const val TAG = "LiveViewMgr"

        /** ~15fps，预留保活通道余量防断联；也是 FR-01 掉帧率的判定阈值（同源，勿在此处改数值）。 */
        private const val FRAME_INTERVAL_MS = BaselineMetrics.LV_TARGET_INTERVAL_MS

        /**
         * AP 通道的帧间隔：**连续拉取**（2026-10-19）。
         *
         * 2026-10-19 真机日志（Z50II + vivo V2509A，v3.3.2）的硬数据：
         * AP 监看 80 轮 / 22.8s ≈ **3.5fps**，但 `late`（单轮取帧 ≥66ms）只占 61% ——
         * 也就是**有 39% 的轮次能在 66ms 内拿到帧**。带宽不够会是「每轮都慢」，
         * 不会一半快一半慢；这种抖动是**手机 Wi-Fi 省电（PS-Poll）**的典型形态：
         * 我们每取一帧就空等 66ms，空口一闲，驱动就进省电，
         * 下一帧的请求要等下一个 DTIM 才被发出去，白吃 100~300ms。
         *
         * 参考实现（用户指定的开源项目）取的正是连续拉取：`delay(frame == null ? 100 : 10)`。
         * 这里按同一思路把 AP 的空档压到 8ms：链路持续有流量，射频不进省电，
         * 帧率就回到相机实际出帧能力上。相机回重复帧时由 [isSameFrame] 挡掉，
         * 不会因为拉得快而多做解码。
         *
         * 只作用于 AP；STA / USB 仍走 66ms 档（它们的帧率受产品口径约束，见指标口径文档）。
         */
        private const val AP_FRAME_INTERVAL_MS = 8L

        /**
         * AP 侧**没拿到帧**时的退避档（对齐参考实现 `delay(frame == null ? 100 : 10)` 的后半句）。
         *
         * 2026-10-19 那轮只落地了「拿到帧 8ms」，没落地「拿不到退到 100ms」，
         * 于是相机忙碌（0x2019 DeviceBusy / 0xA004）或本轮无新帧时，我们按 8ms 猛敲 0x9203：
         * 每敲一次就占一格命令串行锁，把真正出帧的那一轮往后挤，表现就是画面持续顿挫。
         */
        private const val AP_EMPTY_FRAME_INTERVAL_MS = 100L
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /**
         * USB 通道的取帧容错阈值（v2.2）。USB 单帧失败要 2.5s 起（自适应后更久），
         * 沿用 WiFi 的 5 次阈值等于把「相机正常忙碌」判成断线。
         */
        private const val USB_MAX_CONSECUTIVE_ERRORS = 10

        /** 连续「相机没出帧/在忙」多少次后才尝试就地重启码流（约 12s 无画面） */
        private const val USB_STALL_LIMIT = 40

        /** 静默期节流间隔：太快会加重命令通道负担 */
        private const val USB_STALL_DELAY_MS = 300L

        /** 一轮监看最多允许几次就地重启（超过就是真故障，交还用户处理） */
        private const val USB_LV_RESTART_LIMIT = 5

        /** RC-9：StartLiveView 成功后等待相机完成切换（实测 ~250ms）再验帧 */
        private const val LV_START_SETTLE_MS = 300L

        /** RC-10：最多重试轮数，每轮都重发 0x9201 */
        private const val MAX_START_ATTEMPTS = 6

        /** ChangeAfArea(0x9205) 生效后再 AfDrive 的间隔 */
        private const val AF_AREA_SETTLE_MS = 150L
    }

    /**
     * Manager 自持的协程作用域（A5/S1）。
     *
     * **为什么不再用宿主（页面 VM）的 scope**：旧实现 `start(scope)` 把页面的
     * `viewModelScope` 存下来，后者直接覆盖前者——遥控页与全屏页共享本单例，
     * 于是「谁最后进来谁持有帧循环」，先来的那个页面随时可能因为 scope 被顶掉
     * 而拿不到帧（黑屏），而顶掉它的页面退出时又会停流，把还活着的页面一起带走。
     *
     * 帧循环改为跑在 Manager 自己的作用域上：宿主只决定「有没有人在看」，
     * 不再决定「协程跑在哪」。宿主离开 → 没人看了 → 由 [detachHost] 停流。
     */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 宿主登记表（A5）：显式回答「现在谁在看着画面」 */
    private val hostRegistry = LiveViewHostRegistry()

    private var frameJob: Job? = null
    private var consecutiveErrors = 0

    /** 连续「相机没出帧/在忙」计数（与错误分开：前者不是链路故障） */
    private var stallCount = 0

    /** 本轮监看已用掉的就地重启次数，startLiveView 时清零 */
    private var restartCount = 0

    /**
     * 进行中的异步停止作业（0x9202 EndLiveView）。
     * stopLiveView 是 fire-and-forget，而 startLiveView 由另一协程发起——
     * kotlin Mutex 互斥但不保证 FIFO，End 可能晚于 Start 才到相机：
     * 相机先收到 Start（已在监看中）再收到 End（把监看关了），随后帧循环
     * 全部取帧失败 → 全屏后画面异常。startLiveView 必须 join 该作业再启动。
     */
    private var stopJob: Job? = null

    /**
     * 帧错误宽限期截止时间（epoch ms）。
     * 拍照（AF + 曝光 + 写卡）期间 0x9203 取不到帧是**正常现象**，
     * 遥控模式下机身处理更久；按默认阈值连续 5 次无帧（约 0.3~1s）就会
     * 误判「长时间无画面」自停监看——用户感知即「点快门后断连」。
     * 拍照入口调用 [grantFrameErrorGrace] 开启宽限，宽限期内错误不累计。
     */
    @Volatile
    private var errorGraceUntilMs = 0L

    /**
     * 「期望停流」标记（v1.3.0 需求 7）：录像期间置位。
     * 与 [errorGraceUntilMs] 的区别是**没有时长上限**，由 begin/end 显式控制，
     * 覆盖整段录像（时长由用户决定，无法预估）。
     */
    @Volatile
    private var expectedStall = false

    private val _liveViewState = MutableStateFlow(LiveViewState.STOPPED)
    val liveViewState: StateFlow<LiveViewState> = _liveViewState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 最新帧数据（JPEG/MJPEG） */
    private val _latestFrame = MutableSharedFlow<LiveViewFrame>(extraBufferCapacity = 2)
    val latestFrame: SharedFlow<LiveViewFrame> = _latestFrame.asSharedFlow()

    /** 帧率统计 */
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    /** 延迟统计 (ms) */
    private val _latency = MutableStateFlow(0L)
    val latency: StateFlow<Long> = _latency.asStateFlow()

    /** 构图辅助线类型 */
    private val _gridOverlay = MutableStateFlow(GridOverlay.THIRDS)
    val gridOverlay: StateFlow<GridOverlay> = _gridOverlay.asStateFlow()

    /** 画面缩放比例 */
    private val _zoomLevel = MutableStateFlow(1.0f)
    val zoomLevel: StateFlow<Float> = _zoomLevel.asStateFlow()

    private var frameCount = 0
    private var lastFpsTime = 0L

    /** AP 通道 5s 心跳窗口（字节 / 取帧耗时 / 帧数），见帧循环里的 `lv_ap_rate`。 */
    private var apWindowBytes = 0L
    private var apWindowMs = 0L
    private var apWindowFrames = 0

    /** 上一帧指纹（长度 + 首尾各 8 字节），用于连续拉取时挡掉相机重复回的同一帧。 */
    private var lastFrameSize = -1
    private var lastFrameHead = 0L
    private var lastFrameTail = 0L

    /**
     * 最新监看帧的像素尺寸。用于把归一化点击换算到相机 AF 坐标域时校正宽高比：
     * 相机 AF 坐标箱 AF_COORD_MAX_X×Y 是固定 4:3，而 Live View 实际帧可能不是 4:3，
     * 直接 x*4000/y*3000 会产生系统性偏移，必须按帧真实比例缩放。
     * 仅在未知时解析一次 JPEG 头（监看期间分辨率固定），stop 时归零。
     */
    @Volatile
    private var framePixelWidth = 0
    @Volatile
    private var framePixelHeight = 0

    /** 进入无线控制模式前的曝光程序模式，停止监看时恢复（wireless control mode exited） */
    private var savedExposureMode: ByteArray? = null

    /**
     * 登记一个监看宿主（A5/S1）。
     *
     * 遥控页与全屏页各传自己的 hostId（`"remote"` / `"fullscreen"`）。
     * 同一 hostId 重复 attach（旋屏重建、VM 重建）只刷新登记，不算接管；
     * 不同 hostId 接管时打一条 `lv_host_takeover`，让「画面被谁接管过」可追溯。
     */
    fun attachHost(hostId: String) {
        val r = hostRegistry.attach(hostId, managerScope)
        Timber.tag(TAG).i(
            "attachHost=$hostId first=${r.isFirst} tookOverFrom=${r.tookOverFrom} hosts=${hostRegistry.snapshot()}"
        )
        if (r.tookOverFrom != null) {
            eventLogger.event("lv_host_takeover", "from" to r.tookOverFrom, "to" to hostId)
        }
    }

    /**
     * 注销一个监看宿主（A5/S1/S3）。
     *
     * **只有最后一个宿主离开才停流**——这是修掉「全屏页退出把遥控页一起带走」的关键：
     * 旧实现在任何一页销毁时都无条件停流，而那一页可能根本不是会话的当前持有者。
     * 非登记宿主的过期调用（重复销毁、乱序回调）直接忽略。
     */
    fun detachHost(hostId: String) {
        val r = hostRegistry.detach(hostId)
        Timber.tag(TAG).i(
            "detachHost=$hostId removed=${r.removed} remaining=${r.remaining} newOwner=${r.newOwner}"
        )
        if (r.shouldStop) {
            eventLogger.event("lv_host_empty", "last" to hostId)
            stopLiveView()
        } else if (r.removed) {
            eventLogger.event("lv_handover", "from" to hostId, "to" to r.newOwner)
        }
    }

    /** 当前登记的宿主（诊断/测试用） */
    fun activeHosts(): List<String> = hostRegistry.snapshot()

    private val wifiChannel: LvChannel = WifiLvChannel(ptpSession)
    private val usbChannel: LvChannel = UsbLvChannel(usbPtpManager)
    private val apChannel: LvChannel = ApLvChannel(apConnection)

    /**
     * 当前活跃通道：USB 在线且链路判活优先，其次 AP 直连会话，最后 WiFi(PTP/IP)。
     * 避免相机假在线（TCP 在但命令无响应）时把命令打进黑洞。
     */
    private fun lv(): LvChannel = when {
        usbPtpManager.isConnected() && usbPtpManager.isAlive() -> usbChannel
        apConnection.isConnected() -> apChannel
        else -> wifiChannel
    }

    /** 无线通道（曝光程序模式进出等无线链路通用操作）：AP 在线走 AP，否则 WiFi。 */
    private fun wireless(): LvChannel =
        if (apConnection.isConnected()) apChannel else wifiChannel

    // A5：原 stop() 只是「stopLiveView + 清 scope」，全仓无调用者（页面销毁走
    // detachHost）。它同时是旧 scope 覆盖模型的收口动作，随该模型一起删除。

    /**
     * 开启 Live View
     * PRD 2.3: 相机 Live View 画面实时传输至手机屏幕
     */
    suspend fun startLiveView(): Boolean {
        // 幂等：已在监看中直接成功。供全屏入口复用——遥控页与全屏页共用同一
        // LiveViewManager，监看进行中进全屏不重启，帧循环无缝延续。
        if (_liveViewState.value == LiveViewState.RUNNING) return true
        // 等待在途的 EndLiveView 收敛后再启动，杜绝 End/Start 到达相机的顺序颠倒
        stopJob?.let { runCatching { it.join() } }
        stopJob = null
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected() && !apConnection.isConnected()) {
            Timber.tag(TAG).w("PTP not connected")
            _liveViewState.value = LiveViewState.ERROR
            _errorMessage.value = "相机尚未连接，请先完成配对"
            return false
        }

        _liveViewState.value = LiveViewState.STARTING
        _errorMessage.value = null
        return withContext(Dispatchers.IO) {
            try {
                // 监看依赖无线控制模式（相机随后上报 DevicePropChanged prop=0x500E）。
                // 启动前先把曝光程序模式切到 Remote(0x8012)，失败不致命（相机可能已在遥控模式）
                val ch = lv()
                if (ch.isWireless) {
                    runCatching {
                        // 先备份当前曝光程序模式，停止监看时恢复
                        savedExposureMode = ch.getDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_PROGRAM_MODE
                        )
                        val modeOk = ch.setDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_PROGRAM_MODE,
                            byteArrayOf(
                                (PtpConstants.PROP_VALUE_REMOTE_MODE and 0xFF).toByte(),
                                ((PtpConstants.PROP_VALUE_REMOTE_MODE shr 8) and 0xFF).toByte()
                            )
                        )
                        Timber.tag(TAG).i("Wireless control mode 0x500E=0x8012 set: $modeOk")
                    }
                    delay(300)  // 等待相机完成模式切换
                }
                // 启动 LiveView 前先设置 Nikon 图像配置 0xD1AC=3，
                // 否则相机可能不输出 JPEG 帧。
                // AP 是例外（闸门 AP_LV_PROFILE_ALIGN）：参考实现
                // （_ref/NikonLink PtpIpClient.startLiveView）全程不写这个属性，AP 监看依然流畅，
                // 而我们这里无条件写 3，且 3 的档位语义从未真机标定过。若 3 是较大档位，
                // 等于我们主动把每帧字节数抬上去 —— 相机热点只有 2.4GHz，STA 走路由器，
                // 同一档位下 AP 的代价大得多，这正好是「AP 卡、STA 不卡」且前四项修复都没
                // 碰到的那一项。STA / USB 这条写入逐字节不变。
                val skipProfileWrite = isApChannel() &&
                    connFlags.isEnabled(com.nikonlink.app.device.connect.ConnFlags.AP_LV_PROFILE_ALIGN)
                if (skipProfileWrite) {
                    Timber.tag(TAG).i("LiveView image profile 0xD1AC write skipped (AP, 对齐参考实现)")
                    eventLogger.event("lv_profile_write", "code" to "0xD1AC", "written" to false)
                } else runCatching {
                    val profileOk = ch.setDevicePropValue(
                        PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE,
                        byteArrayOf(3)
                    )
                    if (profileOk) {
                        Timber.tag(TAG).i("LiveView image profile set 0xD1AC=3")
                    } else {
                        Timber.tag(TAG).w("LiveView image profile 0xD1AC=3 rejected (non-fatal)")
                    }
                    eventLogger.event("lv_profile_write", "code" to "0xD1AC", "written" to profileOk)
                }
                val success = startLiveViewCommand()
                if (success) {
                    _liveViewState.value = LiveViewState.RUNNING
                    consecutiveErrors = 0
                    stallCount = 0
                    restartCount = 0
                    // USB 监看期间暂停 DeviceReady 保活：帧流量本身即保活，
                    // 保活命令只会与帧竞争命令串行通道、拉高帧延迟
                    if (usbPtpManager.isConnected()) usbPtpManager.setKeepAlivePaused(true)
                    // AP 监看期间追加一把低时延 WiFi 锁：相机热点无外网，系统会把它
                    // 当闲置网络放进省电，单帧被 PS-Poll 切成多段——这是 AP 卡顿的主因之一
                    if (apConnection.isConnected()) {
                        apLinkLocks.retainStreaming()
                        apConnection.markStreaming(true)
                    }
                    startFrameLoop()
                    Timber.tag(TAG).i("✓ Live View started")
                    // 启动后一次性试读候选 AF 属性码，打日志供真机标定（只读、不阻断）。
                    // AP 下跳过：这 11 次属性读排在同一把命令串行锁上，会和最初的帧抢通道
                    // （2026-10-19 实测 AP 侧这 11 次花 3.7s，同一组在 STA 侧 0.3s），
                    // 而结果只写日志、不参与任何运行时判定 —— 拿 3.7s 的干净监看换一条
                    // 标定日志不值。AF 属性的标定数据 STA 侧已经拿到，不必在 AP 重复。
                    if (apConnection.isConnected()) {
                        eventLogger.event("af_readback_skip", "reason" to "ap_liveview")
                    } else managerScope.launch(Dispatchers.IO) {
                        runCatching { probeCameraAfProperties("af_readback_start") }
                    }
                    // AP：把监看图像档位 0xD1AC 的取值域回读一次（只读）。这个属性的
                    // 档位语义（3 到底是"最大"还是"JPEG"）一直没真机标定过，
                    // 而它直接决定每帧字节数——是"卡在带宽"时唯一能动的相机侧旋钮。
                    if (apConnection.isConnected()) {
                        managerScope.launch(Dispatchers.IO) {
                            runCatching { probeApLvProfile() }
                        }
                    }
                } else {
                    // 全链路优化: 启动失败时恢复原曝光模式，
                    // 避免相机卡在无线控制模式导致后续下载被拒绝
                    usbPtpManager.setKeepAlivePaused(false)
                    apLinkLocks.releaseStreaming()
                    apConnection.markStreaming(false)
                    restoreExposureModeIfNeeded()
                    Timber.tag(TAG).e("Failed to start Live View")
                    _liveViewState.value = LiveViewState.ERROR
                    _errorMessage.value =
                        "实时取景启动失败，请检查相机模式（P/A/S/M）并重试"
                }
                success
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Start Live View error")
                _liveViewState.value = LiveViewState.ERROR
                _errorMessage.value = "实时取景启动异常：${e.message ?: "未知错误"}"
                false
            }
        }
    }

    /**
     * RC-9/10/11 闭环启动：
     * 1) 先读 0xD1A4 禁止条件位图——只记录与提示，不阻断（位含义无权威定义）；
     * 2) 每一轮都重发 0x9201（相机假成功/状态回退后，重发才有效）；
     * 3) 返回成功后等 300ms 再拉一帧 0x9203 验真，探测失败不计入 consecutiveErrors；
     * 4) DeviceBusy 时用 DeviceReady 等待，最多 MAX_START_ATTEMPTS 轮。
     */
    private suspend fun startLiveViewCommand(): Boolean {
        readProhibitCondition()?.let { desc ->
            eventLogger.event("lv_prohibit", "reason" to desc)
            _errorMessage.value = "相机提示：$desc"
        }

        repeat(MAX_START_ATTEMPTS) { attempt ->
            val (ok, code) = startLiveViewResult()
            when {
                ok -> {
                    delay(LV_START_SETTLE_MS)
                    if (probeLiveViewFrame()) {
                        eventLogger.event("lv_probe", "ok" to true, "attempt" to attempt)
                        return true
                    }
                    // 假成功：下一轮重发 0x9201
                    eventLogger.event("lv_probe", "ok" to false, "attempt" to attempt)
                }
                code < 0 -> return false
                code != PtpConstants.RESPONSE_DEVICE_BUSY &&
                    code != PtpConstants.RESPONSE_NIKON_NOT_LIVE_VIEW -> {
                    eventLogger.event("lv_start", "ok" to false, "code" to code, "attempt" to attempt)
                    Timber.tag(TAG).w("StartLiveView rejected: 0x${code.toString(16)}")
                    return false
                }
                else -> {
                    eventLogger.event("lv_start_retry", "attempt" to attempt, "code" to code)
                    waitDeviceReady(attempt)
                }
            }
        }
        return false
    }

    /**
     * 读 0xD1A4 LiveView 禁止条件位图（只读、不阻断）。
     * 返回 null 表示读取失败或无已知含义。
     */
    private suspend fun readProhibitCondition(): String? {
        return try {
            val data = lv().getDevicePropValue(PtpConstants.PROP_NIKON_LV_PROHIBIT_CONDITION)
                ?: return null
            if (data.size < 4) return null
            val value = java.nio.ByteBuffer.wrap(data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            PtpConstants.describeProhibitCondition(value)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * RC-11 假成功验证：真正拉一帧 0x9203。
     * 失败不计入 consecutiveErrors（帧循环的错误计数只管运行期）。
     */
    private suspend fun probeLiveViewFrame(): Boolean {
        return try {
            fetchFrame()?.isNotEmpty() == true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchFrame(): ByteArray? = lv().getLiveViewImage()

    /**
     * DeviceBusy 时等待相机就绪（只等待，不判定成功；成功与否由调用方重发 0x9201 验证）。
     */
    private suspend fun waitDeviceReady(fromAttempt: Int) {
        repeat(10) {
            delay(250)
            val (readyOk, readyCode) = deviceReadyResult()
            if (readyOk) {
                Timber.tag(TAG).i("DeviceReady after wait (attempt=$fromAttempt)")
                return
            }
            if (readyCode != -1 && readyCode != PtpConstants.RESPONSE_DEVICE_BUSY) {
                Timber.tag(TAG).w("DeviceReady code=0x${readyCode.toString(16)} (attempt=$fromAttempt)")
                return
            }
        }
    }

    private suspend fun startLiveViewResult(): Pair<Boolean, Int> {
        val r = lv().command(PtpConstants.OP_NIKON_START_LIVE_VIEW)
        return Pair(r.isOk, r.code)
    }

    private suspend fun deviceReadyResult(): Pair<Boolean, Int> {
        val r = lv().command(PtpConstants.OP_NIKON_DEVICE_READY)
        return Pair(r.isOk, r.code)
    }

    /**
     * 停止 Live View
     */
    fun stopLiveView() {
        frameJob?.cancel()
        frameJob = null
        baselineMetrics.lvSessionEnded()
        _errorMessage.value = null
        // 无论此前走哪条通道，恢复 USB 保活（无 USB 连接时该调用无副作用）
        usbPtpManager.setKeepAlivePaused(false)
        // 归还 AP 监看级 WiFi 锁（无 AP 会话时该调用无副作用）
        apLinkLocks.releaseStreaming()
        apConnection.markStreaming(false)
        if (_liveViewState.value == LiveViewState.RUNNING) {
            // 记录停止作业：startLiveView 会 join 它，保证 End 先于下一次 Start 到相机
            stopJob = managerScope.launch(Dispatchers.IO) {
                try {
                    val ok = lv().stopLiveView()
                    if (!ok) eventLogger.event("lv_stop_fail", "channel" to lv().javaClass.simpleName)
                    if (!ok) Timber.tag(TAG).w("stopLiveView returned false")
                } catch (_: Exception) {}
            }
        }
        _liveViewState.value = LiveViewState.STOPPED
        _fps.value = 0
        // 帧尺寸随本次监看会话失效，下次启动重新解析
        framePixelWidth = 0
        framePixelHeight = 0
        // 停止监看后退出无线控制模式，恢复原曝光程序模式
        restoreExposureModeIfNeeded()
        Timber.tag(TAG).i("Live View stopped")
    }

    /**
     * 监看未运行时启动之；已运行直接返回成功。
     * 供视频录制等依赖实时取景的功能前置调用（Nikon 0x920A 需要 LiveView 处于开启状态）。
     * STARTING 状态下等待进行中的启动收敛后再判定，避免并发重复启动。
     */
    suspend fun ensureRunning(): Boolean {
        when (_liveViewState.value) {
            LiveViewState.RUNNING -> return true
            LiveViewState.STARTING -> {
                repeat(40) {
                    delay(100)
                    when (_liveViewState.value) {
                        LiveViewState.RUNNING -> return true
                        LiveViewState.STOPPED, LiveViewState.ERROR -> return startLiveView()
                        else -> {}
                    }
                }
                return _liveViewState.value == LiveViewState.RUNNING
            }
            else -> return startLiveView()
        }
    }

    /** 恢复进入无线控制模式前的曝光程序模式（停止/启动失败时调用） */
    private fun restoreExposureModeIfNeeded() {
        val restore = savedExposureMode
        savedExposureMode = null
        if (restore != null && !usbPtpManager.isConnected()) {
            managerScope.launch(Dispatchers.IO) {
                val ch = wireless()
                runCatching {
                    // 守卫：监看期间用户可能已远程切换了模式（0x500E 可写），
                    // 仅当相机仍停留在遥控值 0x8012 时才回退，避免覆盖用户选择
                    val current = ch.getDevicePropValue(
                        PtpConstants.PROP_EXPOSURE_PROGRAM_MODE
                    )
                    val stillRemote = current != null && current.size >= 2 &&
                        ((current[0].toInt() and 0xFF) or
                            ((current[1].toInt() and 0xFF) shl 8)) ==
                        PtpConstants.PROP_VALUE_REMOTE_MODE
                    if (stillRemote) {
                        ch.setDevicePropValue(
                            PtpConstants.PROP_EXPOSURE_PROGRAM_MODE, restore
                        )
                        Timber.tag(TAG).i("Wireless control mode exited, exposure mode restored")
                    } else {
                        Timber.tag(TAG).i("Exposure mode changed during LV, skip restore")
                    }
                }
            }
        }
    }

    /**
     * 帧获取循环
     * PRD 5.1: 帧率 ≥ 30fps, 延迟 < 200ms
     */
    private fun startFrameLoop() {
        frameJob?.cancel()
        frameCount = 0
        lastFpsTime = System.currentTimeMillis()
        apWindowBytes = 0
        apWindowMs = 0
        apWindowFrames = 0
        lastFrameSize = -1
        lastFrameHead = 0
        lastFrameTail = 0
        baselineMetrics.lvSessionStarted()

        frameJob = managerScope.launch(Dispatchers.IO) {
            while (isActive && _liveViewState.value == LiveViewState.RUNNING) {
                val frameStart = System.currentTimeMillis()
                var frameArrived = false

                try {
                    val imageData = fetchFrame()
                    if (imageData != null && imageData.isNotEmpty()) {
                        frameArrived = true
                        val latency = System.currentTimeMillis() - frameStart
                        _latency.value = latency

                        val frame = LiveViewFrame(
                            data = imageData,
                            timestamp = frameStart,
                            latencyMs = latency
                        )
                        // 仅在帧尺寸未知时解析一次 JPEG 头，供触摸对焦按真实比例换算
                        if (framePixelWidth == 0 || framePixelHeight == 0) {
                            updateFrameSize(imageData)
                            // AP 会话第一帧把尺寸与字节数写进全链路日志：这是判断
                            // 「卡在带宽还是卡在往返」唯一的硬数据（旧版只有 logcat）
                            if (apConnection.isConnected() && framePixelWidth > 0) {
                                eventLogger.event(
                                    "lv_ap_frame",
                                    "w" to framePixelWidth,
                                    "h" to framePixelHeight,
                                    "kb" to imageData.size / 1024,
                                    "ms" to latency
                                )
                            }
                        }
                        // 连续拉取下相机会重复回同一帧：尺寸与首尾字节都没变就跳过上屏，
                        // 省掉一次解码与一次全屏重绘（链路照常保持忙碌）
                        val duplicate = isApChannel() && isSameFrame(imageData)
                        if (!duplicate && _latestFrame.tryEmit(frame)) baselineMetrics.lvFrameDelivered()
                        if (!duplicate) baselineMetrics.lvFrameBytes(imageData.size)
                        consecutiveErrors = 0
                        stallCount = 0

                        // FPS 计算：只数**真正换了画面**的帧。连续拉取下相机重复回同一帧时
                        // 拉取次数远高于换帧次数，按拉取次数算会把 15fps 显示成 30fps
                        if (!duplicate) frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTime >= 1000) {
                            _fps.value = frameCount
                            frameCount = 0
                            lastFpsTime = now
                        }

                        // AP 通道 5s 心跳：把「每帧多大 / 取一帧多久」写进全链路日志。
                        // 这两个数决定卡顿性质——帧大=带宽不够（要降尺寸），
                        // 帧小但取帧慢=往返/省电问题（要改链路参数），二者修法完全不同。
                        if (isApChannel()) {
                            apWindowBytes += imageData.size
                            apWindowMs += latency
                            apWindowFrames++
                            if (apWindowFrames >= 50) {
                                eventLogger.event(
                                    "lv_ap_rate",
                                    "kb_frame" to apWindowBytes / apWindowFrames / 1024,
                                    "ms_frame" to apWindowMs / apWindowFrames,
                                    "frames" to apWindowFrames
                                )
                                apWindowBytes = 0
                                apWindowMs = 0
                                apWindowFrames = 0
                            }
                        }
                    } else {
                        handleFrameFailure(null)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    // 取帧超时：多数是链路拥塞或相机瞬时忙碌，属可自愈的软错误
                    Timber.tag(TAG).w("Frame timeout: ${e.message}")
                    handleFrameFailure(e)
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Frame error: ${e.message}")
                    handleFrameFailure(e)
                }

                // 帧间隔控制（避免过度请求）。
                // 指标口径不变：`lvRound` 仍以 66ms 判「这一轮取帧是否超时」。
                val elapsed = System.currentTimeMillis() - frameStart
                baselineMetrics.lvRound(elapsed)
                val target = apFrameIntervalMs(frameArrived)
                if (elapsed < target) {
                    delay(target - elapsed)
                }
            }
        }
    }

    /**
     * 开启帧错误宽限期：durationMs 内取帧失败不累计 consecutiveErrors。
     * 拍照入口（capture）调用——AF/曝光/写卡期间无监看帧是正常现象，
     * 不得据此判定「长时间无画面」而自停监看。
     */
    fun grantFrameErrorGrace(durationMs: Long = 6000L) {
        errorGraceUntilMs = System.currentTimeMillis() + durationMs
    }

    /**
     * 进入「期望停流」状态（v1.3.0 需求 7：视频模式 + 联动画面下录像期间保持画面）。
     *
     * **背景**：联动画面（相机屏同显）下开始录像时，机身会转入录像应用模式
     * （0x9435 ChangeApplicationMode）并**停止向 PTP 推送 LV 帧**。旧版把这种
     * 预期内的停流当成链路故障，连续 5 次取帧失败即判 ERROR 并 cancel 帧循环，
     * 且没有任何重连 —— 用户看到的就是"点开始录像 → 画面断开，但快门还能按"。
     *
     * **做法**：整段录像期间不再累计取帧错误（等价于一个时长无上限的宽限），
     * 停止录像时由 [endExpectedStall] 退出。机身在录像期间仍出帧的机型不受影响
     * （照常有画面）；真的断链仍会被 [isWifiLinkDead] 的确定性判定立即收口。
     */
    fun beginExpectedStall(reason: String) {
        expectedStall = true
        errorGraceUntilMs = System.currentTimeMillis() + 2000L
        Timber.tag(TAG).i("Live view expected stall ON ($reason)")
    }

    /** 退出「期望停流」状态（停止录像后调用） */
    fun endExpectedStall() {
        if (!expectedStall) return
        expectedStall = false
        Timber.tag(TAG).i("Live view expected stall OFF")
    }

    /**
     * 取帧失败处理。
     *
     * 断连体感的根因之一：旧实现不分错误类型一律累加 [consecutiveErrors]，
     * 达到 5 次才停。而单次 `getLiveViewImage` 的命令超时是 10s，
     * 链路其实早就断了，界面却还要空转约 50 秒才弹出错误提示。
     *
     * 现在分三级（优先级从高到低）：
     * - **链路已死**（PTP 会话进入 ERROR）：立即停止并给出「连接已断开」提示，
     *   把控制权交还重连流程，一次多余的重试都不做。这是确定性信号，
     *   优先于宽限期——拍照途中真断链也应立即收口，不该再干等 6 秒；
     * - **宽限期内**（拍照 AF/曝光/写卡期间无帧属正常）：不累计错误，
     *   但仍节流重试，避免失败快速返回形成紧密轮询加重命令通道负担；
     * - **链路还在**（偶发超时/相机忙碌）：按 [MAX_CONSECUTIVE_ERRORS] 累计，
     *   保持原有「抖动自愈」能力，不会因一两次超时就掐断监看。
     */
    private suspend fun handleFrameFailure(cause: Throwable?) {
        baselineMetrics.lvFrameFailed()
        if (isWifiLinkDead()) {
            Timber.tag(TAG).w("Link already dead, stop live view immediately")
            eventLogger.event(
                "lv_stop",
                "reason" to "link_dead",
                "errors" to consecutiveErrors,
                "cause" to (cause?.javaClass?.simpleName ?: "empty_frame")
            )
            _liveViewState.value = LiveViewState.ERROR
            _errorMessage.value = "与相机的连接已断开，请返回重连后再开启监看"
            frameJob?.cancel()
            // 链路已死这条路径不走 stopLiveView()/stopWithError()，两把"会话期"资源
            // 必须在此归还，否则会挂到下一次监看才释放（USB 保活、AP 低时延锁）
            usbPtpManager.setKeepAlivePaused(false)
            apLinkLocks.releaseStreaming()
            apConnection.markStreaming(false)
            baselineMetrics.lvSessionEnded()
            return
        }
        // 宽限期内（拍照等已知会暂停出帧的场景）错误不累计，但仍节流重试；
        // expectedStall = 录像期间（联动画面下机身停推 LV 帧属预期），同样不累计
        if (expectedStall || System.currentTimeMillis() < errorGraceUntilMs) {
            delay(100)
            return
        }

        // ── AP 直连通道：取帧失败快速返回（无长命令超时兜底），按「相机静默」处理 ──
        // 与 USB 的 NO_FRAME/BUSY 同理：拍照写卡、弱光慢快门期间不出帧是正常现象，
        // 按 WiFi 的 5 次阈值会把正常忙碌误判成故障自停监看。节流重试，
        // 连续静默过久才就地重启码流；真断链由上方 isWifiLinkDead 的确定性判定收口。
        if (apConnection.isConnected()) {
            stallCount++
            if (stallCount >= USB_STALL_LIMIT) {
                if (!restartStreamInPlace()) {
                    return stopWithError("stall_unrecoverable")
                }
                stallCount = 0
            } else {
                delay(if (stallCount > 8) USB_STALL_DELAY_MS else 120L)
            }
            return
        }

        // ── v2.2：USB 通道的分级容错（修「监看过一会儿掉一下」）──────────────────
        // 旧逻辑不分失败类型，累计 5 次即自停监看并要求用户重连；而 USB 上单次
        // 取帧要 2.5s 才失败，5 次仅约 13s —— 弱光慢快门（机身出帧间隔＝曝光时间）、
        // AF 搜索、拍完写卡这几类**完全正常**的忙碌就够了阈值，于是监看被自己掐掉。
        if (usbPtpManager.isConnected()) {
            when (usbPtpManager.lastFrameFailure) {
                // 相机答上了、只是这一轮没有新帧（或在忙）：属正常静默，节流等待，
                // 连续静默过久才就地重启码流 —— 全程不动会话，用户最多看到画面停一下。
                UsbPtpManager.LvFrameFailure.NO_FRAME,
                UsbPtpManager.LvFrameFailure.BUSY -> {
                    stallCount++
                    if (stallCount >= USB_STALL_LIMIT) {
                        if (!restartStreamInPlace()) {
                            return stopWithError("stall_unrecoverable")
                        }
                        stallCount = 0
                    } else {
                        delay(if (stallCount > 8) USB_STALL_DELAY_MS else 120L)
                    }
                    return
                }
                // 事务失败（含超时）：先探活确认链路，别急着拆会话
                UsbPtpManager.LvFrameFailure.TRANSACTION -> {
                    if (!usbPtpManager.probeLinkAlive()) {
                        return stopWithError("usb_link_dead")
                    }
                    stallCount = 0
                }
                UsbPtpManager.LvFrameFailure.NONE -> Unit
            }
        }

        consecutiveErrors++
        val limit = if (usbPtpManager.isConnected()) {
            USB_MAX_CONSECUTIVE_ERRORS
        } else {
            MAX_CONSECUTIVE_ERRORS
        }
        if (consecutiveErrors >= limit) {
            // 判死前最后一次自救：重启监看码流（不掉会话）
            if (usbPtpManager.isConnected() && restartStreamInPlace()) {
                consecutiveErrors = 0
                return
            }
            stopWithError("max_errors")
        } else {
            delay(100)  // 短暂等待后重试
        }
    }

    /** 监看彻底收口：停在 ERROR 并恢复保活，文案按 `reason` 区分。 */
    private fun stopWithError(reason: String) {
        Timber.tag(TAG).e("Live view stopped: $reason (errors=$consecutiveErrors, stalls=$stallCount)")
        baselineMetrics.lvSessionEnded()
        eventLogger.event(
            "lv_stop",
            "reason" to reason,
            "errors" to consecutiveErrors,
            "stalls" to stallCount,
            "restarts" to restartCount
        )
        _liveViewState.value = LiveViewState.ERROR
        _errorMessage.value = if (reason == "usb_link_dead" || reason == "link_dead") {
            "与相机的连接已断开，请返回重连后再开启监看"
        } else {
            "实时取景长时间无画面，已自动停止，请重试"
        }
        // 帧循环自停路径不走 stopLiveView()，保活需在此恢复
        usbPtpManager.setKeepAlivePaused(false)
        apLinkLocks.releaseStreaming()
        apConnection.markStreaming(false)
        frameJob?.cancel()
    }

    /**
     * 就地重启监看码流（EndLiveView → StartLiveView），**不动 PTP 会话**。
     *
     * 相机侧码流偶尔会停止推送而会话本身完好；旧实现只能靠用户点「重连」，
     * 那会重建整条会话。这里先做一次轻量重启，成功则画面直接续上。
     * 每轮监看最多 [USB_LV_RESTART_LIMIT] 次，避免对真故障无限自愈。
     */
    private suspend fun restartStreamInPlace(): Boolean {
        if (restartCount >= USB_LV_RESTART_LIMIT) return false
        restartCount++
        val ended = runCatching {
            lv().command(PtpConstants.OP_NIKON_END_LIVE_VIEW).isOk
        }.getOrDefault(false)
        delay(250)
        val started = runCatching {
            lv().command(PtpConstants.OP_NIKON_START_LIVE_VIEW).isOk
        }.getOrDefault(false)
        eventLogger.event(
            "lv_restart",
            "attempt" to restartCount, "end" to ended, "start" to started,
            "stalls" to stallCount, "errors" to consecutiveErrors
        )
        if (started) delay(LV_START_SETTLE_MS)
        return started
    }

    /** WiFi(PTP/IP) 通道是否已被判死；USB 通道不受此判定影响。 */
    private fun isWifiLinkDead(): Boolean {
        if (usbPtpManager.isConnected()) return false
        return wireless().isLinkDead()
    }

    // ==================== 触摸对焦 ====================

    /**
     * 触摸对焦
     * PRD 2.3: 点击手机屏幕任意位置触发对焦点移动
     * @param x 归一化 X 坐标 (0.0 ~ 1.0)
     * @param y 归一化 Y 坐标 (0.0 ~ 1.0)
     */
    suspend fun touchFocus(x: Float, y: Float): Boolean {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected() && !apConnection.isConnected()) return false
        return withContext(Dispatchers.IO) {
            try {
                // 归一化点击 (x,y ∈ 0..1，相对监看图像内容) → 相机 AF 坐标域。
                // 关键口径：AF 坐标箱与 live view 帧同宽高比（相机厂商官方应用与一款
                // 第三方控制应用的报文分析证实，见 mapTapToAfCoord），按帧真实比例线性映射；
                // 早期「固定 4:3 箱 + letterbox」假设无实机标定，帧≠4:3 时纵向系统偏移。
                val (afX, afY) = mapTapToAfCoord(x, y)

                val response = lv().changeAfArea(afX, afY)

                if (response) {
                    // 触发 AF 驱动：稍等 AF 区域生效后再驱动，部分机型立刻驱动会仍按旧区域对焦
                    delay(AF_AREA_SETTLE_MS)
                    lv().afDrive()
                    Timber.tag(TAG).d(
                        "Touch focus: tap=($x,$y) -> af=($afX,$afY) " +
                            "frame=${framePixelWidth}x$framePixelHeight " +
                            "box=${PtpConstants.AF_COORD_MAX_X}x${mapTapToAfCoord(1f, 1f).second}"
                    )
                    // 下发成功后再回读相机 AF 相关属性，尝试确认相机真实对焦点。
                    // 候选码语义尚未经真机标定，现仅打日志、不解析坐标；无法确认时
                    // 降级为「只显示点击位置」（viewFocusIndicator 本地框），绝不画不符框。
                    managerScope.launch(Dispatchers.IO) {
                        runCatching { probeCameraAfProperties("af_tap") }
                    }
                } else {
                    // 下发失败：相机实际对焦点未知，明确降级为只显示点击位置
                    eventLogger.event(
                        "af_indicator",
                        "mode" to "tap_only",
                        "reason" to "area_set_failed",
                        "afX" to afX,
                        "afY" to afY
                    )
                    Timber.tag(TAG).w("Touch focus area set failed; show tap-only indicator")
                }
                response
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Touch focus failed")
                false
            }
        }
    }

    /**
     * 归一化点击 (nx,ny ∈ 0..1，相对监看图像内容) → 相机 AF 坐标 (0..AF_COORD_MAX_X, 0..AF_COORD_MAX_Y)。
     *
     * 坐标域口径（2026-10-12 对相机厂商官方应用与一款成熟第三方控制应用的报文分析，
     * 两者独立收敛到同一结论）：
     * - 相机 ChangeAfArea(0x9205) 的两个参数就在 **live view 图像的 wholeSize 像素空间**：
     *   厂商官方应用用 `afX = tapX / (viewWidth / wholeWidth)` 的单系数线性映射，AF 区域
     *   回读（centerX/centerY/width/height）也在同一空间直接作画；另一款第三方实现用
     *   `归一化 × displayArea`（displayArea 缺省即 wholeSize）——结论一致。
     * - wholeSize 与图像**同宽高比**：官方应用把图像画满 wholeSize 箱后 AF 框才与画面
     *   重合（其绘制流程先按 bitmap 画图、再按 wholeSize 缩放画 AF 框），故正确换算
     *   是按帧真实宽高比线性映射，而不是把帧塞进一个固定 4:3 的箱。
     * - 历史误区：9295a29（2026-09-17）按「AF 箱固定 4:3」把帧 letterbox 进 4000×3000。
     *   该假设无实机标定（v2.0.2 PRD 自承常数取自第三方实现日志的粗略观察），真机反馈
     *   对焦点仍与点击不符——帧≠4:3 时纵向被系统性压缩，边缘偏移可达约 11%。
     *
     * 纯换算在 [AfCoordMapper]（可单测）；这里只负责取本会话帧尺寸。
     */
    private fun mapTapToAfCoord(nx: Float, ny: Float): Pair<Int, Int> =
        AfCoordMapper.map(nx, ny, framePixelWidth, framePixelHeight)

    /**
     * 当前是否走 AP 直连（帧循环里每次现查，链路切换后立即生效）。
     */
    private fun isApChannel(): Boolean = apConnection.isConnected()

    /**
     * AP 侧帧间隔：连续拉取档（闸门 [ConnFlags.AP_LV_CONTINUOUS_PULL]）或 66ms 档。
     *
     * 连续拉取分两档 —— 拿到帧 8ms、没拿到帧退到 [AP_EMPTY_FRAME_INTERVAL_MS]，
     * 与参考实现的 `delay(frame == null ? 100 : 10)` 同形。
     */
    private fun apFrameIntervalMs(frameArrived: Boolean): Long =
        if (isApChannel() &&
            connFlags.isEnabled(com.nikonlink.app.device.connect.ConnFlags.AP_LV_CONTINUOUS_PULL)
        ) {
            if (frameArrived) AP_FRAME_INTERVAL_MS else AP_EMPTY_FRAME_INTERVAL_MS
        } else {
            FRAME_INTERVAL_MS
        }

    /**
     * 连续拉取下相机会把**同一帧**重复回给我们（0x9203 是"取最新一帧"，不是队列）。
     * 用「长度 + 首尾各 8 字节」做指纹：命中即认为没有新画面，跳过解码与上屏。
     *
     * 为什么不用全量哈希：一帧数百 KB，每帧算一遍哈希等于把省下来的拷贝又花回去；
     * JPEG 的头尾（SOI/量化表开头与 EOI 前的扫描数据结尾）已足以区分两帧。
     * 只在 AP 通道调用（[isSameFrame] 会更新指纹）。
     */
    private fun isSameFrame(data: ByteArray): Boolean {
        val head = edgeLong(data, fromHead = true)
        val tail = edgeLong(data, fromHead = false)
        if (data.size == lastFrameSize && head == lastFrameHead && tail == lastFrameTail) return true
        lastFrameSize = data.size
        lastFrameHead = head
        lastFrameTail = tail
        return false
    }

    private fun edgeLong(data: ByteArray, fromHead: Boolean): Long {
        var v = 0L
        val start = if (fromHead) 0 else maxOf(0, data.size - 8)
        val end = if (fromHead) minOf(8, data.size) else data.size
        for (i in start until end) v = (v shl 8) or (data[i].toLong() and 0xFF)
        return v
    }

    /**
     * 解析监看帧 JPEG 头拿到像素宽高（仅 bounds，不解码像素），用于触摸对焦比例校正。
     * 仅在帧尺寸未知时调用一次；解析失败静默忽略（下次帧再试）。
     */
    private fun updateFrameSize(data: ByteArray) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val start = LvFrame.findJpegStart(data)
            BitmapFactory.decodeByteArray(data, start, data.size - start, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                framePixelWidth = opts.outWidth
                framePixelHeight = opts.outHeight
                Timber.tag(TAG).d("LiveView frame size: ${framePixelWidth}x${framePixelHeight}")
            }
        } catch (_: Exception) {
        }
    }

    // findJpegStart 已合并到 LvFrame（A4/X2）：三个文件各有一份逐字节相同的实现，
    // 抽出去后本文件不再自持。

    /**
     * 只读回读候选 AF 相关设备属性（尼康厂商 0xD0xx/0xD1xx 段），逐个试读并记录成败，
     * 打到 Timber 与 AppEventLogger 供真机标定「哪个码对应真实对焦点」。
     * 不解析坐标、不据此绘制对焦点框；标定前一律由调用方降级为只显示点击位置。
     * [phase] 仅用于区分日志事件（启动校准 / 触摸时回读），不含 v2.0.2 的 af_calib/af_probe 命名。
     */
    private suspend fun probeCameraAfProperties(phase: String) {
        val outcomes = AfReadback.probeAll { readDevicePropValue(it) }
        var readable = 0
        for (o in outcomes) {
            if (o.available) {
                readable++
                Timber.tag(TAG).i("AF prop 0x%04X %s = %s", o.code, o.label, o.rawHex)
            } else {
                Timber.tag(TAG).i("AF prop 0x%04X %s = unavailable (%s)", o.code, o.label, o.error)
            }
        }
        eventLogger.event(
            phase,
            "candidates" to outcomes.size,
            "readable" to readable,
            "codes" to outcomes.filter { it.available }.joinToString(",") { "0x%04X".format(it.code) }
        )
    }

    /** 走当前活跃通道只读回读单条设备属性值（只读，不改连接层） */
    private suspend fun readDevicePropValue(code: Int): ByteArray? = lv().getDevicePropValue(code)

    /**
     * AP 侧回读监看图像档位 0xD1AC 的取值域（只读、一次）。
     *
     * 现在启动前硬写 `0xD1AC=3`，但**3 的语义从未真机标定**：既可能是"最大尺寸"，
     * 也可能只是"输出 JPEG"。若 AP 的瓶颈是带宽（`lv_ap_rate kb_frame` 偏大），
     * 这个属性就是唯一能减小每帧字节数的相机侧旋钮。
     * 这里只把 `current / 可写 / 枚举值或 min-max-step` 写进日志，
     * 下一次真机日志就能决定能不能做"监看尺寸阶梯"，不再靠猜。
     */
    private suspend fun probeApLvProfile() {
        val bridge = apConnection.bridge() ?: return
        val p = runCatching { bridge.property(PtpConstants.PROP_NIKON_LV_IMAGE_PROFILE) }
            .getOrNull() ?: return
        eventLogger.event(
            "lv_profile",
            "code" to "0xD1AC",
            "cur" to p.current,
            "writable" to p.writable,
            "values" to p.values.joinToString(","),
            "min" to p.minimum,
            "max" to p.maximum,
            "step" to p.step
        )
    }

    /**
     * 手动对焦驱动
     * @param direction 1=远端, -1=近端
     * @param speed 驱动速度 1-3
     */
    suspend fun manualFocusDrive(direction: Int, speed: Int = 2): Boolean {
        if (!ptpSession.isConnected() && !usbPtpManager.isConnected() && !apConnection.isConnected()) return false
        return withContext(Dispatchers.IO) {
            try {
                lv().manualFocusDrive(direction, speed)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "MF drive failed")
                false
            }
        }
    }

    // ==================== 构图辅助 ====================

    fun setGridOverlay(grid: GridOverlay) {
        _gridOverlay.value = grid
    }

    fun cycleGridOverlay() {
        val values = GridOverlay.entries
        val nextIdx = (values.indexOf(_gridOverlay.value) + 1) % values.size
        _gridOverlay.value = values[nextIdx]
    }

    // ==================== 画面缩放 ====================

    fun setZoom(level: Float) {
        _zoomLevel.value = level.coerceIn(1.0f, 5.0f)
    }

    fun zoomIn() {
        _zoomLevel.value = (_zoomLevel.value + 0.5f).coerceAtMost(5.0f)
    }

    fun zoomOut() {
        _zoomLevel.value = (_zoomLevel.value - 0.5f).coerceAtLeast(1.0f)
    }

    fun resetZoom() {
        _zoomLevel.value = 1.0f
    }

    fun isRunning(): Boolean = _liveViewState.value == LiveViewState.RUNNING}

/**
 * Live View 状态
 */
enum class LiveViewState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * 单帧数据
 */
data class LiveViewFrame(
    val data: ByteArray,
    val timestamp: Long,
    val latencyMs: Long
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * 构图辅助线类型
 * PRD 2.3: 三分线、黄金分割、对角线、自定义网格
 */
enum class GridOverlay(val displayName: String) {
    NONE("无"),
    THIRDS("三分线"),
    GOLDEN_RATIO("黄金分割"),
    DIAGONAL("对角线"),
    GRID_4X4("4x4 网格")
}

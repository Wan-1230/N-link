package com.nikonlink.app.camera.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.app.camera.data.PhotoMarkRepository
import com.nikonlink.app.capture.RemoteShootingManager
import com.nikonlink.app.capture.ShootingState
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.data.PhotoMarkEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 照片传输 ViewModel
 * PRD 2.1: 浏览相机存储卡照片列表、缩略图预览、选择性下载
 */
@HiltViewModel
class TransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferManager: TransferManager,
    private val ptpSession: PtpSessionManager,
    private val connectionManager: ConnectionManager,
    private val usbPtpManager: UsbPtpManager,
    private val thumbnailCache: ThumbnailCache,
    private val settings: AppSettings,
    private val photoMarkRepository: PhotoMarkRepository,
    private val previewShareExporter: PreviewShareExporter,
    private val remoteShootingManager: RemoteShootingManager
) : ViewModel() {

    companion object {
        private const val TAG = "TransferVM"
        /** 「已下载」状态与媒体库对账的最小间隔（非强制路径），避免频繁扫媒体库 */
        private const val RECONCILE_COOLDOWN_MS = 5_000L

        /**
         * 拍摄完成 → 拉取相册的合并窗口（优化项 3）。
         *
         * 两个作用，缺一不可：
         * 1. **等相机落盘**：快门释放后相机还要把文件写进存储卡，立刻拉列表拿到的
         *    仍是旧清单。留这段时间让 ObjectAdded 真正生效。
         * 2. **合并连拍**：窗口内再次出片只会把窗口往后推。间隔/连拍期间因此
         *    一次都不拉（不会抢 PTP 带宽拖慢拍摄），等一串拍完再统一刷新一次。
         */
        private const val CAPTURE_SYNC_WINDOW_MS = 800L

        /**
         * 相册整体加载硬顶：任何单请求卡顿叠加都不允许把加载圈挂到天荒地老。
         * 超时后保留旧列表、复位加载态并给出可操作提示（下拉重试）。
         */
        private const val LOAD_HARD_DEADLINE_MS = 60_000L
    }

    private val _photoList = MutableStateFlow<List<CameraFile>>(emptyList())
    val photoList: StateFlow<List<CameraFile>> = _photoList.asStateFlow()

    private val _localPhotos = MutableStateFlow<List<CameraFile>>(emptyList())
    val localPhotos: StateFlow<List<CameraFile>> = _localPhotos.asStateFlow()

    private val _activeAlbum = MutableStateFlow(AlbumSource.CAMERA)
    val activeAlbum: StateFlow<AlbumSource> = _activeAlbum.asStateFlow()

    /**
     * 排序规则，默认「拍摄时间倒序」——新拍的照片排在最前面。
     * 从 AppSettings 恢复；取值非法时 [AlbumSort.fromPersistence] 会回退到默认规则。
     */
    private val _sort = MutableStateFlow(
        AlbumSort.fromPersistence(settings.albumSortDimension, settings.albumSortDirection)
    )
    val sort: StateFlow<AlbumSort> = _sort.asStateFlow()

    /**
     * 更新排序规则并持久化。
     * 只改展示顺序，不触发重新拉取列表；下次打开相册自动沿用，切换相册/目录同样生效。
     */
    fun setSort(sort: AlbumSort) {
        _sort.value = sort
        settings.albumSortDimension = sort.dimension.name
        settings.albumSortDirection = sort.direction.name
    }

    /** 当前标签页展示的列表：相机照片或本地照片。排序统一在 [filteredPhotos] 处理 */
    val displayedPhotos: StateFlow<List<CameraFile>> = combine(
        _photoList,
        _localPhotos,
        _activeAlbum
    ) { camera, local, source ->
        if (source == AlbumSource.CAMERA) camera else local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _photoFilter = MutableStateFlow(PhotoFilter.ALL)
    val photoFilter: StateFlow<PhotoFilter> = _photoFilter.asStateFlow()

    private val _selectedHandles = MutableStateFlow<Set<Int>>(emptySet())
    val selectedHandles: StateFlow<Set<Int>> = _selectedHandles.asStateFlow()

    // ---------- F1「已标记」分区状态 ----------

    /** 全部标记记录（DB 流驱动：标记/校验清理后自动刷新） */
    private val _markedRecords = MutableStateFlow<List<PhotoMarkEntity>>(emptyList())
    val markedRecords: StateFlow<List<PhotoMarkEntity>> = _markedRecords.asStateFlow()

    /** 当前相机列表中命中标记的 handle 集合（网格星标角标渲染用） */
    val markedHandles: StateFlow<Set<Int>> =
        _markedRecords.map { list -> list.mapTo(mutableSetOf()) { it.objectHandle } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 「已标记」栏可下载项：标记 JOIN 当前相机列表（键校验保证同 handle 即同文件） */
    val markedPhotos: StateFlow<List<CameraFile>> = combine(
        _markedRecords, _photoList
    ) { marks, photos ->
        val byHandle = photos.associateBy { it.handle }
        marks.mapNotNull { mark -> byHandle[mark.objectHandle] }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 失效待清的标记（相机列表里找不到对应文件的记录；未连接相机时恒为空是正常态） */
    val staleMarkCount: StateFlow<Int> = combine(
        _markedRecords, _photoList
    ) { marks, photos ->
        val handles = photos.mapTo(HashSet(photos.size)) { it.handle }
        marks.count { it.objectHandle !in handles }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** F2：已标记栏「跳过已下载」开关（默认开，会话内状态不持久化） */
    private val _skipDownloadedInMarks = MutableStateFlow(true)
    val skipDownloadedInMarks: StateFlow<Boolean> = _skipDownloadedInMarks.asStateFlow()

    fun setSkipDownloadedInMarks(enabled: Boolean) {
        _skipDownloadedInMarks.value = enabled
    }

    /** F2：已下载判定集合（传输历史按 handle 查询，传输状态变化时增量刷新） */
    private val _downloadedHandles = MutableStateFlow<Set<Int>>(emptySet())
    val downloadedHandles: StateFlow<Set<Int>> = _downloadedHandles.asStateFlow()

    /** 上次与本地媒体库对账的时间戳（v1.3.0，`reconcileWithLocalMedia` 冷却用） */
    private var lastReconcileAt = 0L

    /**
     * 正在增量抓取的"新照片"句柄（v1.3.0 需求 5）。
     * 尼康事件可能同一张重复上报，且抓取是异步的，
     * 用它防止同一个 handle 被并发抓取两次、插入两条。
     */
    private val inFlightNewHandles = mutableSetOf<Int>()

    /** 「已标记」栏最终展示列表：按标记时间倒序 + 可选跳过已下载（F2 联动） */
    val markedDisplayList: StateFlow<List<CameraFile>> = combine(
        markedPhotos, _downloadedHandles, _skipDownloadedInMarks
    ) { photos, downloaded, skip ->
        if (skip) photos.filterNot { it.handle in downloaded } else photos
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** F2：相机栏「未下载」筛选 chip（与类型筛选可叠加） */
    private val _onlyNotDownloaded = MutableStateFlow(false)
    val onlyNotDownloaded: StateFlow<Boolean> = _onlyNotDownloaded.asStateFlow()

    fun setOnlyNotDownloaded(enabled: Boolean) {
        _onlyNotDownloaded.value = enabled
    }

    /** F1：批量下载完成后弹「清除这些标记」确认的一次性事件（载荷 = 可清除张数） */
    private val _clearMarksPrompt = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** 记录从「已标记」栏发起的批量下载，等队列排空后检查是否弹清除提示 */
    private var pendingClearCandidates: Set<Int> = emptySet()

    // 必须在 filteredPhotos 之前声明：后者在属性初始化时就要读它的 flow
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** 筛选与排序的入参快照 */
    private data class SortInput(
        val files: List<CameraFile>,
        val sort: AlbumSort,
        val loading: Boolean
    )

    /**
     * 筛选 + 排序后的最终展示列表。
     *
     * 三个要点：
     * 1. **先筛后排** —— 筛选先缩小集合，参与排序的元素更少。
     * 2. **分页期间不排序** —— 数据还不完整时排序没有意义，且每页重排会让列表不断跳动。
     *    分页只做追加，加载完成（_isLoading 转 false）时再对全量做一次排序，
     *    因此最终结果始终基于**完整数据集**，而非「仅当前页内有序」。
     * 3. **排序在 Dispatchers.Default** —— 大列表不会阻塞主线程。
     *    [mapLatest] 会在新数据到达时取消上一次未完成的排序，避免堆积。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val filteredPhotos: StateFlow<List<CameraFile>> = combine(
        // F2：先把「未下载」筛选折叠进数据源（关闭时原样透传，行为与 v0.1.4 完全一致）。
        // 2026-09-08：限定**仅相机照片源**生效——开关若作用于本地源，本地页（本就是
        // 已下载集合）会被误清空；已标记源另有 skipDownloadedInMarks，不受此开关影响。
        combine(
            displayedPhotos, _onlyNotDownloaded, _downloadedHandles, _activeAlbum
        ) { photos, hideDownloaded, downloaded, source ->
            if (hideDownloaded && source == AlbumSource.CAMERA) {
                photos.filter { it.handle !in downloaded }
            } else {
                photos
            }
        },
        _photoFilter,
        _sort,
        _isLoading
    ) { photos, filter, sort, loading ->
        SortInput(photos.filter { filter.matches(it) }, sort, loading)
    }.mapLatest { input ->
        when {
            input.files.isEmpty() -> emptyList()
            input.loading -> input.files
            else -> withContext(Dispatchers.Default) { sortCameraFiles(input.files, input.sort) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 模块 4.1：Tab 切换的单一数据源。
     *
     * 旧实现里网格收集器分别订阅 filteredPhotos / markedDisplayList，并用
     * activeAlbum.value 做条件过滤——切 Tab 时目标列表 flow 若没有新值
     * （StateFlow 不重发旧值），收集器永远不会执行，界面停留在上一个 Tab。
     * 现在把「按源取列表」收敛进 combine：_activeAlbum 一变，combine 必然重发，
     * 点击 Tab 后选中态、列表内容原子切换（收集器不再有任何条件判断）。
     */
    val uiPhotos: StateFlow<List<CameraFile>> = combine(
        _activeAlbum,
        filteredPhotos,
        markedDisplayList
    ) { source, filtered, marked ->
        if (source == AlbumSource.MARKED) marked else filtered
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transferState: StateFlow<TransferState> = transferManager.transferState
    val queue: StateFlow<List<TransferTask>> = transferManager.queue
    val transferSpeedBps: StateFlow<Long> = transferManager.transferSpeedBps

    /**
     * O2：剩余下载进度 —— 「已下载 M / 总数 N / 剩余 K」。
     *
     * 统计基准是**未过滤的相机原始列表** `_photoList`（不是 [uiPhotos]）：
     * 开启「不重复下载已下载照片」后列表里只剩未下载项，若以展示列表为基准，
     * 剩余数会永远等于总数。用原始列表才能让「被隐藏的张数 = 剩余 K」自洽。
     */
    val downloadStats: StateFlow<DownloadStats> = combine(
        _photoList, _downloadedHandles
    ) { photos, downloaded ->
        val total = photos.size
        DownloadStats(total, photos.count { it.handle in downloaded })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadStats(0, 0))

    /** 已完成缩略图加载的 handle 集合（只用于局部刷新负载），Bitmap 统一由 ThumbnailCache 管理 */
    private val _thumbnails = MutableStateFlow<Set<Int>>(emptySet())
    val thumbnails: StateFlow<Set<Int>> = _thumbnails.asStateFlow()

    /** 缩略图按需加载并发控制（可见项优先，最多 3 个并发 PTP 请求） */
    private val thumbSemaphore = Semaphore(3)
    private val pendingThumbs = mutableSetOf<Int>()
    private var prewarmJob: Job? = null

    /**
     * 高清缩略图升级队列（渐进式加载的第二段）。
     *
     * 与小图通道**分离**是刻意的：高清预览（0x90C4）单张数百 KB，若与小图共用
     * 3 并发窗口，会重新把首屏堵回「转圈」。这里用独立的小并发窗口在后台慢慢替换，
     * 既不抢占首屏带宽，也不阻塞用户滚动。
     */
    private val hdThumbSemaphore = Semaphore(2)
    private val pendingHdThumbs = mutableSetOf<Int>()

    /**
     * 缩略图「内容更新」计数器：高清替换小图时 handle 集合并未变化，
     * Set 相等不会触发 StateFlow 发射，因此用递增计数通知 UI 重绘可见项。
     */
    private val _thumbUpgradeTick = MutableStateFlow(0L)
    val thumbUpgradeTick: StateFlow<Long> = _thumbUpgradeTick.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 全链路优化: TransferManager 的用户消息流（下载成功/失败/通道切换反馈） */
    val managerMessage: StateFlow<String> = transferManager.message

    /** 当前数据通道（USB 优先，USB 断开回退 WiFi） */
    fun activeChannel(): String = transferManager.activeChannel()

    /** 供 UI 层显示轻量状态消息 */
    fun showMessage(msg: String) {
        _message.value = msg
    }

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val statusMessage: StateFlow<String> = connectionManager.statusMessage
    val usbState: StateFlow<UsbConnectionState> = usbPtpManager.usbState

    /**
     * 相机是否就绪（任一通道可用）。
     *
     * 三个来源都必须覆盖：
     * - `FULLY_CONNECTED`：BLE + WiFi 双通道就绪（常规无线连接）
     * - `BLE_CONNECTED`：相机已在线、WiFi 正在升级，此时 PTP 会话可能已可用
     * - `UsbConnectionState.CONNECTED`：USB 有线通道**不进连接状态机**
     *   （ConnectionManager 对 usbState 只打日志），纯 USB 场景必须单独判定
     *
     * 用 Eagerly 是为了冷启动时就能拿到初值：MainActivity 的四个 Fragment 常驻，
     * TransferFragment 的 onViewCreated 全程只跑一次，若用 Lazily 则首次收集前
     * 拿不到真实状态，已连相机时冷启动就不会自动加载（AC-2）。
     */
    val cameraReady: StateFlow<Boolean> = combine(
        connectionState,
        usbState
    ) { conn, usb ->
        conn == ConnectionState.FULLY_CONNECTED ||
            conn == ConnectionState.BLE_CONNECTED ||
            usb == UsbConnectionState.CONNECTED
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 相册加载守卫：防止自动刷新与手动刷新并发跑两个 fetchPhotoList */
    private val loadingGuard = AtomicBoolean(false)
    private var loadJob: Job? = null
    /** 上一次的就绪状态，用于只在「未就绪 → 就绪」上升沿触发一次自动加载 */
    private var lastReady = false

    /** 拍摄后自动同步的合并窗口任务（优化项 3），新事件到来会取消并重排 */
    private var captureSyncJob: Job? = null

    /**
     * 待补拉的脏标记（优化项 3）：出片时若不满足同步条件（不在相机相册页 /
     * 会话未就绪），只置位不拉取，等条件满足时补一次，保证不漏照片。
     */
    private var pendingCaptureSync = false

    init {
        // F1：标记记录流驱动（打标/取消/指纹清理后自动广播到网格与「已标记」栏）
        viewModelScope.launch {
            photoMarkRepository.observeAll().collect { _markedRecords.value = it }
        }
        // 优化项 3：拍摄完成后自动同步相册，用户无需手动刷新
        viewModelScope.launch {
            remoteShootingManager.captureEvents.collect { scheduleCaptureSync() }
        }
        // v1.0.2 反馈：机身实体快门拍的照片相册不刷新——captureEvents 只覆盖 App 内
        // 遥控拍摄，机身拍照只会以 PTP ObjectAdded(0x4002) 事件上报（WiFi/USB 两条
        // 事件通道都在发，此前无人消费）。这里对齐 ZDROP 的实时刷新机制：收到即排一次同步，
        // 连拍/间隔由 scheduleCaptureSync 的 800ms 合并窗口去抖，不会拉爆 PTP 通道。
        viewModelScope.launch {
            ptpSession.events.collect { event ->
                handleCameraEvent(event.eventCode, event.parameters)
            }
        }
        viewModelScope.launch {
            usbPtpManager.events.collect { event ->
                handleCameraEvent(event.eventCode, event.parameters)
            }
        }
        // 拍摄任务（间隔/定时）结束后补一次挂起的同步
        viewModelScope.launch {
            remoteShootingManager.shootingState.collect { state ->
                if (state == ShootingState.IDLE && pendingCaptureSync) {
                    scheduleCaptureSync()
                }
            }
        }
        // 高清缩略图升级：下载原图后本地重生成，网格局部重绑展示清晰版
        viewModelScope.launch {
            transferManager.thumbnailUpgrades.collect { handle ->
                pendingThumbs.remove(handle)
                _thumbnails.value = _thumbnails.value - handle
                requestThumbnail(handle)
            }
        }
        // F2 + F1 批量收尾：下载状态变化时刷新已下载集合；
        // 队列排空（Idle）且存在「已标记」栏发起的批量任务时，检查并弹「清除标记」确认
        viewModelScope.launch {
            transferManager.transferState.collect { state ->
                if (state is TransferState.Idle) {
                    refreshDownloadedHandles()
                    val pending = pendingClearCandidates
                    if (pending.isNotEmpty()) {
                        pendingClearCandidates = emptySet()
                        val downloaded = _downloadedHandles.value.intersect(pending)
                        if (downloaded.isNotEmpty()) _clearMarksPrompt.emit(downloaded.size)
                    }
                }
            }
        }
    }

    /** 查询当前相机列表中已成功下载过的 handle 子集（传输历史批量查询 + 本地媒体对账） */
    private suspend fun refreshDownloadedHandles(forceReconcile: Boolean = false) {
        val handles = _photoList.value.map { it.handle }.filter { it > 0 }
        if (handles.isEmpty()) {
            _downloadedHandles.value = emptySet()
            return
        }
        val fromHistory = transferManager.queryDownloadedHandles(handles)
        _downloadedHandles.value = reconcileWithLocalMedia(fromHistory, forceReconcile)
    }

    /**
     * 「已下载」状态自愈（v1.3.0，需求 3）。
     *
     * 传输历史只记录"下载成功过"，不感知本地文件后来是否被删除：
     * - App 内删除（本地照片页删除）→ 已即时触发本方法；
     * - App 外删除（系统相册 / 文件管理器）→ 靠这里与媒体库对账兜住。
     *
     * 做法：拿本地 /N-Link 目录下的真实文件（文件名 + 大小）与传输记录比对，
     * 对不上的记录连同 transfer_history 一起回收 → 相机照片页角标消失、
     * 「未下载」筛选下重新出现、剩余进度计数回升。
     *
     * 无媒体权限时不做对账（避免把"读不到"误判成"已删除"），只保留 App 内删除那条路径。
     * 非强制调用时带 5 秒冷却，避免每次静默同步都扫一遍媒体库。
     */
    private suspend fun reconcileWithLocalMedia(
        downloaded: Set<Int>,
        force: Boolean = false
    ): Set<Int> {
        if (downloaded.isEmpty() || !hasMediaPermission()) return downloaded
        val now = System.currentTimeMillis()
        if (!force && now - lastReconcileAt < RECONCILE_COOLDOWN_MS) return downloaded
        lastReconcileAt = now
        return runCatching {
            val localKeys = withContext(Dispatchers.IO) {
                queryLocalMedia().map { it.fileName to it.size }.toSet()
            }
            val records = transferManager.completedTransferRecords()
                .filter { it.fileHandle in downloaded }
            if (records.isEmpty()) return@runCatching downloaded
            val gone = records.filterNot { (it.fileName to it.fileSize) in localKeys }
            if (gone.isEmpty()) return@runCatching downloaded
            transferManager.forgetDownloads(gone.map { it.fileHandle })
            Timber.tag(TAG).i("Reconciled ${gone.size} downloaded photos: local file gone")
            downloaded - gone.map { it.fileHandle }.toSet()
        }.getOrElse {
            Timber.tag(TAG).w(it, "Reconcile downloaded state failed")
            downloaded
        }
    }

    /** UI 层订阅：批量下载完成后弹「是否清除这些标记」（载荷 = 可清除张数，0 不弹） */
    val clearMarksPrompt: Flow<Int> = _clearMarksPrompt

    // 注：TransferManager 由 ConnectionManager 以应用级 scope 启动（支撑后台自动下载）

    /**
     * 获取相机照片列表
     * @param force 透传给 [loadPhotos]，手动刷新时为 true
     * @param holdPages 透传给 [loadPhotos]，下拉刷新/手动刷新置 true（防跳底）
     */
    fun fetchPhotos(force: Boolean = false, holdPages: Boolean = false) {
        if (!transferManager.hasActiveSession()) {
            val usbConnected = usbPtpManager.isConnected()
            if (usbConnected) {
                if (!force) _message.value = "正在建立 USB 通道..."
                viewModelScope.launch {
                    val connected = usbPtpManager.isConnected()
                    if (connected) {
                        loadPhotos(force)
                    } else {
                        _isLoading.value = false
                        _message.value = "USB 连接尚未就绪，请稍后重试"
                    }
                }
                return
            }
            // USB 设备已插入但会话仍在建立（CONNECTING/权限请求中）时，等待会话就绪，不要误走 WiFi
            if (usbPtpManager.usbState.value == UsbConnectionState.CONNECTING ||
                usbPtpManager.usbState.value == UsbConnectionState.REQUESTING_PERMISSION
            ) {
                _message.value = "正在建立 USB 通道..."
                viewModelScope.launch {
                    val connected = withTimeoutOrNull(15000L) {
                        while (!usbPtpManager.isConnected()) delay(200)
                        true
                    } ?: false
                    if (connected) {
                        loadPhotos(force)
                    } else {
                        _isLoading.value = false
                        _message.value = "USB 连接尚未就绪，请检查相机 USB 模式（PTP）"
                    }
                }
                return
            }
            if (!force) _message.value = "正在建立 WiFi 通道..."
            connectionManager.requestWifiReconnect()
            viewModelScope.launch {
                val connected = usbPtpManager.isConnected() || connectionManager.awaitPtpSession()
                if (connected) {
                    loadPhotos(force)
                } else {
                    _isLoading.value = false
                    _message.value = "WiFi 通道未就绪，请先在连接页完成配对"
                }
            }
            return
        }
        loadPhotos(force)
    }

    /**
     * @param force 为 true 时无条件打断进行中的加载并重新开始（手动刷新走此路径）；
     *              为 false 时若已有加载在途则直接返回，避免自动刷新与手动刷新叠加。
     * @param silent 为 true 时为后台静默刷新（优化项 3 拍摄后自动同步专用）：
     *               不翻转 [_isLoading]（否则会闪全屏进度条、下拉刷新圈），
     *               不清空已勾选的待下载项，也不覆盖用户当前看到的提示文案。
     * @param holdPages 为 true 时不在分页途中逐页发射，仅在全量拉取完成、[_isLoading]
     *               复位之后一次性写入列表：旧版刷新途中会把「handle 原始序」的中间分页
     *               直接推给网格（与刷新前的倒序展示几乎完全逆序），视口被 DiffUtil
     *               锚点拖到底部、排序完成后再弹回顶部。手动刷新与首连自动加载均启用。
     */
    private fun loadPhotos(
        force: Boolean = false,
        silent: Boolean = false,
        holdPages: Boolean = false
    ) {
        if (!force && !loadingGuard.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Photo loading already in progress, skip")
            return
        }
        loadingGuard.set(true)
        loadJob?.cancel()
        if (!silent) _isLoading.value = true
        loadJob = viewModelScope.launch {
            var fetched: List<CameraFile> = emptyList()
            var fetchedViaHoldPages = false
            try {
                // 媒体列表按 limit=18 分页，每页完成后立即刷新网格，
                // 避免照片多时等待整份列表返回才看到内容。
                // 这里存原始顺序即可：分页期间排序没有意义（数据不完整），
                // 且每页重排会让列表不断跳动；排序统一由 filteredPhotos 在加载完成后做全量处理。
                //
                // 失败兜底（转圈修复的 UI 侧闭环）：
                // 1. 总体硬顶 60s——超时保留旧列表、复位加载态、给出可操作提示；
                // 2. 「整体失败」（通道在线、句柄有货却一条元数据都读不到）静默重试一次，
                //    覆盖插拔瞬间/相机刚唤醒的瞬时抖动，仍失败才以失败文案示人；
                // 3. 部分失败保留已读到的文件并提示缺口，不再静默吞掉。
                var fetch: TransferManager.PhotoListFetch? = null
                for (attempt in 0 until 2) {
                    fetch = withTimeoutOrNull(LOAD_HARD_DEADLINE_MS) {
                        transferManager.fetchPhotoListDetailed(
                            onPage = if (holdPages) null else ({ page -> _photoList.value = page })
                        )
                    } ?: run {
                        if (!silent) _message.value = "相册加载超时，请检查连接后下拉重试"
                        null
                    }
                    if (fetch == null || !fetch.totalFailure) break
                    if (attempt == 0) delay(800)
                }
                val outcome = fetch ?: return@launch
                val result = outcome.files
                fetched = result
                fetchedViaHoldPages = holdPages
                if (!holdPages) _photoList.value = result
                if (!silent) {
                    // 静默刷新不能清勾选：用户可能正勾着一批待下载项在连拍，
                    // 且 handle 是稳定的（新照片只会拿到新 handle），保留勾选是安全的。
                    _selectedHandles.value = emptySet()
                    _message.value = when {
                        outcome.totalFailure ->
                            "相册加载失败（${outcome.totalHandles} 个文件元数据不可读），请下拉重试"
                        outcome.nothingDisplayable -> "未发现可展示的照片或视频"
                        result.isEmpty() -> "存储卡为空或未连接"
                        outcome.partialFailure ->
                            "共 ${result.size} 个文件，${outcome.failedInfoCount} 个读取失败，可下拉重试"
                        else -> "共 ${result.size} 个文件"
                    }
                }
                if (result.isNotEmpty()) {
                    // F1 失效自愈：全量列表到手后做指纹校验，清理机内已删除/换卡失效的标记。
                    // 仅在非空列表时校验——空列表可能是抓取失败，此时清理会把全部标记误删。
                    runCatching { photoMarkRepository.reconcile(result) }
                        .onFailure { Timber.tag(TAG).w(it, "Photo mark reconcile failed") }
                    refreshDownloadedHandles()
                }
                // 后台渐进取预热缩略图；可见项由 Adapter 按需触发
                prewarmThumbnails(result.map { it.handle })
            } finally {
                if (!silent) _isLoading.value = false
                // holdPages：loading 复位后再写列表 → 这一次发射会直接走排序管线，
                // 网格只看到一次「排序后的最终列表」提交，不再有中间态跳动
                if (fetchedViaHoldPages && fetched.isNotEmpty()) _photoList.value = fetched
                loadingGuard.set(false)
            }
        }
    }

    /**
     * 拍摄完成后排一次相册同步（优化项 3）。
     *
     * 合并语义：窗口内再次出片会取消上一个未执行的任务并重排，
     * 因此连拍/间隔拍摄期间一次都不拉，一串拍完（间隔 > [CAPTURE_SYNC_WINDOW_MS]）才刷新。
     *
     * 不满足条件时只置 [pendingCaptureSync] 脏标记、不拉取：
     * - 用户在看本地相册 / 已标记栏：切回相机相册时补（见 [setAlbum]）；
     * - PTP 会话未就绪：不在这里触发重连——重连是有副作用的重动作，
     *   交给连接页和手动刷新；等会话就绪后由 [onCameraReadyChanged] 兜底补拉。
     */
    private fun scheduleCaptureSync() {
        if (_activeAlbum.value != AlbumSource.CAMERA) {
            pendingCaptureSync = true
            Timber.tag(TAG).d("Capture sync deferred: not on camera album")
            return
        }
        if (!transferManager.hasActiveSession()) {
            pendingCaptureSync = true
            Timber.tag(TAG).d("Capture sync deferred: no active PTP session")
            return
        }
        // 拍摄进行中不拉列表：间隔/定时拍摄期间每次出片都触发同步会与下一张的
        // AF+快门抢占 PTP 通道（表现为间隔拍摄被拖慢甚至超时失败）。
        // 挂起为脏标记，拍摄结束（状态回到 IDLE）后统一补一次。
        val shooting = remoteShootingManager.shootingState.value
        if (shooting == ShootingState.INTERVAL_SHOOTING || shooting == ShootingState.TIMER_COUNTDOWN) {
            pendingCaptureSync = true
            Timber.tag(TAG).d("Capture sync deferred: shooting in progress ($shooting)")
            return
        }
        pendingCaptureSync = false
        captureSyncJob?.cancel()
        captureSyncJob = viewModelScope.launch {
            delay(CAPTURE_SYNC_WINDOW_MS)
            Timber.tag(TAG).i("Auto syncing album after capture")
            // holdPages：全程保持旧列表可见、结束时一次性提交排序结果——
            // v1.0.2 反馈"拍摄后自动刷新滚到最底部"的根因即静默刷新的逐页
            // 中间发射与当前倒序展示几乎完全逆序，DiffUtil 把视口拖到底部
            loadPhotos(force = false, silent = true, holdPages = true)
        }
    }

    /**
     * 相机事件总入口（v1.3.0 需求 5）。
     *
     * 分两条路：
     * - **尼康私有 `0xC101 ObjectAddedInSDRAM`**：事件自带新对象句柄 → 只抓这一张，
     *   增量插入（真正的「拍一张、立刻出现一张」）；
     * - **标准 `0x4002` 与完成信号 `0x400D`/`0xC102`**：保留原有 800ms 去抖 + 全量同步，
     *   作为"事件缺失/无句柄"时的兜底，保证不倒退、不漏片。
     */
    private fun handleCameraEvent(eventCode: Int, parameters: List<Int>) {
        when (eventCode) {
            PtpConstants.EVENT_NIKON_OBJECT_ADDED_IN_SDRAM ->
                onNikonObjectAdded(parameters.firstOrNull() ?: 0)

            PtpConstants.EVENT_OBJECT_ADDED,
            PtpConstants.EVENT_CAPTURE_COMPLETE,
            PtpConstants.EVENT_NIKON_CAPTURE_COMPLETE_REC_IN_SDRAM ->
                scheduleCaptureSync()

            else -> Unit
        }
    }

    /**
     * 尼康 `0xC101 ObjectAddedInSDRAM`：逐张增量插入（需求 5 主路径）。
     *
     * 与全量同步的分工：
     * - 句柄有效 → 只取该张 ObjectInfo 插入；抓取失败/无句柄 → 退回 [scheduleCaptureSync]；
     * - 与标准 `0x4002` 可能重复上报 → 用"列表已含该 handle" + 在途集合双重去重；
     * - 不在相机标签页时只置脏标记（与全量同步同一套语义，避免后台频繁拉流）。
     */
    private fun onNikonObjectAdded(handle: Int) {
        if (handle <= 0 || handle == PtpConstants.NIKON_EVENT_HANDLE_NONE) {
            // NEF+RAW 等双拍场景事件不带句柄 → 只能全量同步补齐
            scheduleCaptureSync()
            return
        }
        if (_activeAlbum.value != AlbumSource.CAMERA) {
            pendingCaptureSync = true
            return
        }
        if (!transferManager.hasActiveSession()) {
            pendingCaptureSync = true
            return
        }
        if (_photoList.value.any { it.handle == handle }) return
        if (!inFlightNewHandles.add(handle)) return
        viewModelScope.launch {
            try {
                val file = transferManager.fetchPhotoByHandle(handle)
                if (file == null) {
                    // 取不到（目录对象/不支持格式/链路抖动）→ 交给全量同步兜底
                    scheduleCaptureSync()
                    return@launch
                }
                if (_photoList.value.any { it.handle == handle }) return@launch
                // 增量插入：排序管线（filteredPhotos → sortCameraFiles）会自动放到正确位置，
                // 不做任何整表重拉，因此不会打断滚动位置或引起列表闪烁
                _photoList.value = _photoList.value + file
                Timber.tag(TAG).i("Incremental add after capture: ${file.fileName} (handle=$handle)")
            } finally {
                inFlightNewHandles.remove(handle)
            }
        }
    }

    /**
     * 相机连接就绪状态变化时由 UI 层回调。
     * 只在「未就绪 → 就绪」的上升沿触发一次加载：
     * 既避免 FULLY_CONNECTED 期间持续重刷，也避免切 Tab 回来时无谓重载（AC-4）。
     */
    fun onCameraReadyChanged(ready: Boolean) {
        val rising = ready && !lastReady
        lastReady = ready
        if (!rising) return
        if (_activeAlbum.value != AlbumSource.CAMERA) return
        _message.value = "相机已连接，正在加载相册…"
        // 这次全量加载已包含所有新照片，清掉此前累积的补拉脏标记
        pendingCaptureSync = false
        // holdPages：首次就绪加载同样一次性提交排序结果——逐页中间发射是「handle 原始序」
        // （≈拍摄时间正序），与倒序展示几乎完全逆序，DiffUtil 会把视口拖到底再弹回，
        // 表现为「刚进相册页来回滚动/滚到最底」（2026-09-07 反馈）
        loadPhotos(force = true, holdPages = true)
    }

    /**
     * 切换相册标签：相机照片 / 本地照片 / 已标记（F1）。
     */
    fun setAlbum(source: AlbumSource) {
        if (_activeAlbum.value == source) return
        _activeAlbum.value = source
        _selectedHandles.value = emptySet()
        _message.value = ""
        when (source) {
            AlbumSource.LOCAL -> if (_localPhotos.value.isEmpty()) fetchLocalPhotos()
            AlbumSource.MARKED -> {
                // 首次进入标记栏时刷新一次已下载判定，保证「跳过已下载」开关立即生效；
                // 强制对账一次（不受冷却限制），用户在系统相册删过照片也能立刻反映
                viewModelScope.launch { refreshDownloadedHandles(forceReconcile = true) }
            }
            // 优化项 3：拍摄时若不在本页而留下了补拉标记，切回来时立刻补上
            AlbumSource.CAMERA -> {
                // 切进相机照片页同样强制对账：外部删除的照片在这里应当已是「未下载」
                viewModelScope.launch { refreshDownloadedHandles(forceReconcile = true) }
                if (pendingCaptureSync) scheduleCaptureSync()
            }
        }
    }

    /**
     * 下拉刷新 / 右上角刷新：按当前标签重新拉取对应列表。
     * 「已标记」源的数据源于相机列表，刷新即重拉相机（顺带完成标记指纹校验）。
     */
    fun refreshActiveAlbum() {
        when (_activeAlbum.value) {
            AlbumSource.LOCAL -> fetchLocalPhotos()
            // 手动刷新永远打断进行中的自动加载，保证用户主动操作必有响应；
            // holdPages：刷新全程保持旧列表可见，最终一次性提交排序结果（防跳底）
            else -> fetchPhotos(force = true, holdPages = true)
        }
    }

    /**
     * 拉取已下载到手机 N-Link 目录的本地照片 / 视频。
     */
    fun fetchLocalPhotos() {
        if (!hasMediaPermission()) {
            _message.value = "未授予照片访问权限，无法显示本地照片"
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { queryLocalMedia() }
            _localPhotos.value = items
            _selectedHandles.value = emptySet()
            _isLoading.value = false
            _message.value = if (items.isEmpty()) "尚未下载照片到手机" else "本地共 ${items.size} 个文件"
            loadLocalThumbnails()
        }
    }

    private fun queryLocalMedia(): List<CameraFile> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            // 拍摄时间：DATE_TAKEN 由系统在索引时从 EXIF 的 DateTimeOriginal 提取（毫秒），
            // 拿不到时回退 DATE_MODIFIED（秒）。逐个用 ExifInterface 打开文件读原始 EXIF
            // 会退化成 O(n) 次 IO，这里直接用索引列，性能上是 O(1)。
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?)"
        val selectionArgs = arrayOf(
            "%N-Link%",
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val items = mutableListOf<CameraFile>()
        context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            // DATE_TAKEN 不是 Files 表的保证列，用 getColumnIndex（返回 -1）而非 ...OrThrow
            val takenIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex).orEmpty()
                val mime = cursor.getString(mimeIndex).orEmpty()
                val size = cursor.getLong(sizeIndex)
                val handle = (-id).toInt()
                items += CameraFile(
                    handle = handle,
                    fileName = name,
                    size = size,
                    formatCode = 0,
                    storageId = id.toInt(),
                    format = classifyLocalFormat(name, mime),
                    captureTimeMillis = resolveLocalCaptureTime(cursor, takenIndex, modifiedIndex)
                )
            }
        }
        return items
    }

    /**
     * 解析本地文件的拍摄时间：优先 DATE_TAKEN（系统从 EXIF 提取），缺失回退 DATE_MODIFIED。
     *
     * 注意两者量纲不同：DATE_TAKEN 是**毫秒**，DATE_MODIFIED 是**秒**，回退时需 ×1000。
     * 两者都取不到（列不存在或值为 0）时返回 null，由排序逻辑统一兜底到列表末尾。
     */
    private fun resolveLocalCaptureTime(
        cursor: android.database.Cursor,
        takenIndex: Int,
        modifiedIndex: Int
    ): Long? {
        val taken = if (takenIndex >= 0) cursor.getLong(takenIndex) else 0L
        if (taken > 0) return taken
        val modified = if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) else 0L
        return if (modified > 0) modified * 1000L else null
    }

    private fun classifyLocalFormat(name: String, mime: String): CameraFileFormat {
        val upper = name.uppercase()
        return when {
            mime.startsWith("video/") || upper.endsWith(".MOV") ||
                upper.endsWith(".MP4") || upper.endsWith(".AVI") -> CameraFileFormat.VIDEO
            upper.endsWith(".NEF") || upper.endsWith(".NRW") ||
                upper.endsWith(".ARW") || upper.endsWith(".CR2") ||
                upper.endsWith(".DNG") -> CameraFileFormat.RAW
            mime.startsWith("image/") || upper.endsWith(".JPG") ||
                upper.endsWith(".JPEG") || upper.endsWith(".PNG") -> CameraFileFormat.JPEG
            else -> CameraFileFormat.OTHER
        }
    }

    private fun loadLocalThumbnails() {
        viewModelScope.launch {
            for (photo in _localPhotos.value) {
                loadLocalThumbnailSuspend(photo.handle)
            }
        }
    }

    private suspend fun loadLocalThumbnailSuspend(handle: Int) {
        // 缓存命中即返回。**不能**用 _thumbnails.contains 判提前返回——
        // 该集合记录的是「曾经加载过」，而网格的转圈判定看的是**内存缓存**
        // （PhotoGridAdapter.applyThumb 用 cache.fromMemory）。内存项被 LruCache
        // 驱逐后两者脱节：集合里有 handle、界面上却无图，旧逻辑第一行就返回、
        // 永远不再加载 → 「再次打开本地相册全部转圈」。
        if (thumbnailCache.fromMemory(handle) != null) return
        val bitmap = thumbnailCache.get(handle) ?: withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    localContentUri(handle),
                    Size(512, 512),
                    null
                )
            }.getOrNull()
        }
        if (bitmap != null) {
            // 本地缩略图同时落盘：进程重启后二次打开直接读盘秒出，
            // 不必再逐张走 MediaStore 解码
            thumbnailCache.putBitmap(handle, bitmap, persist = true)
            if (_thumbnails.value.contains(handle)) {
                // handle 已在集合（此前加载过、内存被驱逐后重载）：Set 相等
                // 不会触发 StateFlow 发射，用升级计数通知网格重绘可见项
                _thumbUpgradeTick.value = _thumbUpgradeTick.value + 1
            } else {
                _thumbnails.value = _thumbnails.value + handle
            }
        }
    }

    /** 本地照片的 MediaStore content URI */
    fun localContentUri(handle: Int): Uri {
        return Uri.withAppendedPath(
            MediaStore.Files.getContentUri("external"),
            (-handle).toString()
        )
    }

    fun selectedLocalUris(): List<Uri> {
        return _localPhotos.value
            .filter { it.handle in _selectedHandles.value }
            .map { localContentUri(it.handle) }
    }

    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun deleteLocalSelected(files: List<CameraFile>) {
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = 0
            files.forEach { file ->
                runCatching {
                    context.contentResolver.delete(localContentUri(file.handle), null, null)
                }.onSuccess { count ->
                    if (count > 0) deleted++
                }
            }
            val deletedHandles = files.map { it.handle }.toSet()
            _localPhotos.value = _localPhotos.value.filterNot { it.handle in deletedHandles }
            _thumbnails.value = _thumbnails.value - deletedHandles
            _selectedHandles.value = emptySet()
            _message.value = if (deleted > 0) "已删除 $deleted 个本地文件" else "删除失败，请检查文件权限"
            // v1.3.0（需求 3）：本地文件没了 → 相机照片页的「已下载」状态必须同步恢复为未下载
            // （角标消失、「未下载」筛选下重新出现、剩余进度回升）。强制对账一次，不受冷却限制。
            if (deleted > 0) refreshDownloadedHandles(forceReconcile = true)
        }
    }

    /**
     * 按需加载缩略图（可见项优先，并发窗口 3）。
     * 两级缓存优先：内存 → 磁盘，未命中才走 PTP 网络请求。
     */
    /**
     * 缩略图请求（渐进式：缓存 → 小图秒出 → 后台升高清）。
     *
     * 旧实现只走「高清优先」，单张数百 KB 且并发窗口仅 3，首屏必然长时间转圈。
     * 现在分两段：
     *   1. 缓存命中直接返回；未命中先取 0x100A 小图（几 KB）立刻填满网格，消除转圈
     *   2. 后台再用 0x90C4 高清覆盖同一缓存键并通知重绘
     * **最终展示的仍是高清预览，画质不降低**，只是把等待从阻塞改为后台替换。
     */
    fun requestThumbnail(handle: Int) {
        if (handle < 0) {
            viewModelScope.launch { loadLocalThumbnailSuspend(handle) }
            return
        }
        if (_thumbnails.value.contains(handle) || !pendingThumbs.add(handle)) {
            // 已有图（哪怕是缓存的小图）→ 仍然尝试后台升高清，保证最终画质
            if (_thumbnails.value.contains(handle)) upgradeThumbnailToHd(handle)
            return
        }
        viewModelScope.launch {
            thumbSemaphore.withPermit {
                try {
                    if (_thumbnails.value.contains(handle)) return@withPermit
                    var bitmap = thumbnailCache.fromMemory(handle)
                        ?: thumbnailCache.get(handle)
                    if (bitmap == null) {
                        // 第一段：小图秒出
                        bitmap = transferManager.fetchThumbnailFast(handle)
                            ?.let { thumbnailCache.putBytes(handle, it) }
                    }
                    if (bitmap != null) {
                        _thumbnails.value = _thumbnails.value + handle
                        // 第二段：后台升高清（不阻塞本通道）
                        upgradeThumbnailToHd(handle)
                    }
                } finally {
                    pendingThumbs.remove(handle)
                }
            }
        }
    }

    /**
     * 后台把某张缩略图升级为高清预览（0x90C4）。
     * 失败静默——网格继续显示已到位的小图，不影响可用性。
     */
    private fun upgradeThumbnailToHd(handle: Int) {
        if (handle < 0 || !pendingHdThumbs.add(handle)) return
        viewModelScope.launch {
            hdThumbSemaphore.withPermit {
                try {
                    val hd = transferManager.fetchThumbnail(handle) ?: return@withPermit
                    if (thumbnailCache.putBytes(handle, hd) != null) {
                        _thumbnails.value = _thumbnails.value + handle
                        _thumbUpgradeTick.value = _thumbUpgradeTick.value + 1
                    }
                } catch (e: Exception) {
                    Timber.tag("TransferVM").d("HD thumb upgrade failed handle=$handle: ${e.message}")
                } finally {
                    pendingHdThumbs.remove(handle)
                }
            }
        }
    }

    /** 后台低优先级预热已缓存缩略图（磁盘命中免网络），新照片留给可见项触发下载 */
    private fun prewarmThumbnails(handles: List<Int>) {
        prewarmJob?.cancel()
        prewarmJob = viewModelScope.launch {
            for (handle in handles) {
                if (_thumbnails.value.contains(handle)) continue
                val bitmap = thumbnailCache.fromMemory(handle) ?: thumbnailCache.get(handle) ?: continue
                _thumbnails.value = _thumbnails.value + handle
            }
        }
    }

    fun setPhotoFilter(filter: PhotoFilter) {
        _photoFilter.value = filter
    }

    fun toggleSelection(handle: Int) {
        val current = _selectedHandles.value.toMutableSet()
        if (!current.add(handle)) {
            current.remove(handle)
        }
        _selectedHandles.value = current
    }

    /**
     * 模块 4.4 长按滑动多选：批量选中/取消一段连续项。
     * 一次调用一次状态发射，DiffUtil 以 payload 批量播放勾选动画（滑动过程中连续反馈）。
     */
    fun setSelectionRange(handles: List<Int>, select: Boolean) {
        if (handles.isEmpty()) return
        val current = _selectedHandles.value.toMutableSet()
        if (select) current.addAll(handles) else current.removeAll(handles.toSet())
        _selectedHandles.value = current
    }

    fun selectAllFiltered() {
        _selectedHandles.value = filteredPhotos.value.mapTo(mutableSetOf()) { it.handle }
    }

    /** 「已标记」栏全选：选中当前展示列表（已应用跳过已下载）全部可下载项 */
    fun selectAllMarked() {
        _selectedHandles.value = markedDisplayList.value.mapTo(mutableSetOf()) { it.handle }
    }

    /**
     * 按日期分组的整组全选 / 取消全选。
     *
     * 组内全部已选 → 取消该组；否则 → 把该组并入选中集（不动其它分组的选中）。
     * 只改 [selectedHandles]，UI 的已选数量、底栏按钮、勾选动画都由既有的
     * 收集链路驱动，因此与其它入口（单张点选、底部全选）天然同步。
     */
    fun toggleGroupSelection(handles: List<Int>) {
        if (handles.isEmpty()) return
        val current = _selectedHandles.value.toMutableSet()
        if (handles.all { it in current }) {
            current.removeAll(handles.toSet())
        } else {
            current.addAll(handles)
        }
        _selectedHandles.value = current
    }

    fun clearSelection() {
        _selectedHandles.value = emptySet()
    }

    // ---------- F1：标记 / 取消标记 ----------

    /**
     * 底栏「标记」按钮的切换语义：选中集全部已标记 → 取消标记；否则 → 打标。
     * 返回实际动作方向，供按钮文案切换（true = 打标，false = 取消）。
     */
    suspend fun toggleMarkSelection(): Boolean {
        val files = selectedCameraFiles()
        if (files.isEmpty()) {
            _message.value = "请先选择要标记的照片"
            return true
        }
        val marked = markedHandles.value
        val allMarked = files.all { it.handle in marked }
        if (allMarked) {
            photoMarkRepository.unmark(files)
            _message.value = "已取消 ${files.size} 个标记"
        } else {
            photoMarkRepository.mark(files)
            _message.value = "已标记 ${files.size} 张，可在「已标记」栏批量下载"
            // F1 可选增强：标记后自动入队下载原图（设置开关，默认关）
            if (settings.markAutoDownload && transferManager.hasActiveSession()) {
                transferManager.enqueue(files)
            }
        }
        return !allMarked
    }

    /** 清除指定文件的标记（预览页取消星标入口） */
    suspend fun unmarkFile(file: CameraFile) {
        photoMarkRepository.unmark(listOf(file))
    }

    /** 打标单个文件（预览页加星标入口），返回打标后的状态 */
    suspend fun markFile(file: CameraFile): Boolean {
        photoMarkRepository.mark(listOf(file))
        if (settings.markAutoDownload && transferManager.hasActiveSession()) {
            transferManager.enqueue(listOf(file))
        }
        return true
    }

    /** AC-5：批量下载完成后「清除已下载项的标记」 */
    fun clearDownloadedMarks() {
        val files = markedPhotos.value.filter { it.handle in _downloadedHandles.value }
        if (files.isEmpty()) return
        viewModelScope.launch {
            photoMarkRepository.unmark(files)
            _message.value = "已清除 ${files.size} 个已下载项的标记"
        }
    }

    /** 当前选中集映射到相机文件（仅相机源有效；标记栏复用同一选中集语义） */
    private fun selectedCameraFiles(): List<CameraFile> {
        val source = _activeAlbum.value
        val photos = when (source) {
            AlbumSource.MARKED -> markedPhotos.value
            else -> displayedPhotos.value
        }
        return photos.filter { it.handle in _selectedHandles.value }
    }

    /**
     * F1：「已标记」栏批量下载原图。
     * 入队走现有 TransferManager（去重/断点续传不变）；登记候选集，
     * 队列排空后由 init 的监听弹「清除标记」确认。返回实际入队张数。
     */
    fun downloadMarkedSelected(): Int {
        val selected = selectedCameraFiles()
        if (selected.isEmpty()) {
            _message.value = "请先选择要下载的照片"
            return 0
        }
        val candidates = if (_skipDownloadedInMarks.value) {
            selected.filterNot { it.handle in _downloadedHandles.value }
        } else {
            selected
        }
        if (candidates.isEmpty()) {
            _message.value = "所选照片均已下载"
            return 0
        }
        if (!transferManager.hasActiveSession()) {
            _message.value = "相机未连接，请先在「设备」页连接后再收片"
            return 0
        }
        pendingClearCandidates = candidates.mapTo(mutableSetOf()) { it.handle }
        transferManager.enqueue(candidates)
        _message.value = "已加入队列: ${candidates.size} 个文件"
        return candidates.size
    }

    // ---------- F4：批量分享预览副本 ----------

    /** 导出进度 (已完成, 总数)；null = 空闲 */
    private val _shareExportProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val shareExportProgress: StateFlow<Pair<Int, Int>?> = _shareExportProgress.asStateFlow()

    /** 导出完成的一次性事件，载荷带可分享 URI 列表与跳过/失败清单 */
    private val _shareExportDone = MutableSharedFlow<PreviewShareExporter.ExportResult>(extraBufferCapacity = 1)
    val shareExportDone: Flow<PreviewShareExporter.ExportResult> = _shareExportDone

    /**
     * 相机源多选「分享」：为选中的 JPEG 生成预览副本后拉起分享面板。
     * RAW/视频不在副本支持范围，计入跳过并提示（PRD F4 AC-4）。
     */
    fun shareSelectedCameraCopies() {
        val selected = selectedCameraFiles()
        if (selected.isEmpty()) {
            _message.value = "请先选择要分享的照片"
            return
        }
        if (!transferManager.hasActiveSession()) {
            _message.value = "相机未连接，无法生成分享副本"
            return
        }
        val eligible = selected.filter { previewShareExporter.supportsPreviewCopy(it) }
        val skippedCount = selected.size - eligible.size
        if (eligible.isEmpty()) {
            _message.value = "所选文件暂不支持生成分享副本（RAW/视频请先下载原图）"
            return
        }
        viewModelScope.launch {
            _shareExportProgress.value = 0 to eligible.size
            val result = previewShareExporter.export(eligible) { done, total ->
                _shareExportProgress.value = done to total
            }
            _shareExportProgress.value = null
            val note = if (skippedCount > 0) "，已跳过 $skippedCount 个不支持的文件" else ""
            _message.value = when {
                result.failed.isEmpty() -> "已生成 ${result.uris.size} 个分享副本$note"
                else -> "已生成 ${result.uris.size} 个，${result.failed.size} 个失败$note"
            }
            _shareExportDone.emit(result)
        }
    }


    /**
     * 加载缩略图（旧入口，已由 requestThumbnail 接管）
     */
    @Deprecated("Use requestThumbnail", ReplaceWith("requestThumbnail(handle)"))
    fun loadThumbnail(handle: Int) {
        requestThumbnail(handle)
    }

    private fun loadLocalThumbnail(handle: Int) {
        viewModelScope.launch { loadLocalThumbnailSuspend(handle) }
    }

    /**
     * 下载单张照片
     */
    fun downloadPhoto(file: CameraFile) {
        viewModelScope.launch {
            val result = transferManager.downloadPhoto(file)
            _message.value = when (result) {
                is TransferResult.Success -> "已保存: ${file.fileName}"
                is TransferResult.Failed -> "下载失败: ${result.reason}"
                is TransferResult.Cancelled -> "已取消"
            }
        }
    }

    /**
     * 批量下载
     */
    fun downloadSelected(files: List<CameraFile>) {
        transferManager.enqueue(files)
        _message.value = "已加入队列: ${files.size} 个文件"
    }

    fun downloadSelected() {
        // F1：已标记栏的下载入口走专用链路（跳过已下载 + 完成后清标记提示）
        if (_activeAlbum.value == AlbumSource.MARKED) {
            downloadMarkedSelected()
            return
        }
        val selected = displayedPhotos.value.filter { it.handle in _selectedHandles.value }
        if (selected.isEmpty()) {
            _message.value = "请先选择要下载的照片"
            return
        }
        if (_activeAlbum.value == AlbumSource.LOCAL) {
            _message.value = "本地照片已保存在手机，可分享或删除"
            return
        }
        if (!transferManager.hasActiveSession() && !usbPtpManager.isConnected()) {
            _message.value = "相机未连接，无法下载"
            return
        }
        transferManager.enqueue(selected)
        _message.value = "已加入队列: ${selected.size} 个文件"
    }

    /**
     * 从相机存储卡删除选中的文件。
     */
    fun deleteSelected() {
        // F1：已标记栏的删除目标取「标记 ∩ 相机列表」，不落到本地相册
        val selected = selectedCameraFiles()
        if (selected.isEmpty()) {
            _message.value = "请先选择要删除的照片"
            return
        }
        if (_activeAlbum.value == AlbumSource.LOCAL) {
            deleteLocalSelected(selected)
            return
        }
        if (!transferManager.hasActiveSession()) {
            _message.value = "相机未连接，无法删除"
            return
        }
        viewModelScope.launch {
            val deleted = transferManager.deleteFiles(selected)
            if (deleted.isEmpty()) {
                _message.value = "删除失败，相机可能不支持该操作"
                return@launch
            }
            val deletedSet = deleted.toSet()
            _photoList.value = _photoList.value.filterNot { it.handle in deletedSet }
            _thumbnails.value = _thumbnails.value - deletedSet
            _selectedHandles.value = _selectedHandles.value - deletedSet
            // F1 AC-6：相机端文件已删，对应标记即时失效清理，避免「已标记」栏出现残影
            photoMarkRepository.unmark(selected.filter { it.handle in deletedSet })
            _message.value = "已从相机删除 ${deleted.size} 个文件"
        }
    }

    fun downloadFiltered() {
        if (_activeAlbum.value == AlbumSource.LOCAL) return
        val files = filteredPhotos.value
        if (files.isNotEmpty()) {
            transferManager.enqueue(files)
            _message.value = "已加入队列: ${files.size} 个文件"
        }
    }

    /**
     * 全部下载
     */
    fun downloadAll() {
        if (_activeAlbum.value == AlbumSource.LOCAL) return
        // 与 selectAllFiltered() 保持同一数据源：全选选的是 filteredPhotos，
        // 这里若用 _photoList 会把视频等被筛掉的项也拉进下载队列
        val all = filteredPhotos.value
        if (all.isNotEmpty()) {
            transferManager.enqueue(all)
            _message.value = "全部加入队列: ${all.size} 个文件"
        }
    }

    fun pauseTransfer() = transferManager.pause()
    fun resumeTransfer() = transferManager.resume()
    fun cancelAll() = transferManager.cancelAll()
}

/**
 * 相册数据源：相机机身 / 手机本地 / 已标记（F1 独立分区）。
 */
enum class AlbumSource(val label: String) {
    CAMERA("相机照片"),
    LOCAL("本地照片"),
    MARKED("已标记")
}

/**
 * 影像筛选：全部 / 照片 / 视频 / RAW / JPG
 * （旧「按日期」选项无实际过滤逻辑，已移除）
 */
enum class PhotoFilter(val label: String) {
    ALL("全部"),
    PHOTOS("照片"),
    VIDEO("视频"),
    RAW("RAW"),
    JPEG("JPG");

    fun matches(file: CameraFile): Boolean {
        return when (this) {
            ALL -> true
            PHOTOS -> file.isPhoto
            VIDEO -> file.format == CameraFileFormat.VIDEO
            JPEG -> file.format == CameraFileFormat.JPEG
            RAW -> file.format == CameraFileFormat.RAW
        }
    }
}

/**
 * O2：剩余下载进度快照。
 *
 * @param total 相机照片总数（未过滤的原始列表长度）
 * @param downloaded 其中已下载到手机的数量
 */
data class DownloadStats(
    val total: Int,
    val downloaded: Int
) {
    /** 剩余未下载张数 */
    val remaining: Int get() = (total - downloaded).coerceAtLeast(0)

    /** 下载完成百分比（0-100），空列表时为 0 */
    val percent: Int get() = if (total == 0) 0 else (downloaded * 100 / total).coerceIn(0, 100)
}

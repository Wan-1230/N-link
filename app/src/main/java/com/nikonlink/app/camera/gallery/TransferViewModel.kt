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
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionManager
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
        // F2：先把「未下载」筛选折叠进数据源（关闭时原样透传，行为与 v0.1.4 完全一致）
        combine(displayedPhotos, _onlyNotDownloaded, _downloadedHandles) { photos, hideDownloaded, downloaded ->
            if (hideDownloaded) photos.filter { it.handle !in downloaded } else photos
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

    /** 已完成缩略图加载的 handle 集合（只用于局部刷新负载），Bitmap 统一由 ThumbnailCache 管理 */
    private val _thumbnails = MutableStateFlow<Set<Int>>(emptySet())
    val thumbnails: StateFlow<Set<Int>> = _thumbnails.asStateFlow()

    /** 缩略图按需加载并发控制（可见项优先，最多 3 个并发 PTP 请求） */
    private val thumbSemaphore = Semaphore(3)
    private val pendingThumbs = mutableSetOf<Int>()
    private var prewarmJob: Job? = null

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

    /** 查询当前相机列表中已成功下载过的 handle 子集（传输历史一次性批量查询） */
    private suspend fun refreshDownloadedHandles() {
        val handles = _photoList.value.map { it.handle }.filter { it > 0 }
        if (handles.isEmpty()) {
            _downloadedHandles.value = emptySet()
            return
        }
        _downloadedHandles.value = transferManager.queryDownloadedHandles(handles)
    }

    /** UI 层订阅：批量下载完成后弹「是否清除这些标记」（载荷 = 可清除张数，0 不弹） */
    val clearMarksPrompt: Flow<Int> = _clearMarksPrompt

    // 注：TransferManager 由 ConnectionManager 以应用级 scope 启动（支撑后台自动下载）

    /**
     * 获取相机照片列表
     * @param force 透传给 [loadPhotos]，手动刷新时为 true
     */
    fun fetchPhotos(force: Boolean = false) {
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
     */
    private fun loadPhotos(force: Boolean = false, silent: Boolean = false) {
        if (!force && !loadingGuard.compareAndSet(false, true)) {
            Timber.tag(TAG).d("Photo loading already in progress, skip")
            return
        }
        loadingGuard.set(true)
        loadJob?.cancel()
        if (!silent) _isLoading.value = true
        loadJob = viewModelScope.launch {
            try {
                // 媒体列表按 limit=18 分页，每页完成后立即刷新网格，
                // 避免照片多时等待整份列表返回才看到内容。
                // 这里存原始顺序即可：分页期间排序没有意义（数据不完整），
                // 且每页重排会让列表不断跳动；排序统一由 filteredPhotos 在加载完成后做全量处理。
                val photos = transferManager.fetchPhotoList(
                    onPage = { page -> _photoList.value = page }
                )
                _photoList.value = photos
                if (!silent) {
                    // 静默刷新不能清勾选：用户可能正勾着一批待下载项在连拍，
                    // 且 handle 是稳定的（新照片只会拿到新 handle），保留勾选是安全的。
                    _selectedHandles.value = emptySet()
                    _message.value = if (photos.isEmpty()) "存储卡为空或未连接" else "共 ${photos.size} 个文件"
                }
                if (photos.isNotEmpty()) {
                    // F1 失效自愈：全量列表到手后做指纹校验，清理机内已删除/换卡失效的标记。
                    // 仅在非空列表时校验——空列表可能是抓取失败，此时清理会把全部标记误删。
                    runCatching { photoMarkRepository.reconcile(photos) }
                        .onFailure { Timber.tag(TAG).w(it, "Photo mark reconcile failed") }
                    refreshDownloadedHandles()
                }
                // 后台渐进取预热缩略图；可见项由 Adapter 按需触发
                prewarmThumbnails(photos.map { it.handle })
            } finally {
                if (!silent) _isLoading.value = false
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
        pendingCaptureSync = false
        captureSyncJob?.cancel()
        captureSyncJob = viewModelScope.launch {
            delay(CAPTURE_SYNC_WINDOW_MS)
            Timber.tag(TAG).i("Auto syncing album after capture")
            loadPhotos(force = false, silent = true)
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
        loadPhotos(force = true)
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
                // 首次进入标记栏时刷新一次已下载判定，保证「跳过已下载」开关立即生效
                viewModelScope.launch { refreshDownloadedHandles() }
            }
            // 优化项 3：拍摄时若不在本页而留下了补拉标记，切回来时立刻补上
            AlbumSource.CAMERA -> if (pendingCaptureSync) scheduleCaptureSync()
        }
    }

    /**
     * 下拉刷新 / 右上角刷新：按当前标签重新拉取对应列表。
     * 「已标记」源的数据源于相机列表，刷新即重拉相机（顺带完成标记指纹校验）。
     */
    fun refreshActiveAlbum() {
        when (_activeAlbum.value) {
            AlbumSource.LOCAL -> fetchLocalPhotos()
            // 手动刷新永远打断进行中的自动加载，保证用户主动操作必有响应
            else -> fetchPhotos(force = true)
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
        if (_thumbnails.value.contains(handle)) return
        val cached = thumbnailCache.fromMemory(handle) ?: thumbnailCache.get(handle)
        if (cached == null) {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(
                        localContentUri(handle),
                        Size(512, 512),
                        null
                    )
                }.getOrNull()
            }
            if (bitmap != null) {
                thumbnailCache.putBitmap(handle, bitmap)
            }
        }
        _thumbnails.value = _thumbnails.value + handle
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
        }
    }

    /**
     * 按需加载缩略图（可见项优先，并发窗口 3）。
     * 两级缓存优先：内存 → 磁盘，未命中才走 PTP 网络请求。
     */
    fun requestThumbnail(handle: Int) {
        if (handle < 0) {
            viewModelScope.launch { loadLocalThumbnailSuspend(handle) }
            return
        }
        if (_thumbnails.value.contains(handle) || !pendingThumbs.add(handle)) return
        viewModelScope.launch {
            thumbSemaphore.withPermit {
                try {
                    if (_thumbnails.value.contains(handle)) return@withPermit
                    val bitmap = thumbnailCache.fromMemory(handle)
                        ?: thumbnailCache.get(handle)
                        ?: transferManager.fetchThumbnail(handle)
                            ?.let { thumbnailCache.putBytes(handle, it) }
                    if (bitmap != null) {
                        _thumbnails.value = _thumbnails.value + handle
                    }
                } finally {
                    pendingThumbs.remove(handle)
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

package com.nikonlink.app.camera.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.app.device.model.ConnectionState
import com.nikonlink.app.device.connect.ConnectionManager
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbConnectionState
import com.nikonlink.app.device.usb.UsbPtpManager
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
    private val thumbnailCache: ThumbnailCache
) : ViewModel() {

    private val _photoList = MutableStateFlow<List<CameraFile>>(emptyList())
    val photoList: StateFlow<List<CameraFile>> = _photoList.asStateFlow()

    private val _localPhotos = MutableStateFlow<List<CameraFile>>(emptyList())
    val localPhotos: StateFlow<List<CameraFile>> = _localPhotos.asStateFlow()

    private val _activeAlbum = MutableStateFlow(AlbumSource.CAMERA)
    val activeAlbum: StateFlow<AlbumSource> = _activeAlbum.asStateFlow()

    /** 当前标签页展示的列表：相机照片或本地照片 */
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

    val filteredPhotos: StateFlow<List<CameraFile>> = combine(
        displayedPhotos,
        _photoFilter
    ) { photos, filter ->
        photos.filter { filter.matches(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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

    // 注：TransferManager 由 ConnectionManager 以应用级 scope 启动（支撑后台自动下载）

    /**
     * 获取相机照片列表
     */
    fun fetchPhotos() {
        if (!transferManager.hasActiveSession()) {
            val usbConnected = usbPtpManager.isConnected()
            if (usbConnected) {
                _message.value = "正在建立 USB 通道..."
                viewModelScope.launch {
                    val connected = usbPtpManager.isConnected()
                    if (connected) {
                        loadPhotos()
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
                        loadPhotos()
                    } else {
                        _isLoading.value = false
                        _message.value = "USB 连接尚未就绪，请检查相机 USB 模式（PTP）"
                    }
                }
                return
            }
            _message.value = "正在建立 WiFi 通道..."
            connectionManager.requestWifiReconnect()
            viewModelScope.launch {
                val connected = usbPtpManager.isConnected() || connectionManager.awaitPtpSession()
                if (connected) {
                    loadPhotos()
                } else {
                    _isLoading.value = false
                    _message.value = "WiFi 通道未就绪，请先在连接页完成配对"
                }
            }
            return
        }
        loadPhotos()
    }

    private fun loadPhotos() {
        _isLoading.value = true
        viewModelScope.launch {
            // 媒体列表按 limit=18 分页，每页完成后立即刷新网格，
            // 避免照片多时等待整份列表返回才看到内容。
            val photos = transferManager.fetchPhotoList(
                onPage = { page -> _photoList.value = page }
            )
            _photoList.value = photos
            _selectedHandles.value = emptySet()
            _isLoading.value = false
            _message.value = if (photos.isEmpty()) "存储卡为空或未连接" else "共 ${photos.size} 个文件"
            // 后台渐进取预热缩略图；可见项由 Adapter 按需触发
            prewarmThumbnails(photos.map { it.handle })
        }
    }

    /**
     * 切换相册标签：相机照片 / 本地照片。
     */
    fun setAlbum(source: AlbumSource) {
        if (_activeAlbum.value == source) return
        _activeAlbum.value = source
        _selectedHandles.value = emptySet()
        _message.value = ""
        if (source == AlbumSource.LOCAL && _localPhotos.value.isEmpty()) {
            fetchLocalPhotos()
        }
    }

    /**
     * 下拉刷新 / 右上角刷新：按当前标签重新拉取对应列表。
     */
    fun refreshActiveAlbum() {
        if (_activeAlbum.value == AlbumSource.CAMERA) {
            fetchPhotos()
        } else {
            fetchLocalPhotos()
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
            MediaStore.Files.FileColumns.MEDIA_TYPE
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
                    format = classifyLocalFormat(name, mime)
                )
            }
        }
        return items
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

    fun selectAllFiltered() {
        _selectedHandles.value = filteredPhotos.value.mapTo(mutableSetOf()) { it.handle }
    }

    fun clearSelection() {
        _selectedHandles.value = emptySet()
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
        val selected = displayedPhotos.value.filter { it.handle in _selectedHandles.value }
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
        val all = _photoList.value
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
 * 相册数据源：相机机身 / 手机本地。
 */
enum class AlbumSource(val label: String) {
    CAMERA("相机照片"),
    LOCAL("本地照片")
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

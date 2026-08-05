package com.nikonlink.app.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.connection.ConnectionManager
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.usb.UsbConnectionState
import com.nikonlink.app.core.usb.UsbPtpManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 照片传输 ViewModel
 * PRD 2.1: 浏览相机存储卡照片列表、缩略图预览、选择性下载
 */
@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferManager: TransferManager,
    private val ptpSession: PtpSessionManager,
    private val connectionManager: ConnectionManager,
    private val usbPtpManager: UsbPtpManager
) : ViewModel() {

    private val _photoList = MutableStateFlow<List<CameraFile>>(emptyList())
    val photoList: StateFlow<List<CameraFile>> = _photoList.asStateFlow()

    private val _photoFilter = MutableStateFlow(PhotoFilter.ALL)
    val photoFilter: StateFlow<PhotoFilter> = _photoFilter.asStateFlow()

    private val _selectedHandles = MutableStateFlow<Set<Int>>(emptySet())
    val selectedHandles: StateFlow<Set<Int>> = _selectedHandles.asStateFlow()

    val filteredPhotos: StateFlow<List<CameraFile>> = combine(
        _photoList,
        _photoFilter
    ) { photos, filter ->
        photos.filter { filter.matches(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val transferState: StateFlow<TransferState> = transferManager.transferState
    val queue: StateFlow<List<TransferTask>> = transferManager.queue

    private val _thumbnails = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val thumbnails: StateFlow<Map<Int, ByteArray>> = _thumbnails.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 供 UI 层显示轻量状态消息 */
    fun showMessage(msg: String) {
        _message.value = msg
    }

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val statusMessage: StateFlow<String> = connectionManager.statusMessage
    val usbState: StateFlow<UsbConnectionState> = usbPtpManager.usbState

    init {
        transferManager.start(viewModelScope)
    }

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
            // 参考影犀日志: 媒体列表按 limit=18 分页，每页完成后立即刷新网格，
            // 避免照片多时等待整份列表返回才看到内容。
            val photos = transferManager.fetchPhotoList(
                onPage = { page -> _photoList.value = page }
            )
            _photoList.value = photos
            _selectedHandles.value = emptySet()
            _isLoading.value = false
            _message.value = if (photos.isEmpty()) "存储卡为空或未连接" else "共 ${photos.size} 个文件"
            // 加载全部缩略图，实现相册式实时预览
            loadAllThumbnails()
        }
    }

    /**
     * 加载全部照片缩略图（实时预览）
     * 逐个加载，加载完一张立即刷新一张
     */
    private fun loadAllThumbnails() {
        viewModelScope.launch {
            for (photo in _photoList.value) {
                if (_thumbnails.value.containsKey(photo.handle)) continue
                val thumb = transferManager.fetchThumbnail(photo.handle)
                if (thumb != null) {
                    _thumbnails.value = _thumbnails.value + (photo.handle to thumb)
                }
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
     * 加载缩略图
     */
    fun loadThumbnail(handle: Int) {
        viewModelScope.launch {
            if (_thumbnails.value.containsKey(handle)) return@launch
            val thumb = transferManager.fetchThumbnail(handle)
            if (thumb != null) {
                _thumbnails.value = _thumbnails.value + (handle to thumb)
            }
        }
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
        val selected = _photoList.value.filter { it.handle in _selectedHandles.value }
        if (selected.isEmpty()) {
            _message.value = "请先选择要下载的照片"
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
        val selected = _photoList.value.filter { it.handle in _selectedHandles.value }
        if (selected.isEmpty()) {
            _message.value = "请先选择要删除的照片"
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
            _thumbnails.value = _thumbnails.value.filterKeys { it !in deletedSet }
            _selectedHandles.value = _selectedHandles.value - deletedSet
            _message.value = "已从相机删除 ${deleted.size} 个文件"
        }
    }

    fun downloadFiltered() {
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
 * 影像筛选：全部 / 照片 / 视频 / RAW / JPG / 按日期
 */
enum class PhotoFilter(val label: String) {
    ALL("全部"),
    PHOTOS("照片"),
    VIDEO("视频"),
    RAW("RAW"),
    JPEG("JPG"),
    DATE("按日期");

    fun matches(file: CameraFile): Boolean {
        return when (this) {
            ALL, DATE -> true
            PHOTOS -> file.isPhoto
            VIDEO -> file.format == CameraFileFormat.VIDEO
            JPEG -> file.format == CameraFileFormat.JPEG
            RAW -> file.format == CameraFileFormat.RAW
        }
    }
}

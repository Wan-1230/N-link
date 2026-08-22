package com.nikonlink.app.camera.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.device.wifi_ap.WifiManager
import com.nikonlink.app.camera.data.TransferRepository
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.common.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 照片传输管理器
 *
 * PRD 2.1 照片传输/下载:
 * - 自动传输：相机拍摄后自动传输至手机 (P0)
 * - 手动浏览下载：浏览照片列表，缩略图预览，选择性下载 (P0)
 * - 后台静默传输：App 在后台时持续接收新照片 (P0)
 * - 传输队列管理：暂停/恢复/取消，多任务排队 (P1)
 * - 断点续传：传输中断后自动从断点恢复 (P1)
 *
 * PRD 5.1: 照片传输速率 > 10MB/s (WiFi 5GHz)
 */
@Singleton
class TransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val transferRepository: TransferRepository,
    private val wifiManager: WifiManager,
    private val settings: AppSettings,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "TransferMgr"
        // 大分块降低每字节往返开销（4MB/RTT），失败时减半降级到 1MB，
        // 兼容只支持小分块的老相机（部分机型 GetPartialObject 上限 ~1MB）
        private const val PARTIAL_CHUNK_SIZE = 4 * 1024 * 1024
        private const val MIN_PARTIAL_CHUNK_SIZE = 1024 * 1024
        // 媒体列表分页 limit=18，逐页加载避免一次性阻塞
        private const val PAGE_SIZE = 18
        /** ObjectInfo 读取并发窗口（PTP 命令通道本身串行，窗口用于 USB 多段与响应叠加） */
        private const val OBJECT_INFO_CONCURRENCY = 4
        private const val SPEED_WINDOW_MS = 2000L
        /** 自动下载：单次同步上限与去抖间隔（防止相机连拍时涌进大量任务） */
        private const val AUTO_SYNC_MAX_FILES = 20
        private const val AUTO_SYNC_DEBOUNCE_MS = 60_000L
        /** 压缩画质：长边上限与 JPEG 质量 */
        private const val COMPRESS_MAX_EDGE = 3200
        private const val COMPRESS_JPEG_QUALITY = 90
    }

    private var scope: CoroutineScope? = null
    private val transferQueue = mutableListOf<TransferTask>()
    private var currentTask: TransferTask? = null
    private var currentJob: Job? = null
    private var isPaused = false

    private var autoSyncJob: Job? = null
    private var lastAutoSyncAt = 0L

    private val ptpTransport: CameraTransport = PtpTransport(ptpSession)
    private val usbTransport: CameraTransport = UsbTransport(usbPtpManager)

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val _queue = MutableStateFlow<List<TransferTask>>(emptyList())
    val queue: StateFlow<List<TransferTask>> = _queue.asStateFlow()

    /** 全链路优化: 面向用户的消息流（成功/失败/通道切换都有反馈，不再静默） */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 当前传输速率（bytes/s），用于验证 5GHz 高速通道目标。 */
    private val _transferSpeedBps = MutableStateFlow(0L)
    val transferSpeedBps: StateFlow<Long> = _transferSpeedBps.asStateFlow()

    private val speedSamples = ArrayDeque<Pair<Long, Long>>()

    private fun postMessage(msg: String) {
        _message.value = msg
        Timber.tag(TAG).i("Msg: $msg")
    }

    /** 用最近 2 秒的累计字节滑动窗口计算实时速率。 */
    private fun updateTransferSpeed(receivedBytes: Long) {
        val now = System.currentTimeMillis()
        val first = speedSamples.peekFirst()
        if (first != null && receivedBytes < first.second) {
            speedSamples.clear()
            _transferSpeedBps.value = 0
        }
        speedSamples.addLast(now to receivedBytes)
        while (speedSamples.size > 1 && speedSamples.first().first < now - SPEED_WINDOW_MS) {
            speedSamples.removeFirst()
        }

        val windowFirst = speedSamples.peekFirst() ?: return
        val windowLast = speedSamples.peekLast() ?: return
        if (windowLast.first > windowFirst.first && windowLast.second >= windowFirst.second) {
            val elapsedSec = (windowLast.first - windowFirst.first) / 1000.0
            val bytes = windowLast.second - windowFirst.second
            _transferSpeedBps.value = if (elapsedSec > 0) (bytes / elapsedSec).toLong() else 0
        }
    }

    private fun resetTransferSpeed() {
        speedSamples.clear()
        _transferSpeedBps.value = 0
    }

    /** 当前生效的数据通道（UI 展示用）：按连接偏好选择，断线自动回退另一通道 */
    fun activeChannel(): String = when {
        usbPtpManager.isConnected() && ptpSession.isConnected() ->
            if (settings.connectionPreference == AppSettings.CONN_PREF_WIFI) "WiFi" else "USB 有线"
        usbPtpManager.isConnected() -> "USB 有线"
        ptpSession.isConnected() -> "WiFi"
        else -> "未连接"
    }

    /** 主通道失败时的兑底通道（USB↔WiFi 互为兑底） */
    private fun fallbackOf(primary: CameraTransport): CameraTransport? = when {
        primary === usbTransport && ptpSession.isConnected() -> ptpTransport
        primary === ptpTransport && usbPtpManager.isConnected() -> usbTransport
        else -> null
    }

    fun start(scope: CoroutineScope) {
        this.scope = scope
        Timber.tag(TAG).i("TransferManager started")
    }

    /**
     * 自动下载（设置「自动下载新照片」开启时由连接层触发）：
     * 连接就绪后同步最新照片列表，过滤已下载的并限幅入队，60s 去抖。
     */
    fun scheduleAutoSync() {
        if (!settings.autoDownload) return
        if (!hasActiveSession()) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAt < AUTO_SYNC_DEBOUNCE_MS) return
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = scope?.launch {
            try {
                lastAutoSyncAt = System.currentTimeMillis()
                if (isPaused) return@launch
                val photos = fetchPhotoList()
                if (photos.isEmpty()) return@launch
                // 按 handle 倒序取最新照片，过滤掉已传输过的
                val newestFirst = photos.sortedByDescending { it.handle }
                val newFiles = mutableListOf<CameraFile>()
                for (photo in newestFirst) {
                    if (newFiles.size >= AUTO_SYNC_MAX_FILES) break
                    val done = runCatching {
                        transferRepository.isAlreadyTransferred(photo.handle)
                    }.getOrDefault(false)
                    if (!done) newFiles.add(photo)
                }
                if (newFiles.isNotEmpty()) {
                    Timber.tag(TAG).i("Auto sync: ${newFiles.size} new photos")
                    enqueue(newFiles)
                    postMessage("自动下载：同步 ${newFiles.size} 张新照片")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Auto sync failed")
            } finally {
                autoSyncJob = null
            }
        }
    }

    fun stop() {
        cancelAll()
        scope = null
    }

    /**
     * USB 有线优先，其次 WiFi PTP。
     */
    fun hasActiveSession(): Boolean {
        return usbPtpManager.isConnected() || ptpSession.isConnected()
    }

    /**
     * 获取相机存储卡照片列表
     * PRD 2.1: 浏览相机存储卡照片列表
     */
    suspend fun fetchPhotoList(
        onPage: ((List<CameraFile>) -> Unit)? = null
    ): List<CameraFile> {
        val transport = currentTransport()
        if (!transport.isConnected) {
            Timber.tag(TAG).w("No camera transport connected, cannot fetch photo list")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                val storageIds = transport.storageIds()
                // 遍历所有存储（机身/双卡），避免只读第一张卡漏掉照片
                val handles = storageIds.flatMap { storageId ->
                    transport.objectHandles(storageId)
                }.distinct().ifEmpty {
                    transport.objectHandles(0xFFFFFFFF.toInt())
                }
                Timber.tag(TAG).i("Found ${handles.size} objects on camera")

                // 按 PAGE_SIZE=18 分页读取 ObjectInfo，逐页回调；
                // 页内并发窗口 4，缩短大列表元数据读取时间
                val result = mutableListOf<CameraFile>()
                handles.chunked(PAGE_SIZE).forEach { page ->
                    val semaphore = Semaphore(OBJECT_INFO_CONCURRENCY)
                    val pageFiles = coroutineScope {
                        page.map { handle ->
                            async(Dispatchers.IO) {
                                semaphore.acquire()
                                try {
                                    val infoBytes = transport.objectInfo(handle)
                                    if (infoBytes != null) parseObjectInfo(handle, infoBytes) else null
                                } finally {
                                    semaphore.release()
                                }
                            }
                        }.awaitAll().filterNotNull()
                    }.filter {
                        it.format == CameraFileFormat.JPEG ||
                                it.format == CameraFileFormat.RAW ||
                                it.format == CameraFileFormat.VIDEO
                    }
                    result.addAll(pageFiles)
                    onPage?.invoke(result.toList())
                }
                result
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to fetch photo list")
                emptyList()
            }
        }
    }

    /**
     * 获取缩略图
     * PRD 2.1: 支持缩略图预览
     */
    suspend fun fetchThumbnail(handle: Int): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                currentTransport().thumbnail(handle)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to fetch thumbnail for handle=$handle")
                null
            }
        }
    }

    /**
     * 下载完整照片
     * PRD 2.1: 选择性下载原图
     * 全链路优化: USB 优先 + 失败自动回退另一通道；失败原因中文化；状态不卡死
     */
    suspend fun downloadPhoto(
        file: CameraFile,
        onProgress: ((Long, Long) -> Unit)? = null,
        targetFile: File? = null
    ): TransferResult {
        if (!hasActiveSession()) {
            postMessage("相机未连接，无法下载")
            _transferState.value = TransferState.Idle
            resetTransferSpeed()
            return TransferResult.Failed("相机未连接")
        }

        resetTransferSpeed()
        _transferState.value = TransferState.Downloading(file, 0L, file.size)

        return withContext(Dispatchers.IO) {
            val tempDir = File(context.cacheDir, "n-link_transfer").apply { mkdirs() }
            val tempFile = targetFile ?: File(tempDir, "tmp_${file.handle}.bin")

            // 主通道尝试
            var result = downloadVia(currentTransport(), file, tempFile, onProgress)

            // 通道回退: 主通道失败且另一通道在线时自动切换（如 USB 拔出后回退 WiFi）
            if (result !is TransferResult.Success) {
                val fallback = fallbackOf(currentTransport())
                if (fallback != null && fallback.isConnected) {
                    postMessage("主通道传输失败，已自动切换到 ${if (fallback === usbTransport) "USB" else "WiFi"} 重试")
                    eventLogger.event(
                        "channel_fallback",
                        "from" to channelName(currentTransport()),
                        "to" to channelName(fallback),
                        "handle" to file.handle
                    )
                    delay(500)
                    result = downloadVia(fallback, file, tempFile, onProgress)
                }
            }

            if (result !is TransferResult.Success) {
                _transferState.value = TransferState.Idle
                resetTransferSpeed()
            }
            result
        }
    }

    /**
     * 仅下载到 App 缓存目录，不写入系统相册。
     * 用于后台快门次数查询等无感导出场景。
     */
    suspend fun downloadPhotoToCache(file: CameraFile, targetFile: File): Boolean {
        if (!hasActiveSession()) return false
        return withContext(Dispatchers.IO) {
            try {
                val transport = currentTransport()
                if (!transport.isConnected) return@withContext false
                targetFile.parentFile?.mkdirs()
                downloadToFile(transport, file, targetFile)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Download photo to cache failed: ${file.fileName}")
                false
            }
        }
    }

    /** 单通道下载实现：传输 → 保存 MediaStore → 记录去重 */
    private suspend fun downloadVia(
        transport: CameraTransport,
        file: CameraFile,
        tempFile: File,
        onProgress: ((Long, Long) -> Unit)?
    ): TransferResult {
        if (!transport.isConnected) {
            return TransferResult.Failed("相机未连接")
        }
        eventLogger.event(
            "download_start",
            "handle" to file.handle,
            "name" to file.fileName,
            "size" to file.size,
            "channel" to channelName(transport)
        )
        return try {
            if (tempFile.length() > file.size) {
                tempFile.delete()
            }

            val completed = downloadToFile(transport, file, tempFile) { received, total ->
                val resolvedTotal = resolveProgressTotal(file.size, total, received)
                onProgress?.invoke(received, resolvedTotal)
                _transferState.value = TransferState.Downloading(file, received, resolvedTotal)
                updateTransferSpeed(received)
            }

            if (!completed) {
                Timber.tag(TAG).w("Incomplete transfer: ${tempFile.length()}/${file.size} via ${channelName(transport)}")
                eventLogger.event("download_fail", "handle" to file.handle, "reason" to "incomplete")
                return TransferResult.Failed(
                    "传输中断（${tempFile.length()}/${file.size}），请检查连接后重试"
                )
            }

            val savedPath = saveFileToMediaStore(prepareFileToSave(tempFile, file), file.fileName)
            if (savedPath != null) {
                tempFile.delete()
                transferRepository.recordTransfer(file.handle, file.fileName, file.size, savedPath)
                _transferState.value = TransferState.Completed(file)
                resetTransferSpeed()
                var msg = "已保存: ${file.fileName}"
                eventLogger.event(
                    "download_done",
                    "handle" to file.handle,
                    "name" to file.fileName,
                    "size" to file.size,
                    "path" to savedPath
                )
                // 2.4GHz 链路下的速度提示：大文件传输在 5GHz 可提升 2-5 倍
                val freq = wifiManager.wifiFrequencyMhz.value
                if (ptpTransport.isConnected && freq in 2400..2495) {
                    msg += "\n当前 2.4GHz 链路，可在设置开启 5GHz 优先提升下载速度"
                }
                postMessage(msg)
                TransferResult.Success(savedPath)
            } else {
                TransferResult.Failed("保存失败：存储空间不足或无写入权限")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download failed: ${file.fileName}")
            eventLogger.event("download_fail", "handle" to file.handle, "reason" to (e.message ?: "unknown"))
            TransferResult.Failed(e.message ?: "未知错误")
        }
    }

    private fun channelName(transport: CameraTransport): String =
        if (transport === usbTransport) "USB" else "WiFi"

    /**
     * 流式下载到本地文件，支持从已有临时文件断点续传。
     * WiFi PTP 走 sink 流式写盘（内存 O(网络包)），USB 走分块缓冲；
     * 分块请求失败时 chunk 减半降级，兼容只支持小分块的相机。
     */
    private suspend fun downloadToFile(
        transport: CameraTransport,
        file: CameraFile,
        target: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        var totalReceived = target.length()
        if (file.size > 0 && totalReceived > file.size) {
            target.delete()
            totalReceived = 0
        }
        if (file.size > 0 && totalReceived >= file.size) return true

        // 相机未返回可靠文件大小时直接整文件下载，避免循环条件把文件当作空文件。
        if (file.size <= 0) {
            return downloadWhole(transport, file, target, onProgress)
        }

        val startOffset = totalReceived
        var chunkSize = PARTIAL_CHUNK_SIZE
        while (totalReceived < file.size) {
            val remaining = (file.size - totalReceived).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val size = minOf(chunkSize, remaining)

            if (transport === ptpTransport) {
                // WiFi PTP：sink 流式写盘，分块失败减半降级重试
                val before = target.length()
                var wrote = -1L
                FileOutputStream(target, true).use { out ->
                    val result = transport.partialObject(file.handle, totalReceived.toInt(), size, out)
                    wrote = if (result == null) -1L else (target.length() - before)
                }
                if (wrote < 0L) {
                    if (chunkSize > MIN_PARTIAL_CHUNK_SIZE) {
                        chunkSize /= 2
                        Timber.tag(TAG).w("Partial chunk degraded to ${chunkSize / 1024}KB for ${file.fileName}")
                        continue
                    }
                    break
                }
                if (wrote == 0L) break  // 相机返回 OK 但无新数据，视为文件尾
            } else {
                // USB：分块缓冲写入；失败同样减半重试一次
                val partial = transport.partialObject(file.handle, totalReceived.toInt(), size, null)
                if (partial == null || partial.isEmpty()) {
                    if (partial == null && chunkSize > MIN_PARTIAL_CHUNK_SIZE) {
                        chunkSize /= 2
                        continue
                    }
                    break
                }
                FileOutputStream(target, true).use { output -> output.write(partial) }
            }

            totalReceived = target.length()
            onProgress?.invoke(totalReceived, file.size)
        }

        if (totalReceived >= file.size) return true

        // 部分传输完全不支持时，清掉占位文件后整文件下载并带进度。
        if (startOffset == 0L && totalReceived == 0L) {
            target.delete()
            return downloadWhole(transport, file, target, onProgress)
        }
        return false
    }

    /** 整文件下载（不支持部分传输时的兑底路径，WiFi PTP 同样走流式写盘） */
    private suspend fun downloadWhole(
        transport: CameraTransport,
        file: CameraFile,
        target: File,
        onProgress: ((Long, Long) -> Unit)?
    ): Boolean {
        if (transport === ptpTransport) {
            FileOutputStream(target, false).use { out ->
                val result = transport.getObject(file.handle, { received, _ ->
                    onProgress?.invoke(received, file.size)
                }, out)
                if (result == null) return false
            }
            onProgress?.invoke(target.length(), file.size)
            return target.length() > 0
        }
        val full = transport.getObject(
            file.handle,
            onProgress = { received, _ ->
                onProgress?.invoke(received, file.size)
            }
        ) ?: return false
        FileOutputStream(target, false).use { output -> output.write(full) }
        onProgress?.invoke(target.length(), file.size)
        return full.isNotEmpty()
    }

    /**
     * 批量删除相机存储卡文件（PTP DeleteObject）。
     * 返回成功删除的 handle 列表。
     */
    suspend fun deleteFiles(files: List<CameraFile>): List<Int> {
        val transport = currentTransport()
        if (!transport.isConnected) return emptyList()
        return withContext(Dispatchers.IO) {
            files.mapNotNull { file ->
                val ok = runCatching { transport.deleteObject(file.handle) }.getOrDefault(false)
                if (ok) file.handle else null
            }
        }
    }

    private fun resolveProgressTotal(expected: Long, declared: Long, received: Long): Long {
        val validExpected = expected > 0 && expected != 0xFFFFFFFFL
        val validDeclared = declared > 0 && declared != 0xFFFFFFFFL && declared >= received
        return when {
            validExpected -> expected
            validDeclared -> declared
            else -> 0L
        }
    }

    private fun parseObjectInfo(handle: Int, data: ByteArray): CameraFile? {
        return try {
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val storageId = buffer.int
            val formatCode = buffer.short.toInt() and 0xFFFF
            val protectionStatus = buffer.short.toInt()
            var compressedSize = buffer.int.toLong() and 0xFFFFFFFFL
            if (compressedSize == 0xFFFFFFFFL) compressedSize = 0L
            // Skip thumb format, thumb compressed size, thumb pix width/height
            buffer.short; buffer.int; buffer.int; buffer.int
            // Skip image pix width/height, bit depth
            buffer.int; buffer.int; buffer.int
            val parentObject = buffer.int
            val associationType = buffer.short
            val associationDesc = buffer.int
            val sequenceNumber = buffer.int

            // Read filename (UTF-16LE string with length prefix)
            val nameLength = buffer.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLength * 2)
            buffer.get(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_16LE).trimEnd('\u0000')

            CameraFile(
                handle = handle,
                fileName = fileName,
                size = compressedSize,
                formatCode = formatCode,
                storageId = storageId,
                format = classifyFormat(formatCode, fileName)
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse object info for handle=$handle")
            null
        }
    }

    private fun currentTransport(): CameraTransport {
        // 连接偏好决定双通道在线时的优先级；单通道在线时自然回退另一通道
        if (usbPtpManager.isConnected() && ptpSession.isConnected()) {
            return if (settings.connectionPreference == AppSettings.CONN_PREF_WIFI) {
                ptpTransport
            } else {
                usbTransport
            }
        }
        return if (usbPtpManager.isConnected()) usbTransport else ptpTransport
    }

    /**
     * 添加传输任务到队列
     * PRD 2.1: 传输队列管理 - 多任务排队
     * 全链路优化: 已下载文件的去重在队列处理阶段异步执行（避免阻塞 UI 线程）
     */
    fun enqueue(files: List<CameraFile>) {
        if (files.isEmpty()) {
            postMessage("没有可下载的文件")
            return
        }
        val tasks = files.map { TransferTask(it, TransferTaskStatus.PENDING) }
        transferQueue.addAll(tasks)
        _queue.value = transferQueue.toList()
        processQueue()
    }

    /**
     * 暂停传输
     */
    fun pause() {
        isPaused = true
        currentJob?.cancel()
        currentJob = null
        _transferState.value = TransferState.Paused
        resetTransferSpeed()
    }

    /**
     * 恢复传输
     */
    fun resume() {
        isPaused = false
        processQueue()
    }

    /**
     * 取消所有传输
     */
    fun cancelAll() {
        isPaused = false
        currentJob?.cancel()
        currentJob = null
        transferQueue.clear()
        currentTask = null
        _queue.value = emptyList()
        _transferState.value = TransferState.Idle
        resetTransferSpeed()
        File(context.cacheDir, "n-link_transfer").listFiles()?.forEach { it.delete() }
    }

    private fun processQueue() {
        if (isPaused || currentJob?.isActive == true) return

        val nextTask = transferQueue.firstOrNull { it.status == TransferTaskStatus.PENDING }
        if (nextTask == null) {
            if (transferQueue.isEmpty()) _transferState.value = TransferState.Idle
            return
        }

        currentTask = nextTask
        nextTask.status = TransferTaskStatus.DOWNLOADING
        val tempDir = File(context.cacheDir, "n-link_transfer").apply { mkdirs() }
        val tempFile = File(tempDir, "tmp_${nextTask.file.handle}.bin")
        nextTask.tempFile = tempFile
        _queue.value = transferQueue.toList()
        resetTransferSpeed()
        _transferState.value = TransferState.Downloading(
            nextTask.file,
            tempFile.length(),
            nextTask.file.size
        )

        currentJob = scope?.launch {
            // 全链路优化: 队列阶段去重，已下载过的文件直接跳过，不重复传输
            val alreadyDone = runCatching {
                transferRepository.isAlreadyTransferred(nextTask.file.handle)
            }.getOrDefault(false)
            if (alreadyDone) {
                nextTask.status = TransferTaskStatus.COMPLETED
                postMessage("${nextTask.file.fileName} 已下载过，自动跳过")
                _queue.value = transferQueue.toList()
                currentTask = null
                currentJob = null
                processQueue()
                return@launch
            }

            var result: TransferResult = TransferResult.Cancelled
            // 全链路优化: 失败自动重试 1 次（相机瞬时忙碌/通道切换场景）
            repeat(2) { attempt ->
                result = try {
                    downloadPhoto(
                        file = nextTask.file,
                        onProgress = { received, total ->
                            val resolvedTotal = resolveProgressTotal(nextTask.file.size, total, received)
                            _transferState.value = TransferState.Downloading(
                                nextTask.file,
                                received,
                                resolvedTotal
                            )
                            updateTransferSpeed(received)
                        },
                        targetFile = tempFile
                    )
                } catch (e: CancellationException) {
                    TransferResult.Cancelled
                }
                if (result is TransferResult.Failed && attempt == 0) {
                    Timber.tag(TAG).w("Task retry after failure: ${nextTask.file.fileName}")
                    delay(1500)
                }
            }

            nextTask.status = when (result) {
                is TransferResult.Success -> TransferTaskStatus.COMPLETED
                is TransferResult.Failed -> TransferTaskStatus.FAILED
                is TransferResult.Cancelled -> TransferTaskStatus.PENDING
            }
            when (result) {
                is TransferResult.Failed -> {
                    // 全链路优化: 失败原因上抛给用户，不静默
                    postMessage("下载失败: ${nextTask.file.fileName}（${result.reason}）")
                    nextTask.tempFile?.delete()
                    nextTask.tempFile = null
                }
                is TransferResult.Success -> Unit
                is TransferResult.Cancelled -> Unit
            }
            _queue.value = transferQueue.toList()
            currentTask = null
            currentJob = null
            if (transferQueue.none { it.status == TransferTaskStatus.PENDING }) {
                _transferState.value = TransferState.Idle
                resetTransferSpeed()
            }
            processQueue() // 处理下一个
        }
    }

    /**
     * 按设置「下载画质」预处理待保存文件：
     * 压缩模式仅对 JPG 重编码（长边 ≤ 3200、质量 90），RAW 与其它格式原样保存。
     */
    private fun prepareFileToSave(source: File, file: CameraFile): File {
        if (settings.downloadQuality != AppSettings.QUALITY_COMPRESSED ||
            file.format != CameraFileFormat.JPEG
        ) {
            return source
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > COMPRESS_MAX_EDGE) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, opts)
                ?: return source
            val target = File(source.parentFile, source.nameWithoutExtension + "_compressed.jpg")
            target.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, COMPRESS_JPEG_QUALITY, out)
            }
            bitmap.recycle()
            if (target.length() > 0) target else source
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "JPEG re-encode failed, saving original")
            source
        }
    }

    /**
     * 将下载完成的临时文件保存到 MediaStore（Android Scoped Storage）。
     * 保存路径按设置「save_path」选择 DCIM/N-Link 或 Download/N-Link。
     */
    private fun saveFileToMediaStore(file: File, fileName: String): String? {
        return try {
            val relativePath = if (settings.savePath == AppSettings.SAVE_PATH_DOWNLOAD) {
                Environment.DIRECTORY_DOWNLOADS + "/N-Link"
            } else {
                Environment.DIRECTORY_DCIM + "/N-Link"
            }
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, getMimeType(fileName))
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null

            val output = resolver.openOutputStream(uri)
            if (output == null) {
                resolver.delete(uri, null, null)
                return null
            }
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Timber.tag(TAG).i("Saved to MediaStore: $fileName")
            uri.toString()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save to MediaStore")
            null
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".JPG", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".NEF", ignoreCase = true) -> "image/x-nikon-nef"
            fileName.endsWith(".PNG", ignoreCase = true) -> "image/png"
            fileName.endsWith(".MOV", ignoreCase = true) -> "video/quicktime"
            fileName.endsWith(".MP4", ignoreCase = true) -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}

/**
 * 相机文件信息
 */
data class CameraFile(
    val handle: Int,
    val fileName: String,
    val size: Long,
    val formatCode: Int,
    val storageId: Int,
    val format: CameraFileFormat = CameraFileFormat.OTHER
)

/**
 * 相机文件格式分类。传输页只展示照片（JPEG/RAW），视频与其他文件不进列表。
 */
enum class CameraFileFormat {
    JPEG,
    RAW,
    VIDEO,
    OTHER
}

val CameraFile.isPhoto: Boolean
    get() = format == CameraFileFormat.JPEG || format == CameraFileFormat.RAW

internal fun classifyFormat(formatCode: Int, fileName: String): CameraFileFormat {
    val upperName = fileName.uppercase()
    return when {
        formatCode == 0x3801 || formatCode == 0x3808 || formatCode == 0x380B ||
                upperName.endsWith(".JPG") || upperName.endsWith(".JPEG") -> CameraFileFormat.JPEG
        formatCode == 0xB103 ||
                upperName.endsWith(".NEF") || upperName.endsWith(".NRW") ||
                upperName.endsWith(".ARW") || upperName.endsWith(".CR2") ||
                upperName.endsWith(".DNG") -> CameraFileFormat.RAW
        formatCode in VIDEO_FORMAT_CODES ||
                upperName.endsWith(".MOV") || upperName.endsWith(".MP4") ||
                upperName.endsWith(".AVI") -> CameraFileFormat.VIDEO
        else -> CameraFileFormat.OTHER
    }
}

private val VIDEO_FORMAT_CODES = setOf(0x300A, 0x300B, 0x300D, 0xB104, 0xB982, 0xB980)

/**
 * 传输任务
 */
data class TransferTask(
    val file: CameraFile,
    var status: TransferTaskStatus,
    var tempFile: File? = null
)

enum class TransferTaskStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

/**
 * 传输状态
 */
sealed class TransferState {
    data object Idle : TransferState()
    data class Downloading(val file: CameraFile, val received: Long, val total: Long) : TransferState()
    data object Paused : TransferState()
    data class Completed(val file: CameraFile) : TransferState()
}

/**
 * 传输结果
 */
sealed class TransferResult {
    data class Success(val path: String) : TransferResult()
    data class Failed(val reason: String) : TransferResult()
    data object Cancelled : TransferResult()
}

/**
 * 相机传输通道抽象：USB 有线优先，WiFi PTP 兜底。
 */
private interface CameraTransport {
    val isConnected: Boolean
    suspend fun storageIds(): List<Int>
    suspend fun objectHandles(storageId: Int): List<Int>
    suspend fun objectInfo(handle: Int): ByteArray?
    suspend fun getObject(
        handle: Int,
        onProgress: ((Long, Long) -> Unit)? = null,
        sink: OutputStream? = null
    ): ByteArray?
    suspend fun thumbnail(handle: Int): ByteArray?
    suspend fun partialObject(
        handle: Int,
        offset: Int,
        size: Int,
        sink: OutputStream? = null
    ): ByteArray?
    suspend fun deleteObject(handle: Int): Boolean
}

private class PtpTransport(
    private val ptp: PtpSessionManager
) : CameraTransport {
    override val isConnected: Boolean get() = ptp.isConnected()
    override suspend fun storageIds(): List<Int> = ptp.getStorageIds()
    override suspend fun objectHandles(storageId: Int): List<Int> =
        ptp.getObjectHandles(storageId)
    override suspend fun objectInfo(handle: Int): ByteArray? = ptp.getObjectInfo(handle)
    override suspend fun getObject(handle: Int, onProgress: ((Long, Long) -> Unit)?, sink: OutputStream?): ByteArray? =
        ptp.getObject(handle, onProgress, sink)
    override suspend fun thumbnail(handle: Int): ByteArray? = ptp.getThumbnail(handle)
    override suspend fun partialObject(handle: Int, offset: Int, size: Int, sink: OutputStream?): ByteArray? =
        ptp.getPartialObject(handle, offset, size, sink)
    override suspend fun deleteObject(handle: Int): Boolean = ptp.deleteObject(handle)
}

private class UsbTransport(
    private val usb: UsbPtpManager
) : CameraTransport {
    override val isConnected: Boolean get() = usb.isConnected()
    override suspend fun storageIds(): List<Int> = usb.getStorageIds()
    override suspend fun objectHandles(storageId: Int): List<Int> =
        usb.getObjectHandles(storageId)
    override suspend fun objectInfo(handle: Int): ByteArray? = usb.getObjectInfo(handle)
    // USB 通道暂用分块缓冲（单块 ≤ 4MB，内存可控），sink 参数预留不生效
    override suspend fun getObject(handle: Int, onProgress: ((Long, Long) -> Unit)?, sink: OutputStream?): ByteArray? =
        usb.getObject(handle, onProgress)
    override suspend fun thumbnail(handle: Int): ByteArray? = usb.getThumbnail(handle)
    override suspend fun partialObject(handle: Int, offset: Int, size: Int, sink: OutputStream?): ByteArray? =
        usb.getPartialObject(handle, offset, size)
    override suspend fun deleteObject(handle: Int): Boolean = usb.deleteObject(handle)
}

package com.nikonlink.app.camera.gallery

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.camera.data.TransferRepository
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
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
    private val transferRepository: TransferRepository
) {
    companion object {
        private const val TAG = "TransferMgr"
        private const val PARTIAL_CHUNK_SIZE = 1024 * 1024  // 1MB chunks for partial transfer
        // 媒体列表分页 limit=18，逐页加载避免一次性阻塞
        private const val PAGE_SIZE = 18
        private const val SPEED_WINDOW_MS = 2000L
    }

    private var scope: CoroutineScope? = null
    private val transferQueue = mutableListOf<TransferTask>()
    private var currentTask: TransferTask? = null
    private var currentJob: Job? = null
    private var isPaused = false

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

    /** 当前生效的数据通道（UI 展示用）：USB 优先，USB 断开回退 WiFi */
    fun activeChannel(): String = when {
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

                // 按 PAGE_SIZE=18 分页读取 ObjectInfo，逐页回调
                val result = mutableListOf<CameraFile>()
                handles.chunked(PAGE_SIZE).forEach { page ->
                    val pageFiles = page.mapNotNull { handle ->
                        val infoBytes = transport.objectInfo(handle)
                        if (infoBytes != null) parseObjectInfo(handle, infoBytes) else null
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
                return TransferResult.Failed(
                    "传输中断（${tempFile.length()}/${file.size}），请检查连接后重试"
                )
            }

            val savedPath = saveFileToMediaStore(tempFile, file.fileName)
            if (savedPath != null) {
                tempFile.delete()
                transferRepository.recordTransfer(file.handle, file.fileName, file.size, savedPath)
                _transferState.value = TransferState.Completed(file)
                resetTransferSpeed()
                postMessage("已保存: ${file.fileName}")
                TransferResult.Success(savedPath)
            } else {
                TransferResult.Failed("保存失败：存储空间不足或无写入权限")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Download failed: ${file.fileName}")
            TransferResult.Failed(e.message ?: "未知错误")
        }
    }

    private fun channelName(transport: CameraTransport): String =
        if (transport === usbTransport) "USB" else "WiFi"

    /**
     * 流式下载到本地文件，支持从已有临时文件断点续传。
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
            val full = transport.getObject(file.handle) { received, declared ->
                val total = resolveProgressTotal(file.size, declared, received)
                onProgress?.invoke(received, total)
            } ?: return false
            FileOutputStream(target, false).use { output -> output.write(full) }
            totalReceived = full.size.toLong()
            onProgress?.invoke(totalReceived, totalReceived)
            return full.isNotEmpty()
        }

        val startOffset = totalReceived
        while (totalReceived < file.size) {
            val remaining = (file.size - totalReceived).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val chunkSize = minOf(PARTIAL_CHUNK_SIZE, remaining)
            val partial = transport.partialObject(
                file.handle,
                totalReceived.toInt(),
                chunkSize
            )
            if (partial == null || partial.isEmpty()) break

            FileOutputStream(target, true).use { output -> output.write(partial) }
            totalReceived += partial.size
            onProgress?.invoke(totalReceived, file.size)
        }

        if (totalReceived >= file.size) return true

        // 部分传输完全不支持时，清掉占位文件后整文件下载并带进度。
        if (startOffset == 0L && totalReceived == 0L) {
            target.delete()
            val full = transport.getObject(file.handle) { received, _ ->
                onProgress?.invoke(received, file.size)
            } ?: return false
            FileOutputStream(target, false).use { output -> output.write(full) }
            totalReceived = full.size.toLong()
            onProgress?.invoke(totalReceived, file.size)
            return totalReceived >= file.size
        }
        return false
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
     * 将下载完成的临时文件保存到 MediaStore（Android Scoped Storage）。
     */
    private fun saveFileToMediaStore(file: File, fileName: String): String? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, getMimeType(fileName))
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/N-Link")
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
    suspend fun getObject(handle: Int, onProgress: ((Long, Long) -> Unit)? = null): ByteArray?
    suspend fun thumbnail(handle: Int): ByteArray?
    suspend fun partialObject(handle: Int, offset: Int, size: Int): ByteArray?
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
    override suspend fun getObject(handle: Int, onProgress: ((Long, Long) -> Unit)?): ByteArray? =
        ptp.getObject(handle, onProgress)
    override suspend fun thumbnail(handle: Int): ByteArray? = ptp.getThumbnail(handle)
    override suspend fun partialObject(handle: Int, offset: Int, size: Int): ByteArray? =
        ptp.getPartialObject(handle, offset, size)
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
    override suspend fun getObject(handle: Int, onProgress: ((Long, Long) -> Unit)?): ByteArray? =
        usb.getObject(handle, onProgress)
    override suspend fun thumbnail(handle: Int): ByteArray? = usb.getThumbnail(handle)
    override suspend fun partialObject(handle: Int, offset: Int, size: Int): ByteArray? =
        usb.getPartialObject(handle, offset, size)
    override suspend fun deleteObject(handle: Int): Boolean = usb.deleteObject(handle)
}

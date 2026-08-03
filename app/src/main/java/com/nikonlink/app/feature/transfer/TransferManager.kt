package com.nikonlink.app.feature.transfer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.nikonlink.app.core.ptp.PtpSessionManager
import com.nikonlink.app.core.usb.UsbPtpManager
import com.nikonlink.app.data.repository.TransferRepository
import java.io.File
import java.io.FileOutputStream
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
    suspend fun fetchPhotoList(): List<CameraFile> {
        val transport = currentTransport()
        if (!transport.isConnected) {
            Timber.tag(TAG).w("No camera transport connected, cannot fetch photo list")
            return emptyList()
        }

        return withContext(Dispatchers.IO) {
            try {
                val storageIds = transport.storageIds()
                val handles = if (storageIds.isNotEmpty()) {
                    transport.objectHandles(storageIds.first())
                } else {
                    transport.objectHandles(0xFFFFFFFF.toInt())
                }
                Timber.tag(TAG).i("Found ${handles.size} objects on camera")

                handles.mapNotNull { handle ->
                    val infoBytes = transport.objectInfo(handle)
                    if (infoBytes != null) {
                        parseObjectInfo(handle, infoBytes)
                    } else null
                }.filter { it.isPhoto }
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
     */
    suspend fun downloadPhoto(
        file: CameraFile,
        onProgress: ((Long, Long) -> Unit)? = null,
        targetFile: File? = null
    ): TransferResult {
        val transport = currentTransport()
        if (!transport.isConnected) {
            return TransferResult.Failed("相机未连接")
        }

        _transferState.value = TransferState.Downloading(file, 0L, file.size)

        return withContext(Dispatchers.IO) {
            try {
                val tempDir = File(context.cacheDir, "nikonlink_transfer").apply { mkdirs() }
                val tempFile = targetFile ?: File(tempDir, "tmp_${file.handle}.bin")
                if (tempFile.length() > file.size) {
                    tempFile.delete()
                }

                val completed = downloadToFile(transport, file, tempFile) { received, total ->
                    onProgress?.invoke(received, total)
                    _transferState.value = TransferState.Downloading(file, received, total)
                }

                if (!completed) {
                    return@withContext TransferResult.Failed(
                        "Incomplete transfer: ${tempFile.length()}/${file.size}"
                    )
                }

                val savedPath = saveFileToMediaStore(tempFile, file.fileName)
                if (savedPath != null) {
                    tempFile.delete()
                    transferRepository.recordTransfer(file.handle, file.fileName, file.size, savedPath)
                    _transferState.value = TransferState.Completed(file)
                    TransferResult.Success(savedPath)
                } else {
                    TransferResult.Failed("Failed to save file")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download failed: ${file.fileName}")
                _transferState.value = TransferState.Idle
                TransferResult.Failed(e.message ?: "Unknown error")
            }
        }
    }

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
        if (totalReceived > file.size) {
            target.delete()
            totalReceived = 0
        }
        if (totalReceived >= file.size) return true

        FileOutputStream(target, true).use { output ->
            while (totalReceived < file.size) {
                val remaining = (file.size - totalReceived).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val chunkSize = minOf(PARTIAL_CHUNK_SIZE, remaining)
                val partial = transport.partialObject(
                    file.handle,
                    totalReceived.toInt(),
                    chunkSize
                )
                if (partial == null && totalReceived == 0L) {
                    // 部分传输不支持时整文件重下，与相机兼容性保持一致
                    val full = transport.getObject(file.handle) ?: break
                    output.write(full)
                    totalReceived = full.size.toLong()
                    onProgress?.invoke(totalReceived, file.size)
                    break
                }
                if (partial == null || partial.isEmpty()) break

                output.write(partial)
                totalReceived += partial.size
                onProgress?.invoke(totalReceived, file.size)
            }
        }
        return totalReceived >= file.size
    }

    private fun currentTransport(): CameraTransport {
        return if (usbPtpManager.isConnected()) usbTransport else ptpTransport
    }

    /**
     * 添加传输任务到队列
     * PRD 2.1: 传输队列管理 - 多任务排队
     */
    fun enqueue(files: List<CameraFile>) {
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
        File(context.cacheDir, "nikonlink_transfer").listFiles()?.forEach { it.delete() }
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
        val tempDir = File(context.cacheDir, "nikonlink_transfer").apply { mkdirs() }
        val tempFile = File(tempDir, "tmp_${nextTask.file.handle}.bin")
        nextTask.tempFile = tempFile
        _queue.value = transferQueue.toList()
        _transferState.value = TransferState.Downloading(
            nextTask.file,
            tempFile.length(),
            nextTask.file.size
        )

        currentJob = scope?.launch {
            val result = try {
                downloadPhoto(
                    file = nextTask.file,
                    onProgress = { received, _ ->
                        _transferState.value =
                            TransferState.Downloading(nextTask.file, received, nextTask.file.size)
                    },
                    targetFile = tempFile
                )
            } catch (e: CancellationException) {
                TransferResult.Cancelled
            }

            nextTask.status = when (result) {
                is TransferResult.Success -> TransferTaskStatus.COMPLETED
                is TransferResult.Failed -> TransferTaskStatus.FAILED
                is TransferResult.Cancelled -> TransferTaskStatus.PENDING
            }
            if (result is TransferResult.Failed) {
                nextTask.tempFile?.delete()
                nextTask.tempFile = null
            }
            _queue.value = transferQueue.toList()
            currentTask = null
            currentJob = null
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
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/NikonLink")
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

    private fun parseObjectInfo(handle: Int, data: ByteArray): CameraFile? {
        return try {
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val storageId = buffer.int
            val formatCode = buffer.short.toInt() and 0xFFFF
            val protectionStatus = buffer.short.toInt()
            val compressedSize = buffer.int.toLong() and 0xFFFFFFFFL
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
    suspend fun getObject(handle: Int): ByteArray?
    suspend fun thumbnail(handle: Int): ByteArray?
    suspend fun partialObject(handle: Int, offset: Int, size: Int): ByteArray?
}

private class PtpTransport(
    private val ptp: PtpSessionManager
) : CameraTransport {
    override val isConnected: Boolean get() = ptp.isConnected()
    override suspend fun storageIds(): List<Int> = ptp.getStorageIds()
    override suspend fun objectHandles(storageId: Int): List<Int> =
        ptp.getObjectHandles(storageId)
    override suspend fun objectInfo(handle: Int): ByteArray? = ptp.getObjectInfo(handle)
    override suspend fun getObject(handle: Int): ByteArray? = ptp.getObject(handle)
    override suspend fun thumbnail(handle: Int): ByteArray? = ptp.getThumbnail(handle)
    override suspend fun partialObject(handle: Int, offset: Int, size: Int): ByteArray? =
        ptp.getPartialObject(handle, offset, size)
}

private class UsbTransport(
    private val usb: UsbPtpManager
) : CameraTransport {
    override val isConnected: Boolean get() = usb.isConnected()
    override suspend fun storageIds(): List<Int> = usb.getStorageIds()
    override suspend fun objectHandles(storageId: Int): List<Int> =
        usb.getObjectHandles(storageId)
    override suspend fun objectInfo(handle: Int): ByteArray? = usb.getObjectInfo(handle)
    override suspend fun getObject(handle: Int): ByteArray? = usb.getObject(handle)
    override suspend fun thumbnail(handle: Int): ByteArray? = usb.getThumbnail(handle)
    override suspend fun partialObject(handle: Int, offset: Int, size: Int): ByteArray? =
        usb.getPartialObject(handle, offset, size)
}

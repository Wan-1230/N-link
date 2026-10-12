package com.camerakit.ptp

import com.camerakit.api.CameraError
import com.camerakit.api.CameraSession
import com.camerakit.api.DeviceInfo
import com.camerakit.api.ObjectInfo
import com.camerakit.api.StorageInfo

/**
 * PTP 会话层：在任意 [PtpLink] 之上提供统一相机操作（三通道共享）。
 *
 * 会话序（基准实现 v1.0.257 诊断日志实证）：OpenSession(0x1002, 随机会话号) → GetDeviceInfo(0x1001)。
 * 分段读优先标准 GetPartialObject(0x101B)；设备能力含 0x9431 时按需切换尼康扩展路径。
 */
class PtpSession(
    private val link: PtpLink,
    private val sessionId: Long = randomSessionId(),
    private val log: (String) -> Unit = {},
) : CameraSession {

    private companion object {
        const val RANGE_UNKNOWN = 0
        const val RANGE_STD = 1
        const val RANGE_EX = 2
        const val READ_CHUNK = 256 * 1024

        /** 基准实现 实证：随机会话号（如 804956382），避免与相机残留会话冲突 */
        fun randomSessionId(): Long = (1..0x7FFFFFFF).random().toLong()
    }

    var deviceInfo: DeviceInfo? = null
        private set

    private var opened = false
    private var rangeStrategy = RANGE_UNKNOWN

    suspend fun open() {
        // 基准实现 实证顺序：先 OpenSession（随机会话号）再读 DeviceInfo
        val r = link.transact(PtpConsts.OP_OPEN_SESSION, listOf(sessionId))
        if (!r.isOk && r.code != PtpConsts.RC_SESSION_ALREADY_OPEN) {
            throw CameraError.PtpError(r.code)
        }
        opened = true
        val info = getDeviceInfo()
        log("DeviceInfo: ${info.manufacturer} ${info.model} ${info.deviceVersion} ops=${info.operationsSupported.size}")
    }

    /**
     * 立即关闭会话链路（传输层关闭即隐含 PTP 会话关闭，与 基准实现 的
     * 「transport close requested sendCloseSession」跳过语义一致）。
     */
    fun close() {
        opened = false
        link.close(graceful = true)
    }

    override suspend fun getDeviceInfo(): DeviceInfo {
        val r = checked(PtpConsts.OP_GET_DEVICE_INFO)
        val data = r.dataIn ?: throw CameraError.Protocol("GetDeviceInfo 返回空数据")
        return parseDeviceInfo(data).also { deviceInfo = it }
    }

    override suspend fun listStorages(): List<StorageInfo> {
        val ids = PtpDatasetReader(
            checked(PtpConsts.OP_GET_STORAGE_IDS).dataIn ?: ByteArray(0)
        ).u32Array()
        return ids.map { id ->
            val data = checked(PtpConsts.OP_GET_STORAGE_INFO, listOf(id)).dataIn ?: ByteArray(0)
            parseStorageInfo(id.toInt(), data)
        }
    }

    override suspend fun listObjectHandles(storageId: Int, parent: Long): List<Long> {
        val r = checked(
            PtpConsts.OP_GET_OBJECT_HANDLES,
            listOf(storageId.toLong() and 0xFFFFFFFFL, 0L, parent and 0xFFFFFFFFL),
        )
        return PtpDatasetReader(r.dataIn ?: ByteArray(0)).u32Array()
    }

    override suspend fun getObjectInfo(handle: Long): ObjectInfo {
        val r = checked(PtpConsts.OP_GET_OBJECT_INFO, listOf(handle and 0xFFFFFFFFL))
        return parseObjectInfo(handle, r.dataIn ?: ByteArray(0))
    }

    /** 分段读：标准 0x101B 优先；空载荷或报错且设备支持 0x9431 时切换尼康扩展路径。 */
    override suspend fun readRange(handle: Long, offset: Long, length: Int): ByteArray {
        val supportsEx = deviceInfo?.supportsOperation(PtpConsts.OP_NIKON_GET_PARTIAL_OBJECT_EX) == true

        suspend fun std(): ByteArray =
            checked(
                PtpConsts.OP_GET_PARTIAL_OBJECT,
                listOf(handle and 0xFFFFFFFFL, offset and 0xFFFFFFFFL, length.toLong() and 0xFFFFFFFFL),
                timeoutMs = 60_000,
            ).dataIn ?: ByteArray(0)

        suspend fun ex(): ByteArray =
            checked(
                PtpConsts.OP_NIKON_GET_PARTIAL_OBJECT_EX,
                listOf(handle and 0xFFFFFFFFL, offset and 0xFFFFFFFFL, length.toLong() and 0xFFFFFFFFL),
                timeoutMs = 60_000,
            ).dataIn ?: ByteArray(0)

        when (rangeStrategy) {
            RANGE_EX -> return ex()
            RANGE_STD -> return std()
        }
        return try {
            val data = std()
            if (data.isEmpty() && supportsEx) {
                val alt = ex()
                if (alt.isNotEmpty()) {
                    rangeStrategy = RANGE_EX
                    log("分段读策略确认=0x9431（0x101B 返回空载荷）")
                    alt
                } else {
                    rangeStrategy = RANGE_STD
                    data
                }
            } else {
                rangeStrategy = RANGE_STD
                data
            }
        } catch (e: CameraError.PtpError) {
            if (supportsEx) {
                rangeStrategy = RANGE_EX
                log("分段读策略确认=0x9431（0x101B 失败：${e.message}）")
                ex()
            } else throw e
        }
    }

    /** 流式读取对象（内部分段），`expectedSize <= 0` 时读到空段为止。 */
    override suspend fun readObject(handle: Long, expectedSize: Long, sink: (ByteArray) -> Unit) {
        var offset = 0L
        while (expectedSize <= 0 || offset < expectedSize) {
            val want = if (expectedSize > 0) {
                minOf(READ_CHUNK.toLong(), expectedSize - offset).toInt()
            } else READ_CHUNK
            val data = readRange(handle, offset, want)
            if (data.isEmpty()) break
            sink(data)
            offset += data.size
            if (data.size < want) break
        }
    }

    override suspend fun getThumbnail(handle: Long): ByteArray =
        checked(PtpConsts.OP_GET_THUMB, listOf(handle and 0xFFFFFFFFL)).dataIn ?: ByteArray(0)

    override suspend fun deleteObject(handle: Long) {
        checked(PtpConsts.OP_DELETE_OBJECT, listOf(handle and 0xFFFFFFFFL, 0L))
    }

    // ==================== APP 扩展（宿主 App 功能补充，非连接核心） ====================

    /**
     * 通用事务：返回原始 [PtpResult]，响应码非 OK 也不抛出（由调用方判读）；
     * 仅传输层断开仍抛 [CameraError.TransportLost]。
     * 供同包扩展文件（PtpSessionAppExt）封装宿主 App 功能用命令。
     */
    suspend fun rawTransact(
        opcode: Int,
        params: List<Long> = emptyList(),
        dataOut: ByteArray? = null,
        timeoutMs: Long = 20_000,
    ): PtpResult = link.transact(opcode, params, dataOut = dataOut, timeoutMs = timeoutMs)

    // ---------------- 配对（PC 主机注册，基准实现 实证） ----------------

    /** 0x952B 预备：data-in 返回相机侧展示的字符串（载荷前 8 字节为头部）。 */
    suspend fun hostRegisterPrepare(timeoutMs: Long = 30_000): String {
        val r = checked(PtpConsts.OP_NIKON_HOST_REG_PREPARE, timeoutMs = timeoutMs)
        return decodeHostRegPrepare(r.dataIn)
    }

    /** 0x935A 确认（参数 0x2001）。 */
    suspend fun hostRegisterConfirm(timeoutMs: Long = 30_000) {
        checked(
            PtpConsts.OP_NIKON_HOST_REG_CONFIRM,
            listOf(PtpConsts.HOST_REG_CONFIRM_PARAM),
            timeoutMs = timeoutMs,
        )
    }

    // ---------------- 内部 ----------------

    private suspend fun checked(
        opcode: Int,
        params: List<Long> = emptyList(),
        timeoutMs: Long = 20_000,
    ): PtpResult {
        val r = link.transact(opcode, params, timeoutMs = timeoutMs)
        if (!r.isOk) {
            log("PTP 0x${opcode.toString(16)} 失败：${PtpConsts.responseName(r.code)}")
            throw CameraError.PtpError(r.code)
        }
        return r
    }

    private fun decodeHostRegPrepare(data: ByteArray?): String {
        if (data == null || data.size <= 8) return "空"
        val body = data.copyOfRange(8, data.size)
        val utf16 = buildString {
            var off = 0
            while (off + 1 < body.size) {
                val ch = ((body[off].toInt() and 0xFF) or ((body[off + 1].toInt() and 0xFF) shl 8)).toChar()
                if (ch == 0.toChar()) break
                append(ch)
                off += 2
            }
        }
        val printable = utf16.count { it.code in 0x20..0x7E || it.code > 0xA0 }
        return when {
            utf16.isNotEmpty() && printable >= utf16.length * 0.7 -> utf16
            else -> body.joinToString("") { "%02x".format(it) }.take(64).ifEmpty { "空" }
        }
    }

    // ---------------- 数据集解析 ----------------

    private fun parseDeviceInfo(data: ByteArray): DeviceInfo {
        val r = PtpDatasetReader(data)
        val standardVersion = r.u16()
        val vendorExtensionId = r.u32()
        r.u16() // vendorExtensionVersion
        r.string() // vendorExtensionDesc
        r.u16() // functionalMode
        val ops = r.u16Array()
        val events = r.u16Array()
        val props = r.u16Array()
        val captureFormats = r.u16Array()
        val imageFormats = r.u16Array()
        val manufacturer = r.string()
        val model = r.string()
        val deviceVersion = r.string()
        val serial = if (r.remaining > 0) r.string() else ""
        return DeviceInfo(
            standardVersion = standardVersion,
            vendorExtensionId = vendorExtensionId,
            operationsSupported = ops,
            eventsSupported = events,
            devicePropertiesSupported = props,
            captureFormats = captureFormats,
            imageFormats = imageFormats,
            manufacturer = manufacturer,
            model = model,
            deviceVersion = deviceVersion,
            serialNumber = serial,
        )
    }

    private fun parseStorageInfo(storageId: Int, data: ByteArray): StorageInfo {
        val r = PtpDatasetReader(data)
        return StorageInfo(
            storageId = storageId,
            storageType = r.u16(),
            filesystemType = r.u16(),
            accessCapability = r.u16(),
            maxCapacity = r.u64(),
            freeSpace = r.u64(),
            description = run { r.u32(); r.string() },
            volumeLabel = r.string(),
        )
    }

    private fun parseObjectInfo(handle: Long, data: ByteArray): ObjectInfo {
        val r = PtpDatasetReader(data)
        val storageId = r.u32().toInt()
        val objectFormat = r.u16()
        r.u16() // protectionStatus
        val compressedSize = r.u32()
        r.u16() // thumbFormat
        r.u32() // thumbCompressedSize
        r.u32() // thumbPixWidth
        r.u32() // thumbPixHeight
        val imageWidth = r.u32()
        val imageHeight = r.u32()
        r.u32() // imageBitDepth
        val parent = r.u32()
        r.u16() // associationType
        r.u32() // associationDesc
        r.u32() // sequenceNumber
        val filename = r.string()
        val captureDate = r.string()
        val modifiedDate = r.string()
        return ObjectInfo(
            handle = handle,
            storageId = storageId,
            objectFormat = objectFormat,
            compressedSize = compressedSize,
            parent = parent,
            filename = filename,
            captureDate = captureDate,
            modificationDate = modifiedDate,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }
}

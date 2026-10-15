package com.camerakit.ap

import com.camerakit.api.CameraError
import com.camerakit.api.CameraSession
import com.camerakit.api.DeviceInfo
import com.camerakit.api.ObjectInfo
import com.camerakit.api.StorageInfo
import java.io.OutputStream

/**
 * AP 引擎桥：把 [ApPtpClient]（开源 PTP/IP 客户端实现）适配成本项目的
 * [CameraSession] 契约，并暴露监看/遥控/参数命令面。
 *
 * 目的：AP 路径**整体替换**为参考实现的引擎，同时让下游（相册 / 监看 / 参数 /
 * 遥控）几乎零改动——它们继续调 [CameraSession] 与这里的方法。
 * STA / USB 仍走各自的旧栈，不受影响。
 */
class ApEngineBridge(val client: ApPtpClient, private val log: (String) -> Unit = {}) : CameraSession {

    val connected get() = client.connected
    val recording get() = client.recording
    val propertyChanges get() = client.propertyChanges
    val supportedOperations get() = client.supportedOperations
    val cameraName get() = client.cameraName
    val isConnected get() = client.isConnected
    val lastError get() = client.lastError

    fun disconnect() = client.disconnect()

    // ---------------- 会话契约（相册 / 参数面板用） ----------------

    override suspend fun getDeviceInfo(): DeviceInfo {
        val ops = client.supportedOperations.toList()
        return DeviceInfo(
            standardVersion = 100,
            vendorExtensionId = 0,
            operationsSupported = ops,
            eventsSupported = emptyList(),
            devicePropertiesSupported = emptyList(),
            captureFormats = emptyList(),
            imageFormats = emptyList(),
            manufacturer = "Nikon",
            model = client.cameraName ?: "Nikon",
            deviceVersion = "",
            serialNumber = "",
        )
    }

    override suspend fun listStorages(): List<StorageInfo> {
        val ids = runCatching {
            client.transact(ApOps.OP_GET_STORAGE_IDS)?.takeIf { it.isOk }
                ?.let { ApPtpWire.array32(it.data) }
        }.getOrNull().orEmpty()
        return ids.map { id ->
            StorageInfo(
                storageId = id.toInt(),
                storageType = 0,
                filesystemType = 0,
                accessCapability = 0,
                maxCapacity = 0,
                freeSpace = 0,
                description = "",
                volumeLabel = "",
            )
        }
    }

    override suspend fun listObjectHandles(storageId: Int, parent: Long): List<Long> {
        val r = client.transact(ApOps.OP_GET_OBJECT_HANDLES, listOf(storageId, 0, 0))
            ?: return emptyList()
        if (!r.isOk) return emptyList()
        return ApPtpWire.array32(r.data)
    }

    override suspend fun getObjectInfo(handle: Long): ObjectInfo {
        val r = client.transact(ApOps.OP_GET_OBJECT_INFO, listOf(handle.toInt()))
            ?: throw CameraError.TransportLost("GetObjectInfo 无响应")
        if (!r.isOk) throw CameraError.PtpError(r.code)
        val d = r.data
        if (d.size < 53) throw CameraError.Protocol("ObjectInfo 过短")
        val format = ApPtpWire.u16(d, 4)
        val size = ApPtpWire.u32(d, 8)
        val nameLen = d[52].toInt() and 0xFF
        val name = if (nameLen > 0 && 53 + nameLen * 2 <= d.size) {
            buildString {
                for (i in 0 until nameLen) {
                    val c = ApPtpWire.u16(d, 53 + i * 2)
                    if (c == 0) break
                    append(c.toChar())
                }
            }
        } else ""
        return ObjectInfo(
            handle = handle,
            storageId = 0,
            objectFormat = format,
            compressedSize = size,
            parent = 0,
            filename = name,
            captureDate = "",
            modificationDate = "",
            imageWidth = 0,
            imageHeight = 0,
        )
    }

    override suspend fun readRange(handle: Long, offset: Long, length: Int): ByteArray {
        val r = client.transact(
            ApOps.OP_GET_PARTIAL_OBJECT,
            listOf(handle.toInt(), offset.toInt(), length),
        ) ?: return ByteArray(0)
        return if (r.isOk) r.data else ByteArray(0)
    }

    override suspend fun readObject(handle: Long, expectedSize: Long, sink: (ByteArray) -> Unit) {
        val out = java.io.ByteArrayOutputStream()
        val ok = client.downloadObject(handle, out) { _, _ -> }
        if (ok) sink(out.toByteArray())
    }

    override suspend fun getThumbnail(handle: Long): ByteArray =
        client.thumbnail(handle) ?: ByteArray(0)

    override suspend fun deleteObject(handle: Long) {
        val r = client.transact(ApOps.OP_DELETE_OBJECT, listOf(handle.toInt(), 0))
            ?: throw CameraError.TransportLost("DeleteObject 无响应")
        if (!r.isOk) throw CameraError.PtpError(r.code)
    }

    // ---------------- 命令面（监看 / 遥控 / 参数） ----------------

    /** 相册分页（参考实现的 AlbumIndex 归并分页）。 */
    suspend fun browsePage(offset: Int, refresh: Boolean = false, query: AlbumQuery = AlbumQuery()) =
        client.browsePage(offset, refresh, query)

    suspend fun download(handle: Long, sink: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean =
        client.downloadObject(handle, sink, onProgress)

    // ---- LiveView ----
    suspend fun startLiveView(): Boolean = client.startLiveView().isSuccess
    suspend fun liveViewFrame(): ByteArray? = client.liveViewFrame().getOrNull()
    suspend fun endLiveView() = client.endLiveView()

    // ---- 快门 / 录像 ----
    suspend fun capture(): Boolean = client.capture().isSuccess
    suspend fun movie(start: Boolean): Boolean = client.movie(start).isSuccess

    // ---- 参数（描述符驱动）----
    suspend fun property(code: Int): CameraProperty? = client.property(code)
    suspend fun propertyValue(prop: CameraProperty): CameraProperty? = client.propertyValue(prop)
    suspend fun setProperty(prop: CameraProperty, value: Long): kotlin.Result<CameraProperty> =
        client.setProperty(prop, value)

    /** 原始属性值字节（供本地参数面板既有 ByteArray 读写路径用）。 */
    suspend fun getDevicePropValue(code: Int): ByteArray? = runCatching {
        client.transact(ApOps.OP_GET_DEVICE_PROP_VALUE, listOf(code))?.takeIf { it.isOk }?.data
    }.getOrNull()

    /** 原始属性写入（data-out 帧格式由引擎处理）。 */
    suspend fun setDevicePropValue(code: Int, value: ByteArray): Boolean = runCatching {
        client.transact(
            ApOps.OP_SET_DEVICE_PROP_VALUE,
            listOf(code),
            value,
        )?.isOk == true
    }.getOrDefault(false)

    /** 通用命令（响应码由调用方判读）。 */
    suspend fun command(opcode: Int, params: List<Int> = emptyList()): ApCmdResult {
        val r = runCatching { client.transact(opcode, params) }.getOrNull()
            ?: return ApCmdResult(false, -1)
        return ApCmdResult(r.isOk, r.code)
    }

    /** 原始事务（响应码 + data-in 字节）。 */
    suspend fun rawTransact(opcode: Int, params: List<Int> = emptyList(), dataOut: ByteArray? = null): ApRawResult {
        val r = runCatching { client.transact(opcode, params, dataOut) }.getOrNull()
            ?: return ApRawResult(false, -1, null)
        return ApRawResult(r.isOk, r.code, r.data)
    }
}

/** 命令响应（是否成功 + 响应码；传输失败 code = -1）。 */
data class ApCmdResult(val isOk: Boolean, val code: Int)

/** 原始事务响应。 */
data class ApRawResult(val isOk: Boolean, val code: Int, val dataIn: ByteArray?)

/** 本桥使用的操作码常量（与 PTP/IP 标准及尼康扩展一致）。 */
private object ApOps {
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_DELETE_OBJECT = 0x100B
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016
    const val OP_GET_PARTIAL_OBJECT = 0x101B
}

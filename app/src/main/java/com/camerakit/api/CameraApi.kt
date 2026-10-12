package com.camerakit.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一连接门面：三种连接方式共用同一接口。
 *
 * 典型用法：
 * ```kotlin
 * val client = CameraConnectionFactory.create(context)
 * client.connect(ConnectionMethod.WifiAp())
 * val info = client.requireSession().getDeviceInfo()
 * ```
 */
interface CameraConnection {
    val state: StateFlow<CameraState>
    val events: SharedFlow<CameraEvent>

    /** 发现可用端点（USB 枚举 / mDNS / AP 固定端点）。 */
    suspend fun discover(method: ConnectionMethod): List<CameraEndpoint>

    /**
     * 建立连接（含握手、会话）。
     * [autoPair] 为 true 时：相机拒绝主机或存储不可用会自动执行一次主机配对并重连。
     */
    suspend fun connect(
        method: ConnectionMethod,
        endpoint: CameraEndpoint? = null,
        autoPair: Boolean = true,
    )

    /** 当前会话；未连接返回 null。 */
    fun session(): CameraSession?

    /** 当前会话；未连接抛 [CameraError.NotConnected]。 */
    fun requireSession(): CameraSession

    /** 显式执行主机配对（PC 主机注册）。 */
    suspend fun pair()

    /** 断开连接（优雅关闭，不触发自动恢复）。 */
    fun disconnect()
}

/** 相机操作面（连接建立后可用）。 */
interface CameraSession {
    suspend fun getDeviceInfo(): DeviceInfo
    suspend fun listStorages(): List<StorageInfo>
    suspend fun listObjectHandles(storageId: Int, parent: Long = 0): List<Long>
    suspend fun getObjectInfo(handle: Long): ObjectInfo

    /** 分段读取（offset/length 字节）。 */
    suspend fun readRange(handle: Long, offset: Long, length: Int): ByteArray

    /** 流式读取整个对象；`expectedSize <= 0` 时读到空段为止。 */
    suspend fun readObject(handle: Long, expectedSize: Long = 0, sink: (ByteArray) -> Unit)

    suspend fun getThumbnail(handle: Long): ByteArray

    /** 删除相机存储卡中的对象（破坏性操作，由调用方负责二次确认）。 */
    suspend fun deleteObject(handle: Long)
}

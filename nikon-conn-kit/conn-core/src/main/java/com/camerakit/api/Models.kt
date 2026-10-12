package com.camerakit.api

/** 相机端点：host:port + 展示名 + 来源（usb / ap / mdns / manual）。 */
data class CameraEndpoint(
    val displayName: String,
    val host: String,
    val port: Int,
    val service: String,
) {
    override fun toString(): String = "$displayName ($host:$port, $service)"
}

/** GetDeviceInfo 解析结果。 */
data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val operationsSupported: List<Int>,
    val eventsSupported: List<Int>,
    val devicePropertiesSupported: List<Int>,
    val captureFormats: List<Int>,
    val imageFormats: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    fun supportsOperation(opcode: Int): Boolean = operationsSupported.contains(opcode)
}

/** GetStorageInfo 解析结果。 */
data class StorageInfo(
    val storageId: Int,
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpace: Long,
    val description: String,
    val volumeLabel: String,
)

/** GetObjectInfo 解析结果（子集，覆盖连接验证所需字段）。 */
data class ObjectInfo(
    val handle: Long,
    val storageId: Int,
    val objectFormat: Int,
    val compressedSize: Long,
    val parent: Long,
    val filename: String,
    val captureDate: String,
    val modificationDate: String,
    val imageWidth: Long,
    val imageHeight: Long,
)

/** 三种连接方式。 */
sealed class ConnectionMethod {
    /** USB 有线（PTP over UsbHost bulk）。 */
    data object Usb : ConnectionMethod()

    /** Wi-Fi AP 直连：手机加入相机热点，PTP/IP 到相机热点网关（默认 192.168.1.1:15740）。 */
    data class WifiAp(val host: String = "192.168.1.1", val port: Int = 15740) : ConnectionMethod()

    /** Wi-Fi STA 局域网：相机入同一路由器/手机热点，mDNS `_ptp._tcp` 自动发现或手动指定端点。 */
    data class WifiSta(val endpoint: CameraEndpoint? = null) : ConnectionMethod()
}

/** 主机身份（PTP/IP 会话令牌）：GUID(16B) + 主机名，持久化后跨连接复用。 */
data class HostIdentity(val guid: ByteArray, val hostName: String) {
    init {
        require(guid.size == 16) { "GUID must be 16 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is HostIdentity && other.guid.contentEquals(guid) && other.hostName == hostName

    override fun hashCode(): Int = guid.contentHashCode() * 31 + hostName.hashCode()
}

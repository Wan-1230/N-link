package com.nikonlink.app.device.model

/**
 * 相机设备信息模型
 */
data class CameraDevice(
    val address: String,          // BLE MAC address
    val name: String,             // 设备名称 (e.g., "Z 50II")
    val rssi: Int = 0,           // 信号强度
    val isPaired: Boolean = false,
    val model: CameraModel = CameraModel.UNKNOWN
)

/**
 * 支持的相机型号
 * PRD 7.3: 初期仅适配 Z50II
 */
enum class CameraModel(val displayName: String) {
    Z50II("Z 50II"),
    Z6III("Z 6III"),
    Z7II("Z 7II"),
    Z6II("Z 6II"),
    Z5("Z 5"),
    Z50("Z 50"),
    Z30("Z 30"),
    ZFC("Z fc"),
    Z7("Z 7"),
    Z6("Z 6"),
    Z8("Z 8"),
    Z9("Z 9"),
    ZF("Zf"),
    UNKNOWN("Unknown")
}

/**
 * 连接质量指标
 * PRD 3.4: 连接状态可视化数据
 */
data class ConnectionMetrics(
    val bleRssi: Int = 0,
    val wifiRssi: Int = 0,
    val wifiFrequencyMhz: Int = 0,
    val lastHeartbeatTime: Long = 0L,
    val connectionDuration: Long = 0L,  // ms
    val activeChannels: Set<ChannelType> = emptySet(),
    val reconnectCount: Int = 0
)

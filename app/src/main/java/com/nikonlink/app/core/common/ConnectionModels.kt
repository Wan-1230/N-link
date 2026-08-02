package com.nikonlink.app.core.common

/**
 * 连接状态枚举
 * PRD 3.3: Disconnected → Connecting → BLE_Connected → WiFi_Upgrading → Fully_Connected
 */
enum class ConnectionState {
    /** 完全断开 */
    DISCONNECTED,
    /** 正在尝试连接（BLE扫描/配对中） */
    CONNECTING,
    /** BLE 已连接，WiFi 未建立 */
    BLE_CONNECTED,
    /** BLE 已连接，正在升级 WiFi 通道 */
    WIFI_UPGRADING,
    /** BLE + WiFi 双通道完全连接 */
    FULLY_CONNECTED,
    /** 连接出错，等待重试 */
    ERROR_WAITING_RETRY
}

/**
 * 通道类型
 */
enum class ChannelType {
    BLE,
    WIFI,
    USB
}

/**
 * 连接事件密封类 - 用于状态机事件驱动
 */
sealed class ConnectionEvent {
    data object StartConnect : ConnectionEvent()
    data object BleDiscovered : ConnectionEvent()
    data object BleConnected : ConnectionEvent()
    data object BleDisconnected : ConnectionEvent()
    data object WifiUpgradeRequested : ConnectionEvent()
    data object WifiConnected : ConnectionEvent()
    data object WifiDisconnected : ConnectionEvent()
    data object HeartbeatTimeout : ConnectionEvent()
    data object RetryTriggered : ConnectionEvent()
    data object CameraShutdown : ConnectionEvent()
    data object CameraPowerOn : ConnectionEvent()
    data class ErrorOccurred(val message: String, val recoverable: Boolean = true) : ConnectionEvent()
}

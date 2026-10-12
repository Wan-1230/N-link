package com.camerakit.api

/** 连接状态机（供 UI 订阅）。 */
sealed class CameraState {
    data object Disconnected : CameraState()
    data object Discovering : CameraState()

    data class Connecting(val phase: String) : CameraState()

    /** 配对流程：等待用户在相机「主机确认」界面确认本机。 */
    data class AwaitingHostConfirm(val prepare: String?) : CameraState()

    data class Connected(val endpoint: CameraEndpoint, val deviceInfo: DeviceInfo) : CameraState()

    data class Recovering(val reason: String) : CameraState()

    data class Failed(val error: CameraError) : CameraState()
}

/** 会话级事件（保活/恢复阶梯，语义与 基准实现 事件模型对齐）。 */
sealed class CameraEvent {
    /** 保活临时失败，连接仍可能可用。 */
    data class KeepaliveFailed(val detail: String) : CameraEvent()

    /** 传输层丢失。 */
    data class TransportLost(val detail: String) : CameraEvent()

    /** 无线会话自动恢复成功。 */
    data class SessionRecovered(val detail: String) : CameraEvent()

    /** 相机会话令牌变更（目录/缓存需失效）。 */
    data class SessionTokenChanged(val token: Long) : CameraEvent()

    /** 无线连接断开（不再自动恢复）。 */
    data class WirelessDisconnected(val reason: String) : CameraEvent()

    /** 配对进度文本。 */
    data class PairingProgress(val text: String) : CameraEvent()
}

/** 结构化错误。 */
sealed class CameraError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoCameraFound(detail: String = "未发现尼康相机") : CameraError(detail)
    class UsbPermissionDenied(detail: String = "USB 权限被拒绝") : CameraError(detail)
    class CameraRejected(val failCode: Int) :
        CameraError("相机拒绝该主机（0x${failCode.toString(16)}）")

    class PairingRejected(detail: String) : CameraError(detail)
    class TransportLost(detail: String, cause: Throwable? = null) : CameraError(detail, cause)
    class Timeout(detail: String) : CameraError(detail)
    class PtpError(val code: Int, detail: String = PtpError.defaultMessage(code)) : CameraError(detail) {
        companion object {
            fun defaultMessage(code: Int): String =
                "相机返回 PTP 错误 0x${code.toString(16).uppercase()}"
        }
    }

    class Protocol(detail: String, cause: Throwable? = null) : CameraError(detail, cause)
    class StorageUnavailable(detail: String) : CameraError(detail)
    class NotConnected(detail: String = "尚未连接相机") : CameraError(detail)
}

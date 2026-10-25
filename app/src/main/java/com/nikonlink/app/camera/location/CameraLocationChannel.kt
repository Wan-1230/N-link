package com.nikonlink.app.camera.location

import com.nikonlink.app.device.ap.ApConnectionService
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager
import com.nikonlink.app.shared.common.AppEventLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 0x9523 的发送通道：USB > AP 直连 > PTP/IP（STA/普通 WiFi）依次尝试。
 *
 * 位置写入本身只有一包 41 字节，不参与相册/监看的链路；这里仅复用各通道已有的
 * 事务锁与数据出能力，不改变任何连接建立/保活逻辑。
 */
@Singleton
class CameraLocationChannel @Inject constructor(
    private val ptpSession: PtpSessionManager,
    private val usbPtpManager: UsbPtpManager,
    private val apConnection: ApConnectionService,
    private val eventLogger: AppEventLogger
) {

    data class SendResult(
        val ok: Boolean,
        val code: Int,
        val transport: String
    ) {
        val unsupported: Boolean
            get() = code == PtpConstants.RESPONSE_OPERATION_NOT_SUPPORTED
    }

    fun isAvailable(): Boolean =
        usbPtpManager.isConnected() || apConnection.isConnected() || ptpSession.isConnected()

    /** PTP/IP 会话已明确声明不支持 0x9523 时提前拦下，避免每个周期都白发一包。 */
    fun knownUnsupported(): Boolean {
        if (usbPtpManager.isConnected() || apConnection.isConnected()) return false
        val ops = ptpSession.lastSupportedOperations ?: return false
        return PtpConstants.OP_NIKON_UPDATE_LOCATION !in ops
    }

    suspend fun send(payload: ByteArray): SendResult {
        val result = when {
            usbPtpManager.isConnected() -> {
                val code = usbPtpManager.sendCommandDataOut(
                    PtpConstants.OP_NIKON_UPDATE_LOCATION,
                    emptyList(),
                    payload
                )
                SendResult(code == PtpConstants.RESPONSE_OK, code, "usb")
            }

            apConnection.isConnected() -> {
                val raw = apConnection.bridge()?.rawTransact(
                    PtpConstants.OP_NIKON_UPDATE_LOCATION,
                    emptyList(),
                    payload
                )
                SendResult(raw?.isOk == true, raw?.code ?: -1, "ap")
            }

            ptpSession.isConnected() -> {
                val response = ptpSession.sendCommandWithDataOut(
                    PtpConstants.OP_NIKON_UPDATE_LOCATION,
                    emptyList(),
                    payload
                )
                SendResult(response.isOk, response.responseCode, "ptp")
            }

            else -> SendResult(false, -1, "none")
        }
        eventLogger.event(
            "gps_camera_send",
            "ok" to result.ok,
            "code" to "0x${result.code.toString(16)}",
            "transport" to result.transport
        )
        return result
    }
}

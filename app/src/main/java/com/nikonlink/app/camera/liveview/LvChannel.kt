package com.nikonlink.app.camera.liveview

import com.camerakit.ptp.getDevicePropValue
import com.camerakit.ptp.getLiveViewImage
import com.camerakit.ptp.setDevicePropValue
import com.nikonlink.app.device.ap.ApConnectionService
import com.nikonlink.app.device.ptp.PtpConstants
import com.nikonlink.app.device.ptp.PtpSessionManager
import com.nikonlink.app.device.usb.UsbPtpManager

/**
 * 监看通道抽象：WiFi(PTP/IP) / USB 有线 / AP 直连三条链路的命令面统一。
 *
 * [LiveViewManager] 只面向本接口编程，通道差异（响应包装、取帧方式、判死方式）
 * 全部收敛在下面三个实现里；STA 与 USB 实现是对既有管理器的原样转发，行为不变。
 */
internal interface LvChannel {
    /** 无线类链路（WiFi / AP）：监看前后要做曝光程序模式进出处理。 */
    val isWireless: Boolean

    fun isConnected(): Boolean

    /** 链路是否已被判死（仅无线链路有此判定；USB 恒为 false）。 */
    fun isLinkDead(): Boolean

    suspend fun getDevicePropValue(code: Int): ByteArray?

    suspend fun setDevicePropValue(code: Int, value: ByteArray): Boolean

    /** 发送命令并回读响应码（失败不抛出，由调用方判读）。 */
    suspend fun command(opcode: Int, params: List<Int> = emptyList()): LvCmdResult

    suspend fun getLiveViewImage(): ByteArray?

    suspend fun stopLiveView(): Boolean

    suspend fun afDrive(): Boolean

    suspend fun changeAfArea(x: Int, y: Int): Boolean

    suspend fun manualFocusDrive(direction: Int, speed: Int): Boolean

    // ---- 遥控拍摄命令面（快门 / 对焦 / 录像 / 存储，三通道同语义）----

    /** 释放快门（InitiateCapture）。 */
    suspend fun capture(): Boolean

    /** 停止对焦（AF_DRIVE_CANCEL）。 */
    suspend fun afDriveCancel(): Boolean

    /** 存储 ID 列表（B 门开曝光命令的候选参数来源）。 */
    suspend fun getStorageIds(): List<Int>

    /** 录像命令带响应码（被拒时调用方给中文化提示）。 */
    suspend fun startMovieRec(): LvCmdResult

    suspend fun stopMovieRec(): LvCmdResult
}

/** 命令响应（是否成功 + PTP 响应码；传输失败时 code = -1）。 */
internal data class LvCmdResult(val isOk: Boolean, val code: Int)

/** WiFi(PTP/IP) 通道：对 [PtpSessionManager] 的原样转发。 */
internal class WifiLvChannel(
    private val ptp: PtpSessionManager
) : LvChannel {
    override val isWireless: Boolean get() = true
    override fun isConnected(): Boolean = ptp.isConnected()
    override fun isLinkDead(): Boolean = ptp.isLinkDead()
    override suspend fun getDevicePropValue(code: Int): ByteArray? = ptp.getDevicePropValue(code)
    override suspend fun setDevicePropValue(code: Int, value: ByteArray): Boolean =
        ptp.setDevicePropValue(code, value)

    override suspend fun command(opcode: Int, params: List<Int>): LvCmdResult =
        ptp.sendCommand(opcode, params).let { LvCmdResult(it.isOk, it.responseCode) }

    override suspend fun getLiveViewImage(): ByteArray? = ptp.getLiveViewImage()
    override suspend fun stopLiveView(): Boolean = ptp.stopLiveView()
    override suspend fun afDrive(): Boolean = ptp.afDrive()
    override suspend fun changeAfArea(x: Int, y: Int): Boolean =
        ptp.sendCommand(PtpConstants.OP_NIKON_CHANGE_AF_AREA, listOf(x, y)).isOk

    override suspend fun manualFocusDrive(direction: Int, speed: Int): Boolean =
        ptp.sendCommand(PtpConstants.OP_NIKON_MF_DRIVE, listOf(direction, speed)).isOk

    override suspend fun capture(): Boolean = ptp.initiateCapture()
    override suspend fun afDriveCancel(): Boolean = ptp.afDriveCancel()
    override suspend fun getStorageIds(): List<Int> = ptp.getStorageIds()
    override suspend fun startMovieRec(): LvCmdResult =
        ptp.startMovieRecordingResult().let { LvCmdResult(it.first, it.second) }

    override suspend fun stopMovieRec(): LvCmdResult =
        ptp.stopMovieRecordingResult().let { LvCmdResult(it.first, it.second) }
}

/** USB 有线通道：对 [UsbPtpManager] 的原样转发。 */
internal class UsbLvChannel(
    private val usb: UsbPtpManager
) : LvChannel {
    override val isWireless: Boolean get() = false
    override fun isConnected(): Boolean = usb.isConnected()
    override fun isLinkDead(): Boolean = false
    override suspend fun getDevicePropValue(code: Int): ByteArray? = usb.getDevicePropValue(code)
    override suspend fun setDevicePropValue(code: Int, value: ByteArray): Boolean =
        usb.setDevicePropValue(code, value)

    override suspend fun command(opcode: Int, params: List<Int>): LvCmdResult =
        usb.sendCommand(opcode, params)
            ?.let { LvCmdResult(it.isOk, it.responseCode) }
            ?: LvCmdResult(false, -1)

    override suspend fun getLiveViewImage(): ByteArray? = usb.getLiveViewImage()
    override suspend fun stopLiveView(): Boolean = usb.stopLiveView()
    override suspend fun afDrive(): Boolean = usb.afDrive()
    override suspend fun changeAfArea(x: Int, y: Int): Boolean = usb.changeAfArea(x, y)
    override suspend fun manualFocusDrive(direction: Int, speed: Int): Boolean =
        usb.manualFocusDrive(direction, speed)

    override suspend fun capture(): Boolean = usb.capture()
    override suspend fun afDriveCancel(): Boolean = usb.afDriveCancel()
    override suspend fun getStorageIds(): List<Int> = usb.getStorageIds()
    override suspend fun startMovieRec(): LvCmdResult =
        usb.startMovieRecordingResult().let { LvCmdResult(it.first, it.second) }

    override suspend fun stopMovieRec(): LvCmdResult =
        usb.stopMovieRecordingResult().let { LvCmdResult(it.first, it.second) }
}

/**
 * AP 直连通道（AP 引擎桥）。命令语义与 WiFi 通道一致；会话即链路，会话丢失即判死。
 */
internal class ApLvChannel(
    private val ap: ApConnectionService
) : LvChannel {
    override val isWireless: Boolean get() = true
    override fun isConnected(): Boolean = ap.isConnected()
    override fun isLinkDead(): Boolean = ap.bridge() == null

    override suspend fun getDevicePropValue(code: Int): ByteArray? =
        ap.bridge()?.getDevicePropValue(code)

    override suspend fun setDevicePropValue(code: Int, value: ByteArray): Boolean =
        ap.bridge()?.setDevicePropValue(code, value) == true

    override suspend fun command(opcode: Int, params: List<Int>): LvCmdResult {
        val b = ap.bridge() ?: return LvCmdResult(false, -1)
        return runCatching {
            b.command(opcode, params).let { LvCmdResult(it.isOk, it.code) }
        }.getOrDefault(LvCmdResult(false, -1))
    }

    override suspend fun getLiveViewImage(): ByteArray? =
        ap.bridge()?.liveViewFrame()

    override suspend fun stopLiveView(): Boolean =
        command(PtpConstants.OP_NIKON_END_LIVE_VIEW).isOk

    override suspend fun afDrive(): Boolean =
        command(PtpConstants.OP_NIKON_AF_DRIVE).isOk

    override suspend fun changeAfArea(x: Int, y: Int): Boolean =
        command(PtpConstants.OP_NIKON_CHANGE_AF_AREA, listOf(x, y)).isOk

    override suspend fun manualFocusDrive(direction: Int, speed: Int): Boolean =
        command(PtpConstants.OP_NIKON_MF_DRIVE, listOf(direction, speed)).isOk

    override suspend fun capture(): Boolean =
        captureViaMediaFirst()

    /**
     * 快门命令多级降级（基准实现 实证语义）：无线机身优先 0x9207 CaptureMedia，
     * 部分机身把第一参数按存储 ID 解释（-1 被拒时回退 0x100E InitiateCapture）。
     * 只改 AP 通道，STA/USB 通道保持原样。
     */
    private suspend fun captureViaMediaFirst(): Boolean {
        val media = PtpConstants.OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA
        val r1 = command(media, listOf(-1, 0))
        if (r1.isOk) return true
        if (r1.code == PtpConstants.RESPONSE_PARAMETER_NOT_SUPPORTED) {
            val r2 = command(media, listOf(-1))
            if (r2.isOk) return true
        }
        return command(PtpConstants.OP_INITIATE_CAPTURE, listOf(0, 0)).isOk
    }

    override suspend fun afDriveCancel(): Boolean =
        command(PtpConstants.OP_NIKON_AF_DRIVE_CANCEL).isOk

    override suspend fun getStorageIds(): List<Int> {
        val r = ap.bridge()?.rawTransact(PtpConstants.OP_GET_STORAGE_IDS) ?: return emptyList()
        val data = r.dataIn ?: return emptyList()
        if (data.size < 4) return emptyList()
        val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        return (0 until count).mapNotNull {
            if (buf.remaining() >= 4) buf.int else null
        }
    }

    override suspend fun startMovieRec(): LvCmdResult =
        command(PtpConstants.OP_NIKON_START_MOVIE_REC_IN_CARD)

    override suspend fun stopMovieRec(): LvCmdResult =
        command(PtpConstants.OP_NIKON_END_MOVIE_REC)
}

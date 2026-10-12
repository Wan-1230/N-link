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
}

/**
 * AP 直连通道（工作树连接方案会话）。
 * 命令语义与 WiFi 通道一致；会话即链路，会话丢失即判死。
 */
internal class ApLvChannel(
    private val ap: ApConnectionService
) : LvChannel {
    override val isWireless: Boolean get() = true
    override fun isConnected(): Boolean = ap.isConnected()
    override fun isLinkDead(): Boolean = ap.ptpSession() == null

    override suspend fun getDevicePropValue(code: Int): ByteArray? =
        ap.ptpSession()?.getDevicePropValue(code)

    override suspend fun setDevicePropValue(code: Int, value: ByteArray): Boolean =
        ap.ptpSession()?.setDevicePropValue(code, value) == true

    override suspend fun command(opcode: Int, params: List<Int>): LvCmdResult {
        val s = ap.ptpSession() ?: return LvCmdResult(false, -1)
        return runCatching {
            s.rawTransact(opcode, params.map { it.toLong() })
                .let { LvCmdResult(it.isOk, it.code) }
        }.getOrDefault(LvCmdResult(false, -1))
    }

    override suspend fun getLiveViewImage(): ByteArray? =
        ap.ptpSession()?.getLiveViewImage()

    override suspend fun stopLiveView(): Boolean =
        command(PtpConstants.OP_NIKON_END_LIVE_VIEW).isOk

    override suspend fun afDrive(): Boolean =
        command(PtpConstants.OP_NIKON_AF_DRIVE).isOk

    override suspend fun changeAfArea(x: Int, y: Int): Boolean =
        command(PtpConstants.OP_NIKON_CHANGE_AF_AREA, listOf(x, y)).isOk

    override suspend fun manualFocusDrive(direction: Int, speed: Int): Boolean =
        command(PtpConstants.OP_NIKON_MF_DRIVE, listOf(direction, speed)).isOk
}

package com.nikonlink.app.device.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * 已 claim 的 USB PTP 传输资源。
 *
 * 参考附件中的 MTP 生命周期：设备打开后必须由单一对象持有接口和端点，
 * 关闭时先释放接口，再关闭设备连接。上层会话只使用这里暴露的端点，
 * 不再重复扫描或重复 claim 同一接口。
 */
internal class UsbPtpTransport private constructor(
    val connection: UsbDeviceConnection,
    val usbInterface: UsbInterface,
    val bulkIn: UsbEndpoint,
    val bulkOut: UsbEndpoint,
    val interruptIn: UsbEndpoint?
) {
    @Volatile
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        fun open(connection: UsbDeviceConnection, device: UsbDevice): UsbPtpTransport {
            val selected = findInterface(device)
                ?: throw UsbPtpTransportException(UsbPtpTransportFailure.NO_PTP_INTERFACE)

            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null
            for (index in 0 until selected.endpointCount) {
                val endpoint = selected.getEndpoint(index)
                when {
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN -> bulkIn = endpoint
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT -> bulkOut = endpoint
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        endpoint.direction == UsbConstants.USB_DIR_IN -> interruptIn = endpoint
                }
            }

            val inEndpoint = bulkIn
                ?: throw UsbPtpTransportException(UsbPtpTransportFailure.MISSING_BULK_IN)
            val outEndpoint = bulkOut
                ?: throw UsbPtpTransportException(UsbPtpTransportFailure.MISSING_BULK_OUT)

            if (!runCatching { connection.claimInterface(selected, true) }.getOrDefault(false)) {
                throw UsbPtpTransportException(UsbPtpTransportFailure.CLAIM_FAILED)
            }
            return UsbPtpTransport(connection, selected, inEndpoint, outEndpoint, interruptIn)
        }

        private fun findInterface(device: UsbDevice): UsbInterface? {
            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                    intf.interfaceSubclass == 0x01 &&
                    intf.interfaceProtocol == 0x01
                ) {
                    return intf
                }
            }
            // 某些 MTP/PTP 固件不完整填写 class/subclass，但仍提供一对 bulk 端点。
            for (index in 0 until device.interfaceCount) {
                val intf = device.getInterface(index)
                var hasIn = false
                var hasOut = false
                for (endpointIndex in 0 until intf.endpointCount) {
                    val endpoint = intf.getEndpoint(endpointIndex)
                    if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) hasIn = true
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT) hasOut = true
                }
                if (hasIn && hasOut) return intf
            }
            return null
        }
    }
}

internal enum class UsbPtpTransportFailure {
    NO_PTP_INTERFACE,
    MISSING_BULK_IN,
    MISSING_BULK_OUT,
    CLAIM_FAILED
}

internal class UsbPtpTransportException(
    val failure: UsbPtpTransportFailure
) : Exception(failure.name)

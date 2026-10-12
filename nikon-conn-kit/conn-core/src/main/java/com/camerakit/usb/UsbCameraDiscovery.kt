package com.camerakit.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.camerakit.ptp.PtpConsts

/**
 * USB 相机发现与权限管理（基准实现 同款：VID 0x04B0 过滤 + USB_DEVICE_ATTACHED/DETACHED 广播）。
 */
class UsbCameraDiscovery(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    /** 无 USB host 功能的设备上系统服务为 null，不能强转 */
    private val usbManager: UsbManager? =
        appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** 枚举已连接的尼康 PTP 相机。 */
    fun listCameras(): List<UsbDevice> =
        usbManager?.deviceList?.values?.filter { isNikonPtp(it) } ?: emptyList()

    fun isNikonPtp(device: UsbDevice): Boolean {
        if (device.vendorId == PtpConsts.NIKON_VENDOR_ID) return true
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == PtpConsts.USB_CLASS_IMAGING &&
                intf.interfaceSubclass == PtpConsts.USB_SUBCLASS_STILL_IMAGE &&
                intf.interfaceProtocol == PtpConsts.USB_PROTOCOL_PTP
            ) {
                return true
            }
        }
        return false
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager?.hasPermission(device) == true

    fun requestPermission(device: UsbDevice) {
        log("请求 USB 授权：${device.deviceName}")
        usbManager?.requestPermission(device, permissionPendingIntent())
    }

    fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(appContext, 0, intent, flags)
    }

    fun openDevice(device: UsbDevice): android.hardware.usb.UsbDeviceConnection? =
        usbManager?.openDevice(device)

    companion object {
        const val ACTION_USB_PERMISSION = "com.camerakit.core.USB_PERMISSION"
    }
}

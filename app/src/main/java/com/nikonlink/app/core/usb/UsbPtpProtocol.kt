package com.nikonlink.app.core.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB PTP 协议容器编解码
 *
 * 与 PTP/IP 不同，USB PTP 使用 Bulk Transfer 而非 TCP Socket。
 * 容器格式（12 字节头）：
 *   [4 bytes] Length (含头部)
 *   [2 bytes] Type (1=Command, 2=Data, 3=Response, 4=Event)
 *   [2 bytes] Code (操作码/响应码)
 *   [4 bytes] Transaction ID
 *   [N bytes] Payload
 *
 * 参考: ISO 15740 / gphoto2 libptp USB 实现
 */
object UsbPtpProtocol {

    // USB PTP Container Types
    const val TYPE_COMMAND = 0x0001
    const val TYPE_DATA = 0x0002
    const val TYPE_RESPONSE = 0x0003
    const val TYPE_EVENT = 0x0004

    // Header size
    const val HEADER_SIZE = 12

    // Max USB bulk packet
    const val MAX_BULK_SIZE = 512
    const val MAX_PAYLOAD_SIZE = 1024 * 1024 * 50  // 50MB max

    // Nikon USB Vendor ID
    const val NIKON_VENDOR_ID = 0x04B0

    // Known Nikon Product IDs (Z series)
    val NIKON_PRODUCT_IDS = mapOf(
        0x0455 to "Z 50II",
        0x043A to "Z 50II",
        0x0439 to "Z 6III",
        0x0438 to "Z 8",
        0x0437 to "Z 9",
        0x0436 to "Zf",
        0x0435 to "Z 5",
        0x0434 to "Z 6II",
        0x0433 to "Z 7II",
        0x0432 to "Z 50",
        0x0431 to "Z 6",
        0x0430 to "Z 7"
    )

    /**
     * 构建 USB PTP 命令容器
     */
    fun buildCommandContainer(transactionId: Int, operationCode: Int, params: List<Int> = emptyList()): ByteArray {
        val payloadSize = params.size * 4
        val totalLength = HEADER_SIZE + payloadSize
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(totalLength)
        buffer.putShort(TYPE_COMMAND.toShort())
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        params.forEach { buffer.putInt(it) }

        return buffer.array()
    }

    /**
     * 构建 USB PTP 数据容器
     */
    fun buildDataContainer(transactionId: Int, data: ByteArray): ByteArray {
        val totalLength = HEADER_SIZE + data.size
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(totalLength)
        buffer.putShort(TYPE_DATA.toShort())
        buffer.putShort(0)  // no code for data
        buffer.putInt(transactionId)
        buffer.put(data)

        return buffer.array()
    }

    /**
     * 解析 USB PTP 响应容器
     */
    fun parseResponseContainer(data: ByteArray): UsbPtpResponse? {
        if (data.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val length = buffer.int
        val type = buffer.short.toInt() and 0xFFFF
        val code = buffer.short.toInt() and 0xFFFF
        val transactionId = buffer.int

        if (type != TYPE_RESPONSE) return null

        // 读取响应参数（最多5个）
        val params = mutableListOf<Int>()
        val remaining = (length - HEADER_SIZE) / 4
        repeat(remaining.coerceAtMost(5)) {
            if (buffer.remaining() >= 4) params.add(buffer.int)
        }

        return UsbPtpResponse(
            transactionId = transactionId,
            responseCode = code,
            parameters = params
        )
    }

    /**
     * 解析 USB PTP 数据容器
     */
    fun parseDataContainer(data: ByteArray): UsbPtpData? {
        if (data.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val length = buffer.int
        val type = buffer.short.toInt() and 0xFFFF
        buffer.short  // code (unused for data)
        val transactionId = buffer.int

        if (type != TYPE_DATA) return null

        val payloadSize = length - HEADER_SIZE
        val payload = ByteArray(payloadSize)
        buffer.get(payload)

        return UsbPtpData(
            transactionId = transactionId,
            payload = payload
        )
    }

    /**
     * 解析 USB PTP 事件容器
     */
    fun parseEventContainer(data: ByteArray): UsbPtpEvent? {
        if (data.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val length = buffer.int
        val type = buffer.short.toInt() and 0xFFFF
        val code = buffer.short.toInt() and 0xFFFF
        val transactionId = buffer.int

        if (type != TYPE_EVENT) return null

        val params = mutableListOf<Int>()
        val remaining = (length - HEADER_SIZE) / 4
        repeat(remaining.coerceAtMost(3)) {
            if (buffer.remaining() >= 4) params.add(buffer.int)
        }

        return UsbPtpEvent(
            eventCode = code,
            transactionId = transactionId,
            parameters = params
        )
    }

    /**
     * 判断 USB 设备是否为尼康相机
     */
    fun isNikonCamera(vendorId: Int, productId: Int): Boolean {
        return vendorId == NIKON_VENDOR_ID
    }

    /**
     * 获取相机型号名称
     */
    fun getCameraName(productId: Int): String {
        return NIKON_PRODUCT_IDS[productId] ?: "Nikon Camera (0x${productId.toString(16)})"
    }
}

/**
 * USB PTP 响应
 */
data class UsbPtpResponse(
    val transactionId: Int,
    val responseCode: Int,
    val parameters: List<Int>
) {
    val isOk: Boolean get() = responseCode == 0x2001
}

/**
 * USB PTP 数据
 */
data class UsbPtpData(
    val transactionId: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = transactionId
}

/**
 * USB PTP 事件
 */
data class UsbPtpEvent(
    val eventCode: Int,
    val transactionId: Int,
    val parameters: List<Int>
)

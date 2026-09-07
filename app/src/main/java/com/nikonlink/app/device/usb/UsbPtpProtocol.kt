package com.nikonlink.app.device.usb

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
 * 参考: ISO 15740 / PTP over USB 实现
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
     * 容器头解析结果。
     *
     * USB PTP 的 bulk IN 是无消息边界的字节流：一个容器可能跨多次 bulkTransfer
     * 到达，响应容器也可能与数据容器粘连在同一次读取里。事务层必须先读头、
     * 再按 [length] 组装/流式消费整个容器，不能假设「一次读取 = 一个容器」。
     */
    data class ContainerHeader(
        val length: Int,
        val type: Int,
        val code: Int,
        val transactionId: Int
    )

    /**
     * 解析容器头（12 字节）。数据不足 12 字节返回 null（等待更多字节）。
     */
    fun parseContainerHeader(data: ByteArray): ContainerHeader? {
        if (data.size < HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val length = buffer.int
        val type = buffer.short.toInt() and 0xFFFF
        val code = buffer.short.toInt() and 0xFFFF
        val transactionId = buffer.int
        return ContainerHeader(length, type, code, transactionId)
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
     * 获取相机型号名称（**仅作兜底**）。
     *
     * USB PID 映射表不可能覆盖全部机身，且同一 PID 在不同批次/地区可能对应不同
     * 市场名（例如 Z50II 出现过多个 PID），因此依赖它作为显示名必然出现「名称不对」。
     * 正确名称来源是机身自报的 GetDeviceInfo.Model —— 见 [parseDeviceInfoModel]；
     * 只有在拿不到 Model 时才回退到这里。
     */
    fun getCameraName(productId: Int): String {
        return NIKON_PRODUCT_IDS[productId]
            ?: if (NIKON_PRODUCT_IDS.isEmpty()) "Nikon Camera"
            else "Nikon Camera (0x${productId.toString(16).uppercase()})"
    }

    /**
     * 从 GetDeviceInfo（0x1001）载荷中解析 **Model 字段** —— 机身自报的真实型号。
     *
     * DeviceInfo 是变长结构，必须顺序解码到第 12 项才是 Model：
     *   1 u16  StandardVersion
     *   2 u32  VendorExtensionID
     *   3 u16  VendorExtensionVersion
     *   4 Str  VendorExtensionDesc
     *   5 u16  FunctionalMode
     *   6 Arr(u16) OperationsSupported
     *   7 Arr(u16) EventsSupported
     *   8 Arr(u16) DevicePropertiesSupported
     *   9 Arr(u16) CaptureFormats
     *  10 Arr(u16) ImageFormats
     *  11 Str  Manufacturer
     *  12 Str  Model          ← 目标
     *
     * PTP 字符串格式：u8 字符数 N（含结尾 null） + N×UTF-16LE。
     *
     * @return 型号字符串（如 "NIKON Z 50II"）；解析失败返回 null，由调用方回退到 PID 表。
     */
    fun parseDeviceInfoModel(data: ByteArray): String? {
        return try {
            if (data.size < 64) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            b.short                       // StandardVersion
            b.int                         // VendorExtensionID
            b.short                       // VendorExtensionVersion
            readPtpString(b) ?: return null // VendorExtensionDesc
            b.short                       // FunctionalMode
            repeat(5) { skipU16Array(b) ?: return null } // 五个 u16 数组
            readPtpString(b) ?: return null // Manufacturer
            val model = readPtpString(b) ?: return null
            model.trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** PTP 字符串：[u8 字符数] + N×UTF-16LE，去掉结尾 null。缓冲区不足返回 null。 */
    private fun readPtpString(b: ByteBuffer): String? {
        if (b.remaining() < 1) return null
        val count = b.get().toInt() and 0xFF
        if (count == 0) return ""
        if (b.remaining() < count * 2) return null
        val chars = CharArray(count)
        repeat(count) { chars[it] = b.short.toInt().toChar() }
        return chars.concatToString().trim('\u0000')
    }

    /** 跳过一个 [u32 个数] + N×u16 的数组。缓冲区不足返回 null。 */
    private fun skipU16Array(b: ByteBuffer): Unit? {
        if (b.remaining() < 4) return null
        val count = b.int
        if (count < 0 || count > 4096) return null
        val bytes = count * 2
        if (b.remaining() < bytes) return null
        repeat(bytes) { b.get() }
        return Unit
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

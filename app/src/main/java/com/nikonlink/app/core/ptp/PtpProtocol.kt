package com.nikonlink.app.core.ptp

import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP/IP 协议常量定义
 * 基于 ISO 15740:2013 / ISO 12234-2
 *
 * PRD 4.1: PTP/IP 协议栈
 * - 命令请求（Command）
 * - 数据流（Data）
 * - 事件通知（Event）
 */
object PtpConstants {
    // PTP/IP Packet Types
    const val PACKET_TYPE_INIT_COMMAND = 0x0001
    const val PACKET_TYPE_INIT_RESPONSE = 0x0002
    const val PACKET_TYPE_INIT_EVENT_REQUEST = 0x0003
    const val PACKET_TYPE_INIT_EVENT_RESPONSE = 0x0004
    const val PACKET_TYPE_INIT_FAIL = 0x0005
    const val PACKET_TYPE_COMMAND_REQUEST = 0x0006
    const val PACKET_TYPE_COMMAND_RESPONSE = 0x0007
    const val PACKET_TYPE_EVENT = 0x0008
    const val PACKET_TYPE_START_DATA = 0x0009
    const val PACKET_TYPE_DATA_PACKET = 0x000A
    const val PACKET_TYPE_CANCEL_REQUEST = 0x000B
    const val PACKET_TYPE_END_DATA = 0x000C
    const val PACKET_TYPE_PING = 0x000D
    const val PACKET_TYPE_PONG = 0x000E

    // PTP Operation Codes (standard)
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_NUM_OBJECTS = 0x1006
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMBNAIL = 0x100A
    const val OP_DELETE_OBJECT = 0x100B
    const val OP_INITIATE_CAPTURE = 0x100E
    const val OP_INITIATE_OPEN_CAPTURE = 0x100F
    const val OP_TERMINATE_OPEN_CAPTURE = 0x1010
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016
    const val OP_GET_PARTIAL_OBJECT = 0x101B

    // Nikon Vendor Extension Operations
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203
    const val OP_NIKON_MF_DRIVE = 0x9204
    const val OP_NIKON_CHANGE_AF_AREA = 0x9205
    const val OP_NIKON_AF_DRIVE = 0x9206
    const val OP_NIKON_GET_CAMERA_INFO = 0x9207
    const val OP_NIKON_DEVICE_READY = 0x90C8

    // PTP Response Codes
    const val RESPONSE_OK = 0x2001
    const val RESPONSE_GENERAL_ERROR = 0x2002
    const val RESPONSE_SESSION_NOT_OPEN = 0x2003
    const val RESPONSE_INVALID_TRANSACTION = 0x2004
    const val RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005
    const val RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RESPONSE_INCOMPLETE_TRANSFER = 0x2007
    const val RESPONSE_INVALID_STORAGE_ID = 0x2008
    const val RESPONSE_INVALID_OBJECT_HANDLE = 0x2009
    const val RESPONSE_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RESPONSE_INVALID_OBJECT_FORMAT = 0x200B
    const val RESPONSE_STORE_FULL = 0x200C
    const val RESPONSE_OBJECT_WRITE_PROTECTED = 0x200D
    const val RESPONSE_STORE_READ_ONLY = 0x200E
    const val RESPONSE_ACCESS_DENIED = 0x200F
    const val RESPONSE_NO_THUMBNAIL_PRESENT = 0x2010
    const val RESPONSE_CAPTURE_ALREADY_TERMINATED = 0x2011
    const val RESPONSE_DEVICE_BUSY = 0x2019
    const val RESPONSE_INVALID_PARENT_OBJECT = 0x201A
    const val RESPONSE_INVALID_DEVICE_PROP_FORMAT = 0x201B
    const val RESPONSE_INVALID_DEVICE_PROP_VALUE = 0x201C
    const val RESPONSE_SESSION_ALREADY_OPEN = 0x201E
    const val RESPONSE_TRANSACTION_CANCELLED = 0x201F

    // PTP Event Codes
    const val EVENT_DEVICE_PROP_CHANGED = 0x4006

    // PTP Device Property Codes
    const val PROP_BATTERY_LEVEL = 0x5001
    const val PROP_IMAGE_SIZE = 0x5003
    const val PROP_COMPRESSION_SETTING = 0x5004
    const val PROP_WHITE_BALANCE = 0x5005
    const val PROP_RGB_GAIN = 0x5006
    const val PROP_F_NUMBER = 0x5007
    const val PROP_FOCAL_LENGTH = 0x5008
    const val PROP_FOCUS_DISTANCE = 0x5009
    const val PROP_FOCUS_MODE = 0x500A
    const val PROP_EXPOSURE_METERING_MODE = 0x500B
    const val PROP_FLASH_MODE = 0x500C
    const val PROP_EXPOSURE_TIME = 0x500D
    const val PROP_EXPOSURE_PROGRAM_MODE = 0x500E
    const val PROP_EXPOSURE_INDEX = 0x500F  // ISO
    const val PROP_EXPOSURE_BIAS_COMPENSATION = 0x5010
    const val PROP_DATE_TIME = 0x5011
    const val PROP_CAPTURE_DELAY = 0x5012
    const val PROP_STILL_CAPTURE_MODE = 0x5013
    const val PROP_FOCUS_METERING_MODE = 0x501C

    // PTP/IP default port
    const val DEFAULT_PORT = 15740
    const val EVENT_PORT = 15740

    // Protocol constants
    const val PTP_IP_HEADER_SIZE = 8  // 4 bytes length + 4 bytes type
    const val MAX_PACKET_SIZE = 65536
    const val PROTOCOL_VERSION = 0x00000100  // v1.0
}

/**
 * PTP/IP 数据包基类
 */
sealed class PtpPacket {
    abstract val type: Int
    abstract fun toBytes(): ByteArray

    companion object {
        fun fromStream(inputStream: InputStream): PtpPacket? {
            val header = ByteArray(PtpConstants.PTP_IP_HEADER_SIZE)
            if (!readFully(inputStream, header, PtpConstants.PTP_IP_HEADER_SIZE)) return null

            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val length = buffer.int
            val type = buffer.int

            val payloadLength = length - PtpConstants.PTP_IP_HEADER_SIZE
            if (payloadLength < 0 || length > PtpConstants.MAX_PACKET_SIZE) {
                Timber.w("Invalid PTP packet length: $length")
                return null
            }
            val payload = if (payloadLength > 0) {
                val data = ByteArray(payloadLength)
                if (!readFully(inputStream, data, payloadLength)) return null
                data
            } else {
                ByteArray(0)
            }

            return try {
                createPacket(type, payload)
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse PTP packet type=0x${type.toString(16)}")
                null
            }
        }

        private fun readFully(inputStream: InputStream, buffer: ByteArray, length: Int): Boolean {
            var totalRead = 0
            while (totalRead < length) {
                val r = inputStream.read(buffer, totalRead, length - totalRead)
                if (r == -1) return false
                totalRead += r
            }
            return true
        }

        private fun createPacket(type: Int, payload: ByteArray): PtpPacket {
            return when (type) {
                PtpConstants.PACKET_TYPE_INIT_RESPONSE -> InitResponsePacket.parse(payload)
                PtpConstants.PACKET_TYPE_INIT_EVENT_RESPONSE -> InitEventAckPacket
                PtpConstants.PACKET_TYPE_COMMAND_RESPONSE -> CommandResponsePacket.parse(payload)
                PtpConstants.PACKET_TYPE_EVENT -> EventResponsePacket.parse(payload)
                PtpConstants.PACKET_TYPE_START_DATA -> StartDataPacket.parse(payload)
                PtpConstants.PACKET_TYPE_DATA_PACKET -> DataPacket.parse(payload)
                PtpConstants.PACKET_TYPE_END_DATA -> EndDataPacket.parse(payload)
                PtpConstants.PACKET_TYPE_PONG -> PongPacket
                else -> UnknownPacket(type, payload)
            }
        }
    }
}

/**
 * 初始化命令请求包
 */
data class InitCommandPacket(
    val protocolVersion: Int = PtpConstants.PROTOCOL_VERSION,
    val clientGuid: ByteArray = generateGuid(),
    val clientName: String = "NikonLink"
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_INIT_COMMAND

    override fun toBytes(): ByteArray {
        val nameBytes = clientName.toByteArray(Charsets.UTF_16LE)
        // PTP/IP init command: 16-byte GUID + UTF-16LE name + null + version minor/major
        val size = PtpConstants.PTP_IP_HEADER_SIZE + 16 + nameBytes.size + 2 + 4
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(size)
        buffer.putInt(type)
        buffer.put(clientGuid)
        buffer.put(nameBytes)
        buffer.putShort(0) // null terminator
        buffer.putShort(0) // version minor
        buffer.putShort(1) // version major

        return buffer.array()
    }

    companion object {
        fun generateGuid(): ByteArray {
            val guid = ByteArray(16)
            java.util.UUID.randomUUID().let {
                ByteBuffer.wrap(guid).apply {
                    putLong(it.mostSignificantBits)
                    putLong(it.leastSignificantBits)
                }
            }
            return guid
        }
    }
}

/**
 * 初始化响应包
 */
data class InitResponsePacket(
    val sessionId: Int,
    val serverGuid: ByteArray,
    val serverName: String,
    val sessionStatus: Int
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_INIT_RESPONSE

    override fun toBytes(): ByteArray = ByteArray(0) // Client doesn't send this

    companion object {
        fun parse(payload: ByteArray): InitResponsePacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            // 前 4 字节为相机分配的会话 ID，随后是 16 字节 GUID 和 UTF-16LE 名称
            val sessionId = buffer.int
            val guid = ByteArray(16)
            buffer.get(guid)

            val nameChars = mutableListOf<Char>()
            while (buffer.remaining() >= 2) {
                val ch = buffer.short
                if (ch == 0.toShort()) break
                nameChars.add(ch.toInt().toChar())
            }
            val status = if (buffer.remaining() >= 4) buffer.int else 0

            return InitResponsePacket(
                sessionId = sessionId,
                serverGuid = guid,
                serverName = nameChars.joinToString(""),
                sessionStatus = status
            )
        }
    }
}

/**
 * 命令请求包
 */
data class CommandRequestPacket(
    val transactionId: Int,
    val operationCode: Int,
    val parameters: List<Int> = emptyList(),
    val dataPhase: Boolean = false
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_COMMAND_REQUEST

    override fun toBytes(): ByteArray {
        val size = PtpConstants.PTP_IP_HEADER_SIZE + 4 + 2 + 4 + (parameters.size * 4)
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(size)
        buffer.putInt(type)
        buffer.putInt(if (dataPhase) 2 else 1)
        buffer.putShort(operationCode.toShort())
        buffer.putInt(transactionId)
        parameters.forEach { buffer.putInt(it) }

        return buffer.array()
    }
}

/**
 * 命令响应包
 */
data class CommandResponsePacket(
    val transactionId: Int,
    val responseCode: Int,
    val parameters: List<Int> = emptyList()
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_COMMAND_RESPONSE

    val isOk: Boolean get() = responseCode == PtpConstants.RESPONSE_OK

    override fun toBytes(): ByteArray = ByteArray(0)

    companion object {
        fun parse(payload: ByteArray): CommandResponsePacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val code = buffer.short.toInt() and 0xFFFF
            val txId = buffer.int
            val params = mutableListOf<Int>()
            while (buffer.remaining() >= 4) {
                if (buffer.remaining() >= 4) params.add(buffer.int)
            }
            return CommandResponsePacket(txId, code, params)
        }
    }
}

/**
 * 数据开始包
 */
data class StartDataPacket(
    val transactionId: Int,
    val totalSize: Int
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_START_DATA

    override fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PtpConstants.PTP_IP_HEADER_SIZE + 12)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpConstants.PTP_IP_HEADER_SIZE + 12)
        buffer.putInt(type)
        buffer.putInt(transactionId)
        buffer.putInt(totalSize)
        buffer.putInt(0)
        return buffer.array()
    }

    companion object {
        fun parse(payload: ByteArray): StartDataPacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            return StartDataPacket(buffer.int, buffer.int)
        }
    }
}

/**
 * 数据包
 */
data class DataPacket(
    val transactionId: Int,
    val data: ByteArray
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_DATA_PACKET

    override fun toBytes(): ByteArray {
        val size = PtpConstants.PTP_IP_HEADER_SIZE + 4 + data.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(size)
        buffer.putInt(type)
        buffer.putInt(transactionId)
        buffer.put(data)
        return buffer.array()
    }

    companion object {
        fun parse(payload: ByteArray): DataPacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val txId = buffer.int
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            return DataPacket(txId, data)
        }
    }
}

/**
 * 数据结束包
 */
data class EndDataPacket(
    val transactionId: Int,
    val data: ByteArray
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_END_DATA

    override fun toBytes(): ByteArray {
        val size = PtpConstants.PTP_IP_HEADER_SIZE + 4 + data.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(size)
        buffer.putInt(type)
        buffer.putInt(transactionId)
        buffer.put(data)
        return buffer.array()
    }

    companion object {
        fun parse(payload: ByteArray): EndDataPacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val txId = buffer.int
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            return EndDataPacket(txId, data)
        }
    }
}

/**
 * 事件通道初始化请求
 */
data class InitEventRequestPacket(
    val sessionId: Int
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_INIT_EVENT_REQUEST

    override fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PtpConstants.PTP_IP_HEADER_SIZE + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpConstants.PTP_IP_HEADER_SIZE + 4)
        buffer.putInt(type)
        buffer.putInt(sessionId)
        return buffer.array()
    }
}

/**
 * 事件通道初始化确认（无载荷）
 */
data object InitEventAckPacket : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_INIT_EVENT_RESPONSE

    override fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PtpConstants.PTP_IP_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpConstants.PTP_IP_HEADER_SIZE)
        buffer.putInt(type)
        return buffer.array()
    }
}

/**
 * 事件响应包
 */
data class EventResponsePacket(
    val transactionId: Int,
    val eventCode: Int,
    val parameters: List<Int> = emptyList()
) : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_EVENT

    override fun toBytes(): ByteArray = ByteArray(0)

    companion object {
        fun parse(payload: ByteArray): EventResponsePacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val code = buffer.short.toInt() and 0xFFFF
            val txId = buffer.int
            val params = mutableListOf<Int>()
            while (buffer.remaining() >= 4) {
                if (buffer.remaining() >= 4) params.add(buffer.int)
            }
            return EventResponsePacket(txId, code, params)
        }
    }
}

/**
 * 保活 Ping（无载荷）
 */
data object PingPacket : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_PING

    override fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PtpConstants.PTP_IP_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpConstants.PTP_IP_HEADER_SIZE)
        buffer.putInt(type)
        return buffer.array()
    }
}

/**
 * 相机 Pong 响应（无载荷）
 */
data object PongPacket : PtpPacket() {
    override val type = PtpConstants.PACKET_TYPE_PONG
    override fun toBytes(): ByteArray = ByteArray(0)
}

/**
 * 未知包类型
 */
data class UnknownPacket(
    override val type: Int,
    val payload: ByteArray
) : PtpPacket() {
    override fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PtpConstants.PTP_IP_HEADER_SIZE + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpConstants.PTP_IP_HEADER_SIZE + payload.size)
        buffer.putInt(type)
        buffer.put(payload)
        return buffer.array()
    }
}

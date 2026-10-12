package com.camerakit.ptp

/**
 * PTP / PTP-IP / 尼康私有协议常量。
 *
 * 来源互证：基准实现 v1.0.257 静态分析实证 + gphoto/开源 PTP 协议实现 ptp.h + mmattes/ptpip + CIPA DC-X005。
 */
object PtpConsts {

    // ---------------- 标准 PTP 操作码 ----------------
    const val OP_GET_DEVICE_INFO = 0x1001
    const val OP_OPEN_SESSION = 0x1002
    const val OP_CLOSE_SESSION = 0x1003
    const val OP_GET_STORAGE_IDS = 0x1004
    const val OP_GET_STORAGE_INFO = 0x1005
    const val OP_GET_NUM_OBJECTS = 0x1006
    const val OP_GET_OBJECT_HANDLES = 0x1007
    const val OP_GET_OBJECT_INFO = 0x1008
    const val OP_GET_OBJECT = 0x1009
    const val OP_GET_THUMB = 0x100A
    const val OP_DELETE_OBJECT = 0x100B
    const val OP_GET_DEVICE_PROP_DESC = 0x1014
    const val OP_GET_DEVICE_PROP_VALUE = 0x1015
    const val OP_SET_DEVICE_PROP_VALUE = 0x1016
    const val OP_GET_PARTIAL_OBJECT = 0x101B

    // ---------------- 尼康私有操作码（开源 PTP 协议实现 ptp.h / 基准实现） ----------------
    const val OP_NIKON_CHECK_EVENT = 0x90C7
    const val OP_NIKON_DEVICE_READY = 0x90C8
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203

    /** 基准实现 扩展分段读（设备能力含此 opcode 时用于大文件分段） */
    const val OP_NIKON_GET_PARTIAL_OBJECT_EX = 0x9431

    /** 基准实现「PC 主机注册」配对：预备（data-in 返回字符串）→ 相机端确认 → 确认（参数 0x2001） */
    const val OP_NIKON_HOST_REG_PREPARE = 0x952B
    const val OP_NIKON_HOST_REG_CONFIRM = 0x935A
    const val HOST_REG_CONFIRM_PARAM = 0x2001L

    // ---------------- PTP 响应码（标准段） ----------------
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TRANSACTION_ID = 0x2004
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_INCOMPLETE_TRANSFER = 0x2007
    const val RC_INVALID_STORAGE_ID = 0x2008
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_DEVICE_PROP_NOT_SUPPORTED = 0x200A
    const val RC_INVALID_OBJECT_FORMAT = 0x200B
    const val RC_STORE_FULL = 0x200C
    const val RC_OBJECT_WRITE_PROTECTED = 0x200D
    const val RC_STORE_READ_ONLY = 0x200E
    const val RC_ACCESS_DENIED = 0x200F
    const val RC_NO_THUMBNAIL = 0x2010
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_INVALID_PARAMETER = 0x201D
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    const val RC_TRANSACTION_CANCELLED = 0x201F

    fun responseName(code: Int): String = when (code) {
        RC_OK -> "OK"
        RC_GENERAL_ERROR -> "GeneralError"
        RC_SESSION_NOT_OPEN -> "SessionNotOpen"
        RC_INVALID_TRANSACTION_ID -> "InvalidTransactionID"
        RC_OPERATION_NOT_SUPPORTED -> "OperationNotSupported"
        RC_PARAMETER_NOT_SUPPORTED -> "ParameterNotSupported"
        RC_INCOMPLETE_TRANSFER -> "IncompleteTransfer"
        RC_INVALID_STORAGE_ID -> "InvalidStorageID"
        RC_INVALID_OBJECT_HANDLE -> "InvalidObjectHandle"
        RC_DEVICE_PROP_NOT_SUPPORTED -> "DevicePropNotSupported"
        RC_INVALID_OBJECT_FORMAT -> "InvalidObjectFormatCode"
        RC_STORE_FULL -> "StoreFull"
        RC_OBJECT_WRITE_PROTECTED -> "ObjectWriteProtected"
        RC_STORE_READ_ONLY -> "StoreReadOnly"
        RC_ACCESS_DENIED -> "AccessDenied"
        RC_NO_THUMBNAIL -> "NoThumbnailPresent"
        RC_DEVICE_BUSY -> "DeviceBusy"
        RC_INVALID_PARAMETER -> "InvalidParameter"
        RC_SESSION_ALREADY_OPEN -> "SessionAlreadyOpen"
        RC_TRANSACTION_CANCELLED -> "TransactionCancelled"
        else -> "Unknown(0x${code.toString(16).uppercase()})"
    }

    // ---------------- PTP/IP 包类型（8 字节头 length u32 + type u32） ----------------
    const val IP_INIT_COMMAND = 1
    const val IP_INIT_COMMAND_ACK = 2
    const val IP_INIT_EVENT = 3
    const val IP_INIT_EVENT_ACK = 4
    const val IP_INIT_FAIL = 5
    const val IP_CMD_REQUEST = 6
    const val IP_CMD_RESPONSE = 7
    const val IP_EVENT = 8
    const val IP_START_DATA = 9
    const val IP_DATA = 10
    const val IP_CANCEL = 11
    const val IP_END_DATA = 12
    const val IP_PING = 13
    const val IP_PONG = 14

    // ---------------- USB PTP 容器（12 字节头 length u32 + type u16 + code u16 + transId u32） ----------------
    const val USB_CONTAINER_COMMAND = 1
    const val USB_CONTAINER_DATA = 2
    const val USB_CONTAINER_RESPONSE = 3
    const val USB_CONTAINER_EVENT = 4

    // ---------------- 连接参数 ----------------
    const val DEFAULT_PTP_IP_PORT = 15740
    const val DEFAULT_AP_HOST = "192.168.1.1"

    /** PTP/IP 协议版本 1.0（基准实现 实证 0x00010000） */
    const val PTP_IP_PROTOCOL_VERSION = 0x00010000

    /** 入包长度校验上限（基准实现 实证 32MB） */
    const val MAX_PACKET_BYTES = 32 * 1024 * 1024

    /** 尼康 USB Vendor ID */
    const val NIKON_VENDOR_ID = 0x04B0

    /** USB PTP 接口：Imaging(6) / Still Image(1) / PTP(1) */
    const val USB_CLASS_IMAGING = 6
    const val USB_SUBCLASS_STILL_IMAGE = 1
    const val USB_PROTOCOL_PTP = 1

    /** bulkTransfer 单次上限（多数 Android 控制器限制 16KB） */
    const val USB_BULK_CHUNK = 16 * 1024

    /** mDNS 服务类型（CIPA DC-X005） */
    const val MDNS_SERVICE_TYPE = "_ptp._tcp."

    /** 保活间隔与失活判定（毫秒） */
    const val KEEPALIVE_INTERVAL_MS = 20_000L
    const val KEEPALIVE_DEAD_MS = 60_000L

    /** 相机端主机确认等待（基准实现 实证 8500ms，可配置） */
    const val HOST_CONFIRM_WAIT_MS = 8_500L
}

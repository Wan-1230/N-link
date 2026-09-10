package com.nikonlink.app.device.ptp

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

    // ---- MTP（Media Transfer Protocol）扩展操作 ----
    // MTP 是 PTP 的超集（PTP over USB 的「媒体传输」profile），尼康机身在 USB 连接
    // 时普遍同时暴露 MTP 操作。价值在于 0x9805 GetObjectPropList：
    //   一次事务即可取回**全部对象**的指定属性（文件名/大小/日期/格式）。
    // 对比逐个 GetObjectInfo（0x1008）的 N 次往返——批量路径把元数据读取从
    // 「N × RTT」压缩到「1 × RTT」，是相册首屏加载速度的胜负手。
    // 参考：PixCake 内嵌的 com.truesight.cameraptp SDK 同样使用 MtpGetObjectPropList。
    const val OP_MTP_GET_OBJECT_PROPS_SUPPORTED = 0x9801
    const val OP_MTP_GET_OBJECT_PROP_DESC = 0x9802
    const val OP_MTP_GET_OBJECT_PROP_VALUE = 0x9803
    const val OP_MTP_SET_OBJECT_PROP_VALUE = 0x9804
    const val OP_MTP_GET_OBJECT_PROP_LIST = 0x9805

    const val RESPONSE_MTP_INVALID_OBJECT_PROP_CODE = 0xA801
    const val RESPONSE_MTP_INVALID_OBJECT_PROP_FORMAT = 0xA802
    const val RESPONSE_MTP_SPEC_BY_FORMAT_UNSUPPORTED = 0xA805

    // Nikon Vendor Extension Operations
    const val OP_NIKON_START_LIVE_VIEW = 0x9201
    const val OP_NIKON_END_LIVE_VIEW = 0x9202
    const val OP_NIKON_GET_LIVE_VIEW_IMAGE = 0x9203
    const val OP_NIKON_MF_DRIVE = 0x9204
    const val OP_NIKON_CHANGE_AF_AREA = 0x9205
    const val OP_NIKON_AF_DRIVE = 0x90C1
    const val OP_NIKON_AF_DRIVE_CANCEL = 0x9206
    const val OP_NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207
    const val OP_NIKON_START_MOVIE_REC_IN_CARD = 0x920A
    const val OP_NIKON_END_MOVIE_REC = 0x920B
    const val OP_NIKON_TERMINATE_CAPTURE = 0x920C
    const val OP_NIKON_CHECK_EVENT = 0x90C7
    const val OP_NIKON_DEVICE_READY = 0x90C8
    const val OP_NIKON_GET_PREVIEW_IMG = 0x9200
    const val OP_NIKON_GET_LARGE_THUMB = 0x90C4
    const val OP_NIKON_INITIATE_CAPTURE_REC_IN_SDRAM = 0x90C0
    const val OP_NIKON_AF_CAPTURE_SDRAM = 0x90CB

    /**
     * Nikon 应用模式切换（gphoto2: ChangeApplicationMode，1 参数）。
     * 影犀的录像链路：开录前 0x9435(1) 进入应用模式，收录后 0x9435(0) 退出。
     * 相机端表现为短暂弹出「已连接到智能设备」——进入遥控应用态的正常提示。
     */
    const val OP_NIKON_CHANGE_APPLICATION_MODE = 0x9435

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
    const val RESPONSE_NIKON_NOT_LIVE_VIEW = 0xA00B

    /**
     * Nikon 0xA004 = **InvalidStatus**（libgphoto2 ptp.h: `PTP_RC_NIKON_InvalidStatus`）。
     * 旧代码误标为「拒绝开启实时取景」——真实语义是「相机当前状态不允许该操作」：
     * 录制/监看场景下最常见的触发是**模式拨盘不在所需位置**（录制需视频档、
     * 监看需照片档）或机身停留在回放/菜单界面。
     */
    const val RESPONSE_NIKON_INVALID_STATUS = 0xA004

    /**
     * Nikon 厂商操作码 0x90C2 ChangeCameraMode（libgphoto2: `PTP_OC_NIKON_ChangeCameraMode`，
     * digiCamControl NikonBase.LockCamera/UnLockCamera 同款）：参数 1 = 进入机身控制模式，
     * 参数 0 = 退出。digiCamControl 的录像时序为 ChangeCameraMode(1) → StartMovieRecInCard(0x920A)，
     * 结束后 ChangeCameraMode(0) 恢复，避免相机滞留控制模式影响后续拍照/下载。
     */
    const val OP_NIKON_CHANGE_CAMERA_MODE = 0x90C2

    /**
     * Nikon 厂商响应码 0xA200：B 门收门时机身正在处理曝光写卡（ZRelay 同名证据
     * `0xA200 BulbReleaseBusy`）。结束 B 门遇此码应短暂等待后重试，而非报错。
     *
     * 注：0x9207 InitiateCaptureRecInMedia 常量在本文件上方已有定义（快门多源归一化时引入），
     * B 门链路复用同一操作码——快门处于 Bulb 档时它就是开启曝光的入口。
     */
    const val RESPONSE_NIKON_BULB_BUSY = 0xA200

    /**
     * PTP 响应码中文化描述（日志与用户提示统一使用）。
     * 未知码返回十六进制形式，便于扩展排查。
     */
    fun describeResponseCode(code: Int): String = when (code) {
        RESPONSE_OK -> "成功"
        RESPONSE_GENERAL_ERROR -> "一般错误"
        RESPONSE_SESSION_NOT_OPEN -> "会话未打开"
        RESPONSE_INVALID_TRANSACTION -> "事务无效"
        RESPONSE_OPERATION_NOT_SUPPORTED -> "相机不支持该操作"
        RESPONSE_PARAMETER_NOT_SUPPORTED -> "参数不受支持"
        RESPONSE_INCOMPLETE_TRANSFER -> "传输不完整"
        RESPONSE_INVALID_STORAGE_ID -> "存储 ID 无效"
        RESPONSE_INVALID_OBJECT_HANDLE -> "对象句柄无效"
        RESPONSE_DEVICE_PROP_NOT_SUPPORTED -> "相机不支持该属性"
        RESPONSE_INVALID_OBJECT_FORMAT -> "对象格式无效"
        RESPONSE_STORE_FULL -> "存储卡已满"
        RESPONSE_OBJECT_WRITE_PROTECTED -> "文件写保护"
        RESPONSE_STORE_READ_ONLY -> "存储卡只读"
        RESPONSE_ACCESS_DENIED -> "访问被拒绝（相机端未确认连接）"
        RESPONSE_NO_THUMBNAIL_PRESENT -> "无缩略图可用"
        RESPONSE_DEVICE_BUSY -> "相机忙碌，请稍后重试"
        RESPONSE_SESSION_ALREADY_OPEN -> "会话已打开"
        RESPONSE_TRANSACTION_CANCELLED -> "事务已取消"
        RESPONSE_NIKON_NOT_LIVE_VIEW -> "相机未处于实时取景状态"
        RESPONSE_NIKON_INVALID_STATUS ->
            "相机当前状态不允许该操作（Nikon 0xA004）：录制请把拨盘切到视频档、监看请切到照片档，且不要停留在回放/菜单界面"
        RESPONSE_NIKON_BULB_BUSY -> "相机正在结束 B 门曝光，请稍候"
        else -> "0x${code.toString(16).uppercase()}"
    }

    // PTP Event Codes
    // event code=0x4002 (ObjectAdded) / 0x4006 (DevicePropChanged) / 0x400d
    const val EVENT_OBJECT_ADDED = 0x4002
    const val EVENT_DEVICE_PROP_CHANGED = 0x4006
    const val EVENT_CAPTURE_COMPLETE = 0x400D

    /**
     * 尼康私有事件（v1.3.0 需求 5：逐张实时加载的核心信号）。
     *
     * **依据**：libgphoto2 `camlibs/ptp2/ptp.h` —— `PTP_EC_Nikon_ObjectAddedInSDRAM = 0xC101`、
     * `PTP_EC_Nikon_CaptureCompleteRecInSdram = 0xC102`；其 capture 流程正是等这两个事件
     * （见 gphoto/libgphoto2 issue #846）。参考实现同样以它们为触发：
     * PixCake（`NikonObjectAddedInSdram` + `onRamObjectAdded`）、影犀（`Nikon_ObjectAddedInSDRAM`）。
     *
     * **为什么必须处理**：尼康机身拍照后主要上报的是这两个私有事件，标准 `0x4002` 在部分
     * 机型上延迟或不来 —— 这正是旧版「相册间歇性刷新」的根因。
     *
     * 参数：Param1 = 新对象的 object handle；`0xffff0001` 表示**无句柄**
     * （NEF+RAW 等双拍场景），此时必须退回全量同步。
     */
    const val EVENT_NIKON_OBJECT_ADDED_IN_SDRAM = 0xC101
    const val EVENT_NIKON_CAPTURE_COMPLETE_REC_IN_SDRAM = 0xC102

    /** 尼康事件 Param1 的「无句柄」哨兵值（见上） */
    const val NIKON_EVENT_HANDLE_NONE = 0xffff0001.toInt()

    // Nikon 厂商属性: LiveView 图像配置 (image profile set size=0xd1ac:3)
    const val PROP_NIKON_LV_IMAGE_PROFILE = 0xD1AC

    /**
     * Nikon 厂商属性: LiveView 禁止条件位图（只读）。
     * 官方文档未公布各 bit 含义，gphoto2 / ZRelay 也仅有状态名没有权威位定义，
     * 因此只能做日志记录与错误提示富化：非 0 时不应阻断流程，永远以相机实际
     * 返回码为准。
     */
    const val PROP_NIKON_LV_PROHIBIT_CONDITION = 0xD1A4

    /**
     * 把 0xD1A4 位图翻译成可读提示。
     * 位含义为社区逆向的常见值，未知组合返回 null（按未知处理，不阻断）。
     */
    fun describeProhibitCondition(value: Int): String? {
        if (value == 0) return null
        val parts = mutableListOf<String>()
        if (value and 0x01 != 0) parts.add("镜头未安装")
        if (value and 0x02 != 0) parts.add("电量不足")
        if (value and 0x04 != 0) parts.add("相机处于回放/菜单状态")
        if (value and 0x08 != 0) parts.add("USB 模式占用")
        if (value and 0x10 != 0) parts.add("机身过热")
        if (value and 0x20 != 0) parts.add("正在写卡")
        return if (parts.isEmpty()) null else parts.joinToString("、")
    }

    // 监看启动后相机上报 DevicePropChanged prop=0x500E，
    // 即进入无线控制(遥控)模式时曝光程序模式切到 Remote 值；
    // 启动 LiveView 前先设定该值，否则相机可能拒绝 StartLiveView
    const val PROP_VALUE_REMOTE_MODE = 0x8012

    // 触摸对焦坐标范围约 x:0~4000, y:0~3000
    const val AF_COORD_MAX_X = 4000
    const val AF_COORD_MAX_Y = 3000

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

    /**
     * Nikon 厂商属性: ShutterSpeed (0xD100)。
     *
     * 部分机身（尤其 Z 系列）在遥控模式下只通过该属性上报/接受快门速度，
     * 标准 0x500D 可能只读、不刷新甚至缺报。值布局为 **高 16 位 = 分子，低 16 位 = 分母**
     * 的打包分数（如 1/125 → 0x0001_007D），与 0x500D 的 1/10000s 定点数不同；
     * 解析见 CameraParameterManager。
     */
    const val PROP_NIKON_SHUTTER_SPEED = 0xD100

    /** Nikon 厂商属性: 快门速度数据包中的无效哨兵（B 门等），只用于合法性判断 */
    const val NIKON_SHUTTER_SPEED_INVALID = 0xFFFFFFFFL

    // Nikon 厂商镜头属性（PTP 扩展定义）
    const val PROP_NIKON_LENS_ID = 0xD0E0
    const val PROP_NIKON_LENS_SORT = 0xD0E1
    const val PROP_NIKON_LENS_TYPE = 0xD0E2
    const val PROP_NIKON_FOCAL_LENGTH_MIN = 0xD0E3
    const val PROP_NIKON_FOCAL_LENGTH_MAX = 0xD0E4
    const val PROP_NIKON_MAX_AP_AT_MIN = 0xD0E5
    const val PROP_NIKON_MAX_AP_AT_MAX = 0xD0E6

    // PTP/IP default port
    const val DEFAULT_PORT = 15740
    const val EVENT_PORT = 15740

    // Protocol constants
    const val PTP_IP_HEADER_SIZE = 8  // 4 bytes length + 4 bytes type
    // 部分尼康机型会把较大的对象放在单个 EndData 包中返回，
    // 不能用 64KB 之类的固定值截断，否则命令通道会失步。
    const val MAX_PACKET_SIZE = Int.MAX_VALUE
    const val PROTOCOL_VERSION = 0x00000100  // v1.0
}

/**
 * MTP 对象属性码（ObjectPropCode）。
 *
 * 用于 0x9805 GetObjectPropList 的批量元数据读取。只取相册真正需要的
 * 几个属性即可还原 CameraFile；不认识的条目在解析时跳过。
 */
object MtpObjectProp {
    const val STORAGE_ID = 0xDC01
    const val OBJECT_FORMAT = 0xDC02
    const val PROTECTION_STATUS = 0xDC03
    const val OBJECT_SIZE = 0xDC04
    const val ASSOCIATION_TYPE = 0xDC05
    const val OBJECT_FILE_NAME = 0xDC07
    const val DATE_CREATED = 0xDC08
    const val DATE_MODIFIED = 0xDC09
    const val PARENT_OBJECT = 0xDC0B
    const val PERSISTENT_UID = 0xDC41

    /** 0x9805 的参数取值：请求全部属性 */
    const val ALL = -1  // 0xFFFFFFFF
}

/**
 * MTP 数据类型（DataType）——决定 0x9805 返回值的长度与读取方式。
 * 数组类型以 0x4000 为基（如 0x4006 = AINT32），前 4 字节是元素个数。
 */
object MtpDataType {
    const val UNDEF = 0x0000
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val INT128 = 0x0009
    const val UINT128 = 0x000A
    const val ARRAY_MASK = 0x4000
    const val STRING = 0xFFFF
}

/**
 * 单个对象的批量元数据（由 0x9805 GetObjectPropList 解析得到）。
 * 字段均为可选——机身返回的属性集随机型/模式而异，缺哪一列就用对应的兜底策略。
 */
data class MtpObjectProps(
    val handle: Int,
    val storageId: Int? = null,
    val objectFormat: Int? = null,
    val objectSize: Long? = null,
    val fileName: String? = null,
    val dateCreatedRaw: String? = null,
    val dateModifiedRaw: String? = null,
    val parentObject: Int? = null
) {
    /** 目录（Association）而非照片：相册列表应当过滤掉 */
    val isAssociation: Boolean get() = objectFormat == 0x3001 || objectFormat == 0x3000
}

/**
 * 0x9805 GetObjectPropList 响应解析器。
 *
 * 载荷是**没有 count 前缀**的连续条目流：
 *   u32 ObjectHandle + u16 PropCode + u16 DataType + Value(变长)，连续 N 条
 * 因此只能顺序解码直到缓冲区耗尽；遇到无法识别的 DataType 即判为不可信并整体放弃
 * （交由调用方回退到逐个 GetObjectInfo），避免解析错位产生脏数据。
 */
object MtpObjectPropListParser {

    /**
     * @return 解析结果；返回 null 表示载荷不可信（调用方应回退）。
     */
    fun parse(payload: ByteArray): List<MtpObjectProps>? {
        if (payload.size < 8) return null
        val buffer = java.nio.ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // 按 handle 归并：同一对象的多个属性条目分散在流中
        val acc = LinkedHashMap<Int, MutableBuilder>()
        var entries = 0

        while (buffer.remaining() >= 8) {
            val handle = buffer.int
            val propCode = buffer.short.toInt() and 0xFFFF
            val dataType = buffer.short.toInt() and 0xFFFF

            val value: Any? = readValue(buffer, dataType) ?: return null
            val b = acc.getOrPut(handle) { MutableBuilder(handle) }
            when (propCode) {
                MtpObjectProp.STORAGE_ID -> (value as? Number)?.let { b.storageId = it.toInt() }
                MtpObjectProp.OBJECT_FORMAT -> (value as? Number)?.let { b.objectFormat = it.toInt() }
                MtpObjectProp.OBJECT_SIZE -> (value as? Number)?.let { b.objectSize = it.toLong() }
                MtpObjectProp.OBJECT_FILE_NAME -> (value as? String)?.let { b.fileName = it }
                MtpObjectProp.DATE_CREATED -> (value as? String)?.let { b.dateCreated = it }
                MtpObjectProp.DATE_MODIFIED -> (value as? String)?.let { b.dateModified = it }
                MtpObjectProp.PARENT_OBJECT -> (value as? Number)?.let { b.parentObject = it.toInt() }
                else -> Unit // 其余属性（保护状态、PersistentUID 等）相册用不到，跳过
            }
            entries++
            if (entries > 200_000) return null // 异常膨胀，判为脏数据
        }

        if (acc.isEmpty()) return null
        return acc.values.map { it.build() }
    }

    /** 按 DataType 读一个值；无法识别/越界返回 null（触发整体放弃）。 */
    private fun readValue(buffer: java.nio.ByteBuffer, dataType: Int): Any? {
        return try {
            when (dataType) {
                MtpDataType.INT8, MtpDataType.UINT8 ->
                    if (buffer.remaining() >= 1) buffer.get().toLong() else null
                MtpDataType.INT16, MtpDataType.UINT16 ->
                    if (buffer.remaining() >= 2) buffer.short.toLong() else null
                MtpDataType.INT32, MtpDataType.UINT32 ->
                    if (buffer.remaining() >= 4) buffer.int.toLong() else null
                MtpDataType.INT64, MtpDataType.UINT64 ->
                    if (buffer.remaining() >= 8) buffer.long else null
                MtpDataType.INT128, MtpDataType.UINT128 -> {
                    if (buffer.remaining() < 16) return null
                    repeat(16) { buffer.get() }
                    0L
                }
                MtpDataType.STRING -> readMtpString(buffer)
                else -> {
                    if (dataType and MtpDataType.ARRAY_MASK != 0) readArray(buffer, dataType and 0x0FFF)
                    else null // 未知标量类型：宁可放弃也不猜
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 数组类型：u32 元素个数 + 元素×N，整体跳过（相册不需要） */
    private fun readArray(buffer: java.nio.ByteBuffer, baseType: Int): Any? {
        if (buffer.remaining() < 4) return null
        val count = buffer.int
        if (count < 0 || count > 4096) return null
        val unit = when (baseType) {
            MtpDataType.INT8, MtpDataType.UINT8 -> 1
            MtpDataType.INT16, MtpDataType.UINT16 -> 2
            MtpDataType.INT32, MtpDataType.UINT32 -> 4
            MtpDataType.INT64, MtpDataType.UINT64 -> 8
            else -> return null
        }
        val bytes = count * unit
        if (buffer.remaining() < bytes) return null
        repeat(bytes) { buffer.get() }
        return 0L
    }

    /**
     * MTP 字符串：u8 字符数 N（含结尾 null） + N×UTF-16LE 字符。
     * 部分机身会多给一个 u16 终止符，属于规范内的差异，这里兼容丢弃。
     */
    private fun readMtpString(buffer: java.nio.ByteBuffer): String? {
        if (buffer.remaining() < 1) return null
        val count = buffer.get().toInt() and 0xFF
        if (count == 0) return ""
        if (buffer.remaining() < count * 2) return null
        val chars = CharArray(count)
        repeat(count) { chars[it] = buffer.short.toInt().toChar() }
        return chars.concatToString().trim('\u0000')
    }

    private class MutableBuilder(val handle: Int) {
        var storageId: Int? = null
        var objectFormat: Int? = null
        var objectSize: Long? = null
        var fileName: String? = null
        var dateCreated: String? = null
        var dateModified: String? = null
        var parentObject: Int? = null
        fun build() = MtpObjectProps(
            handle, storageId, objectFormat, objectSize, fileName, dateCreated, dateModified, parentObject
        )
    }

    /**
     * MTP 日期字符串 → 毫秒时间戳。
     * 支持 "YYYYMMDDThhmmss" 与 "YYYYMMDDThhmmss.s" 两种形态；解析失败返回 null，
     * 由调用方决定兜底（本项目按「时间缺失排末尾」处理）。
     */
    fun parseMtpDate(raw: String?): Long? {
        val s = raw ?: return null
        return try {
            val y = s.substring(0, 4).toInt()
            val mo = s.substring(4, 6).toInt()
            val d = s.substring(6, 8).toInt()
            if (s.length < 15) return null
            val h = s.substring(9, 11).toInt()
            val mi = s.substring(11, 13).toInt()
            val sec = s.substring(13, 15).toInt()
            val cal = java.util.Calendar.getInstance()
            cal.set(y, mo - 1, d, h, mi, sec)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
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
                PtpConstants.PACKET_TYPE_INIT_COMMAND -> InitCommandPacket.parse(payload)
                PtpConstants.PACKET_TYPE_INIT_RESPONSE -> InitResponsePacket.parse(payload)
                PtpConstants.PACKET_TYPE_INIT_EVENT_RESPONSE -> InitEventAckPacket
                PtpConstants.PACKET_TYPE_INIT_EVENT_REQUEST -> InitEventRequestPacket.parse(payload)
                PtpConstants.PACKET_TYPE_COMMAND_REQUEST -> CommandRequestPacket.parse(payload)
                PtpConstants.PACKET_TYPE_COMMAND_RESPONSE -> CommandResponsePacket.parse(payload)
                PtpConstants.PACKET_TYPE_EVENT -> EventResponsePacket.parse(payload)
                PtpConstants.PACKET_TYPE_START_DATA -> StartDataPacket.parse(payload)
                PtpConstants.PACKET_TYPE_DATA_PACKET -> DataPacket.parse(payload)
                PtpConstants.PACKET_TYPE_END_DATA -> EndDataPacket.parse(payload)
                PtpConstants.PACKET_TYPE_PING -> PingPacket
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
    val clientName: String = "N-Link"
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
        fun parse(payload: ByteArray): InitCommandPacket {
            val guid = payload.copyOfRange(0, 16)
            val nameChars = mutableListOf<Char>()
            var offset = 16
            while (offset + 1 < payload.size) {
                val charCode = (payload[offset].toInt() and 0xFF) or
                        ((payload[offset + 1].toInt() and 0xFF) shl 8)
                if (charCode == 0) break
                nameChars.add(charCode.toChar())
                offset += 2
            }
            return InitCommandPacket(
                clientGuid = guid,
                clientName = nameChars.joinToString("")
            )
        }

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

    companion object {
        fun parse(payload: ByteArray): CommandRequestPacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val dataPhase = buffer.int
            val operationCode = buffer.short.toInt() and 0xFFFF
            val transactionId = buffer.int
            val params = mutableListOf<Int>()
            while (buffer.remaining() >= 4) {
                params.add(buffer.int)
            }
            return CommandRequestPacket(
                transactionId = transactionId,
                operationCode = operationCode,
                parameters = params,
                dataPhase = dataPhase == 2
            )
        }
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

    companion object {
        fun parse(payload: ByteArray): InitEventRequestPacket {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            return InitEventRequestPacket(buffer.int)
        }
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

    override fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(PtpConstants.PTP_IP_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(PtpConstants.PTP_IP_HEADER_SIZE)
        buffer.putInt(type)
        return buffer.array()
    }
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

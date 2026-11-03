//
//  PtpConstants.swift
//  NLink
//
//  PTP / PTP-IP 常量表 · 与 Android `com/camerakit/ptp/PtpConsts.kt` 逐值对齐。
//
//  这一层是纯协议事实，不做任何业务判断。任何数值改动都必须两端同步，
//  否则 iOS 与 Android 会在同一台相机上表现不一致（最典型：包类型写错一个字节，
//  相机直接把连接复位）。
//

import Foundation

enum Ptp {

    // MARK: - 网络

    /// PTP/IP 默认端口
    static let defaultPort: Int = 15740

    /// 默认端口的别名。业务层（端点解析、探测、模型默认值）统一读这个名字，
    /// `defaultPort` 保留给协议层自己用——两者同值但语义不同，
    /// 将来若要分离「协议端口」与「业务默认端口」，只需改这一处。
    static let defaultPtpIpPort: Int = 15740

    /// PTP/IP 协议版本号（InitCommand 载荷末尾的 u32，基准实现实证 0x00010000 = v1.0）
    static let ptpIpProtocolVersion: UInt32 = 0x0001_0000
    /// 相机热点网关（CIPA DC-X005 / 官方手册互证）
    static let defaultApHost: String = "192.168.1.1"
    /// 尼康 USB 厂商标识
    static let nikonVendorId: Int = 0x04B0
    /// mDNS 标准服务类型
    static let mdnsServiceType: String = "_ptp._tcp."
    /// mDNS 厂商服务类型（部分机身只广播这个）
    static let mdnsVendorServiceType: String = "_nikon._tcp."

    /// 单包上限 32MB；超过需要走流式 sink
    static let maxPacketBytes: Int = 32 * 1024 * 1024

    // MARK: - 保活

    /// 看门狗间隔
    static let keepaliveIntervalMs: UInt64 = 20_000
    /// 超过这个时间没有任何入站数据即判链路死亡
    static let keepaliveDeadMs: UInt64 = 60_000
    /// 主机注册等待用户在相机上确认的时间
    static let hostConfirmWaitMs: UInt64 = 8_500

    // MARK: - PTP/IP 包类型

    static let ipInitCommand: UInt32 = 1
    static let ipInitCommandAck: UInt32 = 2
    static let ipInitEvent: UInt32 = 3
    static let ipInitEventAck: UInt32 = 4
    static let ipInitFail: UInt32 = 5
    static let ipCmdRequest: UInt32 = 6
    static let ipCmdResponse: UInt32 = 7
    static let ipEvent: UInt32 = 8
    static let ipStartData: UInt32 = 9
    static let ipData: UInt32 = 10
    static let ipCancel: UInt32 = 11
    static let ipEndData: UInt32 = 12
    static let ipPing: UInt32 = 13
    static let ipPong: UInt32 = 14

    // MARK: - 标准操作码

    static let opGetDeviceInfo: UInt16 = 0x1001
    static let opOpenSession: UInt16 = 0x1002
    static let opCloseSession: UInt16 = 0x1003
    static let opGetStorageIds: UInt16 = 0x1004
    static let opGetStorageInfo: UInt16 = 0x1005
    static let opGetNumObjects: UInt16 = 0x1006
    static let opGetObjectHandles: UInt16 = 0x1007
    static let opGetObjectInfo: UInt16 = 0x1008
    static let opGetObject: UInt16 = 0x1009
    static let opGetThumb: UInt16 = 0x100A
    static let opDeleteObject: UInt16 = 0x100B
    static let opInitiateCapture: UInt16 = 0x100E
    /// 开光圈快门（B 门降级链的最后一档）
    static let opInitiateOpenCapture: UInt16 = 0x100F
    /// 结束开光圈快门（B 门收门降级链的第二档）
    static let opTerminateOpenCapture: UInt16 = 0x1010
    static let opGetDevicePropDesc: UInt16 = 0x1014
    static let opGetDevicePropValue: UInt16 = 0x1015
    static let opSetDevicePropValue: UInt16 = 0x1016
    static let opGetPartialObject: UInt16 = 0x101B

    // MARK: - 尼康私有操作码
    //
    // 数值口径以 `com/nikonlink/app/device/ptp/PtpProtocol.kt` 为准
    // （该文件是 App 侧实际在用的实现，`camerakit/ptp/PtpConsts.kt` 是更早的子集）。

    /// 机身是否就绪（监看/拍摄前置门）
    static let opNikonDeviceReady: UInt16 = 0x90C8
    /// 高清缩略图（失败回退标准缩略图）
    static let opNikonGetLargeThumb: UInt16 = 0x90C4
    /// 预览图（部分机型比缩略图更快）
    static let opNikonGetPreviewImg: UInt16 = 0x9200
    static let opNikonStartLiveView: UInt16 = 0x9201
    static let opNikonEndLiveView: UInt16 = 0x9202
    static let opNikonGetLiveViewImage: UInt16 = 0x9203
    static let opNikonMfDrive: UInt16 = 0x9204
    static let opNikonChangeAfArea: UInt16 = 0x9205
    static let opNikonAfDrive: UInt16 = 0x90C1
    /// **注意**：AF 取消是 0x9206；0x90C2 是 ChangeCameraMode，两者极易写反
    static let opNikonAfDriveCancel: UInt16 = 0x9206
    static let opNikonChangeCameraMode: UInt16 = 0x90C2
    /// 无线机身优先的拍摄命令（第一参数按存储 ID 解释）
    static let opNikonInitiateCaptureRecInMedia: UInt16 = 0x9207
    /// 拍进机内内存（部分机身不支持写卡时的回退）
    static let opNikonInitiateCaptureRecInSdram: UInt16 = 0x90C0
    /// 拍进 SDRAM 并做 AF
    static let opNikonAfCaptureSdram: UInt16 = 0x90CB
    static let opNikonStartMovieRecInCard: UInt16 = 0x920A
    static let opNikonEndMovieRec: UInt16 = 0x920B
    static let opNikonTerminateCapture: UInt16 = 0x920C
    static let opNikonCheckEvent: UInt16 = 0x90C7
    static let opNikonGetPartialObjectEx: UInt16 = 0x9431
    static let opNikonGetObjectSize: UInt16 = 0x9421
    static let opNikonGetObjectsMetadata: UInt16 = 0x9434
    static let opNikonGetPartialImage: UInt16 = 0x9530
    static let opNikonHostRegPrepare: UInt16 = 0x952B
    static let opNikonHostRegConfirm: UInt16 = 0x935A
    static let opNikonChangeApplicationMode: UInt16 = 0x9435
    static let opNikonUpdateLocation: UInt16 = 0x9523

    /// HostRegConfirm 的唯一参数
    static let hostRegConfirmParam: UInt32 = 0x2001
    /// 监看/遥控模式下的曝光程序模式值（0x500E 写入值）
    static let propValueRemoteMode: UInt32 = 0x8012

    // MARK: - MTP 批量元数据（0x9805）

    static let opMtpGetObjectPropList: UInt16 = 0x9805
    static let mtpPropObjectSize: UInt16 = 0xDC04
    static let mtpPropObjectFileName: UInt16 = 0xDC07
    static let mtpPropDateCreated: UInt16 = 0xDC08
    static let mtpPropDateModified: UInt16 = 0xDC09
    static let mtpPropParentObject: UInt16 = 0xDC0B
    static let mtpPropPersistentUid: UInt16 = 0xDC41
    static let mtpPropStorageId: UInt16 = 0xDC01
    static let mtpPropObjectFormat: UInt16 = 0xDC02
    static let mtpPropProtectionStatus: UInt16 = 0xDC03
    static let mtpPropAssociationType: UInt16 = 0xDC05
    /// 请求全部属性
    static let mtpAllProps: Int32 = -1

    // MARK: - 响应码

    static let rcOk: UInt16 = 0x2001
    static let rcGeneralError: UInt16 = 0x2002
    static let rcSessionNotOpen: UInt16 = 0x2003
    static let rcInvalidTransactionId: UInt16 = 0x2004
    static let rcOperationNotSupported: UInt16 = 0x2005
    static let rcParameterNotSupported: UInt16 = 0x2006
    static let rcIncompleteTransfer: UInt16 = 0x2007
    static let rcInvalidStorageId: UInt16 = 0x2008
    static let rcInvalidObjectHandle: UInt16 = 0x2009
    static let rcDevicePropNotSupported: UInt16 = 0x200A
    static let rcInvalidObjectFormat: UInt16 = 0x200B
    static let rcStoreFull: UInt16 = 0x200C
    static let rcObjectWriteProtected: UInt16 = 0x200D
    static let rcStoreReadOnly: UInt16 = 0x200E
    static let rcAccessDenied: UInt16 = 0x200F
    static let rcNoThumbnail: UInt16 = 0x2010
    static let rcDeviceBusy: UInt16 = 0x2019
    static let rcInvalidParentObject: UInt16 = 0x201A
    static let rcInvalidParameter: UInt16 = 0x201D
    static let rcSessionAlreadyOpen: UInt16 = 0x201E
    static let rcTransactionCancelled: UInt16 = 0x201F
    static let rcStoreNotAvailable: UInt16 = 0x2013
    static let rcCaptureAlreadyTerminated: UInt16 = 0x2011
    /// 尼康：相机当前状态不允许该操作（拨盘档位/回放中）
    static let rcNikonInvalidStatus: UInt16 = 0xA004
    /// 尼康：未处于实时取景状态
    static let rcNikonNotLiveView: UInt16 = 0xA00B
    /// 尼康：B 门曝光正在收尾
    static let rcNikonBulbBusy: UInt16 = 0xA200
    /// 尼康：厂商未定义码，本项目仅见其回绝 DeviceReady 保活
    static let rcNikonUndefinedA002: UInt16 = 0xA002

    // MARK: - PTP 事件码

    static let evObjectAdded: UInt16 = 0x4002
    static let evDevicePropChanged: UInt16 = 0x4006
    static let evCaptureComplete: UInt16 = 0x400D
    /// 尼康：拍到内存（相册逐张实时刷新的核心信号）
    static let evNikonObjectAddedInSdram: UInt16 = 0xC101
    /// 尼康：按内存内拍摄完成
    static let evNikonCaptureCompleteRecInSdram: UInt16 = 0xC102
    /// 尼康事件 Param1 的「无句柄」哨兵（NEF+RAW 双拍时出现），此时必须退回全量同步
    static let nikonEventHandleNone: UInt32 = 0xFFFF0001

    // MARK: - 设备属性码

    static let propBatteryLevel: UInt16 = 0x5001
    static let propImageSize: UInt16 = 0x5003
    static let propCompressionSetting: UInt16 = 0x5004
    static let propWhiteBalance: UInt16 = 0x5005
    static let propRgbGain: UInt16 = 0x5006
    static let propFNumber: UInt16 = 0x5007
    static let propFocalLength: UInt16 = 0x5008
    static let propFocusDistance: UInt16 = 0x5009
    static let propFocusMode: UInt16 = 0x500A
    static let propExposureMeteringMode: UInt16 = 0x500B
    static let propFlashMode: UInt16 = 0x500C
    static let propExposureTime: UInt16 = 0x500D
    static let propExposureProgramMode: UInt16 = 0x500E
    /// ISO
    static let propExposureIndex: UInt16 = 0x500F
    static let propExposureBiasCompensation: UInt16 = 0x5010
    static let propDateTime: UInt16 = 0x5011
    static let propCaptureDelay: UInt16 = 0x5012
    static let propStillCaptureMode: UInt16 = 0x5013
    static let propFocusMeteringMode: UInt16 = 0x501C
    /// 尼康私有：快门速度。**高 16 位 = 分子，低 16 位 = 分母**的打包分数
    /// （1/125 → 0x0001_007D），与标准 0x500D 的 1/10000s 定点数布局不同。
    /// 部分 Z 系机身遥控模式下只认这个属性。
    static let propNikonShutterSpeed: UInt16 = 0xD100
    static let nikonShutterSpeedInvalid: UInt32 = 0xFFFFFFFF
    /// 尼康：LiveView 图像配置
    static let propNikonLvImageProfile: UInt16 = 0xD1AC
    /// 尼康：LiveView 禁止条件位图（只读，非 0 时不阻断流程，只用于错误提示富化）
    static let propNikonLvProhibitCondition: UInt16 = 0xD1A4
    /// 尼康镜头属性
    static let propNikonLensId: UInt16 = 0xD0E0
    static let propNikonLensSort: UInt16 = 0xD0E1
    static let propNikonLensType: UInt16 = 0xD0E2
    static let propNikonFocalLengthMin: UInt16 = 0xD0E3
    static let propNikonFocalLengthMax: UInt16 = 0xD0E4
    static let propNikonMaxApAtMin: UInt16 = 0xD0E5
    static let propNikonMaxApAtMax: UInt16 = 0xD0E6

    // MARK: - AF 坐标域

    /// ChangeAfArea(0x9205) 的目标点取值范围就是监看帧 wholeSize 的像素空间，
    /// 与帧同宽高比。厂商官方应用按单系数线性映射，箱宽取流量实测上限 4000。
    /// 数值本身是相机域，勿改。
    static let afCoordMaxX: Int = 4000
    static let afCoordMaxY: Int = 3000

    /// 响应码 → 可读名称（日志与错误详情用）。
    static func responseName(_ code: UInt16) -> String {
        switch code {
        case rcOk: return "OK"
        case rcGeneralError: return "GeneralError"
        case rcSessionNotOpen: return "SessionNotOpen"
        case rcInvalidTransactionId: return "InvalidTransactionId"
        case rcOperationNotSupported: return "OperationNotSupported"
        case rcParameterNotSupported: return "ParameterNotSupported"
        case rcIncompleteTransfer: return "IncompleteTransfer"
        case rcInvalidStorageId: return "InvalidStorageId"
        case rcInvalidObjectHandle: return "InvalidObjectHandle"
        case rcDevicePropNotSupported: return "DevicePropNotSupported"
        case rcInvalidObjectFormat: return "InvalidObjectFormat"
        case rcStoreFull: return "StoreFull"
        case rcObjectWriteProtected: return "ObjectWriteProtected"
        case rcStoreReadOnly: return "StoreReadOnly"
        case rcAccessDenied: return "AccessDenied"
        case rcNoThumbnail: return "NoThumbnail"
        case rcDeviceBusy: return "DeviceBusy"
        case rcInvalidParameter: return "InvalidParameter"
        case rcSessionAlreadyOpen: return "SessionAlreadyOpen"
        case rcTransactionCancelled: return "TransactionCancelled"
        default: return String(format: "0x%04X", code)
        }
    }

    // MARK: - 对象格式

    static let formatAssociation: UInt16 = 0x3001
    static let formatExifJpeg: UInt16 = 0x3801
    static let formatTiff: UInt16 = 0x3801
    static let formatJpeg: UInt16 = 0x3801
    static let formatNef: UInt16 = 0x3811
    static let formatMov: UInt16 = 0x300D
    static let formatMp4: UInt16 = 0x300C
    static let formatHeif: UInt16 = 0x3809

    /// 该格式是否属于「必须成对下载」的 RAW（与同基名 JPG 配对）
    static func isRawFormat(_ format: UInt16) -> Bool {
        // 尼康 NEF 0x3811；以及部分机身的 NRW 0x3813
        format == 0x3811 || format == 0x3813
    }

    static func isVideoFormat(_ format: UInt16) -> Bool {
        format == formatMov || format == formatMp4 || format == 0x3009 /* MPEG */ ||
        format == 0x300B /* AVCHD */ || format == 0x380D /* DNG 视作图片 */ && false
    }
}

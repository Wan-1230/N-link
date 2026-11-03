//
//  PtpModels.swift
//  NLink
//
//  领域模型 / 错误分类 / 链路接口。对应安卓端 `com.camerakit.api/**`：
//  CameraEndpoint、DeviceInfo、StorageInfo、ObjectInfo、ConnectionMethod、
//  HostIdentity、CameraState、CameraEvent、CameraError、PtpResult、PtpCameraEvent。
//
//  iOS 端保留同一套状态与事件语义，因为 UI 文案与降级策略都是按这些状态
//  逐条写出来的（断连 ≠ 为空、每种失败都要给下一步）。改枚举就等于改文案，
//  所以这里刻意与安卓一一对应，不做「Swift 化」重命名。
//

import Foundation

// MARK: - 端点

/// 一台相机的可达地址。
///
/// `service` 记录「怎么发现的」（`mdns` / `ap` / `usb` / `manual`），
/// 只用于日志与诊断文案——它不参与连接决策。
struct CameraEndpoint: Hashable, Sendable, Identifiable {
    let displayName: String
    let host: String
    let port: Int
    let service: String

    var id: String { "\(host):\(port)" }

    /// 给人看的地址：默认端口省略不写。
    ///
    /// `15740` 是这个协议唯一会用的端口，写出来只会在「相机详情」这种窄栏里
    /// 把 `192.168.100.100:15740` 挤成两行。非默认端口（用户手填的代理、
    /// 端口转发）才必须显示——那时候它是有信息量的。
    var display: String {
        port == Ptp.defaultPtpIpPort ? host : "\(host):\(port)"
    }

    static func ap(host: String = Ptp.defaultApHost, port: Int = Ptp.defaultPtpIpPort) -> CameraEndpoint {
        CameraEndpoint(displayName: "Nikon Z Camera (AP)", host: host, port: port, service: "ap")
    }
}

// MARK: - 相机信息

struct DeviceInfo: Sendable, Equatable {
    let standardVersion: Int
    let vendorExtensionId: Int
    let operationsSupported: [UInt16]
    let eventsSupported: [UInt16]
    let devicePropertiesSupported: [UInt16]
    let manufacturer: String
    let model: String
    let deviceVersion: String
    let serialNumber: String

    func supportsOperation(_ opcode: UInt16) -> Bool { operationsSupported.contains(opcode) }
    func supportsProperty(_ code: UInt16) -> Bool { devicePropertiesSupported.contains(code) }

    /// 顶栏/设备卡展示用的机型名；机身没给时就退回厂商名，不留空串。
    var displayModel: String {
        if !model.isEmpty { return model }
        if !manufacturer.isEmpty { return manufacturer }
        return "Nikon 相机"
    }
}

struct StorageInfo: Sendable, Equatable {
    let storageId: UInt32
    let storageType: UInt16
    let filesystemType: UInt16
    let accessCapability: UInt16
    let maxCapacity: UInt64
    let freeSpace: UInt64
    let description: String
    let volumeLabel: String

    var freeRatio: Double {
        guard maxCapacity > 0 else { return 0 }
        return min(1, max(0, Double(freeSpace) / Double(maxCapacity)))
    }

    /// 「可用 18.2 GB / 共 64 GB」——存储卡文案在两处复用，集中生成避免口径漂移。
    var capacityText: String {
        let free = ByteCountFormatter.string(fromByteCount: Int64(freeSpace), countStyle: .file)
        let total = ByteCountFormatter.string(fromByteCount: Int64(maxCapacity), countStyle: .file)
        return "可用 \(free) / 共 \(total)"
    }
}

struct ObjectInfo: Sendable, Equatable {
    let handle: UInt32
    let storageId: UInt32
    let objectFormat: UInt16
    let compressedSize: UInt32
    let parent: UInt32
    let filename: String
    let captureDate: String
    let modificationDate: String
    let imageWidth: UInt32
    let imageHeight: UInt32
    /// 机内保护位。**`nil` = 机身没返回这一列**，与「返回 0 = 未保护」是两件事。
    ///
    /// `0x1008 GetObjectInfo` 的结构里必有此字段；批量 `0x9805 GetObjectPropList`
    /// 返回哪些列随机型而定，缺列时必须留 nil，否则「只传已保护的」会静默筛出空列表。
    let protectionStatus: UInt16?

    var isRaw: Bool { Ptp.isRawFormat(objectFormat) }
    var isVideo: Bool { Ptp.isVideoFormat(objectFormat) }
}

/// `GetObjectPropList`(0x9805) 的一行。所有字段皆可选——机身只返回它愿意给的列。
struct MtpObjectProps: Sendable, Equatable {
    let handle: UInt32
    let storageId: UInt32?
    let objectFormat: UInt16?
    let objectSize: UInt32?
    let fileName: String?
    let dateCreatedRaw: String?
    let dateModifiedRaw: String?
    let parentObject: UInt32?
    /// `nil` = 机身没返回保护位这一列（≠ 未保护）
    let protectionStatus: UInt16?
}

// MARK: - 连接方式

enum ConnectionMethod: Sendable, Equatable, Hashable {
    /// 有线：iOS 走 USB-C 相机直连不可用（见文档「差异处理」），保留枚举以对齐状态与文案。
    case usb
    /// 相机自身开热点，手机加入该 Wi-Fi，直连 `192.168.1.1:15740`。
    case wifiAp(host: String, port: Int)
    /// 手机与相机在同一局域网（家用/影棚 Wi-Fi），靠 mDNS 或手填地址发现。
    case wifiSta(endpoint: CameraEndpoint?)

    var isWireless: Bool {
        if case .usb = self { return false }
        return true
    }

    var isAp: Bool {
        if case .wifiAp = self { return true }
        return false
    }
}

// MARK: - 主机身份

/// 相机记住的「这台手机」。STA 模式下必须持久且稳定——
/// 安卓端实证：换 GUID 会让相机把它当成新主机，已有的主机注册全部失效。
struct HostIdentity: Sendable, Equatable {
    let guid: Data          // 16 字节
    var hostName: String

    static let defaultHostName = "N-Link-iOS"
}

// MARK: - 连接状态

/// 连接状态。与安卓 `CameraState` 一一对应。
///
/// 注意 `recovering` 是独立状态而不是 `connecting` 的变体：断线自愈时界面
/// 要显示「正在恢复连接」并保留上一帧画面，跟首次连接（还没有画面）完全不同。
enum CameraState: Sendable, Equatable {
    case disconnected
    case discovering
    case connecting(phase: String)
    /// 相机要求在其机身上确认主机注册（机身屏幕会显示一串字符）
    case awaitingHostConfirm(prepare: String)
    case connected(endpoint: CameraEndpoint, deviceInfo: DeviceInfo)
    case recovering(reason: String)
    case failed(CameraError)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }

    var isBusy: Bool {
        switch self {
        case .discovering, .connecting, .awaitingHostConfirm, .recovering: return true
        default: return false
        }
    }

    var deviceInfo: DeviceInfo? {
        if case let .connected(_, info) = self { return info }
        return nil
    }

    var endpoint: CameraEndpoint? {
        if case let .connected(endpoint, _) = self { return endpoint }
        return nil
    }
}

// MARK: - 连接事件

/// 事件级信号。与状态级信号分离——「连不上相机」经常在状态还没推进时就失败，
/// 若只监听状态，界面会停在旧文案，用户读作「点了连接没有任何反应」。
enum CameraEvent: Sendable, Equatable {
    case keepaliveFailed(detail: String)
    case transportLost(detail: String)
    case sessionRecovered(detail: String)
    case sessionTokenChanged(token: Int)
    case wirelessDisconnected(reason: String)
    case pairingProgress(text: String)
}

// MARK: - 错误

/// 全部相机侧错误。
///
/// 每个 case 都自带 `message`：中文文案是产品的一部分（「原因 + 怎么办」），
/// 不让调用方拼字符串，避免同一失败在两处写出两种说法。
enum CameraError: Error, Equatable, Sendable {
    case noCameraFound
    case usbPermissionDenied
    case cameraRejected(code: Int32)
    case pairingRejected(detail: String)
    case transportLost(detail: String, causeDescription: String?)
    case timeout(detail: String)
    case ptp(code: UInt16, detail: String)
    case protocolViolation(detail: String, causeDescription: String?)
    case storageUnavailable(detail: String)
    case notConnected(detail: String)

    static func transportLost(_ detail: String, _ cause: Error?) -> CameraError {
        .transportLost(detail: detail, causeDescription: cause.map { String(describing: $0) })
    }

    static func timeout(_ detail: String) -> CameraError { .timeout(detail: detail) }
    static func notConnected(_ detail: String) -> CameraError { .notConnected(detail: detail) }

    var message: String {
        switch self {
        case .noCameraFound:
            return "没有找到相机。请确认相机已开机、Wi-Fi 已连到相机热点，或改用「手动输入地址」。"
        case .usbPermissionDenied:
            return "USB 访问被系统拒绝。请在系统设置里重新授权后再试。"
        case let .cameraRejected(code):
            return "相机拒绝了这个连接（InitFail 0x\(String(format: "%08X", code))）。相机通常需约 10 秒释放上一条残留连接，请等待后再试。"
        case let .pairingRejected(detail):
            return detail.isEmpty ? "相机没有确认主机注册。请在相机机身屏幕上点「确定」后重试。" : detail
        case let .transportLost(detail, _):
            return "与相机的连接中断了：\(detail)。请确认相机仍在开机且 Wi-Fi 未断开。"
        case let .timeout(detail):
            return "相机没有在预期时间内响应：\(detail)。相机可能正忙（写卡/结束曝光），请稍后重试。"
        case let .ptp(code, detail):
            return detail.isEmpty ? Ptp.responseName(code) : detail
        case let .protocolViolation(detail, _):
            return "与相机的通信出现协议错误：\(detail)。请断开后重新连接。"
        case let .storageUnavailable(detail):
            return detail.isEmpty ? "相机存储卡不可用。请检查卡是否插好、是否已满或写保护。" : detail
        case let .notConnected(detail):
            return detail.isEmpty ? "尚未连接相机。" : detail
        }
    }

    /// 是否值得让用户「重试一次」。用于决定错误页给不给重试按钮。
    var isRetryable: Bool {
        switch self {
        case .noCameraFound, .timeout, .transportLost, .protocolViolation: return true
        case .usbPermissionDenied, .storageUnavailable: return false
        case .cameraRejected: return true      // 冷却 10s 后可重试
        case .pairingRejected: return true
        case .ptp(let code, _):
            // 相机忙 / 状态不允许 → 可重试；参数不支持 / 访问被拒 → 重试也没用
            return code == Ptp.rcDeviceBusy
                || code == Ptp.rcNikonInvalidStatus
                || code == Ptp.rcNikonBulbBusy
        case .notConnected: return true
        }
    }

    /// 是否是「链路断了」这一类。传输队列据此决定要不要等会话恢复后重试。
    ///
    /// 与 `isRetryable` 不同：超时**不算**链路断——相机忙的时候重连只会更慢。
    var isTransportLoss: Bool {
        if case .transportLost = self { return true }
        return false
    }
}

extension CameraError {
    /// 空 detail 的常用构造，避免各处写 `timeout(detail: "")` 又各自发明文案。
    static var bareTimeout: CameraError { .timeout(detail: "") }
}

// MARK: - PTP 结果

struct PtpResult: Sendable {
    let code: UInt16
    let params: [UInt32]
    let dataIn: Data?

    var isOk: Bool { code == Ptp.rcOk }

    func requireOk(opcode: UInt16) throws -> Data? {
        guard isOk else { throw CameraError.ptp(code: code, detail: Ptp.responseName(code)) }
        return dataIn
    }
}

struct PtpCameraEvent: Sendable, Equatable {
    let code: UInt16
    let params: [UInt32]
}

// MARK: - 链路与会话协议

/// 一条 PTP 传输（PTP/IP over TCP，或有线 PTP）。
///
/// iOS 端只实现 PTP/IP——`usb` 分支留给未来 USB-C Host 模式，当前直接以
/// `.usbPermissionDenied` 失败（见文档「差异处理」一节）。
protocol PtpLink: AnyObject, Sendable {
    var events: AsyncStream<PtpCameraEvent> { get }
    var isOpen: Bool { get }

    func open() async throws
    func transact(
        opcode: UInt16,
        params: [UInt32],
        dataOut: Data?,
        timeoutMs: TimeInterval,
        sink: (@Sendable (Data) -> Void)?
    ) async throws -> PtpResult
    func sendKeepalive() async
    func lastReceiveAt() -> TimeInterval
    /// 收尾。`async` 是刻意的：实现方是 actor，同步签名无法被 actor 独立方法满足。
    func close(graceful: Bool) async
}

extension PtpLink {
    func transact(
        opcode: UInt16,
        params: [UInt32] = [],
        dataOut: Data? = nil,
        timeoutMs: TimeInterval = 20
    ) async throws -> PtpResult {
        try await transact(opcode: opcode, params: params, dataOut: dataOut, timeoutMs: timeoutMs, sink: nil)
    }
}

/// 已建立的 PTP 会话（OpenSession 之后）。
protocol CameraSession: AnyObject, Sendable {
    func getDeviceInfo() async throws -> DeviceInfo
    func listStorages() async throws -> [StorageInfo]
    func listObjectHandles(storageId: UInt32, parent: UInt32) async throws -> [UInt32]
    func getObjectInfo(handle: UInt32) async throws -> ObjectInfo
    func readRange(handle: UInt32, offset: UInt64, length: UInt32) async throws -> Data
    func readObject(handle: UInt32, expectedSize: UInt64, sink: @escaping @Sendable (Data) -> Void) async throws
    func getThumbnail(handle: UInt32) async throws -> Data
    func deleteObject(handle: UInt32) async throws
}

// MARK: - 小工具

/// 单调时钟（秒）。用于超时判定——`Date` 会被系统时间调整影响，
/// 连接超时用墙钟会出现「改了时区连接立刻超时」。
enum Clock {
    private static let origin = DispatchTime.now().uptimeNanoseconds

    static func now() -> TimeInterval {
        Double(DispatchTime.now().uptimeNanoseconds - origin) / 1_000_000_000
    }

    static func sleep(seconds: TimeInterval) async throws {
        let ns = UInt64(max(0, seconds) * 1_000_000_000)
        try await Task.sleep(nanoseconds: ns)
    }

    static func sleep(ms: Int) async throws {
        guard ms > 0 else { return }
        try await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
    }
}

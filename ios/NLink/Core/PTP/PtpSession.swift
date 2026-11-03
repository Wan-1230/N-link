//
//  PtpSession.swift
//  NLink
//
//  PTP 会话层（对应安卓 `com/camerakit/ptp/PtpSession.kt`）：在任意 PtpLink 之上
//  提供统一相机操作。无线三条通道（AP / STA）共用这一层。
//
//  会话序（逆向实现 v1.0.257 诊断日志实证）：OpenSession(0x1002, 随机会话号) → GetDeviceInfo。
//  分段读优先标准 GetPartialObject(0x101B)；设备能力含 0x9431 时按需切尼康扩展路径。
//

import Foundation

final class PtpSession: CameraSession, @unchecked Sendable {

    private enum RangeStrategy {
        case unknown, standard, extended
    }

    private static let readChunk = 256 * 1024

    private let link: any PtpLink
    private let sessionId: UInt32
    private let log: @Sendable (String) -> Void

    private(set) var deviceInfo: DeviceInfo?
    private var opened = false
    private var rangeStrategy: RangeStrategy = .unknown
    private let lock = NSLock()

    init(link: any PtpLink, sessionId: UInt32? = nil, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.link = link
        // 随机会话号（逆向实现实证：如 804956382），避免与相机残留会话冲突
        self.sessionId = sessionId ?? UInt32.random(in: 1...0x7FFF_FFFF)
        self.log = log
    }

    // MARK: - 生命周期

    func open() async throws {
        // 顺序不能反：先 OpenSession（随机号）再读 DeviceInfo
        let r = try await link.transact(opcode: Ptp.opOpenSession, params: [sessionId])
        // SESSION_ALREADY_OPEN 视为成功——相机侧还挂着上一条会话，接着用即可
        guard r.isOk || r.code == Ptp.rcSessionAlreadyOpen else {
            throw CameraError.ptp(code: r.code, detail: Ptp.responseName(r.code))
        }
        opened = true
        let info = try await getDeviceInfo()
        log("DeviceInfo: \(info.manufacturer) \(info.model) \(info.deviceVersion) ops=\(info.operationsSupported.count)")
    }

    /// 关闭会话链路。传输层关闭即隐含 PTP 会话关闭（与逆向实现
    /// 「transport close requested sendCloseSession」的跳过语义一致）。
    func close() async {
        opened = false
        await link.close(graceful: true)
    }

    var isOpen: Bool { opened }

    // MARK: - CameraSession

    func getDeviceInfo() async throws -> DeviceInfo {
        let r = try await checked(Ptp.opGetDeviceInfo)
        guard let data = r.dataIn else {
            throw CameraError.protocolViolation(detail: "GetDeviceInfo 返回空数据", causeDescription: nil)
        }
        let info = try Self.parseDeviceInfo(data)
        deviceInfo = info
        return info
    }

    func listStorages() async throws -> [StorageInfo] {
        let r = try await checked(Ptp.opGetStorageIds)
        var reader = PtpDatasetReader(r.dataIn ?? Data())
        let ids = try reader.u32Array()
        var out: [StorageInfo] = []
        out.reserveCapacity(ids.count)
        for id in ids {
            let data = try await checked(Ptp.opGetStorageInfo, params: [id])
            out.append(try Self.parseStorageInfo(storageId: id, data: data.dataIn ?? Data()))
        }
        return out
    }

    func listObjectHandles(storageId: UInt32, parent: UInt32 = 0) async throws -> [UInt32] {
        // 三参数顺序不能省：storageId、0、parent
        let r = try await checked(Ptp.opGetObjectHandles, params: [storageId, 0, parent])
        var reader = PtpDatasetReader(r.dataIn ?? Data())
        return try reader.u32Array()
    }

    func getObjectInfo(handle: UInt32) async throws -> ObjectInfo {
        let r = try await checked(Ptp.opGetObjectInfo, params: [handle])
        return try Self.parseObjectInfo(handle: handle, data: r.dataIn ?? Data())
    }

    /// 分段读：标准 0x101B 优先；空载荷或报错且设备支持 0x9431 时切尼康扩展路径。
    func readRange(handle: UInt32, offset: UInt64, length: UInt32) async throws -> Data {
        let supportsEx = deviceInfo?.supportsOperation(Ptp.opNikonGetPartialObjectEx) ?? false
        let offset32 = UInt32(truncatingIfNeeded: offset)

        func standard() async throws -> Data {
            let r = try await checked(
                Ptp.opGetPartialObject,
                params: [handle, offset32, length],
                timeoutMs: 60
            )
            return r.dataIn ?? Data()
        }

        func extended() async throws -> Data {
            let r = try await checked(
                Ptp.opNikonGetPartialObjectEx,
                params: [handle, offset32, length],
                timeoutMs: 60
            )
            return r.dataIn ?? Data()
        }

        // 用 `withLock` 而不是手动 lock/unlock：在 async 上下文里手动配对的
        // lock / unlock 会触发「instance method 'lock' is unavailable from
        // asynchronous contexts」（Swift 6 里直接是错误）。`withLock` 是同步
        // 作用域闭包，从 async 函数调用安全，且异常路径也保证解锁。
        let strategy = lock.withLock { rangeStrategy }

        switch strategy {
        case .extended:
            return try await extended()
        case .standard:
            return try await standard()
        case .unknown:
            break
        }

        do {
            let data = try await standard()
            if data.isEmpty, supportsEx {
                let alt = try await extended()
                if alt.isEmpty {
                    setStrategy(.standard)
                    return data
                }
                setStrategy(.extended)
                log("分段读策略确认=0x9431（0x101B 返回空载荷）")
                return alt
            }
            setStrategy(.standard)
            return data
        } catch let err as CameraError {
            guard case let .ptp(code, _) = err, supportsEx else { throw err }
            setStrategy(.extended)
            log("分段读策略确认=0x9431（0x101B 失败：\(Ptp.responseName(code))）")
            return try await extended()
        }
    }

    /// 流式读取对象（内部分段）。`expectedSize == 0` 时读到空段/短段为止。
    func readObject(handle: UInt32, expectedSize: UInt64, sink: @escaping @Sendable (Data) -> Void) async throws {
        var offset: UInt64 = 0
        while expectedSize == 0 || offset < expectedSize {
            let want: Int
            if expectedSize > 0 {
                want = Int(min(UInt64(Self.readChunk), expectedSize - offset))
            } else {
                want = Self.readChunk
            }
            guard want > 0 else { break }
            let data = try await readRange(handle: handle, offset: offset, length: UInt32(want))
            if data.isEmpty { break }
            sink(data)
            offset &+= UInt64(data.count)
            if data.count < want { break }
        }
    }

    func getThumbnail(handle: UInt32) async throws -> Data {
        let r = try await checked(Ptp.opGetThumb, params: [handle])
        return r.dataIn ?? Data()
    }

    func deleteObject(handle: UInt32) async throws {
        _ = try await checked(Ptp.opDeleteObject, params: [handle, 0])
    }

    // MARK: - App 扩展（宿主功能补充，非连接核心）

    /// 通用事务：响应码非 OK **不抛**（由调用方判读）；仅传输层断开仍抛。
    /// 供参数读写、遥控、监看等封装命令使用。
    func rawTransact(
        opcode: UInt16,
        params: [UInt32] = [],
        dataOut: Data? = nil,
        timeoutMs: TimeInterval = 20
    ) async throws -> PtpResult {
        try await link.transact(opcode: opcode, params: params, dataOut: dataOut, timeoutMs: timeoutMs, sink: nil)
    }

    func getDevicePropValue(_ code: UInt16, timeoutMs: TimeInterval = 20) async throws -> PtpResult {
        try await rawTransact(opcode: Ptp.opGetDevicePropValue, params: [UInt32(code)], timeoutMs: timeoutMs)
    }

    func setDevicePropValue(_ code: UInt16, value: Data, timeoutMs: TimeInterval = 20) async throws -> PtpResult {
        try await rawTransact(
            opcode: Ptp.opSetDevicePropValue,
            params: [UInt32(code)],
            dataOut: value,
            timeoutMs: timeoutMs
        )
    }

    // MARK: - 配对（PC 主机注册，逆向实现实证）

    /// 0x952B 预备：data-in 返回相机侧展示的字符串（载荷前 8 字节为头部）。
    func hostRegisterPrepare(timeoutMs: TimeInterval = 30) async throws -> String {
        let r = try await checked(Ptp.opNikonHostRegPrepare, timeoutMs: timeoutMs)
        return Self.decodeHostRegPrepare(r.dataIn)
    }

    /// 0x935A 确认（参数 0x2001）。
    func hostRegisterConfirm(timeoutMs: TimeInterval = 30) async throws {
        _ = try await checked(
            Ptp.opNikonHostRegConfirm,
            params: [Ptp.hostRegConfirmParam],
            timeoutMs: timeoutMs
        )
    }

    // MARK: - 内部

    private func setStrategy(_ s: RangeStrategy) {
        // 与 readRange 的读侧保持同一把锁、同一种写法（withLock）；
        // 混用「手动配对」与「作用域闭包」会让后续维护者分不清谁负责解锁。
        lock.withLock { rangeStrategy = s }
    }

    private func checked(
        _ opcode: UInt16,
        params: [UInt32] = [],
        timeoutMs: TimeInterval = 20
    ) async throws -> PtpResult {
        let r = try await link.transact(opcode: opcode, params: params, dataOut: nil, timeoutMs: timeoutMs, sink: nil)
        guard r.isOk else {
            log("PTP 0x\(String(opcode, radix: 16)) 失败：\(Ptp.responseName(r.code))")
            throw CameraError.ptp(code: r.code, detail: Ptp.responseName(r.code))
        }
        return r
    }

    // MARK: - 数据集解析

    static func decodeHostRegPrepare(_ data: Data?) -> String {
        guard let data, data.count > 8 else { return "空" }
        let body = data.subdata(in: 8..<data.count)
        let utf16 = body.decodeUtf16Le(from: 0)
        // 可打印字符占比 <70% 说明这不是给人看的字符串（多半是二进制令牌），回落 hex
        let printable = utf16.unicodeScalars.filter { (0x20...0x7E).contains($0.value) || $0.value > 0xA0 }.count
        if !utf16.isEmpty, Double(printable) >= Double(utf16.count) * 0.7 {
            return utf16
        }
        let hex = body.prefix(32).map { String(format: "%02x", $0) }.joined()
        return hex.isEmpty ? "空" : hex
    }

    static func parseDeviceInfo(_ data: Data) throws -> DeviceInfo {
        var r = PtpDatasetReader(data)
        let standardVersion = Int(try r.u16())
        let vendorExtensionId = Int(try r.u32())
        _ = try r.u16()          // vendorExtensionVersion
        _ = try r.string()       // vendorExtensionDesc
        _ = try r.u16()          // functionalMode
        let ops = try r.u16Array()
        let events = try r.u16Array()
        let props = try r.u16Array()
        _ = try r.u16Array()     // captureFormats
        _ = try r.u16Array()     // imageFormats
        let manufacturer = try r.string()
        let model = try r.string()
        let deviceVersion = try r.string()
        // 部分机身不带序列号字段：剩余为 0 时就当空串，不视为失败
        let serial = r.remaining > 0 ? try r.string() : ""
        return DeviceInfo(
            standardVersion: standardVersion,
            vendorExtensionId: vendorExtensionId,
            operationsSupported: ops,
            eventsSupported: events,
            devicePropertiesSupported: props,
            manufacturer: manufacturer,
            model: model,
            deviceVersion: deviceVersion,
            serialNumber: serial
        )
    }

    static func parseStorageInfo(storageId: UInt32, data: Data) throws -> StorageInfo {
        var r = PtpDatasetReader(data)
        let storageType = try r.u16()
        let filesystemType = try r.u16()
        let accessCapability = try r.u16()
        let maxCapacity = try r.u64()
        let freeSpace = try r.u64()
        _ = try r.u32()          // description 是「u32 长度 + 字符串」，长度字段单独跳
        let description = try r.string()
        let volumeLabel = try r.string()
        return StorageInfo(
            storageId: storageId,
            storageType: storageType,
            filesystemType: filesystemType,
            accessCapability: accessCapability,
            maxCapacity: maxCapacity,
            freeSpace: freeSpace,
            description: description,
            volumeLabel: volumeLabel
        )
    }

    static func parseObjectInfo(handle: UInt32, data: Data) throws -> ObjectInfo {
        var r = PtpDatasetReader(data)
        let storageId = try r.u32()
        let objectFormat = try r.u16()
        let protectionStatus = try r.u16()
        let compressedSize = try r.u32()
        _ = try r.u16()          // thumbFormat
        _ = try r.u32()          // thumbCompressedSize
        _ = try r.u32()          // thumbPixWidth
        _ = try r.u32()          // thumbPixHeight
        let imageWidth = try r.u32()
        let imageHeight = try r.u32()
        _ = try r.u32()          // imageBitDepth
        let parent = try r.u32()
        _ = try r.u16()          // associationType
        _ = try r.u32()          // associationDesc
        _ = try r.u32()          // sequenceNumber
        let filename = try r.string()
        let captureDate = try r.string()
        let modifiedDate = try r.string()
        return ObjectInfo(
            handle: handle,
            storageId: storageId,
            objectFormat: objectFormat,
            compressedSize: compressedSize,
            parent: parent,
            filename: filename,
            captureDate: captureDate,
            modificationDate: modifiedDate,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            // 单条 0x1008 结构里保护位必有，所以这里是确定值而不是 nil
            protectionStatus: protectionStatus
        )
    }

    // MARK: - 批量属性（GetObjectPropList 0x9805）

    /// 批量读对象属性。
    ///
    /// 请求载荷：`storageId u32 = 0xFFFFFFFF`（全部 storage）+ `objectFormat u32 = 0`（全部格式）
    /// + `objectHandle u32`（单个句柄或 0xFFFFFFFF）+ `propCode u32 = 0xFFFFFFFF`（全部属性）。
    ///
    /// 返回载荷：`count u32`，随后每行 = `handle u32` + `propCount u32` + propCount 组
    /// `propCode u16 / dataType u16 / value`（value 的宽度由 dataType 决定）。
    /// 任何一行解析失败都跳过该行而不是整批失败 —— 机身常在保护位这类边缘列上返回怪值。
    func getObjectPropList(handles: [UInt32], propCodes: [UInt32] = [0xFFFF_FFFF]) async throws -> [MtpObjectProps] {
        var out: [MtpObjectProps] = []
        // 分批：一次几千个句柄会让请求载荷超过 32MB 上限，也超出机身的解析缓冲
        let batch = 200
        for start in stride(from: 0, to: handles.count, by: batch) {
            let slice = Array(handles[start..<min(start + batch, handles.count)])
            for handle in slice {
                do {
                    let props = try await objectProps(handle: handle, propCodes: propCodes)
                    if let props { out.append(props) }
                } catch {
                    log("0x9805 handle=0x\(String(handle, radix: 16)) 失败：\(error)")
                }
            }
        }
        return out
    }

    private func objectProps(handle: UInt32, propCodes: [UInt32]) async throws -> MtpObjectProps? {
        var payload = Data()
        payload.appendLE(UInt32(0xFFFF_FFFF))   // storageId：全部
        payload.appendLE(UInt32(0))             // objectFormat：全部
        payload.appendLE(handle)
        payload.appendLE(UInt32(propCodes.first ?? 0xFFFF_FFFF))

        let r = try await link.transact(
            opcode: Ptp.opMtpGetObjectPropList,
            params: [],
            dataOut: payload,
            timeoutMs: 15,
            sink: nil
        )
        guard r.isOk, let data = r.dataIn else { return nil }

        var reader = PtpDatasetReader(data)
        guard let count = try? reader.u32(), count > 0 else { return nil }

        var storageId: UInt32?
        var objectFormat: UInt16?
        var objectSize: UInt32?
        var fileName: String?
        var dateCreated: String?
        var dateModified: String?
        var parent: UInt32?
        var protection: UInt16?

        var parsedHandle: UInt32?
        for _ in 0..<count {
            guard let h = try? reader.u32() else { break }
            parsedHandle = h
            guard let propCount = try? reader.u32() else { break }
            for _ in 0..<propCount {
                guard let code = try? reader.u16(), let type = try? reader.u16() else { break }
                switch type {
                case 0x0001, 0x0002:   // INT8 / UINT8
                    _ = try? reader.u8()
                case 0x0003, 0x0004:   // INT16 / UINT16
                    let v = try? reader.u16()
                    if code == Ptp.mtpPropProtectionStatus { protection = v }
                case 0x0005, 0x0006:   // INT32 / UINT32
                    let v = try? reader.u32()
                    switch code {
                    case Ptp.mtpPropStorageId: storageId = v
                    case Ptp.mtpPropObjectFormat: objectFormat = UInt16(truncatingIfNeeded: v ?? 0)
                    case Ptp.mtpPropObjectSize: objectSize = v
                    case Ptp.mtpPropParentObject: parent = v
                    default: break
                    }
                case 0x0007, 0x0008:   // INT64 / UINT64
                    _ = try? reader.u64()
                case 0xFFFF:           // STRING
                    let text = try? reader.string()
                    switch code {
                    case Ptp.mtpPropObjectFileName: fileName = text
                    case Ptp.mtpPropDateCreated: dateCreated = text
                    case Ptp.mtpPropDateModified: dateModified = text
                    default: break
                    }
                default:
                    // 未知类型无法确定宽度，放弃这一行（继续读会错位）
                    return parsedHandle.map {
                        MtpObjectProps(
                            handle: $0, storageId: storageId, objectFormat: objectFormat,
                            objectSize: objectSize, fileName: fileName,
                            dateCreatedRaw: dateCreated, dateModifiedRaw: dateModified,
                            parentObject: parent, protectionStatus: protection
                        )
                    }
                }
            }
        }

        guard let parsedHandle else { return nil }
        return MtpObjectProps(
            handle: parsedHandle,
            storageId: storageId,
            objectFormat: objectFormat,
            objectSize: objectSize,
            fileName: fileName,
            dateCreatedRaw: dateCreated,
            dateModifiedRaw: dateModified,
            parentObject: parent,
            protectionStatus: protection
        )
    }
}

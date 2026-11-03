//
//  PtpIpCodec.swift
//  NLink
//
//  PTP/IP 包编解码（对应安卓 `com/camerakit/ptp/PtpIpCodec.kt`）。
//
//  规范来源：CIPA DC-X005 / gphoto ptpip 文档 / 多份逆向实现互证。
//  包头 8 字节小端：`length u32`（含头全长，8..32MB）+ `type u32`。
//
//  载荷布局（偏移相对载荷首字节，已去包头）：
//    InitCommand(type1)      GUID(16) + UTF-16LE 主机名(含 \0) + 协议版本 u32
//    InitCommandAck(type2)   ConnectionID u32@0 + 相机 GUID 16B@4 + UTF-16LE 相机名@20
//    InitFail(type5)         错误码 u32@0
//    InitEvent(type3)        ConnectionID u32@0
//    CmdRequest(type6)       dataOut u32@0 + opcode u16@4 + transId u32@6 + params n×u32@10
//    CmdResponse(type7)      code u16@0 + transId u32@2 + params@6
//    Event(type8)            code u16@0 + params@2
//    StartData(type9)        transId u32@0 + 总长 u64@4  ← 12 字节，不是 8
//    Data(10)/EndData(12)    transId u32@0 + 数据@4
//    Cancel(type11)          transId u32@0
//    Ping(13)/Pong(14)       空载荷
//
//  这份实现刻意逐字节照抄，不做「更 Swift 化」的重构：协议两侧都在读同一台相机，
//  任何一处偏移改动都只有真机才能暴露，而真机不在手上。
//

import Foundation

enum PtpIpCodec {

    static let headerSize = 8

    struct Packet: Equatable {
        let type: UInt32
        let payload: Data
    }

    struct InitCommandAck: Equatable {
        let connectionId: Int32
        let cameraGuid: Data
        let cameraName: String
    }

    struct CmdResponse: Equatable {
        let code: UInt16
        let transId: Int32
        let params: [UInt32]
    }

    enum CodecError: Error, CustomStringConvertible {
        case payloadTooShort(String, Int, Int)
        case invalidGuidSize(Int)
        case invalidPacketLength(Int)

        var description: String {
            switch self {
            case let .payloadTooShort(what, got, need):
                return "\(what) 载荷过短：\(got) 字节（至少 \(need)）"
            case let .invalidGuidSize(size):
                return "GUID 必须是 16 字节，实际 \(size)"
            case let .invalidPacketLength(len):
                return "PTP/IP 包长度异常：\(len)"
            }
        }
    }

    // MARK: - 打包

    static func packet(type: UInt32, payload: Data) -> Data {
        var out = Data(capacity: headerSize + payload.count)
        out.appendLE(UInt32(payload.count + headerSize))
        out.appendLE(type)
        out.append(payload)
        return out
    }

    static func initCommandPayload(guid: Data, hostName: String) throws -> Data {
        guard guid.count == 16 else { throw CodecError.invalidGuidSize(guid.count) }
        var out = Data(capacity: 16 + (hostName.count + 1) * 2 + 4)
        out.append(guid)
        out.append(utf16LeZ(hostName))
        out.appendLE(Ptp.ptpIpProtocolVersion)
        return out
    }

    static func initEventPayload(connectionId: Int32) -> Data {
        var out = Data(capacity: 4)
        out.appendLE(connectionId)
        return out
    }

    static func cmdRequestPayload(dataOut: UInt32, opcode: UInt16, transId: Int32, params: [UInt32]) -> Data {
        var out = Data(capacity: 10 + params.count * 4)
        out.appendLE(dataOut)
        out.appendLE(opcode)
        out.appendLE(transId)
        for p in params { out.appendLE(p) }
        return out
    }

    static func cmdResponsePayload(code: UInt16, transId: Int32, params: [UInt32] = []) -> Data {
        var out = Data(capacity: 6 + params.count * 4)
        out.appendLE(code)
        out.appendLE(transId)
        for p in params { out.appendLE(p) }
        return out
    }

    static func eventPayload(code: UInt16, params: [UInt32] = []) -> Data {
        var out = Data(capacity: 2 + params.count * 4)
        out.appendLE(code)
        for p in params { out.appendLE(p) }
        return out
    }

    static func dataPayload(transId: Int32, data: Data) -> Data {
        var out = Data(capacity: 4 + data.count)
        out.appendLE(transId)
        out.append(data)
        return out
    }

    /// StartData 载荷 = transId u32 + 数据总长 **u64**（共 12 字节，高 4 字节恒 0）。
    /// 写成 8 字节会让相机解析错位——这是本协议最容易被「顺手简化」掉的地方。
    static func startDataPayload(transId: Int32, totalLength: UInt64) -> Data {
        var out = Data(capacity: 12)
        out.appendLE(transId)
        out.appendLE(totalLength)
        return out
    }

    static func cancelPayload(transId: Int32) -> Data {
        var out = Data(capacity: 4)
        out.appendLE(transId)
        return out
    }

    // MARK: - 解包

    /// 从包头解析长度。返回 nil 表示长度非法（<8 或 >32MB）。
    static func packetLength(header: Data) throws -> (length: Int, type: UInt32) {
        guard header.count >= headerSize else {
            throw CodecError.payloadTooShort("包头", header.count, headerSize)
        }
        let length = Int(header.readLE32(at: 0))
        let type = header.readLE32(at: 4)
        guard length >= headerSize, length <= Ptp.maxPacketBytes else {
            throw CodecError.invalidPacketLength(length)
        }
        return (length, type)
    }

    static func parseInitCommandAck(_ payload: Data) throws -> InitCommandAck {
        guard payload.count >= 20 else {
            throw CodecError.payloadTooShort("InitCommandAck", payload.count, 20)
        }
        let connId = payload.readLEInt32(at: 0)
        let guid = payload.subdata(in: 4..<20)
        let name = payload.decodeUtf16Le(from: 20)
        return InitCommandAck(connectionId: connId, cameraGuid: guid, cameraName: name)
    }

    static func parseInitFail(_ payload: Data) throws -> Int32 {
        guard payload.count >= 4 else {
            throw CodecError.payloadTooShort("InitFail", payload.count, 4)
        }
        return payload.readLEInt32(at: 0)
    }

    static func parseConnectionId(_ payload: Data) throws -> Int32 {
        guard payload.count >= 4 else {
            throw CodecError.payloadTooShort("InitEvent", payload.count, 4)
        }
        return payload.readLEInt32(at: 0)
    }

    static func parseCmdResponse(_ payload: Data) throws -> CmdResponse {
        guard payload.count >= 6 else {
            throw CodecError.payloadTooShort("CmdResponse", payload.count, 6)
        }
        let code = payload.readLE16(at: 0)
        let transId = payload.readLEInt32(at: 2)
        var params: [UInt32] = []
        var off = 6
        while off + 4 <= payload.count {
            params.append(payload.readLE32(at: off))
            off += 4
        }
        return CmdResponse(code: code, transId: transId, params: params)
    }

    static func parseEvent(_ payload: Data) throws -> (code: UInt16, params: [UInt32]) {
        guard payload.count >= 2 else {
            throw CodecError.payloadTooShort("Event", payload.count, 2)
        }
        let code = payload.readLE16(at: 0)
        var params: [UInt32] = []
        var off = 2
        while off + 4 <= payload.count {
            params.append(payload.readLE32(at: off))
            off += 4
        }
        return (code, params)
    }

    /// Data / EndData / StartData / Cancel 的事务号：载荷 u32@0
    static func payloadTransId(_ payload: Data) throws -> Int32 {
        guard payload.count >= 4 else {
            throw CodecError.payloadTooShort("数据包", payload.count, 4)
        }
        return payload.readLEInt32(at: 0)
    }

    /// StartData 的数据总长：载荷 u64@4。
    /// 注意这不是数据段——误收集会让 data-in 多出 4~12 字节垃圾。
    static func payloadStartDataLen(_ payload: Data) throws -> UInt64 {
        guard payload.count >= 8 else {
            throw CodecError.payloadTooShort("StartData", payload.count, 8)
        }
        if payload.count >= 12 { return payload.readLE64(at: 4) }
        return UInt64(payload.readLE32(at: 4))
    }

    /// Data / EndData 去掉事务号后的数据段
    static func payloadData(_ payload: Data) -> Data {
        guard payload.count > 4 else { return Data() }
        return payload.subdata(in: 4..<payload.count)
    }

    // MARK: - 字符串

    /// UTF-16LE 含结尾 \0：`(字符数 + 1) × 2` 字节。
    static func utf16LeZ(_ s: String) -> Data {
        var out = Data(capacity: (s.utf16.count + 1) * 2)
        for unit in s.utf16 { out.appendLE(unit) }
        out.appendLE(UInt16(0))
        return out
    }

    static func utf16Le(_ s: String, includeNul: Bool = false) -> Data {
        var out = Data(capacity: s.utf16.count * 2)
        for unit in s.utf16 { out.appendLE(unit) }
        if includeNul { out.appendLE(UInt16(0)) }
        return out
    }
}

// MARK: - 小端读写

extension Data {

    mutating func appendLE(_ value: UInt16) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
    }

    mutating func appendLE(_ value: UInt32) {
        append(UInt8(value & 0xFF))
        append(UInt8((value >> 8) & 0xFF))
        append(UInt8((value >> 16) & 0xFF))
        append(UInt8((value >> 24) & 0xFF))
    }

    mutating func appendLE(_ value: Int32) { appendLE(UInt32(bitPattern: value)) }

    mutating func appendLE(_ value: UInt64) {
        appendLE(UInt32(value & 0xFFFF_FFFF))
        appendLE(UInt32((value >> 32) & 0xFFFF_FFFF))
    }

    mutating func appendLE(_ value: Int64) { appendLE(UInt64(bitPattern: value)) }

    /// 注意：`Data` 的切片索引不是从 0 开始，所以一律用 `startIndex` 偏移。
    func readLE16(at index: Int) -> UInt16 {
        let base = startIndex + index
        return UInt16(self[base]) | (UInt16(self[base + 1]) << 8)
    }

    func readLE32(at index: Int) -> UInt32 {
        let base = startIndex + index
        return UInt32(self[base])
            | (UInt32(self[base + 1]) << 8)
            | (UInt32(self[base + 2]) << 16)
            | (UInt32(self[base + 3]) << 24)
    }

    func readLEInt32(at index: Int) -> Int32 { Int32(bitPattern: readLE32(at: index)) }

    func readLE64(at index: Int) -> UInt64 {
        UInt64(readLE32(at: index)) | (UInt64(readLE32(at: index + 4)) << 32)
    }

    /// 逐 u16 读到 0 为止（对应 Android `decodeUtf16Le`）。
    func decodeUtf16Le(from offset: Int) -> String {
        var units: [UInt16] = []
        var off = offset
        while startIndex + off + 1 < endIndex {
            let unit = readLE16(at: off)
            if unit == 0 { break }
            units.append(unit)
            off += 2
        }
        return String(decoding: units, as: UTF16.self)
    }
}

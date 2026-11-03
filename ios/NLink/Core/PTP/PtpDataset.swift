//
//  PtpDataset.swift
//  NLink
//
//  PTP 数据集编解码（对应安卓 `com/camerakit/ptp/PtpDataset.kt`）。全部小端。
//
//  PTP 字符串编码：`u8 字符数（含结尾 \0）+ UTF-16LE 字符`
//  数组编码：`u32 元素个数 + 元素`
//
//  这份 Reader/Writer 是「读完不越界」的强校验版本：协议字段长度由相机决定，
//  截断的数据集如果静默补 0，会得到一台「序列号为空、型号乱码」的相机，
//  比直接失败更难排查。
//

import Foundation

enum PtpDatasetError: Error, CustomStringConvertible {
    case underflow(String, need: Int, remaining: Int)

    var description: String {
        switch self {
        case let .underflow(what, need, remaining):
            return "数据集下溢：读取 \(what) 需要 \(need) 字节，只剩 \(remaining)"
        }
    }
}

struct PtpDatasetReader {

    private let bytes: [UInt8]
    private var pos = 0

    init(_ data: Data) { self.bytes = [UInt8](data) }

    var remaining: Int { bytes.count - pos }

    mutating func u8() throws -> UInt8 {
        guard remaining >= 1 else { throw PtpDatasetError.underflow("u8", need: 1, remaining: remaining) }
        defer { pos += 1 }
        return bytes[pos]
    }

    mutating func u16() throws -> UInt16 {
        guard remaining >= 2 else { throw PtpDatasetError.underflow("u16", need: 2, remaining: remaining) }
        defer { pos += 2 }
        return UInt16(bytes[pos]) | (UInt16(bytes[pos + 1]) << 8)
    }

    mutating func u32() throws -> UInt32 {
        guard remaining >= 4 else { throw PtpDatasetError.underflow("u32", need: 4, remaining: remaining) }
        defer { pos += 4 }
        return UInt32(bytes[pos])
            | (UInt32(bytes[pos + 1]) << 8)
            | (UInt32(bytes[pos + 2]) << 16)
            | (UInt32(bytes[pos + 3]) << 24)
    }

    mutating func u64() throws -> UInt64 {
        guard remaining >= 8 else { throw PtpDatasetError.underflow("u64", need: 8, remaining: remaining) }
        let low = try u32()
        let high = try u32()
        return UInt64(low) | (UInt64(high) << 32)
    }

    /// PTP string：u8 字符数（含 \0）+ UTF-16LE。
    /// 相机偶尔会给出 count=0（空串），也会给出比实际短的长度，两种都要能读下去。
    mutating func string() throws -> String {
        let count = Int(try u8())
        if count == 0 { return "" }
        guard remaining >= count * 2 else {
            throw PtpDatasetError.underflow("string", need: count * 2, remaining: remaining)
        }
        var units: [UInt16] = []
        units.reserveCapacity(count)
        for _ in 0..<count {
            let ch = try u16()
            if ch != 0 { units.append(ch) }
        }
        return String(decoding: units, as: UTF16.self)
    }

    mutating func u16Array() throws -> [UInt16] {
        let n = Int(try u32())
        guard n >= 0, remaining >= n * 2 else {
            throw PtpDatasetError.underflow("u16 array", need: n * 2, remaining: remaining)
        }
        var out: [UInt16] = []
        out.reserveCapacity(n)
        for _ in 0..<n { out.append(try u16()) }
        return out
    }

    mutating func u32Array() throws -> [UInt32] {
        let n = Int(try u32())
        guard n >= 0, remaining >= n * 4 else {
            throw PtpDatasetError.underflow("u32 array", need: n * 4, remaining: remaining)
        }
        var out: [UInt32] = []
        out.reserveCapacity(n)
        for _ in 0..<n { out.append(try u32()) }
        return out
    }

    /// 跳过定长字节（用于「u32 长度 + 内容」这类不需要保留的字段）。
    mutating func skip(_ n: Int) throws {
        guard remaining >= n else {
            throw PtpDatasetError.underflow("skip", need: n, remaining: remaining)
        }
        pos += n
    }
}

final class PtpDatasetWriter {

    private var out = Data()

    @discardableResult
    func u8(_ v: UInt8) -> PtpDatasetWriter { out.append(v); return self }

    @discardableResult
    func u16(_ v: UInt16) -> PtpDatasetWriter { out.appendLE(v); return self }

    @discardableResult
    func u32(_ v: UInt32) -> PtpDatasetWriter { out.appendLE(v); return self }

    @discardableResult
    func u64(_ v: UInt64) -> PtpDatasetWriter { out.appendLE(v); return self }

    /// PTP string：u8 字符数（含 \0）+ UTF-16LE
    @discardableResult
    func string(_ s: String) -> PtpDatasetWriter {
        let units = Array(s.utf16)
        out.append(UInt8(min(units.count + 1, 255)))
        for unit in units { out.appendLE(unit) }
        out.appendLE(UInt16(0))
        return self
    }

    @discardableResult
    func u16Array(_ items: [UInt16]) -> PtpDatasetWriter {
        out.appendLE(UInt32(items.count))
        for item in items { out.appendLE(item) }
        return self
    }

    @discardableResult
    func u32Array(_ items: [UInt32]) -> PtpDatasetWriter {
        out.appendLE(UInt32(items.count))
        for item in items { out.appendLE(item) }
        return self
    }

    func data() -> Data { out }
}

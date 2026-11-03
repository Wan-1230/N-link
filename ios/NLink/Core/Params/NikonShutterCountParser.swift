import Foundation

/// 尼康快门次数本地解析器。
///
/// 数据源唯一：照片 EXIF 的 MakerNote(`0x927C`) → 尼康 IFD → tag `0x00A7`。
/// 尼康机身**不提供**任何快门计数的 PTP 设备属性（对照标准 PTP 规范：
/// 佳能 `0xD1AC` / 奥林巴斯 `0xD059` / 富士 `0xD154` 都有，尼康没有），所以别去读属性。
/// `0x00A7` 本身也不加密 —— 它反倒是尼康用来解密 ShotInfo/ColorBalance/LensData 的密钥。
///
/// MakerNote 的 IFD 位置在三种形态间不同，且**不能**由 `MakerNoteVersion`(`0x0001`) 推断
/// （版本号字符串只用于选 ColorBalance/ShotInfo 子表，与 `0x00A7` 的位置无关）。这里按形态提示
/// 排好候选 IFD 位置 × 字节序后逐个枚举，再用固件恒等式
/// `0x00A5 ImageCount + 0x00A6 DeletedImageCount == 0x00A7 ShutterCount` 反选真正对齐的那一个 ——
/// 比「数值落在合理区间」可靠得多。旧实现只试过 IFD@8/0、从未试现代 D/Z 的 **IFD@18**，
/// 于是本地路径恒失败、把云端兜底顶成了主路径。
///
/// 真机样张实测推翻的两条假设，别再走回头路：
/// - `0x927C` 在 **Exif 子 IFD（`0x8769`）**，不在 IFD0 —— 只在 IFD0 找的话，所有真实照片一律失败；
/// - MakerNote 的字节序与主 TIFF 头**可以相反**：D70 / D2Hs 的笔记是大端而机身主 TIFF 是小端，
///   Z 系列 NEF 则同为小端。所以两种字节序都得试、由恒等式裁决，不能假设「尼康一律小端」。
///
/// 这几个 tag 都是 `count=1` 的 LONG/SHORT，值内联在 12 字节条目的 value 字段里，
/// 所以偏移基准（相对 MakerNote 还是相对主 TIFF 头）对读取结果没有影响，只有 IFD 位置和字节序要紧。
enum NikonShutterCountParser {

    /// 一次成功解析的结果。
    struct Reading: Sendable, Equatable {
        /// 主读数，含机械与电子快门的触发次数
        let count: Int
        /// 仅机械快门（`0x0037`），-1 = 机身未写
        let mechanical: Int
        let imageCount: Int
        let deletedImageCount: Int
        /// 命中的形态标签，用于诊断
        let layout: String
        /// 恒等式成立、读数可信；false = 仅数值合理，UI 需标注未校验
        let verified: Bool
    }

    // MARK: 常量

    private static let tagMakerNote: UInt16 = 0x927C
    private static let tagExifIFD: UInt16 = 0x8769

    private static let tagMechanicalShutterCount: UInt16 = 0x0037
    private static let tagImageCount: UInt16 = 0x00A5
    private static let tagDeletedImageCount: UInt16 = 0x00A6
    private static let tagShutterCount: UInt16 = 0x00A7

    /// 上限 5000 万足以挡掉误读到的其它 LONG 字段，以及 `0xFFFFF7FF` 这类「n/a」哨兵（有符号视角为负）
    private static let maxPlausibleCount = 50_000_000

    /// MakerNote 紧贴文件开头，1MB 覆盖所有尼康机身；超出即视为样本截断。
    ///
    /// 与安卓 `EXIF_HEAD_BYTES` 同值。真机上 MakerNote 落在 0.8~29KB，
    /// 1MB 是「够宽但不至于让一张 NEF 整份下载」的折中。
    static let exifHeadBytes = 1_048_576

    private static let maxIFDElements = 0x100_000

    // MARK: 样本挑选

    /// 挑快门次数样本：只取 JPEG/RAW，按「拍摄时间倒序 → RAW 优先 → 句柄倒序」取 3 张。
    ///
    /// 理由：拍摄时间最新的照片最可能是当前固件写的；RAW 与 JPEG 的 MakerNote 有时不同，
    /// 同时取到才有交叉校验的意义；句柄倒序保证同一时刻的文件也有确定顺序（不依赖字典顺序）。
    static func pickSamples(_ files: [CameraFile]) -> [CameraFile] {
        files
            .filter { $0.format == .jpeg || $0.format == .raw }
            .reduce(into: [UInt32: CameraFile]()) { dict, file in dict[file.handle] = file }
            .values
            .sorted { lhs, rhs in
                let lTime = lhs.captureTime ?? Int64.min
                let rTime = rhs.captureTime ?? Int64.min
                if lTime != rTime { return lTime > rTime }
                let lRaw = lhs.format == .raw ? 1 : 0
                let rRaw = rhs.format == .raw ? 1 : 0
                if lRaw != rRaw { return lRaw > rRaw }
                return lhs.handle > rhs.handle
            }
            .prefix(3)
            .map { $0 }
    }

    // MARK: 解析入口

    /// 解析 JPEG / NEF(TIFF) 字节；失败返回 nil，不抛异常。
    static func parse(_ data: Data) -> Reading? {
        guard data.count >= 16 else { return nil }
        let bytes = [UInt8](data)
        // NEF/TIFF 的文件头即 II/MM；JPEG 要先找 APP1 "Exif\0\0"
        if isTiffHeader(bytes, at: 0) {
            return parseTiffContainer(bytes, tiffStart: 0)
        }
        guard let exifStart = locateExifApp1(bytes) else { return nil }
        return parseTiffContainer(bytes, tiffStart: exifStart)
    }

    // MARK: 容器与 IFD0

    private static func isTiffHeader(_ bytes: [UInt8], at offset: Int) -> Bool {
        guard offset >= 0, offset + 2 <= bytes.count else { return false }
        return (bytes[offset] == 0x49 && bytes[offset + 1] == 0x49)
            || (bytes[offset] == 0x4D && bytes[offset + 1] == 0x4D)
    }

    /// 定位 APP1 Exif 段中 TIFF 头的起始位置（"Exif\0\0" 之后）
    private static func locateExifApp1(_ bytes: [UInt8]) -> Int? {
        var i = (bytes.count > 1 && bytes[0] == 0xFF && bytes[1] == 0xD8) ? 2 : 0
        while i + 4 < bytes.count {
            guard bytes[i] == 0xFF else { return nil }
            let marker = Int(bytes[i + 1])
            if marker == 0xFF || marker == 0x01 { i += 1; continue }   // 填充字节
            if marker == 0xD8 { i += 2; continue }
            if marker == 0xD9 || marker == 0xDA { return nil }          // 走到 EOI/SOS 也没碰上 Exif
            let segLen = (Int(bytes[i + 2]) << 8) | Int(bytes[i + 3])
            if marker == 0xE1 {
                let head = i + 4
                if head + 6 <= bytes.count,
                   bytes[head] == 0x45, bytes[head + 1] == 0x78,
                   bytes[head + 2] == 0x69, bytes[head + 3] == 0x66,
                   bytes[head + 4] == 0x00, bytes[head + 5] == 0x00 {
                    return head + 6
                }
            }
            guard segLen >= 2 else { return nil }
            i += 2 + segLen
        }
        return nil
    }

    private static func parseTiffContainer(_ bytes: [UInt8], tiffStart: Int) -> Reading? {
        guard tiffStart + 8 <= bytes.count else { return nil }
        let bigEndian = bytes[tiffStart] == 0x4D
        let reader = ByteReader(bytes: bytes, bigEndian: bigEndian)
        guard let ifd0Offset = reader.int32(at: tiffStart + 4), ifd0Offset >= 0 else { return nil }

        // MakerNote 的规范位置是 **Exif 子 IFD（0x8769）**，不是 IFD0 —— 真机样张已证实。
        // 旧实现只在 IFD0 找 0x927C，光这一条就足以在所有真实照片上失败。IFD0 仅作宽松兜底。
        var maker: [UInt8]?
        if let exifSub = readTagLong(reader, tiffStart: tiffStart, ifdOffset: ifd0Offset, tag: tagExifIFD) {
            maker = readTagBytes(reader, tiffStart: tiffStart, ifdOffset: exifSub, tag: tagMakerNote)
        }
        if maker == nil {
            maker = readTagBytes(reader, tiffStart: tiffStart, ifdOffset: ifd0Offset, tag: tagMakerNote)
        }
        guard let makerNote = maker else { return nil }
        return parseNikonMakerNote(makerNote)
    }

    private static func readTagLong(
        _ reader: ByteReader,
        tiffStart: Int,
        ifdOffset: Int,
        tag: UInt16
    ) -> Int? {
        guard let raw = readTagBytes(reader, tiffStart: tiffStart, ifdOffset: ifdOffset, tag: tag),
              raw.count == 4 else { return nil }
        let inner = ByteReader(bytes: raw, bigEndian: reader.bigEndian)
        return inner.int32(at: 0)
    }

    /// 在指定 IFD 中查 tag 并返回其原始值字节（≤4 字节取内联，否则按主 TIFF 头基准定位）
    private static func readTagBytes(
        _ reader: ByteReader,
        tiffStart: Int,
        ifdOffset: Int,
        tag: UInt16
    ) -> [UInt8]? {
        let ifdStart = tiffStart + ifdOffset
        guard let entryCountRaw = reader.uint16(at: ifdStart) else { return nil }
        let entryCount = Int(entryCountRaw)
        guard entryCount > 0, entryCount <= 512 else { return nil }
        for i in 0..<entryCount {
            let entry = ifdStart + 2 + i * 12
            guard let entryTag = reader.uint16(at: entry) else { return nil }
            if entryTag != tag { continue }
            guard let typeRaw = reader.uint16(at: entry + 2),
                  let countRaw = reader.int32(at: entry + 4) else { return nil }
            let unit = typeSize(Int(typeRaw))
            let count = countRaw
            guard unit > 0, count > 0, count <= maxIFDElements else { return nil }
            let size = count * unit
            if size <= 4 {
                return reader.slice(at: entry + 8, length: size)
            }
            guard let valueOffset = reader.int32(at: entry + 8) else { return nil }
            let absolute = tiffStart + valueOffset
            guard absolute >= 0, absolute + size <= reader.count else { return nil }
            return reader.slice(at: absolute, length: size)
        }
        return nil
    }

    // MARK: MakerNote：候选枚举 + 自校验

    private struct CandidateIFD {
        let layout: String
        let ifdStart: Int
        let bigEndian: Bool
    }

    /// 逐个候选 IFD 读 tag 组，恒等式成立即采纳；否则保留第一个「数值合理」的候选作为未校验结果
    private static func parseNikonMakerNote(_ maker: [UInt8]) -> Reading? {
        var best: Reading?
        for candidate in candidateIFDs(maker) {
            guard let tags = readNikonIFD(maker, ifdStart: candidate.ifdStart, bigEndian: candidate.bigEndian) else { continue }
            guard let shot = tags[tagShutterCount], isPlausible(shot) else { continue }
            let image = tags[tagImageCount]
            let deleted = tags[tagDeletedImageCount]
            let mechanical = tags[tagMechanicalShutterCount].flatMap { isPlausible($0) ? $0 : nil } ?? -1
            let verified: Bool = {
                guard let image, let deleted else { return false }
                return Int64(image) + Int64(deleted) == Int64(shot)
            }()
            let reading = Reading(
                count: shot,
                mechanical: mechanical,
                imageCount: image ?? -1,
                deletedImageCount: deleted ?? -1,
                layout: candidate.layout,
                verified: verified
            )
            if reading.verified { return reading }
            if best == nil { best = reading }
        }
        return best
    }

    /// 候选 IFD 起点，按命中概率降序。形态判据只决定顺序、不决定成员集合 ——
    /// 真正的裁决权在 `parseNikonMakerNote` 的恒等式校验上。
    private static func candidateIFDs(_ maker: [UInt8]) -> [CandidateIFD] {
        var out: [CandidateIFD] = []
        var seen = Set<String>()
        func add(_ candidate: CandidateIFD) {
            let key = "\(candidate.layout)|\(candidate.ifdStart)|\(candidate.bigEndian)"
            if seen.insert(key).inserted { out.append(candidate) }
        }
        func addOrders(_ label: String, at offset: Int) {
            // 笔记的字节序与主 TIFF 头无关（见类型注释），LE 先试
            add(CandidateIFD(layout: "\(label)/le", ifdStart: offset, bigEndian: false))
            add(CandidateIFD(layout: "\(label)/be", ifdStart: offset, bigEndian: true))
        }

        // 内嵌 TIFF 头（II*\0 / MM\0*）：IFD = 头起点 + 头内记录的偏移，字节序由该头决定
        if maker.count >= 10 {
            for p in 6...min(14, maker.count - 10) {
                guard isTiffHeader(maker, at: p) else { continue }
                let inner = ByteReader(bytes: maker, bigEndian: maker[p] == 0x4D)
                guard let magic = inner.uint16(at: p + 2), magic == 0x2A else { continue }
                guard let offset = inner.int32(at: p + 4) else { continue }
                if offset >= 0, offset < maker.count {
                    add(CandidateIFD(layout: "inner@\(p)", ifdStart: p + offset, bigEndian: maker[p] == 0x4D))
                }
            }
        }

        if hasNikonSignature(maker) {
            // 形态 A：现代 D/Z（"Nikon\0" + 0x02 + 版本串），IFD 固定在 +18
            if maker.count > 6, maker[6] == 0x02 { addOrders("v02xx", at: 18) }
            // 形态 B：E 系列（0x01），IFD 在 +8
            if maker.count > 6, maker[6] == 0x01 { addOrders("v01xx", at: 8) }
            addOrders("sig", at: 0)
            addOrders("sig", at: 8)
        }
        // 形态 C（D1、E99x、部分 Coolpix 无 "Nikon\0" 签名，D1 为大端）与机身变体兜底
        addOrders("flat", at: 0)
        addOrders("any", at: 18)
        addOrders("any", at: 8)
        return out
    }

    private static func hasNikonSignature(_ maker: [UInt8]) -> Bool {
        maker.count >= 6
            && maker[0] == 0x4E && maker[1] == 0x69 && maker[2] == 0x6B
            && maker[3] == 0x6F && maker[4] == 0x6E && maker[5] == 0x00
    }

    /// 读 MakerNote 内某个 IFD 关注的 tag；结构不合法返回 nil，以便继续试下一个候选
    private static func readNikonIFD(_ maker: [UInt8], ifdStart: Int, bigEndian: Bool) -> [UInt16: Int]? {
        guard ifdStart >= 0, ifdStart + 2 <= maker.count else { return nil }
        let reader = ByteReader(bytes: maker, bigEndian: bigEndian)
        guard let entryCountRaw = reader.uint16(at: ifdStart) else { return nil }
        let entryCount = Int(entryCountRaw)
        guard entryCount > 0, entryCount <= 512 else { return nil }
        var out: [UInt16: Int] = [:]
        for i in 0..<entryCount {
            let entry = ifdStart + 2 + i * 12
            guard entry + 12 <= maker.count else { return nil }
            guard let tag = reader.uint16(at: entry) else { return nil }
            if tag != tagShutterCount, tag != tagMechanicalShutterCount,
               tag != tagImageCount, tag != tagDeletedImageCount { continue }
            guard let typeRaw = reader.uint16(at: entry + 2),
                  let countRaw = reader.int32(at: entry + 4) else { continue }
            let unit = typeSize(Int(typeRaw))
            // 内联即够用；需要二次寻址的变体一律跳过，宁缺毋滥
            if unit <= 1 || unit > 4 || countRaw != 1 { continue }
            if unit == 2 {
                if let v = reader.uint16(at: entry + 8) { out[tag] = Int(v) }
            } else {
                if let v = reader.int32(at: entry + 8) { out[tag] = v }
            }
        }
        return out.isEmpty ? nil : out
    }

    private static func isPlausible(_ count: Int) -> Bool {
        count >= 1 && count <= maxPlausibleCount
    }

    // MARK: 字节序小工具（一律绝对寻址，不移动游标）

    private static func typeSize(_ type: Int) -> Int {
        switch type {
        case 1, 2, 6, 7: return 1     // BYTE / ASCII / SBYTE / UNDEFINED
        case 3, 8: return 2           // SHORT / SSHORT
        case 4, 9, 11: return 4       // LONG / SLONG / FLOAT
        case 5, 10, 12: return 8      // RATIONAL / SRATIONAL / DOUBLE
        default: return -1
        }
    }

    /// 绝对寻址的字节读取器。右越界一律返回 nil，不抛异常——
    /// 坏样本（截断的 NEF、被裁过的 JPEG 头）必须安静地失败，由调用方换下一张。
    private struct ByteReader {
        let bytes: [UInt8]
        let bigEndian: Bool

        var count: Int { bytes.count }

        func uint16(at offset: Int) -> UInt16? {
            guard offset >= 0, offset + 2 <= bytes.count else { return nil }
            let a = UInt16(bytes[offset])
            let b = UInt16(bytes[offset + 1])
            return bigEndian ? (a << 8 | b) : (b << 8 | a)
        }

        func int32(at offset: Int) -> Int? {
            guard offset >= 0, offset + 4 <= bytes.count else { return nil }
            let raw: UInt32
            if bigEndian {
                raw = UInt32(bytes[offset]) << 24 | UInt32(bytes[offset + 1]) << 16
                    | UInt32(bytes[offset + 2]) << 8 | UInt32(bytes[offset + 3])
            } else {
                raw = UInt32(bytes[offset + 3]) << 24 | UInt32(bytes[offset + 2]) << 16
                    | UInt32(bytes[offset + 1]) << 8 | UInt32(bytes[offset])
            }
            return Int(Int32(bitPattern: raw))
        }

        func slice(at offset: Int, length: Int) -> [UInt8]? {
            guard offset >= 0, length >= 0, offset + length <= bytes.count else { return nil }
            return Array(bytes[offset..<(offset + length)])
        }
    }
}

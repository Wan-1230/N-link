//
//  CameraFile.swift
//  N-Link
//
//  相机文件的领域模型 + 排序/筛选规则。
//
//  逐条对齐安卓 `camera/gallery/TransferManager.kt:2158`（CameraFile）与
//  `camera/gallery/AlbumSort.kt`。两个 `nil` 语义必须原样保留：
//   - `captureTime` 为 nil → 恒定排在有时间的文件之后（不随升降序翻转）
//   - `protectionStatus` / `rating` 为 nil → 机身**没报告这一列**，不等于「未保护 / 未评级」
//  第二点是「只显示已保护」筛出空列表这类事故的根源，安卓为此写了三段注释。
//

import Foundation

// MARK: - 格式

enum CameraFileFormat: String, Sendable, Hashable {
    case jpeg = "JPEG"
    case raw = "RAW"
    case video = "VIDEO"
    case other = "OTHER"

    var isPhoto: Bool { self == .jpeg || self == .raw }
    var displayName: String {
        switch self {
        case .jpeg: return "照片"
        case .raw: return "RAW"
        case .video: return "视频"
        case .other: return "其他"
        }
    }
}

/// PTP ObjectFormatCode → 格式分类。文件名后缀作为兜底（部分机身的 FormatCode 不规范）。
enum FileFormatClassifier {

    private static let videoCodes: Set<UInt16> = [0x300A, 0x300B, 0x300D, 0xB104, 0xB982, 0xB980]

    static func classify(formatCode: UInt16, fileName: String) -> CameraFileFormat {
        let upper = fileName.uppercased()
        if formatCode == 0x3801 || formatCode == 0x3808 || formatCode == 0x380B
            || upper.hasSuffix(".JPG") || upper.hasSuffix(".JPEG") {
            return .jpeg
        }
        if formatCode == 0xB103
            || upper.hasSuffix(".NEF") || upper.hasSuffix(".NRW")
            || upper.hasSuffix(".ARW") || upper.hasSuffix(".CR2")
            || upper.hasSuffix(".DNG") {
            return .raw
        }
        if videoCodes.contains(formatCode)
            || upper.hasSuffix(".MOV") || upper.hasSuffix(".MP4") || upper.hasSuffix(".AVI") {
            return .video
        }
        return .other
    }
}

// MARK: - 文件

struct CameraFile: Identifiable, Sendable, Hashable {
    let handle: UInt32
    let fileName: String
    let size: Int64
    let formatCode: UInt16
    let storageId: UInt32
    let format: CameraFileFormat
    /// 拍摄时间（epoch ms）。nil = 无法获取（来源：PTP DateCreated，缺失回退 DateModified）
    let captureTime: Int64?
    /// 机内保护状态原值。**nil = 机身未报告这一项**，不等于「未保护」
    let protectionStatus: UInt16?
    /// 配对 JPG 的句柄（RAW+JPG 成对显示时用）
    let pairedJpegHandle: UInt32?
    /// 机内评级（0~5）。**nil = 机身未报告**，不等于「未评级」
    let rating: Int?

    var id: UInt32 { handle }

    var isPhoto: Bool { format.isPhoto }

    var captureDate: Date? {
        guard let captureTime else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(captureTime) / 1000)
    }

    /// 扩展名（小写，无扩展名时为空串）
    var fileExtension: String {
        fileName.split(separator: ".").last.map { $0.lowercased() } ?? ""
    }

    /// 是否已被机内保护或打过星（「已筛选」档）
    var isMarkedInCamera: Bool { (protectionStatus ?? 0) != 0 || (rating ?? 0) != 0 }

    /// 只看保护位（「已保护」档）。未报告 → 视为未保护。
    var isProtectedInCamera: Bool { (protectionStatus ?? 0) != 0 }

    static func from(_ info: ObjectInfo) -> CameraFile {
        CameraFile(
            handle: info.handle,
            fileName: info.filename,
            size: Int64(info.compressedSize),
            formatCode: info.objectFormat,
            storageId: info.storageId,
            format: FileFormatClassifier.classify(
                formatCode: info.objectFormat,
                fileName: info.filename
            ),
            captureTime: PTPDate.parse(info.captureDate) ?? PTPDate.parse(info.modificationDate),
            protectionStatus: info.protectionStatus,
            pairedJpegHandle: nil,
            rating: nil
        )
    }

    /// 从 `GetObjectPropList` 的一行构造。
    ///
    /// 缺列一律留 nil——尤其保护位：`nil` 与 `0` 在「只传已保护的」里结果完全不同。
    /// 文件名缺失时用 `DSC_<handle 低 16 位>` 占位（安卓直读通道同款），
    /// 宁可显示一个能对上的占位名，也不要显示空白格。
    static func from(_ props: MtpObjectProps, fallbackStorageId: UInt32) -> CameraFile {
        let formatCode = props.objectFormat ?? 0
        let name = props.fileName ?? placeholderName(props.handle)
        return CameraFile(
            handle: props.handle,
            fileName: name,
            size: Int64(props.objectSize ?? 0),
            formatCode: formatCode,
            storageId: props.storageId ?? fallbackStorageId,
            format: FileFormatClassifier.classify(formatCode: formatCode, fileName: name),
            captureTime: PTPDate.parse(props.dateCreatedRaw) ?? PTPDate.parse(props.dateModifiedRaw),
            protectionStatus: props.protectionStatus,
            pairedJpegHandle: nil,
            rating: nil
        )
    }

    static func placeholderName(_ handle: UInt32) -> String {
        String(format: "DSC_%04X", UInt16(truncatingIfNeeded: handle))
    }
}

// MARK: - PTP 日期

/// PTP 的 DateCreated / DateModified 是 `YYYYMMDDTHHMMSS` 加可选时区偏移（如 `.000+08:00`）。
enum PTPDate {

    private static let lock = NSLock()
    private static let formatters: [DateFormatter] = {
        // 不带时区与带小数秒两种都得试：机身固件在这处很不统一
        let patterns = ["yyyyMMdd'T'HHmmss", "yyyyMMdd'T'HHmmss.SSS"]
        return patterns.map { pattern in
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = TimeZone.current
            f.dateFormat = pattern
            return f
        }
    }()

    static func parse(_ raw: String?) -> Int64? {
        guard var text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            return nil
        }
        // 去掉结尾的时区偏移：PTP 用「相机本地时间」，我们按手机本地时区解释
        if let dot = text.firstIndex(of: "."), let signIndex = text[dot...].firstIndex(where: { $0 == "+" || $0 == "-" || $0 == "Z" }) {
            text = String(text[text.startIndex..<signIndex])
        } else if let signIndex = text.firstIndex(where: { $0 == "+" || $0 == "-" || $0 == "Z" }) {
            text = String(text[text.startIndex..<signIndex])
        }
        lock.lock()
        defer { lock.unlock() }
        for formatter in formatters where text.count >= formatter.dateFormat.count - 4 {
            if let date = formatter.date(from: text) {
                return Int64(date.timeIntervalSince1970 * 1000)
            }
        }
        return nil
    }
}

// MARK: - 排序

enum AlbumSortDimension: String, CaseIterable, Identifiable {
    case captureTime = "CAPTURE_TIME"
    case fileType = "FILE_TYPE"

    var id: String { rawValue }
    var title: String { self == .captureTime ? "拍摄时间" : "文件类型" }
}

enum AlbumSortDirection: String, CaseIterable, Identifiable {
    case asc = "ASC"
    case desc = "DESC"

    var id: String { rawValue }
    var title: String { self == .asc ? "升序" : "降序" }
    var symbol: String { self == .asc ? "↑" : "↓" }
}

struct AlbumSort: Equatable, Sendable {
    var dimension: AlbumSortDimension = .captureTime
    var direction: AlbumSortDirection = .desc

    static let `default` = AlbumSort()

    /// 工具栏按钮上的短标签，如「时间 ↓」
    var shortLabel: String {
        dimension == .captureTime ? "时间 \(direction.symbol)" : "类型 \(direction.symbol)"
    }

    var menuLabel: String { "\(dimension.title) \(direction.symbol)" }

    static var options: [AlbumSort] {
        AlbumSortDimension.allCases.flatMap { dimension in
            AlbumSortDirection.allCases.map { AlbumSort(dimension: dimension, direction: $0) }
        }
    }

    static func fromPersistence(dimension: String?, direction: String?) -> AlbumSort {
        AlbumSort(
            dimension: AlbumSortDimension(rawValue: dimension ?? "") ?? .captureTime,
            direction: AlbumSortDirection(rawValue: direction ?? "") ?? .desc
        )
    }
}

enum AlbumSorter {

    /// 文件类型分组顺序：照片 → RAW → 动图 → 视频 → 其他
    private enum Group {
        static let photo = 0
        static let raw = 1
        static let animated = 2
        static let video = 3
        static let other = 4
    }

    private static let photoExtensions: Set<String> =
        ["jpg", "jpeg", "jpe", "png", "heic", "heif", "tif", "tiff", "bmp"]
    private static let rawExtensions: Set<String> =
        ["nef", "nrw", "cr2", "cr3", "arw", "dng", "raf", "orf", "rw2", "pef", "srw", "raw"]
    private static let animatedExtensions: Set<String> = ["gif", "apng", "webp", "avifs"]
    private static let videoExtensions: Set<String> =
        ["mov", "mp4", "m4v", "avi", "mkv", "webm", "mpg", "mpeg", "ts", "mts"]

    static func sort(_ files: [CameraFile], by sort: AlbumSort) -> [CameraFile] {
        guard files.count > 1 else { return files }
        switch sort.dimension {
        case .captureTime: return byCaptureTime(files, sort.direction)
        case .fileType: return byFileType(files, sort.direction)
        }
    }

    /// 取不到时间的文件**恒定排在末尾**，不随升降序翻转；组内按文件名升序做稳定兜底。
    private static func byCaptureTime(_ files: [CameraFile], _ direction: AlbumSortDirection) -> [CameraFile] {
        let withTime = files.filter { $0.captureTime != nil }
        let withoutTime = files.filter { $0.captureTime == nil }

        let sorted = withTime.sorted { lhs, rhs in
            let lt = lhs.captureTime ?? 0
            let rt = rhs.captureTime ?? 0
            if lt != rt {
                return direction == .asc ? lt < rt : lt > rt
            }
            return lhs.fileName < rhs.fileName
        }
        return sorted + withoutTime.sorted { $0.fileName < $1.fileName }
    }

    /// 分组以**扩展名**为准而非 ObjectFormatCode：动图（GIF/APNG/WEBP）在 PTP 里没有独立分类，
    /// 只会落到 other，靠枚举分不出来。
    private static func byFileType(_ files: [CameraFile], _ direction: AlbumSortDirection) -> [CameraFile] {
        struct Key {
            let group: Int
            let ext: String
            let lowerName: String
        }
        let decorated = files.map { file -> (Key, CameraFile) in
            let ext = file.fileExtension
            return (Key(group: groupOrder(ext, file.format), ext: ext, lowerName: file.fileName.lowercased()), file)
        }
        let sorted = decorated.sorted { lhs, rhs in
            if lhs.0.group != rhs.0.group { return lhs.0.group < rhs.0.group }
            if lhs.0.ext != rhs.0.ext { return lhs.0.ext < rhs.0.ext }
            return lhs.0.lowerName < rhs.0.lowerName
        }
        let ordered = direction == .asc ? sorted : sorted.reversed()
        return ordered.map { $0.1 }
    }

    private static func groupOrder(_ ext: String, _ format: CameraFileFormat) -> Int {
        if photoExtensions.contains(ext) { return Group.photo }
        if rawExtensions.contains(ext) { return Group.raw }
        if animatedExtensions.contains(ext) { return Group.animated }
        if videoExtensions.contains(ext) { return Group.video }
        // 没有扩展名时才回退到 PTP 的格式分类
        switch format {
        case .jpeg: return Group.photo
        case .raw: return Group.raw
        case .video: return Group.video
        case .other: return Group.other
        }
    }
}

// MARK: - 筛选

/// 相册筛选档。命名与安卓一致（「已筛选」= 机内保护键或评级；「已保护」= 只看保护位）。
enum PhotoFilter: String, CaseIterable, Identifiable {
    case all = "ALL"
    case photos = "PHOTOS"
    case video = "VIDEO"
    case raw = "RAW"
    case jpeg = "JPEG"
    case marked = "PROTECTED"
    case protectedOnly = "PROTECTED_ONLY"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "全部"
        case .photos: return "照片"
        case .video: return "视频"
        case .raw: return "RAW"
        case .jpeg: return "JPG"
        case .marked: return "已筛选"
        case .protectedOnly: return "已保护"
        }
    }

    /// 筛选芯片条上展示的 6 档（「已保护」在长按菜单里，不占主条位置）
    static var chipFilters: [PhotoFilter] { [.all, .photos, .video, .raw, .jpeg, .marked] }

    func accepts(_ file: CameraFile) -> Bool {
        switch self {
        case .all: return true
        case .photos: return file.isPhoto
        case .video: return file.format == .video
        case .raw: return file.format == .raw
        case .jpeg: return file.format == .jpeg
        case .marked: return file.isMarkedInCamera
        case .protectedOnly: return file.isProtectedInCamera
        }
    }
}

// MARK: - RAW+JPG 配对

/// RAW+JPG 成对显示。
///
/// 只有「恰好 2 张、且恰为 1 张 RAW + 1 张 JPG、同名」才配对。
/// 1 张或 ≥3 张视为歧义跳过 —— 猜错等于替用户决定下哪张。
enum RawJpegPairing {

    struct Plan: Sendable {
        /// RAW 句柄 → JPG 句柄
        let pairs: [UInt32: UInt32]
        /// 要隐藏的 JPG 句柄（已在 RAW 上挂了角标）
        let hidden: Set<UInt32>
    }

    static func baseName(_ fileName: String) -> String? {
        guard let dot = fileName.lastIndex(of: "."), dot != fileName.startIndex else { return nil }
        return String(fileName[fileName.startIndex..<dot]).uppercased()
    }

    static func plan(_ files: [CameraFile]) -> Plan {
        var groups: [String: [CameraFile]] = [:]
        for file in files {
            guard let base = baseName(file.fileName) else { continue }
            groups[base, default: []].append(file)
        }

        var pairs: [UInt32: UInt32] = [:]
        var hidden: Set<UInt32> = []
        for (_, group) in groups {
            guard group.count == 2 else { continue }
            let raws = group.filter { $0.format == .raw }
            let jpegs = group.filter { $0.format == .jpeg }
            guard raws.count == 1, jpegs.count == 1 else { continue }
            let raw = raws[0]
            let jpeg = jpegs[0]
            guard raw.handle != jpeg.handle else { continue }
            pairs[raw.handle] = jpeg.handle
            hidden.insert(jpeg.handle)
        }
        return Plan(pairs: pairs, hidden: hidden)
    }

    /// 把配对信息挂到 RAW 上、隐藏 JPG。
    static func applyMerge(_ files: [CameraFile], plan: Plan) -> [CameraFile] {
        files.compactMap { file in
            if plan.hidden.contains(file.handle) { return nil }
            guard let paired = plan.pairs[file.handle] else { return file }
            return CameraFile(
                handle: file.handle,
                fileName: file.fileName,
                size: file.size,
                formatCode: file.formatCode,
                storageId: file.storageId,
                format: file.format,
                captureTime: file.captureTime,
                protectionStatus: file.protectionStatus,
                pairedJpegHandle: paired,
                rating: file.rating
            )
        }
    }

    /// 入队时把该下的一张找回来（成对 / 只 RAW / 只 JPG）。
    static func expand(
        _ selected: [CameraFile],
        plan: Plan,
        mode: AppSettings.RawPairMode,
        lookup: (UInt32) -> CameraFile?
    ) -> [CameraFile] {
        var out: [CameraFile] = []
        var seen = Set<UInt32>()
        for file in selected {
            switch mode {
            case .both:
                if !seen.contains(file.handle) {
                    out.append(file)
                    seen.insert(file.handle)
                }
                if let paired = file.pairedJpegHandle, let twin = lookup(paired), !seen.contains(twin.handle) {
                    out.append(twin)
                    seen.insert(twin.handle)
                }
            case .rawOnly:
                if file.format == .raw, !seen.contains(file.handle) {
                    out.append(file)
                    seen.insert(file.handle)
                }
            case .jpegOnly:
                if file.format == .jpeg, !seen.contains(file.handle) {
                    out.append(file)
                    seen.insert(file.handle)
                } else if file.format == .raw, let paired = file.pairedJpegHandle,
                          let twin = lookup(paired), !seen.contains(twin.handle) {
                    out.append(twin)
                    seen.insert(twin.handle)
                }
            }
        }
        return out
    }
}

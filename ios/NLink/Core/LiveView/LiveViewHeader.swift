//
//  LiveViewHeader.swift
//  N-Link
//
//  实时取景帧头解析。逐字段对齐安卓 `camera/liveview/LiveViewHeader.kt`（363 行）。
//
//  帧不是裸 JPEG：前面挂一段版本化头（v1 = 512 字节 / 42 个 AF 槽，v2 = 1024 / 96）。
//  头里最有价值的是 **display 区域** 与 **AF 区域列表**：
//
//  · AF 区域记录在 whole 像素域（整幅传感器的取景范围），而监看 JPEG 只呈现
//    display 区域（可能更小并带偏移）。所以回显 AF 框必须减去 display 的原点，
//    直接除以 whole 会在机身做裁切时系统性偏移。
//  · 头里所有多字节字段都是**大端**（与 PTP/IP 线协议的小端相反）。
//  · 解析失败一律返回 nil，调用方回退「whole/帧比例 + 只画点击位置」的老行为——
//    宁可少画一个框，不要画一个错位的框。
//

import Foundation

// MARK: - AF 区域角色

enum AfRole: Sendable, Equatable {
    case primaryControl
    case secondaryControl
    case subjectDetection
    case tracking
    case unknown

    var displayName: String {
        switch self {
        case .primaryControl: return "主对焦区"
        case .secondaryControl: return "副对焦区"
        case .subjectDetection: return "被摄体识别"
        case .tracking: return "跟踪"
        case .unknown: return "对焦区"
        }
    }
}

struct AfArea: Sendable, Equatable {
    let width: UInt16
    let height: UInt16
    let centerX: UInt16
    let centerY: UInt16
    let role: AfRole
    let index: Int

    /// 宽高为 0 的槽是空槽，不是「零尺寸的框」。
    var isPlausible: Bool { width > 0 && height > 0 }

    /// 记录必须完整落在 whole 域内。越界的槽多半是上一帧的残留。
    func fitsWhole(_ wholeWidth: UInt16, _ wholeHeight: UInt16) -> Bool {
        guard isPlausible, wholeWidth > 0, wholeHeight > 0 else { return false }
        let halfW = Int(width) / 2
        let halfH = Int(height) / 2
        return centerX >= halfW && centerY >= halfH
            && Int(centerX) + Int(width) - halfW <= Int(wholeWidth)
            && Int(centerY) + Int(height) - halfH <= Int(wholeHeight)
    }
}

/// 相对**监看 JPEG 显示内容**归一化到 0..1 的矩形。
struct NormalizedRect: Sendable, Equatable {
    let left: Double
    let top: Double
    let right: Double
    let bottom: Double

    var width: Double { max(0, right - left) }
    var height: Double { max(0, bottom - top) }
    var center: (x: Double, y: Double) { ((left + right) / 2, (top + bottom) / 2) }
}

// MARK: - 帧头

struct LiveViewHeader: Sendable {

    /// 字段偏移。0..7 是 versioned 前缀，头内容从 8 起。
    private enum Offset {
        static let major = 0
        static let minor = 2
        static let declaredHeaderBytes = 8
        static let jpegLength = 12
        static let wholeWidth = 16
        static let wholeHeight = 18
        static let displayWidth = 20
        static let displayHeight = 22
        static let displayCenterX = 24
        static let displayCenterY = 26
        static let jpegWidth = 28
        static let jpegHeight = 30
        static let focusAbilityState = 40
        static let focusDrive = 41
        static let focusState = 42
        static let afMode = 43
        static let afAreaCount = 44
        static let selectedAfIndex = 45
        static let trackingState = 46
        /// 48 起每个 AF 区域 4×u16 = (width, height, centerX, centerY)
        static let afAreaStart = 48
        static let afAreaBytes = 8
    }

    static let maxAreasV1 = 42
    static let maxAreasV2 = 64
    static let rotationOffsetV1 = 403
    static let rotationOffsetV2 = 839

    let majorVersion: UInt16
    let minorVersion: UInt16
    let headerBytes: Int
    let jpegWidth: UInt16
    let jpegHeight: UInt16
    let wholeWidth: UInt16
    let wholeHeight: UInt16
    let displayWidth: UInt16
    let displayHeight: UInt16
    let displayCenterX: UInt16
    let displayCenterY: UInt16
    let rotation: UInt8
    let focusAbilityState: UInt8
    let focusDrive: UInt8
    let focusState: UInt8
    let afMode: UInt8
    let afAreaCount: UInt8
    let selectedAfIndex: UInt8
    let trackingState: UInt8
    let afAreas: [AfArea]

    // MARK: 派生域

    var displayLeft: Int {
        (Int(displayCenterX) - Int(displayWidth) / 2)
    }

    var displayTop: Int {
        (Int(displayCenterY) - Int(displayHeight) / 2)
    }

    /// display 区域是否可用。不可用时所有换算退回 JPEG 居中域。
    var hasUsableDisplayArea: Bool {
        displayWidth > 0 && displayHeight > 0
            && displayLeft >= 0 && displayTop >= 0
            && displayLeft + Int(displayWidth) <= Int(wholeWidth)
            && displayTop + Int(displayHeight) <= Int(wholeHeight)
    }

    /// JPEG 是否可放在 whole 里（display 缺失时的回退域）。
    var hasUsableImageArea: Bool {
        jpegWidth > 0 && jpegHeight > 0
            && imageLeft >= 0 && imageTop >= 0
            && imageLeft + Int(jpegWidth) <= Int(wholeWidth)
            && imageTop + Int(jpegHeight) <= Int(wholeHeight)
    }

    /// JPEG 在 whole 中居中（逆向实现同款）。
    var imageLeft: Int { (Int(wholeWidth) - Int(jpegWidth)) / 2 }
    var imageTop: Int { (Int(wholeHeight) - Int(jpegHeight)) / 2 }

    /// 当前用于归一化的域：(原点 X, 原点 Y, 宽, 高)。
    private var normDomain: (x: Int, y: Int, w: Int, h: Int)? {
        if hasUsableDisplayArea {
            return (displayLeft, displayTop, Int(displayWidth), Int(displayHeight))
        }
        if hasUsableImageArea {
            return (imageLeft, imageTop, Int(jpegWidth), Int(jpegHeight))
        }
        if wholeWidth > 0, wholeHeight > 0 {
            return (0, 0, Int(wholeWidth), Int(wholeHeight))
        }
        return nil
    }

    // MARK: 换算

    /// AF 区域 → 相对监看 JPEG 内容的归一化矩形。
    ///
    /// 三层域回退：display（首选）→ JPEG 居中 → whole。
    /// 少减 display 原点这一步，只要机身做了画面裁切，框就会整体偏移。
    func normalizedRect(_ area: AfArea) -> NormalizedRect? {
        guard area.fitsWhole(wholeWidth, wholeHeight), let domain = normDomain else { return nil }
        func norm(_ value: Int, _ origin: Int, _ size: Int) -> Double {
            guard size > 0 else { return 0 }
            return min(max(Double(value - origin) / Double(size), 0), 1)
        }
        let halfW = Int(area.width) / 2
        let halfH = Int(area.height) / 2
        let left = norm(Int(area.centerX) - halfW, domain.x, domain.w)
        let top = norm(Int(area.centerY) - halfH, domain.y, domain.h)
        let right = norm(Int(area.centerX) + halfW, domain.x, domain.w)
        let bottom = norm(Int(area.centerY) + halfH, domain.y, domain.h)
        guard right > left, bottom > top else { return nil }
        return NormalizedRect(left: left, top: top, right: right, bottom: bottom)
    }

    /// 点 → AF 域坐标（`ChangeAfArea` 的目标域）。
    ///
    /// 与回显换算严格互逆：`displayLeft + nx * displayWidth`。
    /// display 原点按逆向实现夹到 `[0, whole-display]`：displayCenter 报得比半个
    /// display 还靠边时，相机实际会把画面贴边显示，而不是出现负原点。
    func afCoord(normalizedX nx: Double, normalizedY ny: Double) -> (x: Int, y: Int)? {
        let clampedX = min(max(nx, 0), 1)
        let clampedY = min(max(ny, 0), 1)
        let x: Double
        let y: Double
        let maxX: Int
        let maxY: Int

        if hasUsableDisplayArea {
            let left = min(max(displayLeft, 0), max(0, Int(wholeWidth) - Int(displayWidth)))
            let top = min(max(displayTop, 0), max(0, Int(wholeHeight) - Int(displayHeight)))
            x = Double(left) + clampedX * Double(displayWidth)
            y = Double(top) + clampedY * Double(displayHeight)
            maxX = Int(wholeWidth)
            maxY = Int(wholeHeight)
        } else if hasUsableImageArea {
            x = Double(imageLeft) + clampedX * Double(jpegWidth)
            y = Double(imageTop) + clampedY * Double(jpegHeight)
            maxX = Int(wholeWidth)
            maxY = Int(wholeHeight)
        } else if wholeWidth > 0, wholeHeight > 0 {
            x = clampedX * Double(wholeWidth)
            y = clampedY * Double(wholeHeight)
            maxX = Int(wholeWidth)
            maxY = Int(wholeHeight)
        } else {
            return nil
        }
        return (
            min(max(Int(x), 0), max(0, maxX - 1)),
            min(max(Int(y), 0), max(0, maxY - 1))
        )
    }

    /// 当前应回显的 AF 区域。
    ///
    /// 优先级：跟踪框 > 选中项的主体检测框 > 主体检测框 > 主控制框 > 副控制框 > 第一个。
    /// 「主体检测有多个时优先选中项」与「控制框优先 selected 索引」是两条不同的规则，
    /// 合在一起才能避免把机身边缘的次要框画成对焦点。
    func activeAfArea() -> AfArea? {
        let plausible = afAreas.filter { $0.fitsWhole(wholeWidth, wholeHeight) }
        guard !plausible.isEmpty else { return nil }
        if let tracking = plausible.first(where: { $0.role == .tracking }) { return tracking }
        if let selectedSubject = plausible.first(where: {
            $0.role == .subjectDetection && $0.index == Int(selectedAfIndex)
        }) { return selectedSubject }
        if let subject = plausible.first(where: { $0.role == .subjectDetection }) { return subject }
        if let primary = plausible.first(where: { $0.role == .primaryControl }) { return primary }
        if let secondary = plausible.first(where: { $0.role == .secondaryControl }) { return secondary }
        return plausible.first
    }

    // MARK: 解析

    /// 解析失败返回 nil（调用方回退旧行为）。
    static func parse(_ data: Data) -> LiveViewHeader? {
        guard data.count >= Offset.afAreaStart + Offset.afAreaBytes else { return nil }

        func be16(_ offset: Int) -> UInt16 {
            let base = data.startIndex + offset
            guard base + 1 < data.endIndex else { return 0 }
            return (UInt16(data[base]) << 8) | UInt16(data[base + 1])
        }
        func be32(_ offset: Int) -> UInt32 {
            (UInt32(be16(offset)) << 16) | UInt32(be16(offset + 2))
        }
        func be8(_ offset: Int) -> UInt8 {
            let base = data.startIndex + offset
            guard base < data.endIndex else { return 0 }
            return data[base]
        }

        let major = be16(Offset.major)
        guard major == 1 || major == 2 else { return nil }
        let minor = be16(Offset.minor)

        let wholeWidth = be16(Offset.wholeWidth)
        let wholeHeight = be16(Offset.wholeHeight)
        let displayWidth = be16(Offset.displayWidth)
        let displayHeight = be16(Offset.displayHeight)
        let displayCenterX = be16(Offset.displayCenterX)
        let displayCenterY = be16(Offset.displayCenterY)
        let jpegWidth = be16(Offset.jpegWidth)
        let jpegHeight = be16(Offset.jpegHeight)

        guard (1...20000).contains(jpegWidth), (1...20000).contains(jpegHeight),
              (1...20000).contains(wholeWidth), (1...20000).contains(wholeHeight) else { return nil }

        // 8/12 是「头长度 / JPEG 长度」。部分 mock/老固件不带长度前缀时按 major 取默认。
        let declaredHeader = Int(be32(Offset.declaredHeaderBytes))
        let headerBytes = (declaredHeader == 512 || declaredHeader == 1_024)
            ? declaredHeader
            : (major == 1 ? 512 : 1_024)

        let maxAreas = major == 2 ? maxAreasV2 : maxAreasV1
        let declaredCount = Int(be8(Offset.afAreaCount))
        // 逆向实现只按声明 count 读取；旧版 v1 在 count 不可信时读满 42 槽，
        // 会把未使用槽的残留字节当成 AF 区域画到屏幕上，这里直接判无效。
        let count = (1...maxAreas).contains(declaredCount) ? declaredCount : 0

        let headerEnd = 8 + headerBytes
        let required = Offset.afAreaStart + count * Offset.afAreaBytes
        guard required <= data.count, required <= headerEnd else { return nil }

        let afMode = be8(Offset.afMode)
        let selectedIndex = be8(Offset.selectedAfIndex)
        let tracking = be8(Offset.trackingState)
        // 分类字段是否可信：索引在范围内、<= 34（老固件的槽上限）、跟踪状态 0..2。
        let classificationReliable = count > 0
            && Int(selectedIndex) < count
            && selectedIndex <= 34
            && tracking <= 2

        var areas: [AfArea] = []
        areas.reserveCapacity(count)
        for i in 0..<count {
            let off = Offset.afAreaStart + i * Offset.afAreaBytes
            areas.append(AfArea(
                width: be16(off),
                height: be16(off + 2),
                centerX: be16(off + 4),
                centerY: be16(off + 6),
                role: classifyRole(
                    afMode: afMode,
                    index: i,
                    selectedIndex: Int(selectedIndex),
                    trackingState: tracking,
                    reliable: classificationReliable
                ),
                index: i
            ))
        }

        let rotationOffset = major == 1 ? rotationOffsetV1 : rotationOffsetV2
        let rotation: UInt8 = data.count > rotationOffset ? be8(rotationOffset) : 0

        return LiveViewHeader(
            majorVersion: major,
            minorVersion: minor,
            headerBytes: headerBytes,
            jpegWidth: jpegWidth,
            jpegHeight: jpegHeight,
            wholeWidth: wholeWidth,
            wholeHeight: wholeHeight,
            displayWidth: displayWidth,
            displayHeight: displayHeight,
            displayCenterX: displayCenterX,
            displayCenterY: displayCenterY,
            rotation: rotation,
            focusAbilityState: be8(Offset.focusAbilityState),
            focusDrive: be8(Offset.focusDrive),
            focusState: be8(Offset.focusState),
            afMode: afMode,
            afAreaCount: UInt8(count),
            selectedAfIndex: selectedIndex,
            trackingState: tracking,
            afAreas: areas
        )
    }

    /// 区域分类规则：afMode 0=OTHER / 1=SUBJECT / 2=AUTO_AREA；跟踪状态 0/1/2。
    /// 字段不可信时全部标 unknown，由 `activeAfArea()` 退回「第一个有效区域」。
    private static func classifyRole(
        afMode: UInt8,
        index: Int,
        selectedIndex: Int,
        trackingState: UInt8,
        reliable: Bool
    ) -> AfRole {
        guard reliable else { return .unknown }
        switch afMode {
        case 1:
            return .subjectDetection
        case 2:
            if index == selectedIndex, trackingState == 2 { return .tracking }
            return .subjectDetection
        default:
            return index == selectedIndex ? .primaryControl : .secondaryControl
        }
    }
}

// MARK: - 帧工具

enum LiveViewFrameUtil {

    /// 在帧里找到 JPEG 起点（`FF D8`）。线性扫，帧头通常只有 512/1024 字节。
    static func findJpegStart(_ data: Data) -> Int? {
        guard data.count >= 2 else { return nil }
        var i = data.startIndex
        while i < data.endIndex - 1 {
            if data[i] == 0xFF, data[i + 1] == 0xD8 {
                return i - data.startIndex
            }
            i += 1
        }
        return nil
    }

    /// 剥掉帧头，返回纯 JPEG。找不到 JPEG 起点就返回原数据（解码会失败，但不会崩）。
    static func stripHeader(_ data: Data) -> Data {
        guard let offset = findJpegStart(data), offset > 0 else { return data }
        return data.subdata(in: (data.startIndex + offset)..<data.endIndex)
    }

    /// 两帧是否相同（AP 通道会重复吐同一帧）。
    /// 指纹照抄安卓：size + 首 8 字节 + 尾 8 字节。
    static func fingerprint(_ data: Data) -> (Int, [UInt8], [UInt8]) {
        (data.count, Array(data.prefix(8)), Array(data.suffix(8)))
    }

    /// 快门后的黑帧判定：体积塌到参考帧的 1/8 以下即视为黑帧。
    ///
    /// 安卓原版还叠加了「解码后 99% 像素几乎全黑」的判据（`isMostlyDarkJpeg`）。
    /// iOS 这里只用体积判据，理由：解码每一帧来做这个判断会吃掉 15fps 预算的
    /// 一大半，而体积判据在真机上已经能拦住快门黑屏——黑帧的 JPEG 压缩率极高，
    /// 体积塌陷非常稳定。代价是「用户主动盖住镜头」这种极端情况不能识别，
    /// 但那种场景本来也不该丢帧。
    static func isProbablyBlack(_ data: Data, referenceSize: Int) -> Bool {
        guard referenceSize > 0 else { return false }
        return data.count * 8 < referenceSize
    }
}

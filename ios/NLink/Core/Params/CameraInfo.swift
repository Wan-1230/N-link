//
//  CameraInfo.swift
//  N-Link
//
//  机身信息快照。逐字段对齐安卓 `CameraParameterManager.kt:1960`（CameraInfo）。
//
//  -1 / 空串 / nil 的语义各不相同，不要统一成「空」：
//   -  `batteryLevel == -1` 表示**没读到**（不是 0%）
//   -  `serialNumber == ""` 表示本条通道不提供（AP 引擎就没有），不代表机身没有
//   -  `shutterCountRange != nil` 表示多样本互验不一致，此时应报区间而不是挑一个数
//

import Foundation

enum ShutterCountState: String, Sendable {
    case none = "NONE"
    case querying = "QUERYING"
    case success = "SUCCESS"
    case failed = "FAILED"
}

enum ShutterFailReason: String, Sendable {
    case none = "NONE"
    case noMedia = "NO_MEDIA"
    case readFailed = "READ_FAILED"
    case parseFailed = "PARSE_FAILED"
    /// 本机解不出、云端能试，但用户尚未授权上传原片
    case consentRequired = "CONSENT_REQUIRED"

    /// 「原因 + 怎么办」。四种情形的下一步完全不同，不能合成一句。
    var guidance: String {
        switch self {
        case .none:
            return ""
        case .noMedia:
            return "存储卡里还没有照片，先拍一张再回来查。"
        case .readFailed:
            return "照片读不完整，多半是链路不稳。靠近路由器或改用有线后再试。"
        case .parseFailed:
            return "这张照片的快门计数格式本机不认识。可以在设置里授权云端解析，或换一张更新的照片。"
        case .consentRequired:
            return "本机解不出这份文件的快门计数。可在设置里授权云端解析（会上传整张原片到第三方服务）。"
        }
    }
}

enum ShutterCountSource: String, Sendable {
    case local = "LOCAL"
    case cloud = "CLOUD"

    var displayName: String { self == .local ? "机身 EXIF" : "云端解析" }
}

struct CameraInfo: Sendable, Equatable {
    /// 电量百分比；-1 = 未读到
    var batteryLevel: Int = -1
    /// 快门次数；-1 = 未读到
    var shutterCount: Int = -1
    var shutterCountSource: ShutterCountSource?
    /// 是否通过 `0x00A5 + 0x00A6 == 0x00A7` 恒等式校验。false = 仅数值合理，UI 需标注
    var shutterVerified: Bool = false
    var shutterFailReason: ShutterFailReason = .none
    /// 多样本不一致时的区间；nil = 各样本一致
    var shutterCountRange: ClosedRange<Int>?
    /// 仅机械快门触发次数（MakerNote 0x0037）；-1 = 机身未写
    var shutterMechanicalCount: Int = -1
    var storageFreeMb: Int64 = -1
    var storageTotalMb: Int64 = -1
    var storageDescription: String = ""
    var lensName: String = ""
    var firmwareVersion: String = ""
    var modelName: String = ""
    /// 机身序列号；空串 = 本条通道不提供
    var serialNumber: String = ""
    var shutterQueryState: ShutterCountState = .none
    var lensFocalMinMm: Double = 0
    var lensFocalMaxMm: Double = 0
    var lensMaxApAtMinFocalX100: Int = 0
    var lensMaxApAtMaxFocalX100: Int = 0
    var lensApertureRangeKnown: Bool = false

    // MARK: 展示辅助

    var batteryText: String {
        batteryLevel < 0 ? "未知" : "\(batteryLevel)%"
    }

    var batteryIsLow: Bool {
        batteryLevel >= 0 && batteryLevel <= 20
    }

    /// 快门次数展示：一致时报单值，不一致时报区间。
    ///
    /// **不挑一个数装精确** —— 快门计数不会倒退，两样本不等说明至少一张解错了。
    var shutterCountText: String {
        if let range = shutterCountRange, range.lowerBound != range.upperBound {
            return "\(range.lowerBound)–\(range.upperBound)"
        }
        return shutterCount < 0 ? "未知" : "\(shutterCount)"
    }

    var storageText: String {
        guard storageFreeMb >= 0, storageTotalMb > 0 else { return "未知" }
        let free = ByteCountFormatter.string(fromByteCount: storageFreeMb * 1_048_576, countStyle: .file)
        let total = ByteCountFormatter.string(fromByteCount: storageTotalMb * 1_048_576, countStyle: .file)
        return "可用 \(free) / 共 \(total)"
    }

    var storageFreeRatio: Double {
        guard storageTotalMb > 0, storageFreeMb >= 0 else { return 0 }
        return min(1, max(0, Double(storageFreeMb) / Double(storageTotalMb)))
    }

    var lensText: String {
        guard !lensName.isEmpty else { return "未知" }
        if lensFocalMinMm > 0, lensFocalMaxMm > 0, lensFocalMaxMm > lensFocalMinMm {
            let minText = Int(lensFocalMinMm.rounded())
            let maxText = Int(lensFocalMaxMm.rounded())
            return "\(lensName) · \(minText)–\(maxText)mm"
        }
        if lensFocalMinMm > 0 {
            return "\(lensName) · \(Int(lensFocalMinMm.rounded()))mm"
        }
        return lensName
    }
}

// MARK: - 拍摄预设

/// 一组曝光设置。安卓存 Gson JSON，iOS 用 Codable。
struct ShootingPreset: Identifiable, Codable, Sendable, Equatable {
    var id: String
    var name: String
    /// 光圈（f 值 ×100）
    var aperture: Int
    /// 快门（1/10000s）
    var shutterSpeed: Int
    var iso: Int
    /// 白平衡码（0x5005）
    var whiteBalance: Int
    /// 对焦模式码（0x500A）
    var focusMode: Int
    /// 测光模式码（0x500B）
    var meteringMode: Int

    /// 预设卡片副标题：「f/4 · 1/125 · ISO 100」
    var summary: String {
        "\(ApertureCatalog.format(aperture)) · \(ShutterCatalog.format(rawX10000: shutterSpeed)) · \(IsoCatalog.format(iso))"
    }

    /// 出厂四个预设：人像 / 风光 / 夜景 / 运动。
    static let defaults: [ShootingPreset] = [
        ShootingPreset(id: "portrait", name: "人像", aperture: 180, shutterSpeed: 200,
                       iso: 100, whiteBalance: 2, focusMode: 0x8010, meteringMode: 3),
        ShootingPreset(id: "landscape", name: "风光", aperture: 800, shutterSpeed: 125,
                       iso: 100, whiteBalance: 4, focusMode: 0x8010, meteringMode: 3),
        ShootingPreset(id: "night", name: "夜景", aperture: 280, shutterSpeed: 10,
                       iso: 3200, whiteBalance: 6, focusMode: 1, meteringMode: 4),
        ShootingPreset(id: "sport", name: "运动", aperture: 400, shutterSpeed: 1000,
                       iso: 800, whiteBalance: 2, focusMode: 0x8011, meteringMode: 3)
    ]

    static func custom(from param: PresetSnapshot) -> ShootingPreset {
        ShootingPreset(
            id: "custom_\(Int(Date().timeIntervalSince1970 * 1000))",
            name: "自定义",
            aperture: param.aperture,
            shutterSpeed: param.shutterSpeed,
            iso: param.iso,
            whiteBalance: param.whiteBalance,
            focusMode: param.focusMode,
            meteringMode: param.meteringMode
        )
    }
}

/// 建自定义预设时的当前值快照
struct PresetSnapshot: Sendable, Equatable {
    var aperture: Int = 0
    var shutterSpeed: Int = 0
    var iso: Int = 0
    var whiteBalance: Int = 0
    var focusMode: Int = 0
    var meteringMode: Int = 0
}

// MARK: - 预设仓库

@MainActor
final class PresetStore: ObservableObject {

    @Published private(set) var presets: [ShootingPreset] = []

    private let key = "presets_json"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        load()
    }

    func add(_ preset: ShootingPreset) {
        presets.append(preset)
        persist()
    }

    func update(_ preset: ShootingPreset) {
        guard let index = presets.firstIndex(where: { $0.id == preset.id }) else { return }
        presets[index] = preset
        persist()
    }

    func remove(id: String) {
        presets.removeAll { $0.id == id }
        persist()
    }

    private func load() {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([ShootingPreset].self, from: data),
              !decoded.isEmpty else {
            presets = ShootingPreset.defaults
            return
        }
        presets = decoded
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(presets) else { return }
        defaults.set(data, forKey: key)
    }
}

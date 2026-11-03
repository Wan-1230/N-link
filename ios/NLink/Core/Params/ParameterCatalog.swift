//
//  ParameterCatalog.swift
//  N-Link
//
//  曝光参数的「值 ↔ 显示名」唯一真源。
//  逐条对齐安卓 `camera/params/CameraParameterManager.kt` 的 ShutterCatalog /
//  ApertureCatalog / IsoCatalog 与各 read* 方法的映射表。
//
//  为什么快门必须查表而不是反算：raw 是 1/10000s 定点数，1/160 的精确值是 62.5，
//  取整存 60 → 反算得 10000/60 = 1/167，而机身根本没有 1/167 这一档。
//  安卓为此写了一整段表格注释（CameraParameterManager.kt:1540-1556），照抄。
//

import Foundation

// MARK: - 曝光参数值

/// 一个可滚动的参数档。
struct CameraParam: Sendable, Equatable {
    /// 显示名（`f/2.8`、`1/250`、`ISO 800`、`M (手动)`）
    var currentValue: String = "--"
    /// 可选档位的显示名（滚轮用）
    var availableValues: [String] = []
    /// 与 `availableValues` 一一对应的协议原始值。
    ///
    /// 必须有这一列：显示名是给人看的（`f/2.8`、`1/125`、`M (手动)`），
    /// 写回机身要的是 `280`、`80`、`1`。靠解析显示名反推原始值会在
    /// 快门分数（1/2.5 ↔ 3/2）与「未知(n)」上翻车，所以这里直接存一份。
    var availableRaw: [Int] = []
    /// 协议原始值（写回与滚轮定位用）
    var rawValue: Int = 0

    static let empty = CameraParam()

    /// 当前值在可选列表中的下标。缺失时返回 0（不猜），供滚轮定位。
    var currentIndex: Int {
        if let index = availableRaw.firstIndex(of: rawValue) { return index }
        if let index = availableValues.firstIndex(of: currentValue) { return index }
        return 0
    }
}

// MARK: - 快门

enum ShutterCatalog {

    struct Stop: Sendable, Equatable {
        let raw: Int
        let label: String
    }

    /// 不足 1 秒的 33 档。raw 序列即机身真实档位（升序 = 快 → 慢）。
    static let fractionStops: [Stop] = [
        // 高速段（1/4000 → 1/1250）
        Stop(raw: 2, label: "1/4000"), Stop(raw: 3, label: "1/3200"), Stop(raw: 4, label: "1/2500"),
        Stop(raw: 5, label: "1/2000"), Stop(raw: 6, label: "1/1600"), Stop(raw: 8, label: "1/1250"),
        // 常用段（1/1000 → 1/2.5）
        Stop(raw: 10, label: "1/1000"), Stop(raw: 13, label: "1/800"), Stop(raw: 15, label: "1/640"),
        Stop(raw: 20, label: "1/500"), Stop(raw: 25, label: "1/400"), Stop(raw: 30, label: "1/320"),
        Stop(raw: 40, label: "1/250"), Stop(raw: 50, label: "1/200"), Stop(raw: 60, label: "1/160"),
        Stop(raw: 80, label: "1/125"), Stop(raw: 100, label: "1/100"), Stop(raw: 125, label: "1/80"),
        Stop(raw: 160, label: "1/60"), Stop(raw: 200, label: "1/50"), Stop(raw: 250, label: "1/40"),
        Stop(raw: 320, label: "1/30"), Stop(raw: 400, label: "1/25"), Stop(raw: 500, label: "1/20"),
        Stop(raw: 640, label: "1/15"), Stop(raw: 800, label: "1/13"), Stop(raw: 1000, label: "1/10"),
        Stop(raw: 1250, label: "1/8"), Stop(raw: 1600, label: "1/6"), Stop(raw: 2000, label: "1/5"),
        Stop(raw: 2500, label: "1/4"), Stop(raw: 3200, label: "1/3"), Stop(raw: 4000, label: "1/2.5")
    ]

    /// 长曝光段（0.5s → 30s，1/3 档步进）。≥1s 的数值本身就是标称值。
    static let longExposureRaw: [Int] = [
        5000, 6250, 8000, 10000, 13000, 16000, 20000, 25000, 32000, 40000,
        50000, 64000, 80000, 100000, 130000, 160000, 200000, 250000, 300000
    ]

    /// 全部档位（快 → 慢）。`setShutterSpeed` 的限位取首尾：1/4000s → 30s。
    static let stops: [Stop] = fractionStops + longExposureRaw.map { Stop(raw: $0, label: formatNumeric($0)) }

    static let values: [Int] = stops.map(\.raw)

    private static let labelByRaw: [Int: String] = Dictionary(
        uniqueKeysWithValues: fractionStops.map { ($0.raw, $0.label) }
    )

    /// 每个档位「按标称名还原的真实秒数」。
    ///
    /// 高速档 raw 是取整值（1/4000 存 2，真值 2.5），用 raw 做距离匹配会把 1/4000
    /// 错配到 1/3200，所以匹配必须走这张表。
    private static let trueSecondsByStop: [(raw: Int, seconds: Double)] = stops.map { stop in
        let fromLabel: Double?
        if stop.label.hasPrefix("1/") {
            fromLabel = Double(stop.label.dropFirst(2)).map { 1.0 / $0 }
        } else if stop.label.hasSuffix("s") {
            fromLabel = Double(stop.label.dropLast())
        } else {
            fromLabel = nil
        }
        return (stop.raw, fromLabel ?? Double(stop.raw) / 10000.0)
    }

    static let maxRawX10000 = 600_000
    static let minExposureSeconds = 1.0 / 8000.0
    static let maxExposureSeconds = 30.0

    // MARK: 显示

    static func format(rawX10000: Int) -> String {
        guard rawX10000 > 0 else { return "--" }
        if let label = labelByRaw[rawX10000] { return label }
        return formatNumeric(rawX10000)
    }

    /// 表外 raw 的数值兜底：≥1s 显示秒数，<1s 显示分数。
    static func formatNumeric(_ rawX10000: Int) -> String {
        let seconds = Double(rawX10000) / 10000.0
        if seconds >= 1.0 { return formatSeconds(seconds) }
        return "1/\(formatDenominator(1.0 / seconds))"
    }

    /// 整数秒不带小数位（`1s` / `30s`），其余保留一位（`1.3s`）。
    static func formatSeconds(_ seconds: Double) -> String {
        var text = String(format: "%.1f", seconds)
        if text.hasSuffix(".0") { text.removeLast(2) }
        return "\(text)s"
    }

    /// 分母 ≥10 取整（`1/769.2` 这种读数没有意义）；<10 保留一位。
    static func formatDenominator(_ denominator: Double) -> String {
        if denominator >= 10 { return String(Int(denominator.rounded())) }
        var text = String(format: "%.1f", denominator)
        if text.hasSuffix(".0") { text.removeLast(2) }
        return text
    }

    // MARK: 解析

    /// 用户输入的快门速度 → 秒。非法返回 nil。
    ///
    /// 支持 `1/250`、`1 / 250`、全角斜杠、`60s`、`30秒`；纯整数且 >30 视为分母
    /// （本机最长曝光 30s，因此 >30 的整数不可能是秒数）。
    static func parseSeconds(_ input: String?) -> Double? {
        guard var text = input?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !text.isEmpty else {
            return nil
        }
        text = text.replacingOccurrences(of: "／", with: "/")
        text = text.replacingOccurrences(of: " ", with: "")
        let hasSecondsSuffix = text.hasSuffix("s") || text.hasSuffix("秒")
        if hasSecondsSuffix { text.removeLast() }

        let seconds: Double
        if text.contains("/") {
            let parts = text.split(separator: "/")
            guard parts.count == 2,
                  let numerator = Double(parts[0]),
                  let denominator = Double(parts[1]),
                  numerator > 0, denominator > 0 else { return nil }
            seconds = numerator / denominator
        } else {
            guard let value = Double(text) else { return nil }
            let isInteger = value.truncatingRemainder(dividingBy: 1) == 0
            if !hasSecondsSuffix && value > maxExposureSeconds && isInteger {
                seconds = 1.0 / value
            } else {
                seconds = value
            }
        }
        guard seconds.isFinite, seconds > 0,
              seconds >= minExposureSeconds, seconds <= maxExposureSeconds else { return nil }
        return seconds
    }

    /// 按真实秒数匹配最接近的机身标称档。机身回读值一律走这里。
    static func matchBySeconds(_ seconds: Double) -> Int {
        guard seconds > 0 else { return values.first ?? 0 }
        let target = log(seconds)
        return trueSecondsByStop.min { abs(log($0.seconds) - target) < abs(log($1.seconds) - target) }?.raw
            ?? values.first ?? 0
    }

    /// 按 raw 对数距离匹配（表外值兜底用；高速档请用 `matchBySeconds`）。
    static func matchNearest(_ seconds: Double) -> Int {
        guard !values.isEmpty else { return 0 }
        let target = log(seconds)
        return values.min { abs(log(Double($0) / 10000.0) - target) < abs(log(Double($1) / 10000.0) - target) }
            ?? values.first!
    }

    /// 按机身标称名精确匹配（`1/4000` 这类高速档只有按名字才能命中）。
    static func matchByLabel(_ input: String?) -> Int? {
        guard var text = input?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() else { return nil }
        text = text.replacingOccurrences(of: "／", with: "/").replacingOccurrences(of: " ", with: "")
        guard !text.isEmpty else { return nil }
        return stops.first { $0.label.lowercased() == text }?.raw
    }

    /// 0x500D 的 raw 是否有效（1/10000s）。0xFFFFFFFF（B 门）与哨兵值都无效。
    static func isValidExposureTime(_ rawX10000: Int) -> Bool {
        rawX10000 >= 1 && rawX10000 <= maxRawX10000
    }

    /// 尼康 0xD100 → 秒数。**高 16 位分子 / 低 16 位分母**，与 0x500D 的定点数完全不同。
    static func parseNikonShutterSpeed(_ raw: UInt32) -> Double? {
        guard raw > 0, raw != 0xFFFF_FFFF else { return nil }
        let numerator = Double((raw >> 16) & 0xFFFF)
        let denominator = Double(raw & 0xFFFF)
        guard numerator > 0, denominator > 0 else { return nil }
        let seconds = numerator / denominator
        return (seconds >= 1.0 / 8000.0 && seconds <= 64.0) ? seconds : nil
    }

    /// 档位 raw → 0xD100 打包分数。由标称名还原精确分数（1/2.5 → 2/5 而不是四舍五入）。
    static func packedNikonShutterSpeed(_ rawX10000: Int) -> UInt32? {
        let label = labelByRaw[rawX10000] ?? formatNumeric(rawX10000)
        let seconds: Double
        if label.hasSuffix("s") {
            guard let v = Double(label.dropLast()), v > 0 else { return nil }
            seconds = v
        } else if label.hasPrefix("1/") {
            guard let d = Double(label.dropFirst(2)), d > 0 else { return nil }
            seconds = 1.0 / d
        } else {
            return nil
        }
        guard let fraction = fractionOf(seconds),
              fraction.numerator > 0, fraction.numerator <= 0xFFFF,
              fraction.denominator > 0, fraction.denominator <= 0xFFFF else { return nil }
        return (UInt32(fraction.numerator) << 16) | UInt32(fraction.denominator)
    }

    /// 秒数 → 最简整数分数。分母从 1 往上试，找相对误差最小的；误差 <0.1% 才算可表示。
    private static func fractionOf(_ seconds: Double) -> (numerator: Int, denominator: Int)? {
        guard seconds > 0 else { return nil }
        var best: (Int, Int)?
        var bestError = Double.greatestFiniteMagnitude
        for denominator in 1...0xFFFF {
            let numerator = Int((seconds * Double(denominator)).rounded())
            guard numerator > 0, numerator <= 0xFFFF else { continue }
            let error = abs(Double(numerator) / Double(denominator) - seconds) / seconds
            if error < bestError {
                bestError = error
                best = (numerator, denominator)
            }
            if error < 1e-9 { break }
        }
        return bestError < 0.001 ? best : nil
    }
}

// MARK: - 光圈

enum ApertureCatalog {

    /// 镜头信息未知时的通用档位 f/1.4 – f/22
    static let fallback: [Int] = [140, 180, 200, 280, 350, 400, 560, 800, 1100, 1600, 2200]
    static let defaultMinApertureX100 = 2200

    static func format(_ fStopX100: Int) -> String {
        guard fStopX100 > 0 else { return "--" }
        return "f/\(formatNumber(fStopX100))"
    }

    static func formatNumber(_ fStopX100: Int) -> String {
        var text = String(format: "%.1f", Double(fStopX100) / 100.0)
        if text.hasSuffix(".0") { text.removeLast(2) }
        return text
    }

    /// 当前焦距下镜头的最大光圈（变焦镜头按焦距线性插值）。
    /// 焦距未知时保守取**较大 f 值**的一侧，避免给出镜头不支持的档位。
    static func maxApertureAt(_ info: CameraInfo, currentFocalMm: Double?) -> Int {
        let wide = info.lensMaxApAtMinFocalX100
        let tele = info.lensMaxApAtMaxFocalX100
        let focalMin = info.lensFocalMinMm
        let focalMax = info.lensFocalMaxMm
        guard let focal = currentFocalMm, focalMin > 0, focalMax > focalMin else {
            return max(wide, tele)
        }
        let ratio = min(1, max(0, (focal - focalMin) / (focalMax - focalMin)))
        return Int((Double(wide) + Double(tele - wide) * ratio).rounded())
    }

    static func clampToNearest(_ fStopX100: Int, options: [Int]) -> Int {
        guard !options.isEmpty else { return fStopX100 }
        return options.min { abs($0 - fStopX100) < abs($1 - fStopX100) } ?? fStopX100
    }

    static func parseFStop(_ input: String?) -> Int? {
        guard var text = input?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !text.isEmpty else {
            return nil
        }
        text = text.replacingOccurrences(of: "／", with: "/").replacingOccurrences(of: " ", with: "")
        if text.hasPrefix("f/") { text.removeFirst(2) }
        else if text.hasPrefix("f") { text.removeFirst() }
        if text.hasPrefix("/") { text.removeFirst() }
        guard let value = Double(text), value >= 1.0, value <= 64.0 else { return nil }
        return Int((value * 100).rounded())
    }
}

// MARK: - ISO

enum IsoCatalog {

    static let autoRaw = 0xFFFF
    static let lowRaw = 0xFFFE

    static func parse(_ input: String?) -> Int? {
        guard var text = input?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(), !text.isEmpty else {
            return nil
        }
        if text == "auto" || text == "自动" || text == "isoauto" { return autoRaw }
        if text.hasPrefix("iso") { text.removeFirst(3) }
        text = text.replacingOccurrences(of: " ", with: "")
        guard let value = Int(text), value >= 50, value <= 819_200 else { return nil }
        return value
    }

    /// 对数距离匹配：ISO 是等比数列，线性差会让高感端永远匹配到同一档。
    static func matchNearest(_ iso: Int, options: [Int]) -> Int {
        guard iso != autoRaw, !options.isEmpty, iso > 0 else { return iso }
        let target = log(Double(iso))
        return options.min { abs(log(Double($0)) - target) < abs(log(Double($1)) - target) } ?? iso
    }

    static func format(_ raw: Int) -> String {
        switch raw {
        case autoRaw: return "Auto"
        case lowRaw: return "Lo"
        default: return "ISO \(raw)"
        }
    }
}

// MARK: - 滚轮定位

/// 滚轮当前值下标。优先精确匹配 raw；其次取第一个不小于当前值的档。
/// 不用显示字符串反查——「当前值」与「滚轮刻度」口径不一致时恒失败并跳回首项。
func resolvePickerIndex(_ rawValues: [Int], currentRaw: Int) -> Int {
    guard !rawValues.isEmpty else { return 0 }
    if let exact = rawValues.firstIndex(of: currentRaw) { return exact }
    if let next = rawValues.firstIndex(where: { $0 >= currentRaw }) { return next }
    return rawValues.count - 1
}

//
//  WifiEndpoint.swift
//  NLink
//
//  相机网络端点（IPv4 + TCP 端口）的解析与归一化。
//  对应安卓 `com/nikonlink/app/device/wifi/WifiEndpoint.kt`。
//
//  为什么值得一个完整的归一化器：安卓端真机日志出现
//  `192.168.031.065` 连续 55 次 `phase=socket`、一次 `phase=init` 都没有，
//  用户端只看到「一直在转圈」。归一化 + 校验的价值不在"纠正"，而在
//  **把 192.168.1.256 / 192.168.1 / abc 当场挡掉并明确报错**，
//  而不是静默重试五分钟。
//
//  归一化规则（与安卓逐条一致）：
//   1. 去两端空白 → 去 `wifi:` 前缀 → 去 IPv6 方括号
//   2. 按 `.` 拆成恰好 4 段，每段 1~3 位纯数字
//   3. 逐段去前导零（031→31、065→65、000→0），再校验 0..255
//   4. **用归一化后的段重新拼接**，绝不复用原始字符串
//   5. 端口按 1..65535 校验，缺省 15740
//

import Foundation

struct WifiEndpoint: Hashable, Sendable {
    let host: String
    let port: Int

    init(host: String, port: Int = Ptp.defaultPtpIpPort) {
        self.host = host
        self.port = port
    }

    /// 归一化后的可读地址；默认端口时省略 `:port`。
    var display: String { port == Ptp.defaultPtpIpPort ? host : "\(host):\(port)" }

    /// 与既有持久化格式兼容的地址串。
    var address: String { "wifi:\(host):\(port)" }

    // MARK: - 解析

    /// 解析任意来源的相机地址串，失败返回 nil。
    ///
    /// 接受：`1.2.3.4` / `1.2.3.4:15740` / `wifi:1.2.3.4:15740` / `[1.2.3.4]:15740`
    static func parse(_ raw: String?, defaultPort: Int = Ptp.defaultPtpIpPort) -> WifiEndpoint? {
        guard var text = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
            return nil
        }
        if text.lowercased().hasPrefix("wifi:") {
            text = String(text.dropFirst("wifi:".count)).trimmingCharacters(in: .whitespaces)
        }
        guard !text.isEmpty else { return nil }

        let hostPart: String
        let portPart: String?

        if text.hasPrefix("["), let bracketEnd = text.lastIndex(of: "]") {
            hostPart = String(text[text.index(after: text.startIndex)..<bracketEnd])
            let rest = String(text[text.index(after: bracketEnd)...])
            portPart = rest.hasPrefix(":") ? String(rest.dropFirst()) : nil
        } else if let colon = text.lastIndex(of: ":"),
                  !text[text.index(after: colon)...].contains(":") {
            hostPart = String(text[..<colon])
            portPart = String(text[text.index(after: colon)...])
        } else {
            hostPart = text
            portPart = nil
        }

        guard let host = normalizeIPv4(hostPart) else { return nil }
        let port: Int
        if let portPart, !portPart.trimmingCharacters(in: .whitespaces).isEmpty {
            guard let value = Int(portPart.trimmingCharacters(in: .whitespaces)), (1...65535).contains(value) else {
                return nil
            }
            port = value
        } else {
            port = defaultPort
        }
        return WifiEndpoint(host: host, port: port)
    }

    /// 仅判断 host 是否是合法（且可归一化）的 IPv4 字面量。用于输入框即时校验。
    static func isValidHost(_ host: String?) -> Bool {
        guard let host else { return false }
        return normalizeIPv4(host) != nil
    }

    /// 判断 host 值不值得作为相机候选去探测（比 `isValidHost` 严）。
    ///
    /// 排除：`0.0.0.0/8`（本机/未指定）、`127.0.0.0/8`（回环）、
    /// `169.254.0.0/16`（DHCP 失败自分配的链路本地，无可用路由）、
    /// `224.0.0.0/4`（组播，含 mDNS 的 224.0.0.251）、`240.0.0.0/4`（保留 + 广播）。
    static func isProbeCandidate(_ host: String?) -> Bool {
        guard let host, let normalized = normalizeIPv4(host) else { return false }
        let octets = normalized.split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4 else { return false }
        let a = octets[0], b = octets[1]
        if a == 0 || a == 127 { return false }
        if a == 169 && b == 254 { return false }
        if (224...239).contains(a) { return false }
        if a >= 240 { return false }
        return true
    }

    /// 点分十进制归一化。**返回值一定是重新拼接的字符串**，
    /// 调用方可以直接把它交给 socket API。
    private static func normalizeIPv4(_ host: String) -> String? {
        let parts = host.trimmingCharacters(in: .whitespaces).split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var octets: [Int] = []
        octets.reserveCapacity(4)
        for part in parts {
            guard !part.isEmpty, part.count <= 3, part.allSatisfy({ $0.isASCII && $0.isNumber }) else {
                return nil
            }
            let trimmed = String(part.drop(while: { $0 == "0" }))
            let literal = trimmed.isEmpty ? "0" : trimmed
            guard let value = Int(literal), value <= 255 else { return nil }
            octets.append(value)
        }
        return octets.map(String.init).joined(separator: ".")
    }
}

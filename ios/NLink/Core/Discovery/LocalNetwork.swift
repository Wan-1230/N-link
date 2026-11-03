//
//  LocalNetwork.swift
//  NLink
//
//  本机 IPv4 网段枚举（getifaddrs）。
//  对应安卓 `LocalNetworkInterfaceResolver` + `WifiScanner.currentIpv4Addresses()`。
//
//  iOS 差异记录（详见 docs/iOS-差异处理.md）：
//  - Android 有 `ConnectivityManager`（只看得到客户端 WiFi，**看不到热点/Tethering 接口**），
//    因此安卓端必须再用 `NetworkInterface.getNetworkInterfaces()` 补内核枚举。
//  - iOS 的 NetworkExtension 热点接口（`ap0`/`bridge100`）同样不在常规接口列表里，
//    用 getifaddrs 也拿不到宿主的客户端列表 —— 这是平台限制，不是实现缺陷。
//    因此 iOS 侧**不做「手机开热点让相机连」的反向模式**，只支持
//    「手机连相机热点(AP)」与「手机与相机同一 WiFi(STA)」两种，这与相机本身的
//    工作模式一致（尼康机身的无线功能就是这两种）。
//

import Darwin
import Foundation

/// 本机的一个 IPv4 接口地址。
struct LocalIPv4Address: Sendable, Hashable {
    let interface: String
    let address: String
    let prefixLength: Int

    /// `192.168.1.23/24`
    var subnetText: String { "\(address)/\(prefixLength)" }

    /// 网段基址（如 192.168.1.0）
    ///
    /// 这些工具函数在 `LocalNetwork` 上是 `static`，不是本结构的成员——
    /// 所以这里必须写 `LocalNetwork.` 而不是 `Self.`（`Self` 指向 `LocalIPv4Address`）。
    var networkBase: String {
        guard let ip = LocalNetwork.ipToUInt32(address) else { return address }
        let mask = LocalNetwork.mask(forPrefix: prefixLength)
        return LocalNetwork.uint32ToIp(ip & mask)
    }
}

enum LocalNetwork {

    /// 枚举所有已启用、非回环、有 IPv4 地址的接口。
    static func ipv4Addresses() -> [LocalIPv4Address] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }

        var out: [LocalIPv4Address] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard (flags & IFF_UP) != 0, (flags & IFF_LOOPBACK) == 0 else { continue }
            guard let addr = entry.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            guard let mask = entry.pointee.ifa_netmask else { continue }

            let host = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { ptr -> String in
                var raw = ptr.pointee.sin_addr
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                inet_ntop(AF_INET, &raw, &buffer, socklen_t(INET_ADDRSTRLEN))
                return String(cString: buffer)
            }
            let prefix = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { ptr -> Int in
                let value = UInt32(bigEndian: ptr.pointee.sin_addr.s_addr)
                return value.nonzeroBitCount
            }
            let name = String(cString: entry.pointee.ifa_name)
            // 169.254.x.x 是 DHCP 失败时系统自分配的链路本地地址：没有任何可用路由，
            // 探测必然超时，白白占扫描配额。安卓端在候选层挡，这里在枚举层就挡掉。
            guard !host.hasPrefix("169.254."), WifiEndpoint.isProbeCandidate(host) else { continue }
            out.append(LocalIPv4Address(interface: name, address: host, prefixLength: prefix))
        }
        return out
    }

    /// 计划探测的主机列表（不含自身）。
    ///
    /// `prefix < 24` 时只扫前 1024 个地址控制耗时（与安卓 `subnetHosts` 同口径）；
    /// 否则扫 `.1 ... .254`。
    static func subnetHosts(_ local: LocalIPv4Address) -> [String] {
        guard let ip = ipToUInt32(local.address) else { return [] }
        let mask = mask(forPrefix: local.prefixLength)
        let base = ip & mask
        let prefix = min(max(local.prefixLength, 0), 32)
        if prefix < 24 {
            return (1...1024).map { uint32ToIp(base &+ UInt32($0)) }
        }
        return (1..<255).map { uint32ToIp(base | UInt32($0)) }
    }

    /// 相机 AP/STA 常见网关地址：即便当前网段判定有偏差，也做一次轻量探测。
    static let knownGatewayHosts = [
        "192.168.1.1", "192.168.0.1", "10.0.0.1", "192.168.42.1",
        "192.168.31.1", "192.168.2.1", "192.168.10.1", "192.168.50.1",
        "192.168.137.1", "172.16.0.1", "10.0.1.1"
    ]

    /// 判断某个 host 是否已落在本机某个接口的子网内（AP 通道的路由就绪门用）。
    static func isOnLocalSubnet(host: String, addresses: [LocalIPv4Address]? = nil) -> Bool {
        guard let target = ipToUInt32(host) else { return false }
        let locals = addresses ?? ipv4Addresses()
        return locals.contains { local in
            guard let ip = ipToUInt32(local.address) else { return false }
            let mask = mask(forPrefix: local.prefixLength)
            return (ip & mask) == (target & mask)
        }
    }

    // MARK: - 数值工具

    static func ipToUInt32(_ ip: String) -> UInt32? {
        let parts = ip.split(separator: ".").compactMap { UInt32($0) }
        guard parts.count == 4, parts.allSatisfy({ $0 <= 255 }) else { return nil }
        return (parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]
    }

    static func uint32ToIp(_ value: UInt32) -> String {
        "\((value >> 24) & 0xFF).\((value >> 16) & 0xFF).\((value >> 8) & 0xFF).\(value & 0xFF)"
    }

    static func mask(forPrefix prefix: Int) -> UInt32 {
        let p = min(max(prefix, 0), 32)
        return p == 0 ? 0 : UInt32.max << (32 - UInt32(p))
    }
}

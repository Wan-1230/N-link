//
//  CameraDiscovery.swift
//  NLink
//
//  WiFi 相机发现（对应安卓 `WifiScanner.kt` 的四条并行路径）。
//
//  iOS 差异（详见 docs/iOS-差异处理.md）：
//  | 安卓路径 | iOS 做法 | 理由 |
//  |---|---|---|
//  | 裸 MulticastSocket 收 mDNS | `NWBrowser`（Bonjour）| iOS 上裸 UDP 组播收发需要
//  |  |  | `com.apple.developer.networking.multicast` 特批 entitlement；
//  |  |  | NWBrowser 走系统 mDNSResponder，无需特批且自带重发 |
//  | NsdManager 兜底 | 并入同上一条 | iOS 没有独立的第二套 NSD 服务 |
//  | /proc/net/arp | 移除 | iOS 没有 ARP 表可读，也无等价物；由「网段扫描 + Bonjour」覆盖 |
//  | 网段扫描 | 保留 | 路由器过滤组播时它是唯一出路 |
//
//  两条真实存在的多相机场景都靠「网段扫描」兜住：相机热点下 Bonjour 常被 AP 隔离丢弃，
//  同一 WiFi 下部分路由器不转发组播。所以网段扫描不是兜底，是主力之一。
//

import Foundation
import Network

/// 发现到的相机候选。
struct CameraCandidate: Identifiable, Hashable, Sendable {
    enum Source: String, Sendable {
        case bonjour = "Bonjour"
        case subnet = "网段扫描"
    }

    let ipAddress: String
    let port: Int
    let name: String
    let source: Source
    /// 端口开但没回 PTP 握手：大概率是休眠中的相机，仍要展示
    let needsWake: Bool

    var id: String { "\(ipAddress):\(port)" }
    var endpoint: CameraEndpoint {
        CameraEndpoint(displayName: name, host: ipAddress, port: port, service: source.rawValue)
    }

    /// 安卓端同一常量：通用占位名。去重时优先保留有真名的条目。
    static let genericName = "尼康相机"
    static let wakeName = "尼康相机(待唤醒)"
}

/// 一次扫描的诊断结果。安卓端 `ScanStats` 的对应物 —— 失败原因必须可回答，
/// 否则用户只能看到「搜不到」。
struct DiscoveryDiagnostics: Sendable {
    var elapsed: TimeInterval = 0
    var localSubnets: [String] = []
    var plannedHosts = 0
    var probedHosts = 0
    var bonjourHits = 0
    var probeOk = 0
    var probeWake = 0
    var gateReason: String?
    var secondWave = false

    var summary: String {
        if let gateReason { return gateReason }
        return "子网 \(localSubnets.joined(separator: "、"))｜探测 \(probedHosts)/\(plannedHosts)"
    }
}

@MainActor
final class CameraDiscovery: ObservableObject {

    /// 总扫描窗口。安卓 B4 从 12s 提到 18s：给路由器过滤组播 / 客户端隔离更多机会。
    nonisolated static let scanTimeout: TimeInterval = 18
    /// 单主机盲扫超时（要控制 254 个地址的总耗时）
    nonisolated static let sweepTimeoutMs = 800
    /// 候选确认 / 第二轮补扫的超时（覆盖相机从休眠唤醒的时延）
    nonisolated static let confirmTimeoutMs = 2_500
    /// 并发上限
    nonisolated static let maxConcurrency = 32
    /// 等 WiFi 拿到 IPv4 的上限；等不到也要给出明确原因而不是静默返回
    nonisolated static let routeReadyWait: TimeInterval = 6

    @Published private(set) var candidates: [CameraCandidate] = []
    @Published private(set) var isScanning = false
    @Published private(set) var diagnostics = DiscoveryDiagnostics()

    private var bonjourBrowsers: [NWBrowser] = []
    private var bonjourHits: [String: (ip: String, port: Int, name: String)] = [:]
    private var scanTask: Task<Void, Never>?

    // MARK: - 扫描

    func scan(timeout: TimeInterval = CameraDiscovery.scanTimeout) async -> [CameraCandidate] {
        cancel()
        isScanning = true
        candidates = []
        bonjourHits = [:]
        let startedAt = Clock.now()
        var diag = DiscoveryDiagnostics()
        defer { isScanning = false }

        // ── 路由就绪门 ──────────────────────────────────────────────
        // 旧实现在拿不到本机 IPv4 时直接返回，整条网段扫描静默消失，
        // 用户看到的就是「完全搜不到相机」。这里最多等 routeReadyWait，
        // 等不到也要把原因写进诊断。
        var subnets = LocalNetwork.ipv4Addresses()
        if subnets.isEmpty {
            let deadline = Clock.now() + Self.routeReadyWait
            while Clock.now() < deadline, subnets.isEmpty {
                // Clock.sleep 会因 Task 取消而抛出；扫描被取消就正常退出这个等待循环，
                // 不把 CancellationError 当成「拿不到 IP」。
                do { try await Clock.sleep(ms: 300) } catch { break }
                subnets = LocalNetwork.ipv4Addresses()
            }
        }
        diag.localSubnets = subnets.map(\.subnetText)
        if subnets.isEmpty { diag.gateReason = "未连上 Wi-Fi 或尚未拿到 IP。请先连接相机热点 / 同一 Wi-Fi 再扫描。" }
        diag.elapsed = Clock.now() - startedAt

        guard !subnets.isEmpty else {
            diagnostics = diag
            return []
        }

        // ── 三条路径并行（Bonjour + 网段盲扫 + 已知网关）──────────────
        startBonjour()
        var found = await sweep(subnets: subnets, timeout: timeout, diag: &diag)
        stopBonjour()

        // Bonjour 命中并集（命中的名字比「尼康相机」更好认）
        for (key, hit) in bonjourHits {
            found.append(
                CameraCandidate(
                    ipAddress: hit.ip,
                    port: hit.port,
                    name: hit.name.isEmpty ? CameraCandidate.genericName : hit.name,
                    source: .bonjour,
                    needsWake: false
                )
            )
            _ = key
        }
        diag.bonjourHits = bonjourHits.count

        // ── 第二轮补扫 ────────────────────────────────────────────
        // 第一轮用 800ms 短超时压缩总耗时；一条候选都没出时，用 2.5s 长超时
        // 再扫一遍 —— 覆盖「相机刚从休眠唤醒」，这时第一轮必然全部落空。
        if found.isEmpty {
            do { try await Clock.sleep(ms: 600) } catch { return [] }
            diag.secondWave = true
            var secondDiag = DiscoveryDiagnostics()
            found = await sweep(subnets: subnets, timeout: timeout, diag: &secondDiag, timeoutMs: Self.confirmTimeoutMs)
            diag.probeOk += secondDiag.probeOk
            diag.probeWake += secondDiag.probeWake
            diag.probedHosts += secondDiag.probedHosts
        }

        diag.elapsed = Clock.now() - startedAt

        // ── 去重：同一台相机可能同时被 Bonjour 与网段扫描命中 ──────────
        // 按 IP 分组，优先保留「有真名」的条目（安卓同款规则）。
        var byIp: [String: CameraCandidate] = [:]
        for candidate in found {
            if let existing = byIp[candidate.ipAddress] {
                let existingReal = existing.name != CameraCandidate.genericName
                    && existing.name != CameraCandidate.wakeName
                let newReal = candidate.name != CameraCandidate.genericName
                    && candidate.name != CameraCandidate.wakeName
                if !existingReal && newReal { byIp[candidate.ipAddress] = candidate }
                if existing.needsWake && !candidate.needsWake { byIp[candidate.ipAddress] = candidate }
                continue
            }
            byIp[candidate.ipAddress] = candidate
        }

        let result = byIp.values
            .sorted { lhs, rhs in
                if lhs.needsWake != rhs.needsWake { return !lhs.needsWake }
                return lhs.name < rhs.name
            }
        candidates = result
        diagnostics = diag
        return result
    }

    func cancel() {
        scanTask?.cancel()
        scanTask = nil
        stopBonjour()
        isScanning = false
    }

    // MARK: - Bonjour

    private func startBonjour() {
        // 双服务类型并行：`_nikon._tcp`（厂商）+ `_ptp._tcp`（标准）。
        // 基准实现实证：部分机身只广播前者，只盯 `_ptp._tcp` 会漏机。
        for type in ["_ptp._tcp", "_nikon._tcp"] {
            let params = NWParameters.tcp
            params.includePeerToPeer = true
            let browser = NWBrowser(for: .bonjour(type: type, domain: nil), using: params)
            browser.stateUpdateHandler = { _ in }
            // `browseResultsChangedHandler` 在 NWBrowser 的内部队列上回调（不是主队列），
            // 而 `resolve` 是 @MainActor 隔离的。显式跳一次主 actor，别依赖「运行时恰好是主线程」。
            browser.browseResultsChangedHandler = { [weak self] results, _ in
                guard let self else { return }
                Task { @MainActor in
                    for result in results {
                        self.resolve(result)
                    }
                }
            }
            browser.start(queue: .main)
            bonjourBrowsers.append(browser)
        }
    }

    private func resolve(_ result: NWBrowser.Result) {
        guard case let .service(name, _, _, _) = result.endpoint else { return }
        // NWBrowser 的 endpoint 是服务名；用 NWConnection 触发解析拿到主机与端口。
        // 这里只做一次短命连接（不发数据），拿到 endpoint 后立即取消。
        let connection = NWConnection(to: result.endpoint, using: .tcp)
        connection.stateUpdateHandler = { [weak self] state in
            // `stateUpdateHandler` 是 @Sendable 闭包：既不能直接改 @MainActor 属性
            // `bonjourHits`，也不能在其中同步调用 @MainActor 的 `cleanServiceName`。
            // 做法是先把地址算成纯值，再整体跳主 actor 落库。
            guard let self else { return }
            switch state {
            case .ready:
                var host: String?
                var port: Int = Ptp.defaultPtpIpPort
                if case let .hostPort(h, p) = connection.currentPath?.remoteEndpoint {
                    host = "\(h)".split(separator: "%").first.map(String.init)
                    // `NWEndpoint.Port` 的原始值是 `.rawValue`（UInt16），没有 `.port` 成员。
                    port = Int(p.rawValue)
                }
                let resolvedHost = host
                let resolvedPort = port
                connection.cancel()
                guard let resolvedHost, WifiEndpoint.isProbeCandidate(resolvedHost) else { return }
                Task { @MainActor in
                    self.bonjourHits["\(resolvedHost):\(resolvedPort)"] =
                        (resolvedHost, resolvedPort, Self.cleanServiceName(name))
                }
            case .failed, .cancelled:
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: .main)
        // 解析超时保护：Bonjour 解析在网络不佳时会挂很久
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            connection.cancel()
        }
    }

    private func stopBonjour() {
        bonjourBrowsers.forEach { $0.cancel() }
        bonjourBrowsers.removeAll()
    }

    /// 服务名清洗：尼康机身广播形如 `Z6III_12345678`、`Nikon Z Camera`，直接用即可。
    private static func cleanServiceName(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespaces)
        for suffix in ["._tcp", "."] where text.hasSuffix(suffix) {
            text = String(text.dropLast(suffix.count))
        }
        return text.trimmingCharacters(in: .whitespaces)
    }

    // MARK: - 网段盲扫

    private func sweep(
        subnets: [LocalIPv4Address],
        timeout: TimeInterval,
        diag: inout DiscoveryDiagnostics,
        timeoutMs: Int = CameraDiscovery.sweepTimeoutMs
    ) async -> [CameraCandidate] {
        var hosts = subnets.flatMap { local in
            LocalNetwork.subnetHosts(local).filter { $0 != local.address }
        }
        hosts += LocalNetwork.knownGatewayHosts
        var seen = Set<String>()
        let planned = hosts.filter { WifiEndpoint.isProbeCandidate($0) && seen.insert($0).inserted }
        diag.plannedHosts += planned.count
        diag.probedHosts += planned.count

        let deadline = Clock.now() + timeout
        let chunkSize = Self.maxConcurrency
        var out: [CameraCandidate] = []

        // 分批并发：iOS 上没有 Semaphore 的协程等价物，直接按块并发即可，
        // 每块最多 maxConcurrency 个探测同时在飞。
        var index = 0
        while index < planned.count {
            if Clock.now() >= deadline { break }
            let slice = planned[index..<min(index + chunkSize, planned.count)]
            index += chunkSize

            let batch = await withTaskGroup(of: CameraCandidate?.self) { group -> [CameraCandidate] in
                for host in slice {
                    // 显式标注返回类型：`return nil` 单独出现时编译器无法
                    // 从 `withTaskGroup(of:)` 的泛型推回 `CameraCandidate?`，
                    // 会报「'nil' is not compatible with closure result type」。
                    group.addTask { () -> CameraCandidate? in
                        // 两段式：先只连 TCP（快），通了再判断是否需要完整握手。
                        // **不发 InitCommand 做盲扫**：尼康机身同时只允许一个 PTP/IP
                        // 客户端，盲扫握手会挤占/污染配对槽，反而把目标挤下线。
                        let screen = await PtpIpProbe.tcpScreen(host: host, timeoutMs: timeoutMs)
                        guard screen.open else { return nil }
                        return CameraCandidate(
                            ipAddress: host,
                            port: Ptp.defaultPtpIpPort,
                            name: CameraCandidate.genericName,
                            source: .subnet,
                            needsWake: false
                        )
                    }
                }
                var collected: [CameraCandidate] = []
                for await item in group {
                    if let item { collected.append(item) }
                }
                return collected
            }

            for candidate in batch {
                diag.probeOk += 1
                out.append(candidate)
            }
        }
        return out
    }
}

//
//  CameraConnectionClient.swift
//  NLink
//
//  连接编排层。对应安卓 `com/camerakit/client/NikonCameraClient.kt`（597 行）。
//
//  这一层不是「连上就完事」，它把真机踩过的坑固化成流程：
//   · 失败冷却门（10s）——相机被锤进残留态后对所有连接秒败，越点恢复越慢
//   · AP 每次新 GUID / STA 与注册必须持久 GUID——相机对同 GUID 残留会话做冲突清理
//   · 事件通道迟 650ms 才建——建早了相机 6~12ms 内就复位
//   · 心跳看门狗——20s Ping，60s 无收包判死
//   · 链路丢失后清场重连 3 次，而不是直接把用户丢回未连接态
//
//  iOS 差异：安卓要用 NetworkBinder 把**进程**钉在相机网络上，否则系统会因
//  「该 Wi-Fi 无互联网」中途拆网。iOS 的路由是**按目的地址**选的——到
//  192.168.x.x 只可能走本地 Wi-Fi，不会漂到蜂窝，所以没有等价物；取而代之的是
//  「本地网络」隐私授权（iOS 14+）与 AP 模式下的 NEHotspotConfiguration 加入网络。
//

import Foundation

// MARK: - 阶段文案

/// 连接过程中的用户可见阶段。安卓 `CameraState.Connecting(phase)` 用的是
/// 自由字符串，这里收敛成枚举，既保证文案不漂移，也方便 UI 直接 switch。
enum ConnectPhase: String, Sendable, Equatable {
    case preparingNetwork = "正在准备网络…"
    case waitingRoute = "等待相机网络就绪…"
    case discovering = "正在查找相机…"
    case openingCommand = "正在建立命令通道…"
    case openingEvent = "正在建立事件通道…"
    case openingSession = "正在打开会话…"
    case registering = "正在注册这台设备…"
    case recovering = "连接中断，正在恢复…"

    var text: String { rawValue }
}

// MARK: - 客户端

@MainActor
final class CameraConnectionClient: ObservableObject {

    // MARK: 常量（与安卓逐值对齐）

    private enum C {
        /// STA 发现超时。取首个结果即返回——旧版收满整窗会让「秒到」也白等十几秒。
        static let staDiscoverTimeout: TimeInterval = 6
        /// 主机注册退避（首次不等，之后 2/4/6 秒）。
        static let registrationBackoffMs: [Int] = [0, 2_000, 4_000, 6_000]
        /// 注册会话建立后等相机就绪。
        static let regSettleFirstMs: Int = 350
        static let regSettleRetryMs: Int = 650
        /// 失败冷却门。
        static let connectFailCooldown: TimeInterval = 10
        /// 等相机网络出现本地路由。
        static let routeReadyTimeout: TimeInterval = 8
        /// 路由出现后再等一拍（DHCP 收尾）。
        static let routeSettleMs: Int = 2_500
        /// 拿到网络后等系统稳定。
        static let networkSettleMs: Int = 1_200
        /// 单次连接尝试里的会话建立次数。
        static let sessionAttempts = 2
        /// 第 2 次尝试前复查路由。
        static let routeRecheck: TimeInterval = 1.5
        /// 清场后等系统回收旧 socket。
        static let cleanupDelayMs: Int = 1_000
        /// 注册完成后等相机落盘。
        static let registrationPersistMs: Int = 1_500
        /// 看门狗。
        static let keepaliveInterval: TimeInterval = 20
        static let keepaliveDead: TimeInterval = 60
        /// 链路丢失后的恢复次数与退避基数。
        static let recoveryAttempts = 3
        static let recoveryBackoffBaseMs: Int = 1_500
    }

    // MARK: 对外状态

    @Published private(set) var state: CameraState = .disconnected
    @Published private(set) var connectPhase: ConnectPhase?
    /// 失败冷却门剩余秒数（>0 时禁止发起连接）。
    @Published private(set) var cooldownRemaining: Int = 0
    /// 最近一次失败的完整说明（「原因 + 怎么办」）。
    @Published private(set) var lastFailureText: String?

    private(set) var events: AsyncStream<CameraEvent>
    private let eventContinuation: AsyncStream<CameraEvent>.Continuation

    // MARK: 内部

    private var link: PtpIpLink?
    private var session: PtpSession?
    private var identity: HostIdentity
    private var method: ConnectionMethod = .wifiSta(endpoint: nil)
    private var lastConnectFailAt: TimeInterval = -1
    private var watchdog: Task<Void, Never>?
    private var connectTask: Task<Void, Never>?
    private var generation = 0
    private let log: @Sendable (String) -> Void

    init(
        identity: HostIdentity = HostIdentity.loadOrCreate(),
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) {
        self.identity = identity
        self.log = log
        var cont: AsyncStream<CameraEvent>.Continuation!
        self.events = AsyncStream(bufferingPolicy: .bufferingNewest(64)) { cont = $0 }
        self.eventContinuation = cont
    }

    // MARK: - 门面

    func sessionOrNil() -> PtpSession? { session }

    func requireSession() throws -> PtpSession {
        guard let session else {
            throw CameraError.notConnected(detail: "相机尚未连接。请先在「设备」页完成连接，再回到这里。")
        }
        return session
    }

    var isConnected: Bool { state.isConnected && session != nil }

    // MARK: - 发现

    /// STA 模式发现（mDNS + 网段扫描）。
    func discoverSta(timeout: TimeInterval = C.staDiscoverTimeout) async -> [CameraCandidate] {
        setState(.discovering)
        let discovery = CameraDiscovery()
        let found = await discovery.scan(timeout: timeout)
        if found.isEmpty {
            setState(.disconnected)
            lastFailureText = "同一 Wi-Fi 下没找到相机。请确认相机已开启 Wi-Fi 连接、手机与相机在同一个网络，然后重试。"
        } else {
            setState(.disconnected)
        }
        return found
    }

    // MARK: - 连接

    /// 发起连接。`autoPair` 为真时，首次连接失败会自动走一次主机注册再重试。
    func connect(method: ConnectionMethod, endpoint: CameraEndpoint? = nil, autoPair: Bool = true) {
        connectTask?.cancel()
        connectTask = Task { [weak self] in
            guard let self else { return }
            await self.runConnect(method: method, endpoint: endpoint, autoPair: autoPair)
        }
    }

    private func runConnect(method: ConnectionMethod, endpoint: CameraEndpoint?, autoPair: Bool) async {
        generation += 1
        let gen = generation

        // 冷却门：USB 不受限（USB 无「残留连接」概念）。
        if method.isWireless {
            let elapsed = Clock.now() - lastConnectFailAt
            if lastConnectFailAt > 0, elapsed < C.connectFailCooldown {
                let wait = Int((C.connectFailCooldown - elapsed).rounded(.up))
                cooldownRemaining = max(1, wait)
                connectPhase = nil
                lastFailureText = "上次连接失败，相机约需 10s 释放残留连接。请等待 \(max(1, wait))s 后再试（连续快速点击会延长恢复时间）。"
                return
            }
        }
        cooldownRemaining = 0
        self.method = method

        setPhase(.preparingNetwork)
        setState(.connecting(phase: ConnectPhase.preparingNetwork.text))

        do {
            switch method {
            case .wifiSta(let explicit):
                try await connectWireless(endpoint: explicit ?? endpoint, autoPair: autoPair, gen: gen)
            case .wifiAp(let host, let port):
                let ap = CameraEndpoint.ap(host: host, port: port)
                try await connectWireless(endpoint: ap, autoPair: autoPair, gen: gen)
            case .usb:
                // iOS 无 USB PTP 主机模式（需要 MFi / ExternalAccessory 硬件授权，
                // 且 Lightning/USB-C 相机直连不在公开 API 范围内）。见 docs 差异表。
                throw CameraError.usbPermissionDenied
            }
        } catch {
            guard gen == generation else { return }
            fail(error)
        }
    }

    private func connectWireless(endpoint: CameraEndpoint?, autoPair: Bool, gen: Int) async throws {
        // 1) 目标解析
        var target = endpoint
        if target == nil {
            setPhase(.discovering)
            let discovery = CameraDiscovery()
            let found = await discovery.scan(timeout: C.staDiscoverTimeout)
            guard gen == generation else { return }
            guard let first = found.first else { throw CameraError.noCameraFound }
            target = first.endpoint
        }
        guard let target else { throw CameraError.noCameraFound }

        // 2) 路由就绪门：只轮询本地子网判断，**绝不用一次性 TCP 探测**
        //    （探测连接会干扰相机的 PTP 服务，真机上会直接把服务打成 ECONNREFUSED）
        setPhase(.waitingRoute)
        try await waitForRoute(host: target.host, timeout: C.routeReadyTimeout)
        try await Clock.sleep(ms: C.routeSettleMs)
        try Task.checkCancellation()

        // 3) 等系统把网络配置落定
        try await Clock.sleep(ms: C.networkSettleMs)
        guard gen == generation else { return }

        // 4) 建会话；失败全清场再试一次
        var lastError: Error?
        for attempt in 0..<C.sessionAttempts {
            if attempt > 0 {
                cleanup()
                try await waitForRoute(host: target.host, timeout: C.routeRecheck)
                try await Clock.sleep(ms: C.cleanupDelayMs)
            }
            do {
                try await openWirelessSession(endpoint: target, gen: gen)
                startWatchdog()
                lastConnectFailAt = -1
                lastFailureText = nil
                return
            } catch {
                lastError = error
                log("会话建立失败 attempt=\(attempt + 1) \(error)")
            }
            guard gen == generation else { return }
        }

        // 5) 相机拒绝且还没注册过 → 走一次主机注册再重连
        if autoPair, let err = lastError as? CameraError, case .cameraRejected = err {
            setPhase(.registering)
            try await runRegistration(endpoint: target, gen: gen)
            guard gen == generation else { return }
            try await openWirelessSession(endpoint: target, gen: gen)
            startWatchdog()
            lastConnectFailAt = -1
            lastFailureText = nil
            return
        }

        throw lastError ?? CameraError.timeout(detail: "连接相机超时。请让相机保持开机并靠近手机后重试。")
    }

    /// 建一条 PTP/IP 会话并 OpenSession。
    private func openWirelessSession(endpoint: CameraEndpoint, gen: Int) async throws {
        setPhase(.openingCommand)
        // AP 每次新 GUID；STA 用持久 GUID。两者不能混——见文件头。
        let isAp = endpoint.service == "ap"
        let guid = isAp ? HostIdentity.randomGuid() : identity.guid

        let newLink = PtpIpLink(
            endpoint: endpoint,
            hostGuid: guid,
            hostName: identity.hostName,
            log: log,
            onTransportLost: { [weak self] detail in
                Task { @MainActor in self?.handleTransportLost(detail: detail) }
            }
        )
        setPhase(.openingEvent)
        try await newLink.open()
        guard gen == generation else {
            await newLink.close(graceful: true)
            return
        }

        setPhase(.openingSession)
        let newSession = PtpSession(link: newLink, log: log)
        let info = try await newSession.open()

        link = newLink
        session = newSession
        setPhase(nil)
        setState(.connected(endpoint: endpoint, deviceInfo: info))
        log("会话已就绪 \(info.displayModel) \(info.serialNumber)")
    }

    // MARK: - 主机注册

    /// 独立注册会话：必须先把在用会话关掉，再去 0x952B / 0x935A。
    private func runRegistration(endpoint: CameraEndpoint, gen: Int) async throws {
        cleanup()
        let regLink = PtpIpLink(
            endpoint: endpoint,
            hostGuid: identity.guid,
            hostName: identity.hostName,
            log: log
        )
        try await regLink.open()
        defer { Task { await regLink.close(graceful: true) } }

        let regSession = PtpSession(link: regLink, log: log)
        _ = try await regSession.open()
        try await Clock.sleep(ms: C.regSettleFirstMs)

        var attempt = 0
        var lastError: Error?
        while attempt < C.registrationBackoffMs.count {
            if attempt > 0 {
                try await Clock.sleep(ms: C.registrationBackoffMs[attempt])
            }
            do {
                let prepare = try await HostRegistrar.register(session: regSession) { [weak self] text in
                    await MainActor.run {
                        self?.setState(.awaitingHostConfirm(prepare: text))
                    }
                }
                log("主机注册成功 prepare=\(prepare)")
                markStaRegistered()
                try await Clock.sleep(ms: C.registrationPersistMs)
                return
            } catch let err as CameraError {
                // PairingRejected 是「用户没按机身确认」，重试只会让用户更困惑。
                if case .pairingRejected = err { throw err }
                lastError = err
            } catch {
                lastError = error
            }
            attempt += 1
            try await Clock.sleep(ms: C.regSettleRetryMs)
            guard gen == generation else { return }
        }
        throw lastError ?? CameraError.pairingRejected(detail: "相机未确认主机注册。请在相机屏幕上按「确定」后重试。")
    }

    /// 单独发起 STA 主机注册（设置页/设备页的「注册这台设备」）。
    func registerStaHost(endpoint: CameraEndpoint) async {
        guard !isConnected else {
            lastFailureText = "已连接状态下不需要注册。请先断开相机，再发起注册。"
            return
        }
        generation += 1
        let gen = generation
        do {
            state = .connecting(phase: "正在注册这台设备…")
            try await runRegistration(endpoint: endpoint, gen: gen)
            state = .disconnected
        } catch {
            fail(error)
        }
    }

    // MARK: - 断开

    func disconnect() {
        generation += 1
        watchdog?.cancel()
        watchdog = nil
        connectTask?.cancel()
        connectTask = nil
        cleanup()
        setPhase(nil)
        setState(.disconnected)
        lastFailureText = nil
    }

    /// 「取消连接」：语义是**停止重试并抑制自动重连**，不是断开已有会话。
    ///
    /// 安卓把这两件事严格分开（`btnCancelConnect` 只出现在扫描/配对/自动重连期间）。
    /// 已连上的情况下按它什么也不做——用户的意图是结束等待，不是断网。
    func cancelPendingWork() {
        guard !isConnected else { return }
        generation += 1
        connectTask?.cancel()
        connectTask = nil
        cleanup()
        watchdog?.cancel()
        watchdog = nil
        setPhase(nil)
        setState(.disconnected)
        lastFailureText = nil
        // 记录一次失败时间，避免用户立刻手点又被残留在相机那边的旧连接挡住
        lastConnectFailAt = Clock.now()
    }

    // MARK: - 供界面读取的派生信息

    /// 当前端点（未连接时返回上一次用过的目标）。
    var lastStaEndpoint: CameraEndpoint? {
        if let ep = state.endpoint, ep.service == "sta" { return ep }
        if let ep = lastEndpointHint, ep.service == "sta" { return ep }
        return nil
    }

    /// 本机是否已向相机完成过 STA 主机注册。
    ///
    /// 注意：这里只反映「本机发起注册并成功过」，不代表相机一定还记得——
    /// 相机被重置或换路由器后它会失效。真正的判据永远是一次成功的连接。
    var staHostRegistered: Bool {
        UserDefaults.standard.bool(forKey: Self.staRegisteredKey)
    }

    private static let staRegisteredKey = "nl_sta_host_registered"

    private func markStaRegistered() {
        UserDefaults.standard.set(true, forKey: Self.staRegisteredKey)
    }

    /// 本次进程内的自动重连次数（对应安卓 `ConnectionMetrics.reconnectCount`）。
    private(set) var reconnectCount: Int = 0

    /// 本次连接建立成功的时刻（`Date().timeIntervalSince1970`）。未连接时为 nil。
    ///
    /// 从「真正可用」那一刻算起，不是从点下按钮那一刻——
    /// 用户想知道的是「已经稳了多久」，不是「折腾了多久」。
    private(set) var connectedSince: TimeInterval?

    /// 链路频段描述。iOS 拿不到 5GHz/2.4GHz 的直读值——
    /// `NEHotspotNetwork.fetchCurrent` 只在有定位授权时给 SSID/BSSID，
    /// 频段要额外一次 `CWWiFiClient`（仅 macOS）。这里如实返回未知。
    var currentBandDescription: String? { nil }

    /// 是否 5GHz 链路。安卓用它把传输分块从 4MB 提到 8MB。
    ///
    /// iOS 无法判定频段（理由同上），**恒为 false** 即恒用 4MB 分块 ——
    /// 这是保守选择：分块偏小最多慢一点，偏大在弱链路上一失败就半小时。
    var isLink5GHz: Bool { false }

    private func cleanup() {
        if let link {
            Task { await link.close(graceful: true) }
        }
        session = nil
        link = nil
    }

    // MARK: - 看门狗

    private func startWatchdog() {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Clock.sleep(seconds: C.keepaliveInterval)
                guard let self else { return }
                guard await self.tickWatchdog() else { return }
            }
        }
    }

    /// 返回 false 表示看门狗应停止。
    private func tickWatchdog() async -> Bool {
        guard let link else { return false }
        let open = await link.isOpen
        guard open else { return false }
        let last = await link.lastReceiveAt()
        if Clock.now() - last > C.keepaliveDead {
            log("看门狗判定链路已死（\(Int(Clock.now() - last))s 无收包）")
            eventContinuation.yield(.keepaliveFailed(detail: "相机 60 秒没有响应。请确认相机还在开机状态，必要时重新连接。"))
            handleTransportLost(detail: "keepalive timeout")
            return false
        }
        await link.sendKeepalive()
        return true
    }

    // MARK: - 链路丢失与恢复

    private func handleTransportLost(detail: String) {
        // USB 没有「重连」这条路；无线才值得清场重试。
        if case .usb = method {
            eventContinuation.yield(.transportLost(detail: detail))
            fail(CameraError.transportLost(detail: "USB 连接已断开。请重新插好数据线。", causeDescription: detail))
            return
        }

        watchdog?.cancel()
        watchdog = nil
        generation += 1
        let gen = generation
        eventContinuation.yield(.transportLost(detail: detail))

        Task { [weak self] in
            guard let self else { return }
            await self.recover(gen: gen, detail: detail)
        }
    }

    private func recover(gen: Int, detail: String) async {
        let target = state.endpoint ?? lastEndpointHint
        guard let target else {
            fail(CameraError.transportLost(detail: "无线连接已断开。请回到「设备」页重新连接相机。", causeDescription: detail))
            return
        }
        setPhase(.recovering)
        setState(.recovering(reason: "连接中断，正在恢复…"))

        for attempt in 0..<C.recoveryAttempts {
            if attempt > 0 {
                try? await Clock.sleep(ms: C.recoveryBackoffBaseMs * (attempt + 1))
            }
            cleanup()
            do {
                try await openWirelessSession(endpoint: target, gen: gen)
                guard gen == generation else { return }
                startWatchdog()
                reconnectCount += 1
                eventContinuation.yield(.sessionRecovered(detail: "已恢复连接"))
                eventContinuation.yield(.sessionTokenChanged(token: gen))
                return
            } catch {
                log("恢复失败 attempt=\(attempt + 1) \(error)")
            }
            guard gen == generation else { return }
        }

        eventContinuation.yield(.wirelessDisconnected(reason: detail))
        fail(CameraError.transportLost(
            detail: "无线连接已断开，自动恢复 3 次均失败。请确认相机仍开机、手机还连着相机网络，然后重新连接。",
            causeDescription: detail
        ))
    }

    /// 恢复时用得上：失败后 state 会变成 .failed，但目标端点仍需记住。
    private var lastEndpointHint: CameraEndpoint?

    // MARK: - 状态写入

    private func setState(_ new: CameraState) {
        if let ep = new.endpoint { lastEndpointHint = ep }
        // 连接时长的起点：只有真正连上才开始计时，断开就清零。
        switch new {
        case .connected:
            if connectedSince == nil { connectedSince = Date().timeIntervalSince1970 }
        default:
            connectedSince = nil
        }
        state = new
    }

    private func setPhase(_ phase: ConnectPhase?) {
        connectPhase = phase
        if let phase {
            state = .connecting(phase: phase.text)
        }
    }

    private func fail(_ error: Error) {
        let cameraError = (error as? CameraError) ?? .protocolViolation(detail: error.localizedDescription, causeDescription: nil)
        if method.isWireless { lastConnectFailAt = Clock.now() }
        lastFailureText = cameraError.message
        setPhase(nil)
        setState(.failed(cameraError))
        log("连接失败：\(cameraError.message)")
    }

    // MARK: - 路由等待

    /// 轮询本地子网，直到相机地址出现在任一网卡的同网段里。
    /// **不做 TCP 探测**——理由见 connectWireless 注释。
    private func waitForRoute(host: String, timeout: TimeInterval) async throws {        let deadline = Clock.now() + timeout
        while Clock.now() < deadline {
            if LocalNetwork.isOnLocalSubnet(host: host, addresses: LocalNetwork.ipv4Addresses()) {
                return
            }
            try Task.checkCancellation()
            try await Clock.sleep(ms: 500)
        }
        throw CameraError.timeout(detail: "手机还不在相机的网络里。请先在系统设置里连上相机的 Wi-Fi（或让两台设备连同一个路由器），再重试。")
    }
}

// MARK: - 便捷关闭

extension PtpIpLink {
    /// 静默关闭：忽略关闭过程中的任何错误（关闭本身不抛，这里只是语义别名）。
    func closeQuietly(graceful: Bool) async {
        await close(graceful: graceful)
    }
}

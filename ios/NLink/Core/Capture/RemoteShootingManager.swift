//
//  RemoteShootingManager.swift
//  N-Link
//
//  遥控拍摄：快门 / B 门 / 定时 / 间隔 / 录像。对应安卓
//  `capture/RemoteShootingManager.kt`（1028 行）。
//
//  **最重要的一条认知**：尼康 PTP 没有「半按保持」这种能力。安卓写的所谓
//  「两段式快门」其实是「发一次 AF 驱动 → 固定等 1.2s → 再发拍摄」。
//  这 1.2 秒不是随便定的：真机点按对焦实测 1.2~1.5s 才能合焦，等短了拍出来是糊的，
//  等长了快门迟滞明显。连续对焦则每 1.2s 重发一次 0x90C1，因为相机在一次
//  AF 驱动结束后会松手。
//
//  B 门的模型也很反直觉：**开 = 把 0x500D 写成 0xFFFFFFFF 再触发快门；
//  收 = 把 0x500D 写回原值**。尼康用「改快门值」表示结束曝光。
//  所以收门失败时绝不能把状态置回 IDLE——那会让用户以为曝光结束了，
//  而机身还在曝光。安卓的注释写得很直白：这是最危险的静默失败。
//

import Foundation

// MARK: - 状态

enum ShootingState: String, Sendable, Equatable {
    case idle = "IDLE"
    case capturing = "CAPTURING"
    case timerCountdown = "TIMER_COUNTDOWN"
    case intervalShooting = "INTERVAL_SHOOTING"
    case bulbExposing = "BULB_EXPOSING"
    case videoPreparing = "VIDEO_PREPARING"
    case videoRecording = "VIDEO_RECORDING"

    var isBusy: Bool { self != .idle }
}

struct IntervalConfig: Sendable, Equatable {
    var intervalMs: Int = 5_000
    var totalShots: Int = 100

    /// 界面三档：3s×30 / 5s×50 / 10s×100
    static let presets: [IntervalConfig] = [
        IntervalConfig(intervalMs: 3_000, totalShots: 30),
        IntervalConfig(intervalMs: 5_000, totalShots: 50),
        IntervalConfig(intervalMs: 10_000, totalShots: 100)
    ]

    var title: String { "\(intervalMs / 1000)s × \(totalShots)" }
}

struct IntervalProgress: Sendable, Equatable {
    var completedShots: Int = 0
    var lastShotSuccess: Bool = true
    var failedShots: Int = 0

    var percent: Double {
        guard totalShots > 0 else { return 0 }
        return Double(completedShots) / Double(totalShots)
    }

    var totalShots: Int = 0
}

// MARK: - B 门策略

/// B 门的「开/收」命令阶梯。抽出来是因为它同时被手动 B 门与定时 B 门使用。
enum BulbPolicy {

    /// 收门失败后的重试次数。安卓用 6 次（每 500ms 一次）。
    static let shutterRestoreRetries = 6
    static let shutterRestoreIntervalMs = 500

    /// 开曝光：逐级降级。
    ///
    /// ①`0x9207 [-1, 0]`（无线机身优先，第一参按存储 ID 解释，-1 = 用当前卡）
    /// ②每个真实 storageId 的 `0x9207 [id, 0]`
    /// ③`0x100E`（标准 InitiateCapture；USB 只传 1 个参数）
    /// ④`0x100F`（开光圈快门）
    static func startPlan(storageIds: [UInt32], isUSB: Bool) -> [(opcode: UInt16, params: [UInt32])] {
        var plan: [(UInt16, [UInt32])] = [(Ptp.opNikonInitiateCaptureRecInMedia, [UInt32(bitPattern: -1), 0])]
        for id in storageIds {
            plan.append((Ptp.opNikonInitiateCaptureRecInMedia, [id, 0]))
        }
        plan.append((Ptp.opInitiateCapture, isUSB ? [0] : [0, 0]))
        plan.append((Ptp.opInitiateOpenCapture, [0]))
        return plan
    }

    /// 收门：先终止拍摄，再收快门。
    static func stopPlan() -> [(opcode: UInt16, params: [UInt32])] {
        [
            (Ptp.opNikonTerminateCapture, [0, 0]),
            (Ptp.opTerminateOpenCapture, [0])
        ]
    }

    /// 快门「B 门」哨兵值。
    static let bulbMarker: UInt32 = 0xFFFF_FFFF
}

// MARK: - 遥控拍摄管理器

@MainActor
final class RemoteShootingManager: ObservableObject {

    private enum C {
        /// 对焦后到释放快门的固定等待。真机实测 1.2~1.5s。
        static let afSettleMs = 1_200
        /// 连续对焦的重发间隔。旧值 600ms 会打断相机正在进行的对焦。
        static let afHoldIntervalMs = 1_200
        /// 录像前置：切应用模式后等机身就绪。
        static let appModeSettleMs = 300
        /// 0xA00B 后等监看起来再重试录像。
        static let movieLiveViewRetryMs = 800
        /// B 门计时刷新周期。
        static let bulbTickMs = 100
        /// 定时 B 门的检查周期。
        static let bulbTimerTickMs = 200
        /// 收门重试上限（定时 B 门）。
        static let bulbAutoStopRetries = 30
        /// 设备忙碌时的重试次数与间隔。
        static let busyRetries = 3
        static let busyRetryMs = 500
    }

    // MARK: 对外状态

    @Published private(set) var state: ShootingState = .idle
    @Published private(set) var message: String?
    @Published private(set) var shotCount: Int = 0
    @Published private(set) var intervalProgress: IntervalProgress?
    @Published private(set) var bulbExposureTime: TimeInterval = 0
    /// nil = 手动收门（不自动结束）
    @Published var bulbDuration: TimeInterval?
    @Published private(set) var timerCountdown: Int = 0
    /// 每次拍到照片（供相册刷新）
    @Published private(set) var lastCaptureAt: TimeInterval = 0

    // MARK: 依赖

    private let client: CameraConnectionClient
    private let liveView: LiveViewManager
    private let log: @Sendable (String) -> Void

    private var task: Task<Void, Never>?
    private var bulbTask: Task<Void, Never>?
    private var holdFocusTask: Task<Void, Never>?
    private var bulbStartedAt: TimeInterval = 0
    private var originalShutterRaw: UInt32?

    init(
        client: CameraConnectionClient,
        liveView: LiveViewManager,
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) {
        self.client = client
        self.liveView = liveView
        self.log = log
    }

    // MARK: - 单张快门

    /// 单张拍摄。`autofocus` 为假时跳过 AF 直接释放（B 门/间隔用得上）。
    @discardableResult
    func capture(autofocus: Bool = true) async -> Bool {
        guard state == .idle else {
            message = "正在拍摄中，请稍候。"
            return false
        }
        guard let session = client.sessionOrNil() else {
            message = "相机尚未连接，请先完成配对。"
            return false
        }

        // 取景会因对焦/曝光短暂停流，先放宽阈值，避免误判为链路故障
        liveView.grantFrameErrorGrace()
        liveView.beginCaptureGuard()
        state = .capturing

        if autofocus {
            _ = try? await session.rawTransact(opcode: Ptp.opNikonAfDrive)
            try? await Clock.sleep(ms: C.afSettleMs)
        }

        let ok = await fireShutter(session)
        if ok {
            shotCount += 1
            lastCaptureAt = Clock.now()
        } else {
            message = lastShutterErrorMessage ?? "相机没有响应拍摄命令。请确认机身处于拍照档、没有停在回放或菜单界面，然后重试。"
        }
        state = .idle
        return ok
    }

    private var lastShutterErrorMessage: String?

    /// 触发快门，按通道逐级降级。
    private func fireShutter(_ session: PtpSession) async -> Bool {
        // ① 0x9207 [-1, 0]
        if let r = try? await session.rawTransact(
            opcode: Ptp.opNikonInitiateCaptureRecInMedia,
            params: [UInt32(bitPattern: -1), 0], timeoutMs: 30
        ) {
            if r.isOk { return true }
            if r.code == Ptp.rcParameterNotSupported {
                // ② 0x9207 [-1]（少一个参数的老固件）
                if let r2 = try? await session.rawTransact(
                    opcode: Ptp.opNikonInitiateCaptureRecInMedia,
                    params: [UInt32(bitPattern: -1)], timeoutMs: 30
                ), r2.isOk { return true }
            }
            lastShutterErrorMessage = describeCaptureFailure(r.code)
        }

        // ③ 标准 InitiateCapture
        if let r = try? await session.rawTransact(
            opcode: Ptp.opInitiateCapture,
            params: [0, 0], timeoutMs: 30
        ), r.isOk { return true }

        return false
    }

    /// 拍摄失败码 → 「原因 + 怎么办」。
    private func describeCaptureFailure(_ code: UInt16) -> String {
        switch code {
        case Ptp.rcNikonNotLiveView:
            return "相机当前没在实时取景状态。请先打开监看，或把拨盘切到照片档后重试。"
        case Ptp.rcNikonInvalidStatus:
            return "相机当前状态不允许拍摄：录制请把拨盘切到视频档、拍照请切到照片档，且不要停留在回放/菜单界面。"
        case Ptp.rcNikonBulbBusy:
            return "相机正在结束 B 门曝光，请稍候再试。"
        case Ptp.rcDeviceBusy:
            return "相机正忙（多半在写卡）。请等机身指示灯停止闪烁后重试。"
        case Ptp.rcAccessDenied:
            return "相机拒绝了这次操作。请确认机身屏幕上没有停留确认弹窗。"
        case Ptp.rcStoreFull:
            return "存储卡已满。请在机身上清理空间，或换一张卡。"
        default:
            return "相机返回错误 \(Ptp.responseName(code))。请确认机身处于拍照档并重试。"
        }
    }

    // MARK: - 连续对焦（按住）

    /// 开始连续对焦：立即发一次 0x90C1，之后每 1.2s 重发。
    func beginFocusHold() {
        guard state == .idle else { return }
        holdFocusTask?.cancel()
        holdFocusTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if let session = self.client.sessionOrNil() {
                    _ = try? await session.rawTransact(opcode: Ptp.opNikonAfDrive)
                }
                try? await Clock.sleep(ms: C.afHoldIntervalMs)
            }
        }
    }

    /// 结束连续对焦：必须发 0x9206 取消，否则相机会一直保持对焦驱动。
    ///
    /// 注意 0x9206 是 AF_DRIVE_CANCEL，**不是** 0x90C2（ChangeCameraMode）。
    /// 这两个码在文档里挨着，写反的后果是切了机身模式。
    func endFocusHold() {
        holdFocusTask?.cancel()
        holdFocusTask = nil
        guard let session = client.sessionOrNil() else { return }
        Task { _ = try? await session.rawTransact(opcode: Ptp.opNikonAfDriveCancel) }
    }

    // MARK: - 定时拍摄

    /// 倒计时拍摄。UI 固定 2/5/10 秒。
    @discardableResult
    func startTimerCapture(delaySeconds: Int) async -> Bool {
        guard state == .idle else { return false }
        state = .timerCountdown
        for remaining in stride(from: delaySeconds, through: 1, by: -1) {
            timerCountdown = remaining
            try? await Clock.sleep(seconds: 1)
        }
        timerCountdown = 0
        state = .idle
        return await capture()
    }

    // MARK: - 间隔拍摄

    func startInterval(_ config: IntervalConfig) {
        guard state == .idle else { return }
        task?.cancel()
        state = .intervalShooting
        intervalProgress = IntervalProgress(completedShots: 0, lastShotSuccess: true, failedShots: 0, totalShots: config.totalShots)

        task = Task { [weak self] in
            guard let self else { return }
            var progress = IntervalProgress(completedShots: 0, lastShotSuccess: true, failedShots: 0, totalShots: config.totalShots)
            for index in 1...config.totalShots {
                guard !Task.isCancelled else { break }
                let ok = await self.capture()
                progress.completedShots = index
                progress.lastShotSuccess = ok
                if !ok { progress.failedShots += 1 }
                self.intervalProgress = progress
                // 最后一张不等——等了也只是白等一段
                if index < config.totalShots {
                    try? await Clock.sleep(seconds: Double(config.intervalMs) / 1000.0)
                }
            }
            if progress.failedShots > 0 {
                self.message = "间隔拍摄结束：\(config.totalShots) 张中 \(progress.failedShots) 张失败（相机未响应或连接中断）。"
            }
            self.state = .idle
            self.intervalProgress = nil
        }
    }

    func stopInterval() {
        task?.cancel()
        task = nil
        intervalProgress = nil
        state = .idle
    }

    // MARK: - B 门

    /// 开始 B 门曝光。
    ///
    /// 步骤照抄安卓 `bulbStart`（RemoteShootingManager.kt:415-535）：
    /// ①非遥控模式时先把机身切到 M 档；②读 0x500D 并备份；
    /// ③写成 0xFFFFFFFF；④按 BulbPolicy.startPlan 逐级触发拍摄。
    @discardableResult
    func startBulb() async -> Bool {
        guard state == .idle else { return false }
        guard let session = client.sessionOrNil() else {
            message = "相机尚未连接，请先完成配对。"
            return false
        }

        state = .capturing
        liveView.beginCaptureGuard()
        liveView.beginExpectedStall()

        // 读原快门值
        if let r = try? await session.getDevicePropValue(Ptp.propExposureTime),
           let data = r.dataIn, data.count >= 4, r.isOk {
            originalShutterRaw = data.readLE32(at: 0)
        }

        // 写 B 门哨兵
        var payload = Data()
        payload.appendLE(BulbPolicy.bulbMarker)
        let wrote = (try? await session.setDevicePropValue(Ptp.propExposureTime, value: payload))?.isOk ?? false
        if !wrote {
            message = "无法进入 B 门：请把相机拨盘切到 M 档，并在机身菜单里把快门速度调到 Bulb 后重试。"
            state = .idle
            liveView.endExpectedStall()
            return false
        }

        guard await fireShutter(session) else {
            // 触发失败要**保证**把快门值写回：否则机身停在 Bulb 上，用户下次按快门
            // 会开一次不知道什么时候结束的曝光。写回失败也继续往下走并置 idle，
            // 但要经 message 说明清楚，不能假装成功。
            _ = await restoreShutter(session)
            message = "B 门触发失败。请确认机身处于 M 档且快门速度已设为 Bulb。"
            state = .idle
            liveView.endExpectedStall()
            return false
        }

        state = .bulbExposing
        bulbStartedAt = Clock.now()
        bulbExposureTime = 0
        startBulbTicker(session)

        if let duration = bulbDuration, duration > 0 {
            scheduleAutoStop(after: duration, session: session)
        }
        return true
    }

    /// 结束 B 门曝光。
    ///
    /// **写回快门值是结束曝光的唯一手段**。失败必须留在 .bulbExposing，
    /// 不能假装成功——机身还在曝光，UI 说结束了是最危险的谎言。
    @discardableResult
    func stopBulb() async -> Bool {
        guard state == .bulbExposing, let session = client.sessionOrNil() else { return false }
        bulbTask?.cancel()
        bulbTask = nil

        let restored = await restoreShutter(session)
        var stopped = false
        for step in BulbPolicy.stopPlan() {
            if let r = try? await session.rawTransact(opcode: step.opcode, params: step.params), r.isOk {
                stopped = true
                break
            }
        }

        if restored || stopped {
            state = .idle
            liveView.endExpectedStall()
            message = nil
            return true
        }
        message = "结束曝光失败：请重试，或直接轻按机身快门结束这次曝光。"
        return false
    }

    private func startBulbTicker(_ session: PtpSession) {
        bulbTask?.cancel()
        bulbTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Clock.sleep(ms: C.bulbTickMs)
                guard let self, self.state == .bulbExposing else { return }
                self.bulbExposureTime = Clock.now() - self.bulbStartedAt
            }
        }
    }

    /// 定时 B 门：到点自动收门，每秒重试一次最多 30 次。
    private func scheduleAutoStop(after duration: TimeInterval, session: PtpSession) {
        Task { [weak self] in
            while let self, self.state == .bulbExposing {
                try? await Clock.sleep(ms: C.bulbTimerTickMs)
                guard Clock.now() - self.bulbStartedAt >= duration else { continue }
                var attempts = 0
                while attempts < C.bulbAutoStopRetries {
                    if await self.stopBulb() { return }
                    attempts += 1
                    try? await Clock.sleep(seconds: 1)
                    if self.state != .bulbExposing { return }
                }
                self.message = "定时 B 门结束失败。请手动结束这次曝光，或直接轻按机身快门。"
                return
            }
        }
    }

    /// 写回备份的快门值，失败重试 6 次。
    private func restoreShutter(_ session: PtpSession) async -> Bool {
        guard let original = originalShutterRaw else { return true }
        for attempt in 0..<BulbPolicy.shutterRestoreRetries {
            var payload = Data()
            payload.appendLE(original)
            if (try? await session.setDevicePropValue(Ptp.propExposureTime, value: payload))?.isOk == true {
                originalShutterRaw = nil
                return true
            }
            if attempt < BulbPolicy.shutterRestoreRetries - 1 {
                try? await Clock.sleep(ms: BulbPolicy.shutterRestoreIntervalMs)
            }
        }
        log("写回原快门值失败（重试 \(BulbPolicy.shutterRestoreRetries) 次）")
        return false
    }

    // MARK: - 录像

    @discardableResult
    func startVideoRecording() async -> Bool {
        guard state == .idle else { return false }
        guard let session = client.sessionOrNil() else {
            message = "相机尚未连接，请先完成配对。"
            return false
        }
        state = .videoPreparing
        liveView.beginExpectedStall()

        // 切到录像应用模式
        _ = try? await session.rawTransact(opcode: Ptp.opNikonChangeApplicationMode, params: [1])
        try? await Clock.sleep(ms: C.appModeSettleMs)

        for attempt in 0..<C.busyRetries {
            guard let r = try? await session.rawTransact(
                opcode: Ptp.opNikonStartMovieRecInCard, timeoutMs: 30
            ) else { break }
            if r.isOk {
                state = .videoRecording
                return true
            }
            if r.code == Ptp.rcNikonNotLiveView {
                // 录像依赖取景：先把取景拉起来再试一次
                message = "正在启动监看…"
                await liveView.startLiveView()
                try? await Clock.sleep(ms: C.movieLiveViewRetryMs)
                continue
            }
            if r.code == Ptp.rcDeviceBusy, attempt < C.busyRetries - 1 {
                try? await Clock.sleep(ms: C.busyRetryMs)
                continue
            }
            message = describeMovieFailure(r.code)
            break
        }

        state = .idle
        liveView.endExpectedStall()
        return false
    }

    func stopVideoRecording() async {
        guard state == .videoRecording, let session = client.sessionOrNil() else { return }
        _ = try? await session.rawTransact(opcode: Ptp.opNikonEndMovieRec, timeoutMs: 30)
        _ = try? await session.rawTransact(opcode: Ptp.opNikonChangeApplicationMode, params: [0])
        state = .idle
        liveView.endExpectedStall()
    }

    private func describeMovieFailure(_ code: UInt16) -> String {
        switch code {
        case Ptp.rcNikonNotLiveView:
            return "相机不在实时取景状态。请先打开监看，并把拨盘切到视频档。"
        case Ptp.rcNikonInvalidStatus:
            return "相机当前状态不允许录像。请把拨盘切到视频档，并退出回放/菜单界面。"
        case Ptp.rcAccessDenied:
            return "相机拒绝了录像请求。请检查机身是否有确认弹窗。"
        case Ptp.rcOperationNotSupported:
            return "这台机型不支持通过 App 触发录像。请在机身上操作。"
        case Ptp.rcStoreFull:
            return "存储卡已满。请在机身上清理空间或换卡。"
        default:
            return "录像启动失败：\(Ptp.responseName(code))。请确认拨盘在视频档后重试。"
        }
    }

    // MARK: - 清理

    func shutdown() {
        task?.cancel()
        bulbTask?.cancel()
        holdFocusTask?.cancel()
        task = nil
        bulbTask = nil
        holdFocusTask = nil
        state = .idle
    }
}

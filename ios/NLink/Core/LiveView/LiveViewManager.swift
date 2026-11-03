//
//  LiveViewManager.swift
//  N-Link
//
//  实时取景生命周期与取帧循环。对应安卓 `camera/liveview/LiveViewManager.kt`（1489 行）。
//
//  这一层几乎全部是「真机教出来的」时序，逐条照抄：
//
//   · 起流前先写 0x8012（遥控模式）再写 0xD1AC=3（取景档位），中间隔 300ms。
//     不写前者，部分机身直接拒绝 StartLiveView；不写后者，取景是低分辨率缩略流。
//   · 0x9201 回 OK **不代表**取景真的起来了：部分固件会假成功。所以必须再拉一帧
//     0x9203 验活，拉不到就当这次失败重发（最多 6 轮）。
//   · USB/DEVICE_BUSY 时用 0x90C8（DeviceReady）当敲门砖，10 轮 ×250ms。
//   · 取帧按 66ms（≈15 fps）节流，不是「能多快拉多快」——拉太快相机会把命令
//     通道占死，参数轮询和快门全排不上队。
//   · 按下快门后 4 秒内出现的「几乎全黑」帧要丢掉，否则屏幕会闪一下黑。
//
//  iOS 差异：安卓用 ImageView + imageMatrix 做缩放，这里把缩放/网格留给 SwiftUI 层，
//  本类只发帧与解析头，保持「一个渲染链只有一个真相源」。
//

import Foundation
import UIKit

// MARK: - 时序常量

/// 取景链路的时序常量集中在这里，因为它同时被管理器与指标使用。
enum LiveViewTiming {
    /// 取帧节拍。15 fps 是这条链路上真机能稳住的最高值；
    /// 改这个数必须同步改指标口径（超轮率的分母就是它）。
    static let frameIntervalMs = 66
}

// MARK: - 帧

struct LiveViewFrame: Identifiable, Sendable {
    let id = UUID()
    /// 已剥掉帧头的纯 JPEG
    let jpeg: Data
    let timestamp: TimeInterval
    /// 从发出取帧请求到拿到数据的往返毫秒
    let latencyMs: Int
    /// 帧头解析结果；nil = 这帧没有可识别的头
    let header: LiveViewHeader?

    /// 解出来的图（供 SwiftUI 直接用）。解码失败返回 nil。
    func image(inSample: CGFloat = 1) -> UIImage? {
        UIImage(data: jpeg, scale: max(1, inSample))
    }
}

enum LiveViewState: String, Sendable, Equatable {
    case stopped = "STOPPED"
    case starting = "STARTING"
    case running = "RUNNING"
    case error = "ERROR"
}

enum GridOverlay: String, CaseIterable, Sendable {
    case off = "OFF"
    case thirds = "THIRDS"
    case diagonal = "DIAGONAL"
    case golden = "GOLDEN"

    var displayName: String {
        switch self {
        case .off: return "关闭"
        case .thirds: return "三分线"
        case .diagonal: return "对角构图"
        case .golden: return "黄金分割"
        }
    }

    /// 轮换顺序：关闭 → 三分 → 对角 → 黄金 → 关闭
    func next() -> GridOverlay {
        switch self {
        case .off: return .thirds
        case .thirds: return .diagonal
        case .diagonal: return .golden
        case .golden: return .off
        }
    }
}

// MARK: - 指标

/// 取景指标（对应安卓 BaselineMetrics 的 lv 部分）。
///
/// 核心口径：**超轮率**，不是掉帧率。`elapsed >= 66ms` 就算一轮超时。
/// 帧数与轮数相等时也可能有 21.7% 超轮——那说明每轮都在临界点上，
/// 再看 fps 是看不出来的。
struct LiveViewMetrics: Sendable, Equatable {
    var rounds: Int = 0
    var lateRounds: Int = 0
    var frames: Int = 0
    var failed: Int = 0
    var bytes: Int = 0
    var startedAt: TimeInterval = 0
    var endedAt: TimeInterval = 0

    /// 超轮率（0..100）
    var overrunPercent: Double {
        rounds > 0 ? Double(lateRounds) * 100.0 / Double(rounds) : 0
    }

    var fps: Double {
        let duration = endedAt - startedAt
        guard duration > 0, frames > 0 else { return 0 }
        return Double(frames) / duration
    }

    var kbPerFrame: Double {
        frames > 0 ? Double(bytes) / Double(frames) / 1024.0 : 0
    }

    mutating func begin(at now: TimeInterval) {
        self = LiveViewMetrics(startedAt: now)
    }

    mutating func round(elapsedMs: Int) {
        rounds += 1
        if elapsedMs >= LiveViewTiming.frameIntervalMs { lateRounds += 1 }
    }

    mutating func frame(bytes: Int) {
        frames += 1
        self.bytes += bytes
    }

    mutating func end(at now: TimeInterval) {
        endedAt = now
    }
}

// MARK: - 管理器

@MainActor
final class LiveViewManager: ObservableObject {

    /// 取帧节拍。15 fps 是这条链路上真机能稳住的最高值（见 LiveViewTiming）。
    static var frameIntervalMs: Int { LiveViewTiming.frameIntervalMs }
    /// 单轮最大错误宽限
    private static let errorGraceSeconds: TimeInterval = 6
    /// 快门黑帧抑制窗口
    private static let captureGuardSeconds: TimeInterval = 4
    /// 起流重试轮数
    private static let startAttempts = 6
    /// DeviceReady 敲门次数与间隔
    private static let readyProbes = 10
    private static let readyProbeIntervalMs = 250

    private enum C {
        static let usbStallThreshold = 10
        static let genericStallThreshold = 5
        static let apStallThreshold = 40
        static let restartLimit = 5
        static let restartPauseMs = 250
    }

    // MARK: 对外状态

    @Published private(set) var state: LiveViewState = .stopped
    @Published private(set) var errorMessage: String?
    @Published private(set) var latestFrame: LiveViewFrame?
    @Published private(set) var metrics = LiveViewMetrics()
    /// 相机回报的对焦框（归一化，相对监看画面内容）
    @Published private(set) var cameraAfArea: NormalizedRect?
    @Published var gridOverlay: GridOverlay = .thirds
    @Published private(set) var zoomLevel: Double = 1.0
    /// 亮度直方图（96 档，0..1 归一化）。仅当 `setAnalysisEnabled(true)` 时非空。
    @Published private(set) var luminanceBins: [Double]?
    /// RGB 波形水平抽样（128 列平均亮度）。
    @Published private(set) var waveformRows: [Double]?

    var minZoom: Double { 1.0 }
    var maxZoom: Double { 5.0 }

    // MARK: 依赖

    private let client: CameraConnectionClient
    private let log: @Sendable (String) -> Void
    private var frameTask: Task<Void, Never>?
    private var lastFramePrint: (Int, [UInt8], [UInt8])?
    private var stallCount = 0
    private var restartCount = 0
    /// 原曝光模式（起流前读到的 0x500E），停流时只在这个值仍是 0x8012 才写回
    private var originalExposureProgram: UInt16?
    private var errorGraceUntil: TimeInterval = 0
    private var captureGuardUntil: TimeInterval = 0
    private var expectedStall = false
    private var lastMedianSize = 0
    private var recentSizes: [Int] = []
    private var analysisEnabled = false

    init(client: CameraConnectionClient, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.client = client
        self.log = log
    }

    var isRunning: Bool { state == .running }

    // MARK: - 起流

    func startLiveView() async {
        guard state != .running, state != .starting else { return }
        guard let session = client.sessionOrNil() else {
            fail("相机尚未连接，请先完成配对。")
            return
        }
        state = .starting
        errorMessage = nil

        // 1) 备份并切换到遥控曝光模式
        if let current = try? await session.getDevicePropValue(Ptp.propExposureProgramMode),
           let data = current.dataIn, data.count >= 2, current.isOk {
            originalExposureProgram = data.readLE16(at: 0)
        }
        await writeProp(session, Ptp.propExposureProgramMode, UInt16(Ptp.propValueRemoteMode))
        try? await Clock.sleep(ms: 300)

        // 2) 取景档位（失败不阻断，只是画质差一些）
        await writeProp(session, Ptp.propNikonLvImageProfile, 3)

        // 3) 起流 + 验活
        guard await startLiveViewCommand(session) else {
            await restoreExposureMode(session)
            fail("实时取景启动失败，请检查相机模式（P/A/S/M）并重试。")
            return
        }

        state = .running
        stallCount = 0
        restartCount = 0
        metrics.begin(at: Clock.now())
        startFrameLoop(session)
    }

    /// 0x9201 → 拉一帧验活 → 失败则 DeviceReady 敲门后重试。
    private func startLiveViewCommand(_ session: PtpSession) async -> Bool {
        // 0xD1A4 禁止位图：只做提示富化，不阻断流程（位语义无权威定义）
        if let r = try? await session.getDevicePropValue(Ptp.propNikonLvProhibitCondition),
           let data = r.dataIn, data.count >= 4, r.isOk {
            let bits = data.readLE32(at: 0)
            if bits != 0 { log("取景禁止条件位图 0x\(String(bits, radix: 16))（不阻断）") }
        }

        for attempt in 0..<Self.startAttempts {
            let result = (try? await session.rawTransact(opcode: Ptp.opNikonStartLiveView)) ?? nil
            if let result, result.isOk {
                try? await Clock.sleep(ms: 300)
                // 假成功探测：真拉一帧
                if await probeFrame(session) { return true }
                log("0x9201 假成功，重发 attempt=\(attempt + 1)")
                continue
            }

            let code = result?.code ?? 0
            if code == Ptp.rcDeviceBusy || code == Ptp.rcNikonNotLiveView {
                await waitDeviceReady(session)
            } else if let code = result?.code {
                log("0x9201 失败：\(Ptp.responseName(code))")
                // 非忙碌类失败再重试也没意义
                if attempt >= 1 { return false }
                try? await Clock.sleep(ms: 300)
            }
        }
        return false
    }

    private func probeFrame(_ session: PtpSession) async -> Bool {
        guard let r = try? await session.rawTransact(opcode: Ptp.opNikonGetLiveViewImage),
              r.isOk, let data = r.dataIn, data.count > 1_024 else { return false }
        return true
    }

    /// DeviceReady 敲门：10 轮 ×250ms。相机从回放/菜单切出来需要时间。
    private func waitDeviceReady(_ session: PtpSession) async {
        for _ in 0..<Self.readyProbes {
            try? await Clock.sleep(ms: Self.readyProbeIntervalMs)
            if let r = try? await session.rawTransact(opcode: Ptp.opNikonDeviceReady),
               r.isOk || r.code == Ptp.rcDeviceBusy {
                return
            }
        }
    }

    // MARK: - 取帧循环

    private func startFrameLoop(_ session: PtpSession) {
        frameTask?.cancel()
        frameTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                let started = Clock.now()
                await self.tick(session: session)
                let elapsed = Int((Clock.now() - started) * 1000)
                self.metrics.round(elapsedMs: elapsed)
                if elapsed < Self.frameIntervalMs {
                    try? await Clock.sleep(ms: Self.frameIntervalMs - elapsed)
                }
            }
        }
    }

    private func tick(session: PtpSession) async {
        let started = Clock.now()
        do {
            let result = try await session.rawTransact(opcode: Ptp.opNikonGetLiveViewImage, timeoutMs: 10)
            guard result.isOk, let data = result.dataIn, data.count > 512 else {
                await handleFrameFailure(nil, session: session)
                return
            }
            guard acceptFrame(data) else { return }

            let latency = Int((Clock.now() - started) * 1000)
            let header = LiveViewHeader.parse(data)
            let jpeg = LiveViewFrameUtil.stripHeader(data)
            metrics.frame(bytes: data.count)
            publish(jpeg: jpeg, latency: latency, header: header)
            stallCount = 0
            if result.isOk { errorGraceUntil = 0 }
        } catch {
            await handleFrameFailure(error, session: session)
        }
    }

    /// 丢帧判定：快门黑帧窗口 / 重复帧 / 明显塌缩的帧。
    private func acceptFrame(_ data: Data) -> Bool {
        let print = LiveViewFrameUtil.fingerprint(data)
        defer { lastFramePrint = print }

        // 重复帧（AP 通道会吐同一帧）直接跳过，省一次解码
        if let last = lastFramePrint,
           last.0 == print.0, last.1 == print.1, last.2 == print.2 {
            return false
        }

        // 快门后黑帧：体积会塌到正常帧的 1/8 以下
        if Clock.now() < captureGuardUntil, lastMedianSize > 0,
           LiveViewFrameUtil.isProbablyBlack(data, referenceSize: lastMedianSize) {
            return false
        }

        recentSizes.append(data.count)
        if recentSizes.count > 9 { recentSizes.removeFirst() }
        lastMedianSize = recentSizes.sorted()[recentSizes.count / 2]
        return true
    }

    private func publish(jpeg: Data, latency: Int, header: LiveViewHeader?) {
        latestFrame = LiveViewFrame(
            jpeg: jpeg,
            timestamp: Clock.now(),
            latencyMs: latency,
            header: header
        )
        if let header, let area = header.activeAfArea() {
            cameraAfArea = header.normalizedRect(area)
        }
        if analysisEnabled {
            let analysis = LiveFrameAnalysis.analyze(jpeg: jpeg)
            luminanceBins = analysis.bins
            waveformRows = analysis.rows
        } else if luminanceBins != nil {
            // 关掉直方图/波形后不再算，也把旧数据清掉——留着一片静止的柱状图
            // 比没有更误导（用户会以为画面变了它没反应）。
            luminanceBins = nil
            waveformRows = nil
        }
    }

    /// 打开后才在 `publish` 里做亮度/波形统计。
    ///
    /// 默认关：这两张图是进阶工具，默认关时**一帧都不该多解一次**。
    /// 15fps 的预算里，多解一次 96px 缩略图约占 3~4ms，开着能接受，白开着不行。
    func setAnalysisEnabled(_ enabled: Bool) {
        analysisEnabled = enabled
        if !enabled {
            luminanceBins = nil
            waveformRows = nil
        }
    }

    // MARK: - 失败处理

    private func handleFrameFailure(_ error: Error?, session: PtpSession) async {
        metrics.failed += 1

        // 链路已死：直接报错，不再重试
        if let cameraError = error as? CameraError, cameraError.isTransportLoss {
            stopWithError("相机连接已断开。请确认相机仍开机、手机还连着相机网络，然后重新连接。")
            return
        }

        // 期望内的停流（切录像/快门）不累计错误
        if expectedStall || Clock.now() < errorGraceUntil {
            try? await Clock.sleep(ms: 100)
            return
        }

        stallCount += 1
        let threshold = stallThreshold()
        if stallCount >= threshold, stallCount % threshold == 0 {
            // 阈值命中：先就地重启，不打断用户
            if restartCount < C.restartLimit {
                restartCount += 1
                await restartStreamInPlace(session)
            } else {
                stopWithError("实时取景连续中断 \(stallCount) 次，已尝试重启 \(C.restartLimit) 次。请回到设备页重新连接相机。")
            }
        } else {
            try? await Clock.sleep(ms: 100)
        }
    }

    /// 停流判死阈值按通道分档。
    ///
    /// AP 通道给到 40：热点链路本来就抖，重连一次要十几秒，不值得为几次空帧重建。
    /// USB 给 10：有线几乎不会凭空停流，连续 10 次说明机身那边真的卡住了。
    /// 无线 STA 给 5：最需要及时自救的一档。
    private func stallThreshold() -> Int {
        switch client.state.endpoint?.service {
        case "ap": return C.apStallThreshold
        case "usb": return C.usbStallThreshold
        default: return C.genericStallThreshold
        }
    }

    /// 就地重启：0x9202 → 250ms → 0x9201。不停会话、不重建链路。
    private func restartStreamInPlace(_ session: PtpSession) async {
        log("取景就地重启（第 \(restartCount) 次）")
        _ = try? await session.rawTransact(opcode: Ptp.opNikonEndLiveView)
        try? await Clock.sleep(ms: C.restartPauseMs)
        if let r = try? await session.rawTransact(opcode: Ptp.opNikonStartLiveView), r.isOk {
            stallCount = 0
            errorGraceUntil = Clock.now() + 2
        }
    }

    private func stopWithError(_ message: String) {
        frameTask?.cancel()
        frameTask = nil
        metrics.end(at: Clock.now())
        state = .error
        errorMessage = message
        latestFrame = nil
        cameraAfArea = nil
        log("取景终止：\(message)")
    }

    private func fail(_ message: String) {
        state = .error
        errorMessage = message
        log("取景启动失败：\(message)")
    }

    // MARK: - 停流

    func stopLiveView() async {
        frameTask?.cancel()
        frameTask = nil
        metrics.end(at: Clock.now())
        latestFrame = nil
        cameraAfArea = nil
        lastFramePrint = nil
        recentSizes.removeAll()

        guard let session = client.sessionOrNil() else {
            state = .stopped
            return
        }
        _ = try? await session.rawTransact(opcode: Ptp.opNikonEndLiveView)
        await restoreExposureMode(session)
        state = .stopped
    }

    /// 只在这个值仍是 0x8012（遥控模式）时才写回——用户中途自己拧了拨盘就别抢。
    private func restoreExposureMode(_ session: PtpSession) async {
        guard let original = originalExposureProgram else { return }
        defer { originalExposureProgram = nil }
        guard let current = try? await session.getDevicePropValue(Ptp.propExposureProgramMode),
              let data = current.dataIn, data.count >= 2, current.isOk,
              data.readLE16(at: 0) == UInt16(Ptp.propValueRemoteMode) else { return }
        await writeProp(session, Ptp.propExposureProgramMode, original)
    }

    // MARK: - 交互

    /// 触摸对焦：屏幕归一化点 → AF 域 → 0x9205 → 150ms → 0x90C1。
    ///
    /// 坐标域来自帧头（见 LiveViewHeader）；没有帧头时按 4000×按比例折算兜底。
    @discardableResult
    func touchFocus(normalizedX: Double, normalizedY: Double) async -> Bool {
        guard let session = client.sessionOrNil() else { return false }
        let header = latestFrame?.header
        let coord: (x: Int, y: Int)
        if let header, let point = header.afCoord(normalizedX: normalizedX, normalizedY: normalizedY) {
            coord = point
        } else {
            coord = Self.fallbackAfCoord(normalizedX: normalizedX, normalizedY: normalizedY,
                                         frame: latestFrame)
        }
        do {
            let r = try await session.rawTransact(
                opcode: Ptp.opNikonChangeAfArea,
                params: [UInt32(coord.x), UInt32(coord.y)]
            )
            guard r.isOk else { return false }
            try? await Clock.sleep(ms: 150)
            let drive = try await session.rawTransact(opcode: Ptp.opNikonAfDrive)
            return drive.isOk
        } catch {
            log("触摸对焦失败：\(error)")
            return false
        }
    }

    /// 无帧头兜底：箱宽 4000，箱高按**帧的宽高比**折算（不是固定 4:3）。
    ///
    /// 安卓 `AfCoordMapper.kt:24-53`：`boxH = 4000 * frameHeight / frameWidth`。
    /// 固定 4:3 会让 3:2 的机身在纵向偏移几个百分点。
    static func fallbackAfCoord(normalizedX nx: Double, normalizedY ny: Double, frame: LiveViewFrame?) -> (x: Int, y: Int) {
        let boxW = 4_000.0
        let aspect = Double(frame?.header?.jpegHeight ?? 3) / Double(frame?.header?.jpegWidth ?? 2)
        let boxH = (boxW * aspect).rounded()
        let x = Int((min(max(nx, 0), 1) * boxW).rounded())
        let y = Int((min(max(ny, 0), 1) * boxH).rounded())
        return (min(max(x, 0), 3_999), min(max(y, 0), max(0, Int(boxH) - 1)))
    }

    /// 手动对焦驱动。speed 1..5，越大越粗。
    @discardableResult
    func manualFocusDrive(direction: Int, speed: Int = 2) async -> Bool {
        guard let session = client.sessionOrNil() else { return false }
        let r = try? await session.rawTransact(
            opcode: Ptp.opNikonMfDrive,
            params: [UInt32(bitPattern: Int32(direction)), UInt32(clamping: speed)]
        )
        return r?.isOk ?? false
    }

    func setZoom(_ value: Double) {
        zoomLevel = min(max(value, minZoom), maxZoom)
    }

    func zoomIn() { setZoom(zoomLevel + 0.5) }
    func zoomOut() { setZoom(zoomLevel - 0.5) }
    func resetZoom() { zoomLevel = 1.0 }

    func setGrid(_ overlay: GridOverlay) { gridOverlay = overlay }
    func cycleGrid() { gridOverlay = gridOverlay.next() }

    // MARK: - 供快门/录像调用

    /// 按下快门前：暂时放宽取错阈值（对焦/曝光会停流几百毫秒）。
    func grantFrameErrorGrace(seconds: TimeInterval? = nil) {
        errorGraceUntil = Clock.now() + (seconds ?? Self.errorGraceSeconds)
    }

    /// 快门窗口：这期间的近黑帧要丢掉。
    func beginCaptureGuard(seconds: TimeInterval? = nil) {
        let window = seconds ?? Self.captureGuardSeconds
        captureGuardUntil = Clock.now() + window
        grantFrameErrorGrace(seconds: window + 2)
    }

    /// 期望内的停流（切录像模式等），不累计错误。
    func beginExpectedStall() { expectedStall = true }
    func endExpectedStall() {
        expectedStall = false
        errorGraceUntil = Clock.now() + 2
    }

    /// 链路断开时的就地收尾。**不发 0x9202**——会话已经没了，发命令只会再等一次超时。
    func abortForDisconnect() {
        frameTask?.cancel()
        frameTask = nil
        metrics.end(at: Clock.now())
        latestFrame = nil
        cameraAfArea = nil
        lastFramePrint = nil
        recentSizes.removeAll()
        originalExposureProgram = nil
        state = .stopped
        errorMessage = nil
    }

    // MARK: - 属性写

    private func writeProp(_ session: PtpSession, _ code: UInt16, _ value: UInt16) async {
        var payload = Data()
        payload.appendLE(value)
        _ = try? await session.setDevicePropValue(code, value: payload)
    }
}

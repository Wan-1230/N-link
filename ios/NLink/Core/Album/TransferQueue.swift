//
//  TransferQueue.swift
//  N-Link
//
//  下载队列。逐条对齐安卓 `camera/gallery/TransferManager.kt` 的任务状态机与
//  `TransferQueueSummary.kt` 的摘要文案。
//
//  五条硬约束（全部来自真机事故，不是设计偏好）：
//  1. **并发度 1**。PTP 命令通道全局串行，并发下载只会让每条都变慢，且相机会
//     在交错的事务号上返回错乱数据（`:1710-1765`）。
//  2. **暂停/取消保留 tmp 文件**做断点续传，只有 FAILED 与 cancelAll 才删。
//     「取消 = 丢掉已下的 800MB」是本项目明确否决的行为。
//  3. **>2GB 只能整份重下**。`0x101B` 的 offset 是 32 位参数，超过 Int32 上限后
//     续传不可信；`ResumePolicy` 就是这条判据，必须保留降级路径与它的文案。
//  4. **任务级重试恰好一次**。重试两次以上会让看似「网络抖动」的场景变成
//     用户以为卡死。
//  5. **缩略图在批量传输期间整体让路**。每张缩略图都要占一次命令通道，
//     和原图抢通道会把 20 分钟的传输拖成 40 分钟。
//

import Foundation
import Combine

// MARK: - 状态

enum TransferTaskStatus: String, Sendable, Equatable {
    case pending = "PENDING"
    case downloading = "DOWNLOADING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"

    var title: String {
        switch self {
        case .pending: return "等待中"
        case .downloading: return "下载中"
        case .completed: return "已完成"
        case .failed: return "失败"
        case .cancelled: return "已取消"
        }
    }

    var isRetryable: Bool { self == .failed || self == .cancelled }
    var isFinished: Bool { self == .completed || self == .failed || self == .cancelled }
}

struct TransferTask: Identifiable, Sendable, Equatable {
    let file: CameraFile
    var status: TransferTaskStatus
    /// 临时文件路径（续传依据）
    var tempPath: String?
    var failReason: String?

    var id: UInt32 { file.handle }
}

enum TransferState: Sendable, Equatable {
    case idle
    case downloading(handle: UInt32, received: Int64, total: Int64)
    case paused
    case completed(handle: UInt32)

    var isBusy: Bool {
        switch self {
        case .downloading: return true
        default: return false
        }
    }
}

enum TransferResult: Sendable, Equatable {
    case success(path: String)
    case failed(reason: String)
    case cancelled
}

/// 单张文件的下载进度（预览页按 handle 订阅）
enum DownloadProgress: Sendable, Equatable {
    case notQueued
    case queued
    case downloading(received: Int64, total: Int64)
    case completed(localPath: String)
    case failed(reason: String)
}

// MARK: - 续传策略

enum ResumePolicy {

    /// 32 位 offset 能否容纳「已收字节数 + 本次分块」。
    ///
    /// 用 `Int32.max` 而不是 `Int.max`：这是**协议参数宽度**，不是平台整数宽度。
    static func offsetFits(offset: Int64, chunkSize: Int64) -> Bool {
        offset <= Int64(Int32.max) - chunkSize
    }

    /// 中途放弃后是否必须整份重下。
    ///
    /// 起点为 0（从未写下任何字节）或起点已越过 32 位上限的，都只能重下。
    static func restartWholeFileAfterAbort(startOffset: Int64) -> Bool {
        startOffset == 0 || startOffset > Int64(Int32.max) - 1
    }

    /// 单块大小：Wi-Fi 5GHz 8MB，其余 4MB。
    ///
    /// 5GHz 的往返延迟低且丢包少，8MB 能把事务开销摊薄；2.4GHz 上 8MB 一旦重传
    /// 就要重传 8MB，反而更慢。
    static func chunkSize(is5GHz: Bool) -> Int64 {
        is5GHz ? 8 * 1024 * 1024 : 4 * 1024 * 1024
    }
}

// MARK: - 队列摘要

/// 队列页顶部的一行摘要。文案与安卓 `TransferQueueSummary.kt:8-41` 一致。
struct TransferQueueSummary: Sendable, Equatable {
    let total: Int
    let finished: Int
    let failed: Int
    let downloading: Int
    let paused: Bool

    var position: Int { finished + (downloading > 0 ? 1 : 0) }

    var text: String {
        guard total > 0 else { return "" }
        if paused && downloading == 0 { return "已暂停 \(finished)/\(total)" }
        if downloading > 0 { return "下载中 \(position)/\(total)" }
        if failed > 0 && failed == total { return "\(failed) 项下载失败" }
        if finished == total { return "已完成 \(finished)/\(total)" }
        if failed > 0 { return "\(failed) 项下载失败 · \(finished)/\(total)" }
        return "等待中 0/\(total)"
    }

    static func from(_ tasks: [TransferTask], paused: Bool) -> TransferQueueSummary {
        TransferQueueSummary(
            total: tasks.count,
            finished: tasks.filter { $0.status == .completed }.count,
            failed: tasks.filter { $0.status == .failed }.count,
            downloading: tasks.filter { $0.status == .downloading }.count,
            paused: paused
        )
    }
}

// MARK: - 队列

/// 下载队列。UI 层的唯一数据源。
@MainActor
final class TransferQueue: ObservableObject {

    // MARK: 对外状态

    @Published private(set) var tasks: [TransferTask] = []
    @Published private(set) var state: TransferState = .idle
    @Published private(set) var message: String?
    @Published private(set) var speedBytesPerSecond: Double = 0
    /// 已完成文件的句柄 → 本地路径
    @Published private(set) var completedPaths: [UInt32: String] = [:]

    /// 摘要（给队列面板标题用）
    var summary: TransferQueueSummary {
        TransferQueueSummary.from(tasks, paused: isPaused)
    }

    var isPaused: Bool {
        if case .paused = state { return true }
        return false
    }

    // MARK: 依赖

    private let settings: AppSettings
    private let saver: FileSaver
    private let log: @Sendable (String) -> Void
    private weak var client: CameraConnectionClient?

    private var currentTask: Task<Void, Never>?
    private var speedWindow: [(time: TimeInterval, bytes: Int64)] = []
    private let tempDirectory: URL

    /// 单次重试的会话等待上限。安卓同值（`TRANSFER_RECONNECT_WAIT_MS=30_000`）。
    private static let reconnectWait: TimeInterval = 30
    /// 断线恢复的轮询间隔
    private static let reconnectPollInterval: TimeInterval = 0.5
    /// 重试前的普通等待
    private static let retryDelay: TimeInterval = 1.5

    init(
        client: CameraConnectionClient,
        settings: AppSettings = .shared,
        saver: FileSaver = FileSaver(),
        log: @escaping @Sendable (String) -> Void = { _ in }
    ) {
        self.client = client
        self.settings = settings
        self.saver = saver
        self.log = log
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("n-link_transfer", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        self.tempDirectory = base
    }

    // MARK: - 入队

    /// 入队。返回真正新增的数量（0 表示全部已存在/已下载）。
    ///
    /// 三层去重与安卓一致：已下载 → 队列内同 handle（FAILED/CANCELLED 原地复活）→ 批内去重。
    @discardableResult
    func enqueue(_ files: [CameraFile], expandPair: Bool = true, allFiles: [CameraFile] = []) -> Int {
        var pending: [CameraFile] = files
        if expandPair, settings.rawPairMode != .both {
            let plan = RawJpegPairing.plan(allFiles.isEmpty ? files : allFiles)
            let lookup = Dictionary(uniqueKeysWithValues: (allFiles.isEmpty ? files : allFiles).map { ($0.handle, $0) })
            pending = RawJpegPairing.expand(files, plan: plan, mode: settings.rawPairMode, lookup: { lookup[$0] })
        }

        var added = 0
        var seenInBatch = Set<UInt32>()
        for file in pending {
            guard seenInBatch.insert(file.handle).inserted else { continue }
            if completedPaths[file.handle] != nil { continue }

            if let index = tasks.firstIndex(where: { $0.file.handle == file.handle }) {
                // 已失败的原地复活并移到队尾，而不是再压一条重名任务
                if tasks[index].status.isRetryable {
                    var revived = tasks.remove(at: index)
                    revived.status = .pending
                    revived.failReason = nil
                    tasks.append(revived)
                    added += 1
                }
                continue
            }

            tasks.append(TransferTask(file: file, status: .pending, tempPath: nil, failReason: nil))
            added += 1
        }

        if added == 0 && !files.isEmpty {
            message = "所选照片均已在队列中或已下载"
        }
        processQueue()
        return added
    }

    // MARK: - 控制

    func pause() {
        guard !isPaused else { return }
        currentTask?.cancel()
        currentTask = nil
        state = .paused
        speedBytesPerSecond = 0
        speedWindow.removeAll()
        log("传输已暂停，保留 \(tasks.filter { $0.tempPath != nil }.count) 个临时文件供续传")
    }

    func resume() {
        guard isPaused else { return }
        state = .idle
        processQueue()
    }

    /// 取消全部：清队列并删临时目录。这是唯一会丢已下字节的入口。
    func cancelAll() {
        currentTask?.cancel()
        currentTask = nil
        tasks.removeAll()
        state = .idle
        speedBytesPerSecond = 0
        speedWindow.removeAll()
        try? FileManager.default.removeItem(at: tempDirectory)
        try? FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
    }

    /// 重排全部可重试任务
    func retryFailed() {
        for index in tasks.indices where tasks[index].status.isRetryable {
            tasks[index].status = .pending
            tasks[index].failReason = nil
        }
        processQueue()
    }

    /// 移除单条任务（下载中的不可删）
    func removeTask(handle: UInt32) {
        guard let index = tasks.firstIndex(where: { $0.file.handle == handle }) else { return }
        guard tasks[index].status != .downloading else { return }
        tasks.remove(at: index)
    }

    /// 单张的下载进度
    func progress(for handle: UInt32) -> DownloadProgress {
        if let path = completedPaths[handle] { return .completed(localPath: path) }
        guard let task = tasks.first(where: { $0.file.handle == handle }) else { return .notQueued }
        switch task.status {
        case .pending: return .queued
        case .downloading:
            if case let .downloading(h, received, total) = state, h == handle {
                return .downloading(received: received, total: total)
            }
            return .downloading(received: 0, total: task.file.size)
        case .completed: return .completed(localPath: completedPaths[handle] ?? "")
        case .failed: return .failed(reason: task.failReason ?? "传输失败")
        case .cancelled: return .queued
        }
    }

    // MARK: - 队列推进

    private func processQueue() {
        guard currentTask == nil, !isPaused else { return }
        guard let index = tasks.firstIndex(where: { $0.status == .pending }) else {
            if case .downloading = state { return }
            state = .idle
            return
        }
        let file = tasks[index].file
        tasks[index].status = .downloading
        currentTask = Task { [weak self] in
            guard let self else { return }
            let result = await self.run(file)
            await self.finish(file: file, result: result)
        }
    }

    private func finish(file: CameraFile, result: TransferResult) async {
        currentTask = nil
        guard let index = tasks.firstIndex(where: { $0.file.handle == file.handle }) else {
            processQueue()
            return
        }

        switch result {
        case .success(let path):
            tasks[index].status = .completed
            tasks[index].tempPath = nil
            completedPaths[file.handle] = path
            state = .completed(handle: file.handle)
            message = "已保存: \((path as NSString).lastPathComponent)"

        case .failed(let reason):
            tasks[index].status = .failed
            tasks[index].failReason = reason
            message = reason

        case .cancelled:
            // 取消回 PENDING 而不是 CANCELLED —— 「暂停后继续」应当能接着下
            tasks[index].status = .pending
        }

        speedBytesPerSecond = 0
        speedWindow.removeAll()
        processQueue()
    }

    // MARK: - 单文件下载

    private func run(_ file: CameraFile, isRetry: Bool = false) async -> TransferResult {
        guard let session = await client?.sessionOrNil() else {
            // 会话不在：等最多 30s 让连接层恢复，再试一次
            if !isRetry {
                let recovered = await awaitSession()
                if recovered { return await run(file, isRetry: true) }
            }
            return .failed(reason: "相机未连接，请先在设备页连接后再试")
        }

        let temp = tempURL(for: file)
        var offset: Int64 = fileExists(temp) ? fileSize(temp) : 0
        if offset >= file.size && file.size > 0 {
            // 已下满：直接落盘
            return await save(temp: temp, file: file)
        }

        do {
            try await download(session: session, file: file, temp: temp, startOffset: offset)
            return await save(temp: temp, file: file)
        } catch let error as CameraError where error.isTransportLoss {
            // 断线：清理后重试一次
            if !isRetry {
                let recovered = await awaitSession()
                if recovered { return await run(file, isRetry: true) }
            }
            return .failed(reason: "传输中断（\(offset)/\(file.size) 字节），请检查连接后重试")
        } catch is CancellationError {
            return .cancelled
        } catch {
            return .failed(reason: describe(error, file: file, offset: offset))
        }
    }

    /// 分块下载。失败的分块把临时文件截回块起点；分块仍大于 1MB 时减半重试。
    private func download(session: CameraSession, file: CameraFile, temp: URL, startOffset: Int64) async throws {
        var offset = startOffset
        var chunk = ResumePolicy.chunkSize(is5GHz: await is5GHzLink())
        var rebuilt = false

        if !FileManager.default.fileExists(atPath: temp.path) {
            FileManager.default.createFile(atPath: temp.path, contents: nil)
        }
        guard let handle = try? FileHandle(forWritingTo: temp) else {
            throw FileSaveError.cannotOpenTemp
        }
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(offset))

        // 超过 32 位可表达范围的续传一律从头
        if !ResumePolicy.offsetFits(offset: offset, chunkSize: chunk) {
            offset = 0
            try handle.truncate(atOffset: 0)
            rebuilt = true
            log("起点越过 32 位 offset 上限，整份重下：\(file.fileName)")
        }

        while offset < file.size {
            try Task.checkCancellation()

            let want = min(chunk, file.size - offset)
            do {
                let data = try await session.readRange(handle: file.handle, offset: UInt64(offset), length: UInt32(want))
                guard !data.isEmpty else { break }

                if !ResumePolicy.offsetFits(offset: offset, chunkSize: Int64(data.count)) {
                    // 越界：整份重下（安卓在同一判定点 break 并标记 abortedBeyondRange）
                    offset = 0
                    try handle.truncate(atOffset: 0)
                    try handle.seek(toOffset: 0)
                    rebuilt = true
                    continue
                }

                try handle.write(contentsOf: data)
                offset += Int64(data.count)
                reportProgress(file: file, received: offset)
            } catch {
                // 把临时文件截回本块起点，避免半块数据混进续传
                try? handle.truncate(atOffset: UInt64(offset))
                try? handle.seek(toOffset: UInt64(offset))
                if chunk > 1_048_576 {
                    chunk /= 2
                    log("分块失败，减小到 \(chunk / 1024)KB 重试：\(error)")
                    continue
                }
                throw error
            }
        }

        if offset < file.size && rebuilt {
            throw CameraError.storageUnavailable(
                detail: "这份文件超过 2GB，机身不支持从中间接着传；已重试整份仍未完成，请靠近路由器或改用数据线后重试"
            )
        }
    }

    private func save(temp: URL, file: CameraFile) async -> TransferResult {
        do {
            let path = try await saver.save(temp: temp, file: file, location: settings.saveLocation)
            try? FileManager.default.removeItem(at: temp)
            return .success(path: path)
        } catch {
            return .failed(reason: saver.describe(error))
        }
    }

    // MARK: - 辅助

    private func awaitSession() async -> Bool {
        let deadline = Clock.now() + Self.reconnectWait
        while Clock.now() < deadline {
            if let session = await client?.sessionOrNil(), session != nil { return true }
            try? await Clock.sleep(seconds: Self.reconnectPollInterval)
        }
        return false
    }

    private func is5GHzLink() async -> Bool {
        await client?.isLink5GHz ?? false
    }

    private func reportProgress(file: CameraFile, received: Int64) {
        state = .downloading(handle: file.handle, received: received, total: file.size)
        let now = Clock.now()
        speedWindow.append((now, received))
        while let first = speedWindow.first, now - first.time > 2 {
            speedWindow.removeFirst()
        }
        if let first = speedWindow.first, now - first.time > 0.5 {
            let bytes = received - first.bytes
            speedBytesPerSecond = Double(bytes) / (now - first.time)
        }
    }

    private func tempURL(for file: CameraFile) -> URL {
        tempDirectory.appendingPathComponent("tmp_\(file.handle).bin")
    }

    private func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func describe(_ error: Error, file: CameraFile, offset: Int64) -> String {
        if let cameraError = error as? CameraError {
            return cameraError.message
        }
        return "传输中断（\(offset)/\(file.size) 字节），请检查连接后重试"
    }
}

// MARK: - 错误

enum FileSaveError: LocalizedError {
    case cannotOpenTemp
    case notEnoughSpace(remaining: Int64, needed: Int64)
    case noPhotoPermission
    case writeInterrupted(String)

    var errorDescription: String? {
        switch self {
        case .cannotOpenTemp:
            return "保存失败：无法建立临时文件，请检查手机剩余空间"
        case .notEnoughSpace(let remaining, let needed):
            let r = ByteCountFormatter.string(fromByteCount: remaining, countStyle: .file)
            let n = ByteCountFormatter.string(fromByteCount: needed, countStyle: .file)
            return "保存失败：存储空间不足（剩余 \(r)，需要 \(n)）"
        case .noPhotoPermission:
            return "保存失败：没有相册写入权限。请到「设置 → 隐私与安全性 → 照片」打开 N-Link 的访问权限"
        case .writeInterrupted(let detail):
            return "保存失败：写入相册中断（\(detail)）"
        }
    }
}

//
//  AlbumLoader.swift
//  N-Link
//
//  相册分页加载。逐条对齐安卓 `camera/gallery/TransferManager.kt` 的
//  `loadCameraPhotos` 路径（:105 起）与 `TransferViewModel` 的渐进发射（:769-790）。
//
//  三条不可改的行为：
//  1. **没有数值游标**。安卓每次都先取整卡句柄全集再 `chunked(18)`，因为 PTP/IP
//     没有便宜的分页原语，`GetObjectHandles` 是一次性全量返回的。照抄它而不是
//     自创「第 N 页」——自创会在机身句柄数变化时错页。
//  2. **快路径 `GetObjectPropList`(0x9805)**。一次拿全卡属性，省掉 N 次 `0x1008`。
//     N 次单条请求在 200 张的卡上要 40 秒以上，用户会以为 App 挂了。
//  3. **`holdPages` 语义**。首进相册 / 下拉刷新 / 拍摄同步期间不发中间态：
//     handle 原始序与「拍摄时间倒序」几乎完全逆序，渐进发射会让视口在加载过程中
//     从底部跳到顶部。只有第二页之后（增量刷新）才逐片外发。
//

import Foundation

// MARK: - 加载结果

struct AlbumPage: Sendable {
    let files: [CameraFile]
    let index: Int
    let isLast: Bool
}

enum AlbumLoadPhase: String, Sendable {
    case idle
    case queryingHandles = "取句柄"
    case fetchingMetadata = "读文件信息"
    case done
}

struct AlbumLoadStats: Sendable {
    var handles = 0
    var fetched = 0
    var fastPath = false
}

// MARK: - 加载器

/// 相册加载器。**所有 PTP 调用串行**——机身同一时刻只允许一条命令通道，
/// 并发只会让每条都变慢（安卓为此把传输并发度压到 1，见 `TransferManager.kt:1710`）。
actor AlbumLoader {

    /// 每页条数。18 是安卓实测值：3 列 × 6 行的整数倍，且一屏 12 格滚动一次就能看到下一页。
    static let pageSize = 18
    /// 每片元数据的并发度。命令通道串行，这里只是让 UI 线程不被单张阻塞。
    static let metadataConcurrency = 4
    /// 整轮加载的硬超时。卡上几千张时超时告警比无限转圈强。
    static let loadHardDeadline: TimeInterval = 60

    private let session: any CameraSession
    private let log: @Sendable (String) -> Void

    init(session: any CameraSession, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.session = session
        self.log = log
    }

    // MARK: 对外入口

    /// 加载整卡文件列表。
    ///
    /// - Parameters:
    ///   - holdPages: true 时只在最后回一次（首进/刷新/拍摄同步），false 时逐页回。
    ///   - onPage: 每页回调（主线程外，调用方自己切）。
    func load(
        holdPages: Bool,
        onPhase: @Sendable (AlbumLoadPhase) -> Void = { _ in },
        onPage: @Sendable (AlbumPage) -> Void
    ) async throws -> [CameraFile] {

        let deadline = Clock.now() + Self.loadHardDeadline

        onPhase(.queryingHandles)
        let handles = try await collectHandles()
        log("相册句柄：\(handles.count) 个")
        guard !handles.isEmpty else { return [] }

        onPhase(.fetchingMetadata)
        let files = try await fetchMetadata(handles: handles, deadline: deadline, holdPages: holdPages, onPage: onPage)
        onPhase(.done)
        return files
    }

    // MARK: 句柄

    /// 取整卡句柄全集。
    ///
    /// 先列 storage 再逐个列；全空时回落一次 `0x10007` 的特殊 storageId `0xFFFFFFFF`
    /// ——部分机身（尤其 USB 直连的旧型号）只认后者。
    private func collectHandles() async throws -> [UInt32] {
        let storages: [StorageInfo]
        do {
            storages = try await withPtpTimeout(10) { [session] in try await session.listStorages() }
        } catch {
            log("listStorages 失败，回落到 0xFFFFFFFF：\(error)")
            storages = []
        }

        var handles: [UInt32] = []
        for storage in storages {
            do {
                let batch = try await withPtpTimeout(15) { [session, id = storage.storageId] in
                    try await session.listObjectHandles(storageId: id, parent: 0)
                }
                handles.append(contentsOf: batch)
            } catch {
                log("storage 0x\(String(storage.storageId, radix: 16)) 列句柄失败：\(error)")
            }
        }

        if handles.isEmpty {
            do {
                handles = try await withPtpTimeout(15) { [session] in
                    try await session.listObjectHandles(storageId: 0xFFFF_FFFF, parent: 0)
                }
            } catch {
                log("兜底列句柄也失败：\(error)")
                throw error
            }
        }

        // 去重但保持机身给的原始顺序（排序交给 AlbumSorter，这里不乱动）
        var seen = Set<UInt32>()
        return handles.filter { seen.insert($0).inserted }
    }

    // MARK: 元数据

    /// 读元数据。
    ///
    /// 快路径 `GetObjectPropList` 能一次拿全卡；失败或机身不支持时退回逐张 `GetObjectInfo`。
    private func fetchMetadata(
        handles: [UInt32],
        deadline: TimeInterval,
        holdPages: Bool,
        onPage: @Sendable (AlbumPage) -> Void
    ) async throws -> [CameraFile] {

        // —— 快路径 ——
        if let all = await tryFastPath(handles: handles) {
            // 快路径一次就是全部：仍然按页切给调用方，保证 UI 的「逐页出现」有统一来源
            return emitPages(all, holdPages: holdPages, onPage: onPage)
        }

        // —— 逐张路径 ——
        var collected: [CameraFile] = []
        var lastEmit = Clock.now()
        let chunks = stride(from: 0, to: handles.count, by: Self.pageSize).map {
            Array(handles[$0..<min($0 + Self.pageSize, handles.count)])
        }

        for (index, chunk) in chunks.enumerated() {
            guard Clock.now() < deadline else {
                log("相册加载超时，已取 \(collected.count) / \(handles.count)")
                break
            }
            let page = await fetchChunk(chunk)
            collected.append(contentsOf: page)

            // holdPages：只回最后一页；否则节流 250ms
            let isLast = index == chunks.count - 1
            let due = holdPages ? isLast : (isLast || Clock.now() - lastEmit >= 0.25)
            if due {
                lastEmit = Clock.now()
                onPage(AlbumPage(files: collected, index: index, isLast: isLast))
            }
        }

        if holdPages, chunks.isEmpty {
            onPage(AlbumPage(files: [], index: 0, isLast: true))
        }
        return collected
    }

    /// 一片 18 张，片内 4 路并发（命令通道串行，实际是排队）。
    private func fetchChunk(_ handles: [UInt32]) async -> [CameraFile] {
        await withTaskGroup(of: (Int, CameraFile?).self) { group in
            var iterator = handles.enumerated().makeIterator()
            var inFlight = 0

            func addNext() {
                guard let (index, handle) = iterator.next() else { return }
                inFlight += 1
                group.addTask { [session, log] in
                    do {
                        let info = try await withPtpTimeout(8) {
                            try await session.getObjectInfo(handle: handle)
                        }
                        return (index, CameraFile.from(info))
                    } catch {
                        log("getObjectInfo(0x\(String(handle, radix: 16))) 失败：\(error)")
                        return (index, nil)
                    }
                }
            }

            for _ in 0..<Self.metadataConcurrency { addNext() }

            var result: [(Int, CameraFile?)] = []
            while let item = await group.next() {
                result.append(item)
                inFlight -= 1
                addNext()
            }
            _ = inFlight
            return result.sorted { $0.0 < $1.0 }.compactMap { $0.1 }
        }
    }

    /// `GetObjectPropList`(0x9805) 快路径。返回 nil 表示机身不支持或返回不可用。
    private func tryFastPath(handles: [UInt32]) async -> [CameraFile]? {
        guard let ptp = session as? PtpSession else { return nil }
        do {
            let props = try await withPtpTimeout(15) {
                try await ptp.getObjectPropList(handles: handles)
            }
            guard !props.isEmpty else { return nil }
            log("快路径命中：\(props.count) 条属性")
            return props.map { CameraFile.from($0, fallbackStorageId: 0) }
        } catch {
            log("快路径不可用，改逐张：\(error)")
            return nil
        }
    }

    private func emitPages(
        _ files: [CameraFile],
        holdPages: Bool,
        onPage: @Sendable (AlbumPage) -> Void
    ) -> [CameraFile] {
        guard !files.isEmpty else {
            onPage(AlbumPage(files: [], index: 0, isLast: true))
            return []
        }
        let pages = stride(from: 0, to: files.count, by: Self.pageSize).map {
            Array(files[$0..<min($0 + Self.pageSize, files.count)])
        }
        if holdPages {
            onPage(AlbumPage(files: files, index: pages.count - 1, isLast: true))
        } else {
            for (index, _) in pages.enumerated() {
                let upto = min((index + 1) * Self.pageSize, files.count)
                onPage(AlbumPage(files: Array(files[0..<upto]), index: index, isLast: index == pages.count - 1))
            }
        }
        return files
    }
}

// MARK: - 超时包装

/// 给单个 PTP 调用套超时。
///
/// 用 `Task` 竞速而不是依赖 socket 超时：socket 层只保证「包边界能否读到」，
/// 相机在事务中途沉默时 socket 不会报错，会一直挂到 60s 的传输超时。
func withPtpTimeout<T: Sendable>(
    _ seconds: TimeInterval,
    op: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await op() }
        group.addTask {
            try await Clock.sleep(seconds: seconds)
            throw CameraError.bareTimeout
        }
        defer { group.cancelAll() }
        guard let first = try await group.next() else { throw CameraError.bareTimeout }
        return first
    }
}

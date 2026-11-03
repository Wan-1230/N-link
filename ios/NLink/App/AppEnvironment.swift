//
//  AppEnvironment.swift
//  N-Link
//
//  全 App 的依赖容器。对应安卓的 Hilt 模块 + 那几个 `@Singleton` 管理器。
//
//  为什么是单个 ObservableObject 而不是继续用 `@EnvironmentObject` 注入一堆：
//  Android 侧这些管理器互相引用（传输队列要会话、遥控要监看、监看要会话），
//  拆成多个 EnvironmentObject 只会让 View 的依赖声明变长而不会更清晰。
//  这里做成一层显式容器，谁依赖谁在 init 里一眼看得见。
//

import SwiftUI

@MainActor
final class AppEnvironment: ObservableObject {

    // MARK: 设置

    let settings: AppSettings

    // MARK: 连接

    let client: CameraConnectionClient
    let discovery: CameraDiscovery

    // MARK: 相机能力

    let parameters: CameraParameterManager
    let liveView: LiveViewManager
    let remote: RemoteShootingManager
    /// 拍摄预设（对应安卓 PresetManager）
    let presets: PresetStore

    // MARK: 内容

    let transfer: TransferQueue
    let saver: FileSaver

    // MARK: 页面级状态

    /// 相册（相机源）
    @Published var album: AlbumState
    /// 相册当前页签
    @Published var albumTab: AlbumTab = .camera
    /// 设备页当前连接方式面板
    @Published var connectionPanel: ConnectionPanel = .wifiAp
    /// 底部 dock 当前页
    @Published var tab: AppTab = .device

    /// 全局轻提示（自动消失）
    @Published var toast: ToastMessage?

    // MARK: 生命周期

    private var connectionObserver: Task<Void, Never>?
    private var albumTask: Task<Void, Never>?

    init() {
        let settings = AppSettings.shared
        let client = CameraConnectionClient()
        self.settings = settings
        self.client = client
        self.discovery = CameraDiscovery()

        let parameters = CameraParameterManager(client: client)
        let liveView = LiveViewManager(client: client)
        self.parameters = parameters
        self.liveView = liveView
        self.remote = RemoteShootingManager(client: client, liveView: liveView)
        self.presets = PresetStore()

        let saver = FileSaver()
        self.saver = saver
        self.transfer = TransferQueue(client: client, settings: settings, saver: saver)

        self.album = AlbumState()

        observeConnection()
    }

    // MARK: - 启动后的接线

    func start() {
        // 已连上会话后，参数轮询与相册预取才有意义
        if client.state.isConnected {
            onConnected()
        }
    }

    /// 连接状态变化的总线。放在这里而不是各页面里，是因为「连上之后要做什么」
    /// 属于全局编排（拉参数、取相册、开模式监听），不属于任何一个页面。
    private func observeConnection() {
        connectionObserver = Task { [weak self] in
            var wasConnected = false
            while !Task.isCancelled {
                guard let self else { return }
                let connected = self.client.state.isConnected
                if connected, !wasConnected {
                    self.onConnected()
                } else if !connected, wasConnected {
                    self.onDisconnected()
                }
                wasConnected = connected
                try? await Clock.sleep(ms: 400)
            }
        }
    }

    private func onConnected() {
        Task {
            await parameters.readAll()
            parameters.startPolling()
            parameters.startModeWatch(streaming: liveView.state == .running)
        }
        reloadAlbum(holdPages: true)
    }

    private func onDisconnected() {
        parameters.stopPolling()
        parameters.stopModeWatch()
        liveView.abortForDisconnect()
        remote.shutdown()
        album = AlbumState()
    }

    // MARK: - 相册

    func reloadAlbum(holdPages: Bool) {
        guard let session = client.sessionOrNil() else {
            album = AlbumState(
                phase: .idle,
                files: [],
                error: "连接相机后查看相册"
            )
            return
        }
        albumTask?.cancel()
        album.isLoading = true
        if !holdPages { album.files = [] }

        albumTask = Task { [weak self] in
            guard let self else { return }
            do {
                let loader = AlbumLoader(session: session)
                let files = try await loader.load(
                    holdPages: holdPages,
                    onPhase: { phase in
                        Task { @MainActor in self.album.phase = phase }
                    },
                    onPage: { page in
                        Task { @MainActor in
                            if !holdPages {
                                self.album.files = page.files
                            }
                        }
                    }
                )
                guard !Task.isCancelled else { return }
                self.album.files = files
                self.album.isLoading = false
                self.album.phase = .done
                self.album.error = nil
            } catch {
                guard !Task.isCancelled else { return }
                self.album.isLoading = false
                self.album.phase = .idle
                self.album.error = (error as? CameraError)?.message ?? error.localizedDescription
            }
        }
    }

    /// 拍完照后刷新相册（延后一点，等相机把文件落定）
    func scheduleAlbumRefreshAfterCapture() {
        Task {
            try? await Clock.sleep(seconds: 2.5)
            reloadAlbum(holdPages: false)
        }
    }

    // MARK: - 轻提示

    struct ToastMessage: Identifiable, Equatable {
        let id = UUID()
        let text: String
        var isError: Bool = false
    }

    func showToast(_ text: String, isError: Bool = false) {
        withAnimation(.easeOut(duration: NlMotion.fast)) {
            toast = ToastMessage(text: text, isError: isError)
        }
        let current = toast?.id
        Task {
            try? await Clock.sleep(seconds: isError ? 3.2 : 2.0)
            // 期间又有新提示就不要再清
            if toast?.id == current {
                withAnimation(.easeOut(duration: NlMotion.base)) { toast = nil }
            }
        }
    }
}

// MARK: - 页面枚举

enum AppTab: Int, CaseIterable, Identifiable {
    case device = 0
    case album = 1
    case remote = 2
    case settings = 3

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .device: return "设备"
        case .album: return "相机照片"
        case .remote: return "拍摄"
        case .settings: return "设置"
        }
    }

    var icon: String {
        switch self {
        case .device: return "house"
        case .album: return "photo.on.rectangle"
        case .remote: return "camera"
        case .settings: return "gearshape"
        }
    }

    var iconFilled: String {
        switch self {
        case .device: return "house.fill"
        case .album: return "photo.on.rectangle.fill"
        case .remote: return "camera.fill"
        case .settings: return "gearshape.fill"
        }
    }
}

enum ConnectionPanel: String, CaseIterable, Identifiable {
    case wifiAp
    case wifiSta
    case usb

    var id: String { rawValue }

    var title: String {
        switch self {
        case .wifiAp: return "WiFi AP 连接"
        case .wifiSta: return "WiFi STA 连接"
        case .usb: return "USB 有线连接"
        }
    }
}

enum AlbumTab: String, CaseIterable, Identifiable {
    case camera
    case marked
    case local

    var id: String { rawValue }

    var title: String {
        switch self {
        case .camera: return "相机照片"
        case .marked: return "已标记"
        case .local: return "本地照片"
        }
    }
}

// MARK: - 相册状态

struct AlbumState {
    var phase: AlbumLoadPhase = .idle
    /// 整卡文件（未过滤未排序）。配置项变化时从不重读，只重算展示层。
    var files: [CameraFile] = []
    var isLoading: Bool = false
    var error: String?

    var filter: PhotoFilter = .all
    var sort: AlbumSort = .default
    /// 多选中的句柄
    var selection: Set<UInt32> = []
    /// 只显示未下载（「跳过已下载」芯片）
    var onlyNotDownloaded: Bool = false
    /// 已下载句柄（来自传输历史）
    var downloadedHandles: Set<UInt32> = []

    var isEmpty: Bool { files.isEmpty && !isLoading }

    /// 原始配对表。**必须从完整 `files` 算，不能从筛过的列表算** ——
    /// 否则用户把筛选切到「只显示 RAW」时，配对的 JPG 被滤掉，
    /// 「成对下载」会退化成「只下 RAW」，而这是安卓侧踩过的坑。
    var pairPlan: RawJpegPairing.Plan { RawJpegPairing.plan(files) }

    /// 展示管线：过滤 → 配对合并 → 排序。**顺序不可换**。
    ///
    /// 配对表先由完整 `files` 算好，再作用到筛过的列表上——
    /// 这样「筛到只显示 RAW」时，被滤掉的 JPG 仍能被合并进来（否则成对下载会退化）。
    var displayFiles: [CameraFile] {
        let plan = pairPlan
        var shown = files

        if onlyNotDownloaded {
            shown = shown.filter { !downloadedHandles.contains($0.handle) }
        }
        shown = shown.filter { filter.accepts($0) }
        shown = RawJpegPairing.applyMerge(shown, plan: plan)
        return AlbumSorter.sort(shown, by: sort)
    }

    var selectedFiles: [CameraFile] {
        files.filter { selection.contains($0.handle) }
    }

    func isSelected(_ file: CameraFile) -> Bool { selection.contains(file.handle) }

    mutating func toggle(_ file: CameraFile) {
        if selection.contains(file.handle) {
            selection.remove(file.handle)
        } else {
            selection.insert(file.handle)
        }
    }

    /// 区间选择：以「上次点击位置」到当前为界全选。
    mutating func selectRange(from anchor: UInt32, to handle: UInt32) {
        let list = displayFiles
        guard let a = list.firstIndex(where: { $0.handle == anchor }),
              let b = list.firstIndex(where: { $0.handle == handle }) else { return }
        let (lo, hi) = a <= b ? (a, b) : (b, a)
        for file in list[lo...hi] { selection.insert(file.handle) }
    }
}

import SwiftUI
import UIKit

/// 缩略图缓存。
///
/// 与安卓 `thumbnailUpgrades`（cap 8）+ `thumbArrivals`（cap 256）等价，但更简单：
/// 这里只做「句柄 → 图像」的 LRU + 在途去重。
///
/// 三条纪律：
/// 1. **并发上限 2**——PTP 命令通道是全局串行的，缩略图抢太多会拖慢正在下载的原图。
/// 2. 失败**不缓存**：一次超时不该让那张图永久空白，用户滚动回来还有机会。
/// 3. 大图不留在内存：只留解码后的 `UIImage`，原始 Data 立刻释放（一张 160×120 的
///    JPEG 解码后约 75KB，256 张就是 19MB，不能再叠一份 Data）。
@MainActor
final class ThumbnailCache: ObservableObject {

    private var images: [UInt32: UIImage] = [:]
    private var order: [UInt32] = []
    private var inFlight: Set<UInt32> = []
    private var failed: Set<UInt32> = []

    private let limit = 256
    private let maxConcurrent = 2
    private weak var client: CameraConnectionClient?

    /// 每完成一张自增，供视图触发重绘
    @Published private(set) var revision: Int = 0

    init(client: CameraConnectionClient) {
        self.client = client
    }

    func image(for handle: UInt32) -> UIImage? { images[handle] }

    func hasFailed(_ handle: UInt32) -> Bool { failed.contains(handle) }

    /// 请求缩略图（幂等）。
    func request(_ handle: UInt32) {
        if images[handle] != nil || inFlight.contains(handle) || failed.contains(handle) { return }
        guard inFlight.count < maxConcurrent else { return }
        guard let client, let session = client.sessionOrNil() else { return }

        inFlight.insert(handle)
        Task { [weak self] in
            defer {
                Task { @MainActor in self?.inFlight.remove(handle) }
            }
            do {
                // 缩略图命令超时要比原图短：它只是网格里的 3 列小图，
                // 卡住 8 秒会连带堵住同一条命令通道上排队的其它请求。
                let data = try await withPtpTimeout(8) {
                    try await session.getThumbnail(handle: handle)
                }
                guard let image = UIImage(data: data) else {
                    await MainActor.run { self?.markFailed(handle) }
                    return
                }
                await MainActor.run { self?.store(image, for: handle) }
            } catch {
                await MainActor.run { self?.markFailed(handle) }
            }
        }
    }

    private func store(_ image: UIImage, for handle: UInt32) {
        images[handle] = image
        order.removeAll { $0 == handle }
        order.append(handle)
        while order.count > limit, let oldest = order.first {
            order.removeFirst()
            images.removeValue(forKey: oldest)
            failed.remove(oldest)
        }
        revision &+= 1
    }

    private func markFailed(_ handle: UInt32) {
        failed.insert(handle)
        revision &+= 1
    }

    /// 换相册/重连后清空——句柄是「本次会话内有效」的编号，跨会话复用会串图。
    func reset() {
        images.removeAll()
        order.removeAll()
        inFlight.removeAll()
        failed.removeAll()
        revision &+= 1
    }
}

/// 相机照片页。对应安卓 `TransferFragment`（1401 行）+ `TransferViewModel`。
struct GalleryView: View {

    @EnvironmentObject private var env: AppEnvironment
    @StateObject private var thumbs: ThumbnailCache
    @State private var showQueue = false
    @State private var showFilterMenu = false
    @State private var showSortMenu = false
    @State private var confirmDelete = false
    @State private var confirmCancelAll = false
    @State private var previewTarget: PreviewTarget?
    @State private var shareTarget: ShareTarget?
    /// 多选长按拖选的锚点
    @State private var dragAnchor: UInt32?

    init(client: CameraConnectionClient) {
        _thumbs = StateObject(wrappedValue: ThumbnailCache(client: client))
    }

    private var state: AlbumState { env.album }
    private var files: [CameraFile] { state.displayFiles }
    private var isSelecting: Bool { !state.selection.isEmpty }

    var body: some View {
        ZStack(alignment: .bottom) {
            NlColor.background.ignoresSafeArea()

            VStack(spacing: 0) {
                topBar
                NlDivider()
                chipBar
                if state.onlyNotDownloaded || state.filter != .all {
                    downloadStatsRow
                }
                content
            }

            bottomBar

            if showQueue {
                queueSheet
            }
        }
        .sheet(item: $previewTarget) { target in
            PhotoPreviewView(
                files: files,
                startIndex: target.index,
                thumbs: thumbs,
                onDownload: { file in
                    enqueue([file])
                },
                onShare: { file in
                    shareTarget = ShareTarget(files: [file], fromCamera: true)
                }
            )
        }
        .sheet(item: $shareTarget) { target in
            ShareSheetView(files: target.files, fromCamera: target.fromCamera)
        }
        .alert("删除照片？", isPresented: $confirmDelete) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                deleteSelected()
            }
        } message: {
            Text("将从相机存储卡永久删除这 \(state.selection.count) 张照片，不可撤销。手机上已下载的副本不受影响。")
        }
        .alert("取消全部下载？", isPresented: $confirmCancelAll) {
            Button("继续下载", role: .cancel) {}
            Button("取消全部", role: .destructive) {
                env.transfer.cancelAll()
                showQueue = false
            }
        } message: {
            Text("正在进行的 \(env.transfer.tasks.filter { $0.status == .downloading }.count) 项会中断，已完成的保留。中断的照片需要重新下载。")
        }
        .onChange(of: env.album.files) { _ in
            // 换了一整卡文件 → 句柄会变，缓存必须清，否则网格里会出现上一轮的图
            thumbs.reset()
        }
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack(spacing: NlSpacing.s16) {
            if isSelecting {
                Button("取消") {
                    withAnimation(NlMotion.fastAnim) { env.album.selection.removeAll() }
                }
                .font(NlText.subtitle)
                .foregroundStyle(NlColor.textPrimary)
                .frame(minHeight: NlSize.hitTarget)

                Text("已选 \(state.selection.count) 项")
                    .font(NlText.sectionTitle)
                    .foregroundStyle(NlColor.textPrimary)
                    .lineLimit(1)
                    .layoutPriority(1)
            } else {
                Text("相机照片")
                    .font(NlText.title)
                    .foregroundStyle(NlColor.textPrimary)
            }

            Spacer(minLength: NlSpacing.s8)

            if !isSelecting {
                Button {
                    toggleSkipDownloaded()
                } label: {
                    Image(systemName: state.onlyNotDownloaded
                          ? "line.3.horizontal.decrease.circle.fill"
                          : "line.3.horizontal.decrease.circle")
                        .font(.system(size: NlSize.iconGlyph, weight: .medium))
                        .foregroundStyle(state.onlyNotDownloaded ? NlColor.textPrimary : NlColor.textTertiary)
                        .frame(width: NlSize.iconButton, height: NlSize.iconButton)
                }
                .accessibilityLabel(state.onlyNotDownloaded ? "显示全部照片" : "跳过已下载")

                Button {
                    env.reloadAlbum(holdPages: true)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: NlSize.iconGlyph, weight: .medium))
                        .foregroundStyle(NlColor.textSecondary)
                        .frame(width: NlSize.iconButton, height: NlSize.iconButton)
                }
                .disabled(!env.client.isConnected)
                .accessibilityLabel("刷新相册")

                Menu {
                    ForEach(AlbumSortDimension.allCases, id: \.self) { dimension in
                        ForEach(AlbumSortDirection.allCases, id: \.self) { direction in
                            Button {
                                applySort(AlbumSort(dimension: dimension, direction: direction))
                            } label: {
                                Label(
                                    "\(dimension.title) \(direction.title)",
                                    systemImage: (state.sort.dimension == dimension && state.sort.direction == direction)
                                        ? "checkmark" : "arrow.up.arrow.down"
                                )
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 2) {
                        Text(state.sort.shortLabel)
                            .font(NlText.nav)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                    }
                    .foregroundStyle(NlColor.textSecondary)
                    .padding(.horizontal, NlSpacing.s8)
                    .frame(height: 32)
                    .background(NlColor.surfaceVariant, in: Capsule())
                }
                .accessibilityLabel("排序：\(state.sort.menuLabel)")

                Button("多选") {
                    // 多选只能从已有选择进入；没有选择时点它只提示怎么开始
                    if let first = files.first {
                        withAnimation(NlMotion.fastAnim) {
                            env.album.selection = [first.handle]
                        }
                    }
                }
                .font(NlText.subtitle)
                .foregroundStyle(files.isEmpty ? NlColor.textTertiary : NlColor.textPrimary)
                .disabled(files.isEmpty)
            }
        }
        .padding(.horizontal, NlSpacing.pageMargin)
        .frame(height: NlSize.topBarHeight)
        .background(NlColor.background)
    }

    // MARK: - 筛选芯片

    private var chipBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: NlSpacing.s8) {
                ForEach(PhotoFilter.chipFilters) { filter in
                    NlChip(title: filter.title, selected: state.filter == filter) {
                        withAnimation(NlMotion.fastAnim) { env.album.filter = filter }
                    }
                }
                if state.filter != .all {
                    NlChip(title: "已保护", selected: false) {
                        withAnimation(NlMotion.fastAnim) { env.album.filter = .protectedOnly }
                    }
                }
            }
            .padding(.horizontal, NlSpacing.pageMargin)
            .padding(.vertical, NlSpacing.s12)
        }
    }

    private var downloadStatsRow: some View {
        HStack {
            Text(statsText)
                .font(NlText.foot)
                .foregroundStyle(NlColor.textTertiary)
            Spacer()
        }
        .padding(.horizontal, NlSpacing.pageMargin)
        .frame(height: 32)
    }

    private var statsText: String {
        let total = state.files.count
        let downloaded = state.files.filter { state.downloadedHandles.contains($0.handle) }.count
        return "已下载 \(downloaded)/\(total) · 剩余 \(total - downloaded) 张"
    }

    // MARK: - 主体

    @ViewBuilder
    private var content: some View {
        if state.isLoading && state.files.isEmpty {
            skeletonGrid
        } else if let error = state.error, state.files.isEmpty {
            // 断连 ≠ 为空：这里给的是「连接后才有的东西」，不是「这里什么都没有」
            NlEmptyState(
                title: "无法读取相册",
                desc: error,
                systemImage: "wifi.exclamationmark",
                actionTitle: env.client.isConnected ? "重试" : "去连接相机",
                action: {
                    if env.client.isConnected {
                        env.reloadAlbum(holdPages: true)
                    } else {
                        env.tab = .device
                    }
                }
            )
        } else if files.isEmpty {
            NlEmptyState(
                title: emptyTitle,
                desc: emptyDescription,
                systemImage: "photo.on.rectangle.angled"
            )
        } else {
            grid
        }
    }

    private var emptyTitle: String {
        switch state.filter {
        case .all:
            if !env.client.isConnected { return "连接相机后查看相册" }
            return state.onlyNotDownloaded ? "全部照片都已下载" : "相机相册里没有照片"
        default:
            return "没有符合「\(state.filter.title)」的照片"
        }
    }

    private var emptyDescription: String {
        switch state.filter {
        case .all:
            if !env.client.isConnected {
                return "相册需要相机连接才能读取。手机里已下载的照片在「本地照片」页。"
            }
            return state.onlyNotDownloaded
                ? "关掉「跳过已下载」可以看全部照片。"
                : "在相机上拍几张，或换一张存储卡再刷新。"
        default:
            return "换一个筛选档，或点相册右上角刷新重读整卡。"
        }
    }

    /// 3 列方形格的边长。
    ///
    /// 抽成 static 函数而不是写在 `GeometryReader` 里：那条表达式同时含
    /// 减法、乘法、除法与三个 token 读取，塞在 ViewBuilder 内会让类型检查器
    /// 在复杂 body 里超时（编译器直接报 "unable to type-check in reasonable time"）。
    private static func cellSide(for containerWidth: CGFloat) -> CGFloat {
        let usable = containerWidth - NlSpacing.pageMargin * 2 - NlSpacing.cellGap * 2
        return max(1, usable / 3)
    }

    /// 3 个等宽固定列
    private static func threeColumns(side: CGFloat, spacing: CGFloat) -> [GridItem] {
        Array(repeating: GridItem(.fixed(side), spacing: spacing), count: 3)
    }

    private var skeletonGrid: some View {
        GeometryReader { geo in
            let side = Self.cellSide(for: geo.size.width)
            let spacing = NlSpacing.cellGap
            ScrollView {
                LazyVGrid(
                    columns: Self.threeColumns(side: side, spacing: spacing),
                    spacing: spacing
                ) {
                    ForEach(0..<9, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(NlColor.surfaceVariant)
                            .frame(width: side, height: side)
                    }
                }
                .padding(.horizontal, NlSpacing.pageMargin)
            }
            .scrollDisabled(true)
        }
    }

    private var grid: some View {
        GeometryReader { geo in
            let side = Self.cellSide(for: geo.size.width)
            let spacing = NlSpacing.cellGap
            ScrollView {
                LazyVGrid(
                    columns: Self.threeColumns(side: side, spacing: spacing),
                    spacing: spacing
                ) {
                    if state.isLoading {
                        // 逐页填充时顶部给一条细进度，而不是整块骨架闪一下
                        ProgressView()
                            .scaleEffect(0.7)
                            .frame(maxWidth: .infinity)
                            .gridCellColumns(3)
                            .padding(.vertical, NlSpacing.s8)
                    }
                    ForEach(Array(files.enumerated()), id: \.element.id) { index, file in
                        cell(file: file, side: side)
                            .onTapGesture {
                                handleTap(file: file, index: index)
                            }
                            .onLongPressGesture(minimumDuration: 0.35) {
                                dragAnchor = file.handle
                                withAnimation(NlMotion.fastAnim) {
                                    env.album.selection.insert(file.handle)
                                }
                            }
                            .onChange(of: dragAnchor) { _ in }
                    }
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.bottom, isSelecting ? 72 : NlSize.dockBaselineHeight + 16)
            }
            .refreshable {
                env.reloadAlbum(holdPages: true)
            }
        }
    }

    private func cell(file: CameraFile, side: CGFloat) -> some View {
        let selected = state.isSelected(file)
        return ZStack(alignment: .topLeading) {
            Group {
                if let image = thumbs.image(for: file.handle) {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                } else {
                    // 未加载格与页面同色，**不逐格转圈**——
                    // 一屏 15 个转圈比空白更吵，而且用户无法分辨哪个还在加载
                    NlColor.surfaceVariant
                }
            }
            .frame(width: side, height: side)
            .clipped()

            if selected {
                NlColor.photoScrimSelect
                    .frame(width: side, height: side)
            }

            VStack {
                HStack(alignment: .top) {
                    if file.pairedJpegHandle != nil {
                        Image(systemName: "star.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(NlColor.nikonYellow)
                            .padding(6)
                    }
                    Spacer()
                    if isSelecting {
                        selectionBadge(selected: selected)
                            .padding(6)
                    }
                }
                Spacer()
                HStack(alignment: .bottom) {
                    Text(file.fileExtension.uppercased())
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(NlColor.photoBadge, in: RoundedRectangle(cornerRadius: 3))
                        .padding(6)
                    Spacer()
                    if state.downloadedHandles.contains(file.handle) {
                        Text("✓ 已下载")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 2)
                            .background(NlColor.photoBadge, in: RoundedRectangle(cornerRadius: 3))
                            .padding(6)
                    }
                }
            }
            .frame(width: side, height: side)
        }
        .contentShape(Rectangle())
        .onAppear { thumbs.request(file.handle) }
    }

    private func selectionBadge(selected: Bool) -> some View {
        ZStack {
            Circle()
                .fill(selected ? NlColor.nikonYellow : Color.black.opacity(0.35))
                .frame(width: 24, height: 24)
            Circle()
                .strokeBorder(.white.opacity(selected ? 0 : 0.8), lineWidth: 1.5)
                .frame(width: 24, height: 24)
            if selected {
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(NlColor.onNikonYellow)
            }
        }
    }

    // MARK: - 底栏（多选态）

    @ViewBuilder
    private var bottomBar: some View {
        if isSelecting {
            HStack(spacing: 0) {
                barAction("标记", "star") { markSelected() }
                barAction("全选", "checkmark.circle") { selectAll() }
                barAction("分享", "square.and.arrow.up") {
                    shareTarget = ShareTarget(files: state.selectedFiles, fromCamera: env.albumTab == .camera)
                }
                barAction("删除", "trash", destructive: true) { confirmDelete = true }

                Button {
                    enqueue(state.selectedFiles)
                } label: {
                    Text("下载 \(state.selection.count) 张")
                        .font(NlText.subtitle)
                        .foregroundStyle(NlColor.onNikonYellow)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(NlColor.nikonYellow, in: RoundedRectangle(cornerRadius: NlRadius.control))
                }
                .padding(.leading, NlSpacing.s8)
            }
            .padding(.horizontal, NlSpacing.pageMargin)
            .padding(.vertical, NlSpacing.s8)
            .background {
                NlColor.surface
                    .overlay(alignment: .top) { NlDivider() }
                    .ignoresSafeArea(edges: .bottom)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        } else if !env.transfer.tasks.isEmpty {
            Button {
                showQueue = true
            } label: {
                HStack(spacing: NlSpacing.s8) {
                    if env.transfer.isPaused {
                        Image(systemName: "pause.circle.fill")
                    } else {
                        ProgressView().scaleEffect(0.6).frame(width: 14, height: 14)
                    }
                    Text(env.transfer.summary.text)
                        .font(NlText.nav)
                }
                .foregroundStyle(NlColor.onPrimary)
                .padding(.horizontal, NlSpacing.s16)
                .frame(height: 36)
                .background(NlColor.primary, in: Capsule())
            }
            .padding(.bottom, NlSize.dockBaselineHeight + NlSpacing.s8)
        }
    }

    private func barAction(
        _ title: String,
        _ icon: String,
        destructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 17, weight: .medium))
                Text(title)
                    .font(NlText.foot)
            }
            .foregroundStyle(destructive ? Color.red : NlColor.textSecondary)
            .frame(minWidth: NlSize.hitTarget, minHeight: NlSize.hitTarget)
        }
        .buttonStyle(.plain)
    }

    // MARK: - 队列面板

    private var queueSheet: some View {
        ZStack(alignment: .bottom) {
            Color.black.opacity(0.28)
                .ignoresSafeArea()
                .onTapGesture { withAnimation(NlMotion.baseAnim) { showQueue = false } }

            VStack(spacing: 0) {
                HStack {
                    Text("下载队列")
                        .font(NlText.sectionTitle)
                        .foregroundStyle(NlColor.textPrimary)
                    Spacer()
                    Button {
                        withAnimation(NlMotion.baseAnim) { showQueue = false }
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(NlColor.textSecondary)
                            .frame(width: NlSize.iconButton, height: NlSize.iconButton)
                    }
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.top, NlSpacing.s16)

                if env.transfer.tasks.isEmpty {
                    // 空态时摘要行**同时留空**——同一屏里两种说法（「共 0 项」+「队列里还没有任务」）是噪音
                    NlEmptyState(
                        title: "队列里还没有任务",
                        desc: "在相册里选中照片，点「下载」即可开始",
                        systemImage: "arrow.down.circle"
                    )
                    .frame(height: 220)
                } else {
                    HStack {
                        Text(queueSummaryLine)
                            .font(NlText.foot)
                            .foregroundStyle(NlColor.textTertiary)
                        Spacer()
                    }
                    .padding(.horizontal, NlSpacing.pageMargin)
                    .padding(.top, NlSpacing.s4)

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: NlSpacing.s8) {
                            if env.transfer.isPaused {
                                NlChip(title: "继续", selected: true) { env.transfer.resume() }
                            } else {
                                NlChip(title: "暂停", selected: false) { env.transfer.pause() }
                            }
                            NlChip(title: "重试失败", selected: false) { env.transfer.retryFailed() }
                            NlChip(title: "全部取消", selected: false) {
                                confirmCancelAll = true
                                showQueue = false
                            }
                        }
                        .padding(.horizontal, NlSpacing.pageMargin)
                    }
                    .padding(.vertical, NlSpacing.s12)

                    ScrollView {
                        LazyVStack(spacing: 0) {
                            ForEach(env.transfer.tasks) { task in
                                queueRow(task)
                                NlDivider()
                            }
                        }
                        .padding(.horizontal, NlSpacing.pageMargin)
                    }
                    .frame(maxHeight: 320)
                }
            }
            .padding(.bottom, NlSpacing.s24)
            .background {
                UnevenRoundedRectangle(
                    topLeadingRadius: NlRadius.sheet,
                    bottomLeadingRadius: 0,
                    bottomTrailingRadius: 0,
                    topTrailingRadius: NlRadius.sheet
                )
                .fill(NlColor.surface)
                .ignoresSafeArea(edges: .bottom)
            }
            .transition(.move(edge: .bottom))
        }
    }

    private var queueSummaryLine: String {
        let tasks = env.transfer.tasks
        let done = tasks.filter { $0.status == .completed }.count
        let failed = tasks.filter { $0.status == .failed }.count
        let remaining = tasks.count - done - failed
        var parts = ["共 \(tasks.count) 项", "已完成 \(done)"]
        if failed > 0 { parts.append("失败 \(failed)") }
        parts.append("剩余 \(remaining)")
        if env.transfer.isPaused { parts.append("已暂停") }
        return parts.joined(separator: " · ")
    }

    private func queueRow(_ task: TransferTask) -> some View {
        HStack(spacing: NlSpacing.s12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(task.file.fileName)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textPrimary)
                    .lineLimit(1)
                Text(statusText(task))
                    .font(NlText.foot)
                    .foregroundStyle(task.status == .failed ? Color.red : NlColor.textTertiary)
                    .lineLimit(2)
            }
            Spacer()
            if task.status != .downloading, task.status != .completed {
                Button {
                    env.transfer.removeTask(handle: task.file.handle)
                } label: {
                    Text("移除")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .padding(.horizontal, NlSpacing.s8)
                        .frame(height: 28)
                        .background(NlColor.surfaceVariant, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(minHeight: 52)
    }

    private func statusText(_ task: TransferTask) -> String {
        switch task.status {
        case .pending: return "排队中"
        case .downloading:
            if case .downloading(_, let received, let total) = env.transfer.state, total > 0 {
                let pct = Int(Double(received) / Double(total) * 100)
                return "下载中 \(pct)%"
            }
            return "下载中"
        case .completed: return "已完成"
        case .failed: return task.failReason.map { "下载失败：\($0)" } ?? "下载失败"
        case .cancelled: return "已取消"
        }
    }
}

// MARK: - 交互

extension GalleryView {

    private func handleTap(file: CameraFile, index: Int) {
        if isSelecting {
            withAnimation(NlMotion.instantAnim) { env.album.toggle(file) }
            return
        }
        previewTarget = PreviewTarget(index: index)
    }

    private func toggleSkipDownloaded() {
        withAnimation(NlMotion.fastAnim) {
            env.album.onlyNotDownloaded.toggle()
        }
    }

    private func applySort(_ sort: AlbumSort) {
        withAnimation(NlMotion.fastAnim) { env.album.sort = sort }
        env.settings.albumSort = sort
    }

    private func selectAll() {
        withAnimation(NlMotion.fastAnim) {
            if env.album.selection.count == files.count {
                env.album.selection.removeAll()
            } else {
                env.album.selection = Set(files.map(\.handle))
            }
        }
    }

    private func markSelected() {
        // 机内保护位写入：0xDC03 是 MTP 对象属性，走 SET_OBJECT_PROP_VALUE
        // 这里先给明确结果，不假装成功
        env.showToast("标记需要在相机上设置。当前版本不写入机内保护位。", isError: true)
    }

    private func deleteSelected() {
        let handles = Array(env.album.selection)
        Task {
            guard let session = env.client.sessionOrNil() else {
                env.showToast("相机未连接，无法删除", isError: true)
                return
            }
            var failed = 0
            for handle in handles {
                do {
                    try await session.deleteObject(handle: handle)
                } catch {
                    failed += 1
                }
            }
            await MainActor.run {
                env.album.selection.removeAll()
                if failed == 0 {
                    env.showToast("已删除 \(handles.count) 张")
                    env.reloadAlbum(holdPages: true)
                } else {
                    env.showToast("\(failed) 张删除失败，其余已删除", isError: true)
                    env.reloadAlbum(holdPages: true)
                }
            }
        }
    }

    private func enqueue(_ files: [CameraFile]) {
        let added = env.transfer.enqueue(files, expandPair: true, allFiles: env.album.files)
        guard added > 0 else {
            env.showToast("所选照片均已在队列中或已下载")
            return
        }
        withAnimation(NlMotion.fastAnim) { env.album.selection.removeAll() }
        env.showToast("已加入下载队列：\(added) 张")
    }
}

// MARK: - 辅助类型

struct PreviewTarget: Identifiable {
    let id = UUID()
    let index: Int
}

struct ShareTarget: Identifiable {
    let id = UUID()
    let files: [CameraFile]
    let fromCamera: Bool
}


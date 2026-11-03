import SwiftUI
import UIKit

/// 照片全屏预览。对应安卓 `PreviewActivity`（843 行）。
///
/// 结构（自上而下）：
/// 1. 顶栏：返回 / 文件名 / 「第 N / 共 M 张」/ 更多
/// 2. 可左右滑的页面，每页双指缩放（1.0–5.0），双击复位
/// 3. 底部五格：标记 / 下载 / EXIF / 分享 / 删除
/// 4. 下载进度条悬在底条上方
///
/// 三条设计约束：
/// - **恒黑底**：预览页是唯一「画面即内容」的页面，用深灰会让人误判照片的暗部。
/// - 顶/底条随单击显隐，但**首次出现时可见**——空手进来找不到返回按钮是最常见抱怨。
/// - 五格动作里只有「下载」是主色填充；其余一律描边，避免页面出现两个重心。
struct PhotoPreviewView: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    let files: [CameraFile]
    let thumbs: ThumbnailCache
    var onDownload: (CameraFile) -> Void
    var onShare: (CameraFile) -> Void

    @State private var index: Int
    @State private var chromeVisible = true
    @State private var showEXIF = false
    @State private var confirmDelete = false
    @State private var bigImage: [UInt32: UIImage] = [:]
    @State private var loadingBig: Set<UInt32> = []
    @State private var bigFailed: Set<UInt32> = []

    init(
        files: [CameraFile],
        startIndex: Int,
        thumbs: ThumbnailCache,
        onDownload: @escaping (CameraFile) -> Void,
        onShare: @escaping (CameraFile) -> Void
    ) {
        self.files = files
        self.thumbs = thumbs
        self.onDownload = onDownload
        self.onShare = onShare
        _index = State(initialValue: min(max(0, startIndex), max(0, files.count - 1)))
    }

    private var current: CameraFile? {
        files.indices.contains(index) ? files[index] : nil
    }

    var body: some View {
        ZStack {
            NlColor.liveViewPageBackground.ignoresSafeArea()

            if files.isEmpty {
                NlEmptyState(title: "没有可预览的照片", desc: "回到相册重新选一张。", systemImage: "photo")
            } else {
                pager
            }

            VStack(spacing: 0) {
                topBar
                Spacer(minLength: 0)
                if let file = current, let progress = downloadProgress(for: file) {
                    progressBar(progress, file: file)
                }
                bottomBar
            }
            .opacity(chromeVisible ? 1 : 0)
            .animation(NlMotion.fastAnim, value: chromeVisible)
        }
        .statusBarHidden(!chromeVisible)
        .alert("删除照片？", isPresented: $confirmDelete) {
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) { deleteCurrent() }
        } message: {
            Text("将从相机存储卡永久删除这张照片，不可撤销。手机上已下载的副本不受影响。")
        }
        .sheet(isPresented: $showEXIF) {
            if let file = current {
                // `.presentationBackground` 自 iOS 16.4 才可用，本工程部署目标是 16.0。
                // EXIFSheet 自身已有背景，去掉这个修饰符视觉无差别。
                EXIFSheet(file: file)
                    .presentationDetents([.medium, .large])
            }
        }
    }

    // MARK: - 页面

    private var pager: some View {
        TabView(selection: $index) {
            ForEach(Array(files.enumerated()), id: \.element.id) { i, file in
                ZoomableImage(
                    image: displayImage(for: file),
                    placeholder: thumbs.image(for: file.handle),
                    onSingleTap: { chromeVisible.toggle() }
                )
                .tag(i)
                .task {
                    await loadBigIfNeeded(file)
                }
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
    }

    private func displayImage(for file: CameraFile) -> UIImage? {
        bigImage[file.handle]
    }

    /// 拉一张可缩放的大图。
    ///
    /// 只拉**当前页**（`task` 只在该页出现时跑）。相册页的缩略图是 160×120 级别，
    /// 放大到 5 倍就是马赛克；这里改用相机侧的大缩略图（`0x90C4`，走同一会话），
    /// 长边通常 1600–2560，足以覆盖 5 倍缩放的可见区域。
    private func loadBigIfNeeded(_ file: CameraFile) async {
        guard bigImage[file.handle] == nil,
              !loadingBig.contains(file.handle),
              !bigFailed.contains(file.handle) else { return }
        guard let session = env.client.sessionOrNil() else { return }

        loadingBig.insert(file.handle)
        defer { loadingBig.remove(file.handle) }

        do {
            let data = try await withPtpTimeout(20) { () -> Data? in
                let result = try await session.rawTransact(
                    opcode: Ptp.opNikonGetLargeThumb,
                    params: [file.handle],
                    timeoutMs: 20
                )
                // rawTransact 不抛非 OK，这里必须自己判：机身不支持大缩略图时
                // 会回 0x2005/0x2010，此时应回落到已缓存的网格缩略图，而不是空白
                guard result.isOk, let payload = result.dataIn, !payload.isEmpty else {
                    return nil
                }
                return payload
            }
            guard let data, let image = UIImage(data: data) else {
                bigFailed.insert(file.handle)
                return
            }
            bigImage[file.handle] = image
        } catch {
            bigFailed.insert(file.handle)
        }
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack(spacing: NlSpacing.s8) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(NlColor.liveViewText)
                    .frame(width: NlSize.iconButton, height: NlSize.iconButton)
            }
            .accessibilityLabel("返回")

            VStack(alignment: .leading, spacing: 1) {
                Text(current?.fileName ?? "")
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.liveViewText)
                    .lineLimit(1)
                Text("第 \(index + 1) / 共 \(files.count) 张")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.liveViewTextDim)
            }

            Spacer()

            Menu {
                if let file = current {
                    Button {
                        showEXIF = true
                    } label: { Label("查看拍摄参数", systemImage: "info.circle") }
                    Button {
                        copyFileName(file)
                    } label: { Label("复制文件名", systemImage: "doc.on.doc") }
                    if env.album.downloadedHandles.contains(file.handle) {
                        Button {
                            env.showToast("这张已经下载过了")
                        } label: { Label("已下载", systemImage: "checkmark.circle") }
                    }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(NlColor.liveViewText)
                    .frame(width: NlSize.iconButton, height: NlSize.iconButton)
            }
        }
        .padding(.horizontal, NlSpacing.s8)
        .padding(.top, NlSpacing.s4)
        .background(alignment: .top) {
            LinearGradient(
                colors: [Color.black.opacity(0.55), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 110)
            .ignoresSafeArea(edges: .top)
            .allowsHitTesting(false)
        }
    }

    // MARK: - 底部五格

    private var bottomBar: some View {
        HStack(spacing: 0) {
            barItem("标记", "star", active: current?.isMarkedInCamera ?? false) {
                env.showToast("标记需要在相机上设置。当前版本不写入机内保护位。", isError: true)
            }
            barItem("下载", "arrow.down.circle", primary: true) {
                guard let file = current else { return }
                if env.album.downloadedHandles.contains(file.handle) {
                    env.showToast("这张已经下载过了")
                } else {
                    onDownload(file)
                }
            }
            barItem("EXIF", "info.circle", active: showEXIF) {
                showEXIF = true
            }
            barItem("分享", "square.and.arrow.up") {
                guard let file = current else { return }
                onShare(file)
            }
            barItem("删除", "trash", destructive: true) {
                confirmDelete = true
            }
        }
        .padding(.horizontal, NlSpacing.s8)
        .padding(.top, NlSpacing.s8)
        .padding(.bottom, NlSpacing.s4)
        .background(alignment: .bottom) {
            LinearGradient(
                colors: [.clear, Color.black.opacity(0.62)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 150)
            .ignoresSafeArea(edges: .bottom)
            .allowsHitTesting(false)
        }
    }

    private func barItem(
        _ title: String,
        _ icon: String,
        active: Bool = false,
        primary: Bool = false,
        destructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .font(.system(size: 19, weight: .medium))
                    .foregroundStyle(primary ? NlColor.nikonYellow
                                     : destructive ? Color.red
                                     : NlColor.liveViewText)
                Text(title)
                    .font(.system(size: 11))
                    .foregroundStyle(primary ? NlColor.nikonYellow
                                     : destructive ? Color.red
                                     : active ? NlColor.liveViewText : NlColor.liveViewTextDim)
            }
            .frame(maxWidth: .infinity, minHeight: 52)
        }
        .buttonStyle(.plain)
        .disabled(current == nil)
    }

    // MARK: - 进度条

    private func downloadProgress(for file: CameraFile) -> (received: Int64, total: Int64)? {
        guard case .downloading(let handle, let received, let total) = env.transfer.state,
              handle == file.handle, total > 0 else { return nil }
        return (received, total)
    }

    private func progressBar(_ p: (received: Int64, total: Int64), file: CameraFile) -> some View {
        VStack(spacing: 4) {
            HStack {
                Text("正在下载 \(Int(Double(p.received) / Double(p.total) * 100))%")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.liveViewText)
                Spacer()
                Text(byteText(p.received) + " / " + byteText(p.total))
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.liveViewTextDim)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(NlColor.photoTrack)
                    Capsule()
                        .fill(NlColor.nikonYellow)
                        .frame(width: max(2, geo.size.width * CGFloat(Double(p.received) / Double(max(1, p.total)))))
                }
            }
            .frame(height: 3)
        }
        .padding(.horizontal, NlSpacing.s16)
        .padding(.vertical, NlSpacing.s8)
        .background(Color.black.opacity(0.42))
    }

    private func byteText(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }

    // MARK: - 动作

    private func copyFileName(_ file: CameraFile) {
        UIPasteboard.general.string = file.fileName
        env.showToast("已复制文件名")
    }

    private func deleteCurrent() {
        guard let file = current else { return }
        Task {
            guard let session = env.client.sessionOrNil() else {
                env.showToast("相机未连接，无法删除", isError: true)
                return
            }
            do {
                try await session.deleteObject(handle: file.handle)
                await MainActor.run {
                    env.showToast("已删除 \(file.fileName)")
                    env.album.files.removeAll { $0.handle == file.handle }
                    env.album.selection.remove(file.handle)
                    if files.count <= 1 {
                        dismiss()
                    } else if index >= files.count - 1 {
                        index = max(0, index - 1)
                    }
                }
            } catch {
                env.showToast("删除失败：\(error.localizedDescription)", isError: true)
            }
        }
    }
}

// MARK: - 可缩放图片

/// 双指缩放 + 单指平移 + 双击复位。
///
/// 与安卓 `LiveViewZoomController` 同构，但这里允许留黑边——
/// 预览页的用户目的是「看清这张照片」，不是「监看无黑边画面」，
/// 因此平移边界钳到「图不跑出屏幕」即可，不做 fitCenter 的严格裁切。
struct ZoomableImage: View {

    let image: UIImage?
    let placeholder: UIImage?
    var onSingleTap: () -> Void

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    private let minScale: CGFloat = 1
    private let maxScale: CGFloat = 5

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .scaleEffect(scale)
                        .offset(offset)
                        .gesture(zoomGesture)
                        .simultaneousGesture(panGesture(in: geo.size))
                        .onTapGesture(count: 2) { resetZoom() }
                        .onTapGesture { onSingleTap() }
                } else if let placeholder {
                    // 大图还没到：先铺缩略图（放大后是糊的，但比纯黑更有信息）
                    Image(uiImage: placeholder)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .blur(radius: 0.5)
                        .overlay {
                            ProgressView()
                                .tint(NlColor.liveViewText)
                        }
                        .onTapGesture { onSingleTap() }
                } else {
                    ProgressView()
                        .tint(NlColor.liveViewText)
                        .task { }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private var zoomGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                scale = min(max(minScale, lastScale * value), maxScale)
            }
            .onEnded { _ in
                lastScale = scale
                if scale <= minScale {
                    withAnimation(NlMotion.baseAnim) {
                        offset = .zero
                        lastOffset = .zero
                    }
                }
            }
    }

    private func panGesture(in size: CGSize) -> some Gesture {
        DragGesture()
            .onChanged { value in
                guard scale > 1 else { return }
                let limitX = max(0, (size.width * scale - size.width) / 2)
                let limitY = max(0, (size.height * scale - size.height) / 2)
                offset = CGSize(
                    width: min(max(-limitX, lastOffset.width + value.translation.width), limitX),
                    height: min(max(-limitY, lastOffset.height + value.translation.height), limitY)
                )
            }
            .onEnded { _ in
                lastOffset = offset
            }
    }

    private func resetZoom() {
        withAnimation(NlMotion.baseAnim) {
            scale = 1
            lastScale = 1
            offset = .zero
            lastOffset = .zero
        }
    }
}

// MARK: - EXIF

/// 拍摄参数表。
///
/// 只展示**相机确实给出**的字段：`CameraInfo` 里未知值一律是 -1，这里直接省略该行，
/// 而不是显示「--」占位（PRODUCT.md 明令禁止占位符充当内容）。
struct EXIFSheet: View {

    @Environment(\.dismiss) private var dismiss
    let file: CameraFile

    private var rows: [(String, String)] {
        var out: [(String, String)] = []
        out.append(("文件名", file.fileName))
        out.append(("格式", file.format.displayName))
        out.append(("大小", ByteCountFormatter.string(fromByteCount: Int64(file.size), countStyle: .file)))
        if let date = file.captureDate {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
            out.append(("拍摄时间", formatter.string(from: date)))
        }
        out.append(("扩展名", file.fileExtension.uppercased()))
        out.append(("对象句柄", "0x" + String(format: "%08X", file.handle)))
        if let protect = file.protectionStatus {
            out.append(("保护位", protect == 0 ? "未保护" : "已保护（\(protect)）"))
        }
        if let rating = file.rating {
            out.append(("评级", "\(rating) 星"))
        }
        if let paired = file.pairedJpegHandle {
            out.append(("配对 JPEG", "句柄 0x" + String(format: "%08X", paired)))
        }
        return out
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("拍摄参数")
                    .font(NlText.sectionTitle)
                    .foregroundStyle(NlColor.textPrimary)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(NlColor.textSecondary)
                        .frame(width: NlSize.iconButton, height: NlSize.iconButton)
                }
            }
            .padding(.horizontal, NlSpacing.pageMargin)
            .padding(.top, NlSpacing.s16)

            ScrollView {
                VStack(spacing: 0) {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        NlKeyValueRow(key: row.0, value: row.1)
                        NlDivider()
                    }
                    Text("这里只列本机读到的字段。曝光三要素随相机参数实时读取，未在此重复。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, NlSpacing.pageMargin)
                        .padding(.top, NlSpacing.s16)
                }
                .padding(.top, NlSpacing.s8)
            }
        }
    }
}

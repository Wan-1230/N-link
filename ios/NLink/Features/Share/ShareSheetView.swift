import SwiftUI
import UIKit

/// 分享页。对应安卓 `ShareActivity`（470 行）+ `PreviewShareExporter`。
///
/// 结构（自上而下）：
/// 1. 顶栏：关闭 / 「分享」/ 副标题「共 N 项 · 相机相册」
/// 2. 预览缩略行（最多 6 张 + 余数）
/// 3. 发送方式两芯片：预览副本 / 原图
/// 4. 「分享到」4 列应用网格（只列系统确实能收图的目标）
/// 5. 准备进度卡（生成副本时可见）
/// 6. 「更多分享方式」→ 系统 share sheet
/// 7. 底部长说明
///
/// 为什么自己画应用网格而不直接弹系统面板：
/// 安卓版能在网格里直达微信/相册等目标；iOS 上系统 `UIActivityViewController`
/// 已把这一步做完了，而且第三方应用列表**无法枚举**。因此这里只保留
/// 「存到相册」与「存到文件」两个确定可达的本地动作，其余全部交给系统面板——
/// 假装能枚举会更糟：点进去发现什么都没有。
struct ShareSheetView: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    let files: [CameraFile]
    let fromCamera: Bool

    @State private var mode: ShareExporter.ShareMode = .preview
    @State private var preparing = false
    @State private var progressText = ""
    @State private var preparedURLs: [URL] = []
    @State private var skipped: [String] = []
    @State private var failed: [String] = []
    @State private var showSystemSheet = false
    @State private var saveToPhotos = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    header
                    thumbnailStrip
                    modeSelector
                    targets
                    if preparing || !progressText.isEmpty {
                        progressCard
                    }
                    if !skipped.isEmpty || !failed.isEmpty {
                        resultNotes
                    }
                    systemSheetButton
                    footnote
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.vertical, NlSpacing.s16)
            }
            .background(NlColor.background)
            .navigationTitle("分享")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(NlColor.textPrimary)
                }
            }
            .sheet(isPresented: $showSystemSheet) {
                ActivityView(items: preparedURLs)
            }
        }
        .onAppear {
            // 安卓默认：有相机项且含 JPEG → 预览副本。
            // iOS 保持一致：进页面就能直接点「分享」，不用先做选择。
            mode = defaultMode
        }
    }

    private var defaultMode: ShareExporter.ShareMode {
        if fromCamera && files.contains(where: { ShareExporter.supportsPreviewCopy($0) }) {
            return .preview
        }
        return .original
    }

    // MARK: - 头部

    private var header: some View {
        VStack(alignment: .leading, spacing: NlSpacing.s4) {
            Text("共 \(files.count) 项 · \(fromCamera ? "相机相册" : "手机相册")")
                .font(NlText.body)
                .foregroundStyle(NlColor.textSecondary)
        }
    }

    private var thumbnailStrip: some View {
        HStack(spacing: NlSpacing.s8) {
            ForEach(Array(files.prefix(6).enumerated()), id: \.offset) { _, file in
                RoundedRectangle(cornerRadius: NlRadius.control)
                    .fill(NlColor.surfaceVariant)
                    .frame(width: 48, height: 48)
                    .overlay {
                        Image(systemName: file.format == .video ? "play.fill" : "photo")
                            .font(.system(size: 16))
                            .foregroundStyle(NlColor.textTertiary)
                    }
                    .overlay(alignment: .bottomTrailing) {
                        Text(file.fileExtension.uppercased())
                            .font(.system(size: 8, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 3)
                            .padding(.vertical, 1)
                            .background(NlColor.photoBadge, in: RoundedRectangle(cornerRadius: 2))
                            .padding(2)
                    }
            }
            if files.count > 6 {
                Text("+\(files.count - 6)")
                    .font(NlText.nav)
                    .foregroundStyle(NlColor.textSecondary)
                    .frame(width: 48, height: 48)
                    .background(NlColor.surfaceVariant, in: RoundedRectangle(cornerRadius: NlRadius.control))
            }
            Spacer()
        }
    }

    // MARK: - 发送方式

    private var modeSelector: some View {
        HStack(spacing: NlSpacing.s8) {
            ForEach(ShareExporter.ShareMode.allCases) { candidate in
                Button {
                    withAnimation(NlMotion.fastAnim) { mode = candidate }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(candidate.title)
                            .font(NlText.subtitle)
                            .foregroundStyle(mode == candidate ? NlColor.onPrimary : NlColor.textPrimary)
                        Text(candidate.detail(keepGps: env.settings.shareKeepGps))
                            .font(.system(size: 10))
                            .foregroundStyle(mode == candidate
                                             ? NlColor.onPrimary.opacity(0.75)
                                             : NlColor.textTertiary)
                            .lineLimit(3)
                            .multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(NlSpacing.s12)
                    .background(
                        RoundedRectangle(cornerRadius: NlRadius.card)
                            .fill(mode == candidate ? NlColor.primary : NlColor.surface)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: NlRadius.card)
                            .stroke(NlColor.outline, lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - 分享目标

    private var targets: some View {
        VStack(alignment: .leading, spacing: NlSpacing.s12) {
            Text("分享到")
                .font(NlText.groupTitle)
                .foregroundStyle(NlColor.textSecondary)

            HStack(spacing: NlSpacing.s16) {
                targetTile("存到相册", "photo.stack") {
                    saveToPhotoLibrary()
                }
                targetTile("存到文件", "folder") {
                    saveToFiles()
                }
                targetTile("系统面板", "square.and.arrow.up") {
                    prepareThenSystemSheet()
                }
                Spacer()
            }
        }
    }

    private func targetTile(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: NlSpacing.s8) {
                Image(systemName: icon)
                    .font(.system(size: 22, weight: .regular))
                    .foregroundStyle(NlColor.textPrimary)
                    .frame(width: 56, height: 56)
                    .background(NlColor.surfaceVariant, in: RoundedRectangle(cornerRadius: NlRadius.card))
                Text(title)
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textSecondary)
            }
        }
        .buttonStyle(NlPressStyle())
        .disabled(preparing)
    }

    // MARK: - 进度

    private var progressCard: some View {
        NlCard {
            HStack(spacing: NlSpacing.s12) {
                if preparing {
                    ProgressView()
                        .scaleEffect(0.8)
                } else {
                    Image(systemName: "checkmark.circle")
                        .foregroundStyle(NlColor.textSecondary)
                }
                Text(progressText.isEmpty ? "准备中…" : progressText)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textSecondary)
                Spacer()
            }
        }
    }

    private var resultNotes: some View {
        VStack(alignment: .leading, spacing: NlSpacing.s4) {
            if !skipped.isEmpty {
                Text("跳过 \(skipped.count) 个 RAW/视频：预览副本只支持 JPEG。想分享它们请选「原图」。")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
            }
            if !failed.isEmpty {
                Text("\(failed.count) 项准备失败：\(failed.prefix(2).joined(separator: "、"))。请检查相机连接后重试。")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
            }
        }
    }

    // MARK: - 系统面板

    private var systemSheetButton: some View {
        NlOutlineButton(title: "更多分享方式（系统面板）") {
            prepareThenSystemSheet()
        }
        .disabled(preparing || preparedURLs.isEmpty && files.isEmpty)
    }

    private var footnote: some View {
        Text("2048 长边 JPEG 适合社交；送印刷或后期请用原图。原图会按机身原始字节传输，RAW 与视频都可分享。")
            .font(NlText.foot)
            .foregroundStyle(NlColor.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - 动作实现

    private func prepare(_ completion: @escaping ([URL]) -> Void) {
        guard !preparing else { return }
        guard !files.isEmpty else { return }
        preparing = true
        progressText = "准备中…"
        skipped = []
        failed = []

        Task {
            if fromCamera {
                let result = await ShareExporter.exportPreview(
                    files: files,
                    client: env.client,
                    settings: env.settings
                ) { done, total in
                    Task { @MainActor in
                        progressText = "正在准备 \(done)/\(total)…"
                    }
                }
                await MainActor.run {
                    finish(result, completion: completion)
                }
            } else {
                // 本地相册项：files 里的路径来自传输完成记录
                let items: [(url: URL, displayName: String)] = files.compactMap { file in
                    guard let path = env.transfer.completedPaths[file.handle] else { return nil }
                    return (URL(fileURLWithPath: path), file.fileName)
                }
                let result = await ShareExporter.exportPreview(
                    localURLs: items,
                    keepGps: env.settings.shareKeepGps
                ) { done, total in
                    Task { @MainActor in
                        progressText = "正在准备 \(done)/\(total)…"
                    }
                }
                await MainActor.run {
                    finish(result, completion: completion)
                }
            }
        }
    }

    private func finish(_ result: ShareExporter.ExportResult, completion: @escaping ([URL]) -> Void) {
        preparing = false
        skipped = result.skipped
        failed = result.failed
        preparedURLs = result.urls
        progressText = result.urls.isEmpty
            ? "没有可分享的项。"
            : "已准备 \(result.urls.count) 项"
        completion(result.urls)
    }

    private func prepareThenSystemSheet() {
        if !preparedURLs.isEmpty {
            showSystemSheet = true
            return
        }
        prepare { urls in
            if urls.isEmpty {
                env.showToast("没有可分享的项", isError: true)
            } else {
                showSystemSheet = true
            }
        }
    }

    private func saveToPhotoLibrary() {
        guard !files.isEmpty else { return }
        preparing = true
        progressText = "正在准备…"
        Task {
            if fromCamera {
                let result = await ShareExporter.exportOriginalBatch(
                    files: files,
                    client: env.client
                ) { done, total in
                    Task { @MainActor in progressText = "正在准备 \(done)/\(total)…" }
                }
                await MainActor.run {
                    preparing = false
                    preparedURLs = result.urls
                    skipped = result.skipped
                    failed = result.failed
                    progressText = "已准备 \(result.urls.count) 项"
                }
            } else {
                await MainActor.run {
                    preparing = false
                    progressText = "本地照片请在系统相册里直接分享。"
                }
            }
        }
    }

    private func saveToFiles() {
        // 文件 App 的动作交给系统面板（它比任何自绘 UI 都更会说清「存到哪里」）
        prepareThenSystemSheet()
    }
}

// MARK: - 系统分享面板

struct ActivityView: UIViewControllerRepresentable {
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

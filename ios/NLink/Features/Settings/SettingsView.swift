//
//  SettingsView.swift
//  N-Link
//
//  设置页 —— 对应安卓 `settings/SettingsFragment.kt`（1038 行）+ `fragment_settings.xml`。
//
//  6 分组 22 项（UI 规格 :239-269 表格）。三条纪律：
//    · 每一项都必须真的生效或真的说清为什么不能生效。iOS 上不存在的能力
//      （USB 通道、后台常驻、夸克网盘）不删行，改成**可点开看到解释**的说明行 ——
//      悄悄少一行，用户会以为是 bug。
//    · 开关的默认值全部来自 `AppSettings`，与安卓 `nl_settings` 同一批键名，
//      换端不该忘用户上次怎么设的。
//    · 所有破坏性动作（清缓存、导出日志）都给出实际结果，不写「操作成功」。
//

import SwiftUI

struct SettingsView: View {

    @EnvironmentObject private var env: AppEnvironment

    @State private var showSupport = false
    @State private var showAppearance = false
    @State private var showSaveLocation = false
    @State private var showQuality = false
    @State private var showConnPref = false
    @State private var showLocationHelp = false
    @State private var showDiagnostics = false
    @State private var showAbout = false
    @State private var showTutorial = false
    @State private var showFAQ = false
    @State private var showFeedback = false
    @State private var showCameraDetail = false
    @State private var cacheText = "计算中…"
    @State private var exportingLogs = false

    private var settings: AppSettings { env.settings }

    var body: some View {
        VStack(spacing: 0) {
            NlTopBar(title: "设置")
            ScrollView {
                VStack(spacing: NlSpacing.moduleGapLarge) {
                    supportHero
                    transferSection
                    privacySection
                    cameraSection
                    generalSection
                    helpSection
                    footNote
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.top, NlSpacing.s8)
                .padding(.bottom, NlSize.dockBaselineHeight + NlSpacing.s24)
            }
        }
        .background(NlColor.background)
        .sheet(isPresented: $showSupport) { SupportSheet() }
        .sheet(isPresented: $showDiagnostics) { diagnosticsSheet }
        .sheet(isPresented: $showLocationHelp) { LocationHelpSheet() }
        .sheet(isPresented: $showTutorial) { TutorialSheet() }
        .sheet(isPresented: $showFAQ) { FAQSheet() }
        .sheet(isPresented: $showFeedback) { FeedbackSheet() }
        .sheet(isPresented: $showAbout) { AboutSheet() }
        .sheet(isPresented: $showCameraDetail) {
            CameraDetailView().environmentObject(env)
        }
        // 四个二选一/多选一用 actionSheet：与监看页、相册页保持同一种交互语言。
        .onChange(of: showAppearance) { presented in
            guard presented else { return }
            showAppearance = false
            pick(title: "深浅色模式",
                 options: AppSettings.AppearanceMode.allCases.map(\.title),
                 current: settings.appearanceMode.title) { title in
                if let mode = AppSettings.AppearanceMode.allCases.first(where: { $0.title == title }) {
                    settings.appearanceMode = mode
                }
            }
        }
        .onChange(of: showQuality) { presented in
            guard presented else { return }
            showQuality = false
            pick(title: "下载画质",
                 options: AppSettings.DownloadQuality.allCases.map(\.title),
                 current: settings.downloadQuality.title) { title in
                if let q = AppSettings.DownloadQuality.allCases.first(where: { $0.title == title }) {
                    settings.downloadQuality = q
                }
            }
        }
        .onChange(of: showSaveLocation) { presented in
            guard presented else { return }
            showSaveLocation = false
            pick(title: "默认保存路径",
                 options: AppSettings.SaveLocation.allCases.map(\.title),
                 current: settings.saveLocation.title) { title in
                if let loc = AppSettings.SaveLocation.allCases.first(where: { $0.title == title }) {
                    settings.saveLocation = loc
                }
            }
        }
        .onChange(of: showConnPref) { presented in
            guard presented else { return }
            showConnPref = false
            pick(title: "连接偏好",
                 options: AppSettings.ConnectionPreference.allCases.map(\.title),
                 current: settings.connectionPreference.title) { title in
                if let pref = AppSettings.ConnectionPreference.allCases.first(where: { $0.title == title }) {
                    settings.connectionPreference = pref
                }
            }
        }
        .task { await refreshCacheSize() }
        .onChange(of: showSupport) { presented in
            if !presented { Task { await refreshCacheSize() } }
        }
    }

    // MARK: - ① 支持开发

    private var supportHero: some View {
        Button {
            showSupport = true
        } label: {
            HStack(spacing: NlSpacing.s12) {
                Image(systemName: "cup.and.saucer.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(NlColor.onDarkCard)
                VStack(alignment: .leading, spacing: 2) {
                    Text("支持开发 · 请喝杯咖啡")
                        .font(NlText.subtitle)
                        .foregroundStyle(NlColor.onDarkCard)
                    Text("N-Link 永久免费、永久开源，没有账号也没有内购")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.onDarkCardVariant)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(NlColor.onDarkCardVariant)
            }
            .padding(.horizontal, 20)
            .frame(height: 68)
            .background(
                RoundedRectangle(cornerRadius: NlRadius.card)
                    .fill(NlColor.darkCard)
            )
        }
        .buttonStyle(.nlPress)
        .accessibilityLabel("支持开发，请喝杯咖啡")
    }

    // MARK: - ② 传输设置

    private var transferSection: some View {
        NlSection(title: "传输设置") {
            SettingsGroup {
                VStack(spacing: 0) {
                    NlSettingRow(
                        title: "下载画质",
                        value: settings.downloadQuality.title,
                        action: { showQuality = true }
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "RAW+JPEG 下载方式",
                        value: settings.rawPairMode.title,
                        subtitle: "成对时只算一张：下载 RAW 会连带它的 JPG",
                        showsChevron: false
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "默认保存路径",
                        value: settings.saveLocation.title,
                        action: { showSaveLocation = true }
                    )
                    NlDivider()
                    toggleRow(
                        title: "自动下载新照片",
                        subtitle: nil,
                        isOn: Binding(
                            get: { settings.autoDownload },
                            set: { settings.autoDownload = $0 }
                        )
                    )
                    NlDivider()
                    toggleRow(
                        title: "标记后自动下载",
                        subtitle: "相册内标记照片后立即下载原图（联机拍摄用）",
                        isOn: Binding(
                            get: { settings.markAutoDownload },
                            set: { settings.markAutoDownload = $0 }
                        )
                    )
                }
            }
        }
    }

    // MARK: - ③ 位置与隐私

    private var privacySection: some View {
        NlSection(title: "位置与隐私") {
            SettingsGroup {
                VStack(spacing: 0) {
                    toggleRow(
                        title: "向相机发送位置信息",
                        subtitle: "写进相机，此后拍的照片会带 GPS。需要系统定位权限。",
                        isOn: Binding(
                            get: { settings.cameraLocationEnabled },
                            set: { settings.cameraLocationEnabled = $0 }
                        )
                    )
                    NlDivider()
                    toggleRow(
                        title: "分享副本保留 GPS",
                        subtitle: "默认剥离 GPS，仅留拍摄参数",
                        isOn: Binding(
                            get: { settings.shareKeepGps },
                            set: { settings.shareKeepGps = $0 }
                        )
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "这两个位置选项有什么区别？",
                        subtitle: nil,
                        showsChevron: true,
                        action: { showLocationHelp = true }
                    )
                }
            }
        }
    }

    // MARK: - ④ 相机设置

    private var cameraSection: some View {
        NlSection(title: "相机设置") {
            SettingsGroup {
                VStack(spacing: 0) {
                    NlSettingRow(
                        title: "连接偏好",
                        value: settings.connectionPreference.title,
                        subtitle: usbNote,
                        action: { showConnPref = true }
                    )
                    NlDivider()
                    toggleRow(
                        title: "5GHz 频段优先",
                        subtitle: "相机热点支持双频时先连 5GHz，失败自动回落 2.4GHz",
                        isOn: Binding(
                            get: { settings.preferWifi5GHz },
                            set: { settings.preferWifi5GHz = $0 }
                        )
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "连接诊断",
                        value: env.client.isConnected ? "已连接" : "未连接",
                        action: { showDiagnostics = true }
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "相机详情",
                        value: env.parameters.cameraInfo.modelName.isEmpty
                            ? nil : env.parameters.cameraInfo.modelName,
                        action: { showCameraDetail = true }
                    )
                }
            }
        }
    }

    /// iOS 没有 USB 通道。这行副文案是「如实」而不是「禁用」：
    /// 用户点进去仍然能选（设置本身是有效的），只是选了之后设备页会说明走不了。
    private var usbNote: String? {
        settings.connectionPreference == .usb
            ? "iOS 端没有可用的 USB 通道：需要 MFi 认证与 ExternalAccessory 授权，目前走 WiFi。"
            : nil
    }

    // MARK: - ⑤ 通用设置

    private var generalSection: some View {
        NlSection(title: "通用设置") {
            SettingsGroup {
                VStack(spacing: 0) {
                    NlSettingRow(
                        title: "后台运行",
                        subtitle: "iOS 不允许相机类 App 常驻。App 进后台约 30 秒后会释放会话，回到前台自动重连。",
                        showsChevron: false
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "深浅色模式",
                        value: settings.appearanceMode.title,
                        action: { showAppearance = true }
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "清除缓存",
                        value: cacheText,
                        action: { Task { await clearCache() } }
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "导出日志",
                        value: exportingLogs ? "导出中…" : nil,
                        action: { Task { await exportLogs() } }
                    )
                    NlDivider()
                    NlSettingRow(
                        title: "关于应用",
                        value: "v\(Self.appVersion)",
                        action: { showAbout = true }
                    )
                }
            }
        }
    }

    // MARK: - ⑥ 帮助与反馈

    private var helpSection: some View {
        NlSection(title: "帮助与反馈") {
            SettingsGroup {
                VStack(spacing: 0) {
                    NlSettingRow(title: "使用教程", action: { showTutorial = true })
                    NlDivider()
                    NlSettingRow(title: "常见问题", action: { showFAQ = true })
                    NlDivider()
                    NlSettingRow(title: "意见反馈", action: { showFeedback = true })
                }
            }
        }
    }

    private var footNote: some View {
        Text("N-Link 不下载、不刷写相机固件。所有参数读写都通过 PTP/IP 标准与会话上的尼康私有操作码完成。")
            .font(NlText.foot)
            .foregroundStyle(NlColor.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, NlSpacing.s8)
    }

    // MARK: - 行构造

    private func toggleRow(title: String, subtitle: String?, isOn: Binding<Bool>) -> some View {
        NlSettingToggleRow(title: title, subtitle: subtitle, isOn: isOn)
    }

    // MARK: - 弹窗

    private var diagnosticsSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    NlSection(title: "连接") {
                        SettingsGroup {
                            VStack(spacing: NlSpacing.s8) {
                                NlKeyValueRow(key: "状态", value: stateText)
                                NlKeyValueRow(key: "方式", value: methodText)
                                NlKeyValueRow(key: "相机", value: env.client.state.deviceInfo?.displayModel ?? "—")
                                NlKeyValueRow(key: "重连次数", value: "\(env.client.reconnectCount)")
                                NlKeyValueRow(key: "频段", value: env.client.currentBandDescription ?? "iOS 无法读取")
                            }
                        }
                    }
                    NlSection(title: "相机") {
                        SettingsGroup {
                            VStack(spacing: NlSpacing.s8) {
                                NlKeyValueRow(key: "型号", value: fallback(env.parameters.cameraInfo.modelName))
                                NlKeyValueRow(key: "固件", value: fallback(env.parameters.cameraInfo.firmwareVersion))
                                NlKeyValueRow(key: "序列号", value: fallback(env.parameters.cameraInfo.serialNumber))
                                NlKeyValueRow(key: "镜头", value: env.parameters.cameraInfo.lensText)
                                NlKeyValueRow(key: "电池", value: env.parameters.cameraInfo.batteryText)
                                NlKeyValueRow(key: "存储卡", value: env.parameters.cameraInfo.storageText)
                            }
                        }
                    }
                    NlSection(title: "监看") {
                        SettingsGroup {
                            VStack(spacing: NlSpacing.s8) {
                                NlKeyValueRow(key: "状态", value: liveStateText)
                                NlKeyValueRow(key: "帧率", value: String(format: "%.0f fps", env.liveView.metrics.fps))
                                NlKeyValueRow(key: "超轮率", value: String(format: "%.1f%%", env.liveView.metrics.overrunPercent))
                                NlKeyValueRow(key: "单帧体积", value: String(format: "%.0f KB", env.liveView.metrics.kbPerFrame))
                            }
                        }
                    }
                    Text("「超轮率」= 一轮取帧耗时超过 66ms 的比例。它不是掉帧率：帧数等于轮数时也可能有 20% 超轮。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                }
                .padding(NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("连接诊断")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { showDiagnostics = false }
                }
            }
        }
    }

    private var stateText: String {
        switch env.client.state {
        case .connected: return "已连接"
        case .connecting(let phase): return phase
        case .discovering: return "正在搜索"
        case .recovering: return "自动恢复中"
        case .awaitingHostConfirm: return "等待相机确认"
        case .failed: return "连接失败"
        case .disconnected: return "未连接"
        }
    }

    private var methodText: String {
        guard let endpoint = env.client.state.endpoint else { return "—" }
        switch endpoint.service {
        case "ap": return "WiFi AP（相机热点）"
        case "sta": return "WiFi STA（同一网络）"
        case "usb": return "USB"
        default: return endpoint.service
        }
    }

    private var liveStateText: String {
        switch env.liveView.state {
        case .running: return "监看中"
        case .starting: return "启动中"
        case .error: return "异常"
        case .stopped: return "未启动"
        }
    }

    private func fallback(_ text: String) -> String {
        text.isEmpty ? "—" : text
    }

    // MARK: - 动作

    private func refreshCacheSize() async {
        let bytes = await cacheBytes()
        cacheText = bytes <= 0 ? "0 MB" : ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func cacheBytes() async -> Int64 {
        // 只算本 App 自己的两个临时目录，不扫整个 Caches（那是系统与框架的地盘）。
        let fm = FileManager.default
        var total: Int64 = 0
        let dirs = [
            fm.temporaryDirectory.appendingPathComponent("n-link_transfer", isDirectory: true),
            fm.temporaryDirectory.appendingPathComponent(ShareExporter.shareSubDir, isDirectory: true)
        ]
        for dir in dirs {
            guard let enumerator = fm.enumerator(at: dir,
                                                includingPropertiesForKeys: [URLResourceKey.fileSizeKey],
                                                options: [.skipsHiddenFiles]) else { continue }
            for case let url as URL in enumerator {
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                total += Int64(size)
            }
        }
        return total
    }

    private func clearCache() async {
        // 正在下载时不能清：临时文件就是断点。
        if env.transfer.state.isBusy {
            env.showToast("正在下载，先暂停再清缓存。清掉临时文件会丢掉断点，只能从头下。", isError: true)
            return
        }
        ShareExporter.clearCache()
        let fm = FileManager.default
        let transferDir = fm.temporaryDirectory.appendingPathComponent("n-link_transfer", isDirectory: true)
        let items = (try? fm.contentsOfDirectory(at: transferDir, includingPropertiesForKeys: nil)) ?? []
        for item in items { try? fm.removeItem(at: item) }
        await refreshCacheSize()
        env.showToast("已清除缓存：\(cacheText)")
    }

    private func exportLogs() async {
        exportingLogs = true
        defer { exportingLogs = false }
        // 本版没有常驻的日志管道（iOS 上 os_log 落在系统统一日志里，
        // 拿不到「导出成文件」的稳定接口），因此导出的是一份现场快照。
        let snapshot = """
        N-Link iOS \(Self.appVersion)
        时间：\(ISO8601DateFormatter().string(from: Date()))
        连接：\(stateText) / \(methodText)
        相机：\(env.client.state.deviceInfo?.displayModel ?? "—")
        固件：\(fallback(env.parameters.cameraInfo.firmwareVersion))
        序列号：\(fallback(env.parameters.cameraInfo.serialNumber))
        监看：\(liveStateText) · \(String(format: "%.0f", env.liveView.metrics.fps)) fps · 超轮 \(String(format: "%.1f", env.liveView.metrics.overrunPercent))%
        重连：\(env.client.reconnectCount) 次
        """
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("n-link-diagnostics.txt")
        do {
            try snapshot.write(to: url, atomically: true, encoding: .utf8)
            showDiagnostics = true
            env.showToast("诊断快照已生成，可在「连接诊断」里查看。")
        } catch {
            env.showToast("生成诊断快照失败：\(error.localizedDescription)", isError: true)
        }
    }

    private static var appVersion: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "1.0"
    }
}

// MARK: - 行容器

/// `NlSection` 里的一组行。
///
/// 它是**透明的**——不画底、不画描边、不加内衬。`NlSection` 自己已经给了
/// surface 底 + `NlRadius.card` 描边 + 水平 16 内衬；行里再套一层卡片就变成
/// 「卡中卡」，描边叠描边，是 anti-cheap 清单点名的廉价 tell。
///
/// 保留这个类型而不是直接把 `VStack` 写在各处，是为了让「这组行属于同一张卡」
/// 在代码里读得出来——将来若要给整组加吸顶标题，也只改这一处。
private struct SettingsGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        content
    }
}

// MARK: - 选择器（统一用 actionSheet，与监看页一致）

private extension SettingsView {

    func pick(title: String, options: [String], current: String, onPick: @escaping (String) -> Void) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        let alert = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for option in options {
            alert.addAction(UIAlertAction(
                title: (option == current ? "✓ " : "") + option,
                style: .default
            ) { _ in onPick(option) })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = root.view
            popover.sourceRect = CGRect(x: root.view.bounds.midX, y: root.view.bounds.midY, width: 0, height: 0)
        }
        root.present(alert, animated: true)
    }
}

// MARK: - 支持开发

/// 打赏页。文案红线（UI 规格 :273-282）：**不得出现解锁 / 付费 / 配额 / 权益**。
struct SupportSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: NlSpacing.moduleGap) {
                    VStack(spacing: NlSpacing.s8) {
                        Text("N-Link 永久免费 · 永久开源")
                            .font(NlText.title)
                            .foregroundStyle(NlColor.textPrimary)
                        Text("没有账号、没有内购、没有功能门禁。所有功能对所有人生效。")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.textSecondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, NlSpacing.s8)

                    // 二维码卡恒白（深色主题下也是白的）：二维码扫不出来比配色不一致更糟
                    VStack(spacing: NlSpacing.s8) {
                        Text("微信扫码 · 请作者喝杯咖啡")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.textPrimary)
                        Image(systemName: "qrcode")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 180, height: 180)
                            .foregroundStyle(NlColor.textPrimary)
                            .padding(NlSpacing.s16)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 260)
                    .background(
                        RoundedRectangle(cornerRadius: NlRadius.card)
                            .fill(Color.white)
                    )

                    VStack(spacing: NlSpacing.s12) {
                        NlOutlineButton(title: "在 GitHub 上查看源码") { openGitHub() }
                        NlOutlineButton(title: "查看更新日志") { openReleases() }
                    }

                    Text("打赏完全自愿，不影响任何功能。这笔钱会用在相机租赁与真机兼容性测试上。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.bottom, NlSpacing.s24)
            }
            .background(NlColor.background)
            .navigationTitle("支持开发")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func openGitHub() {
        guard let url = URL(string: "https://github.com/") else { return }
        UIApplication.shared.open(url)
    }

    private func openReleases() {
        guard let url = URL(string: "https://github.com/") else { return }
        UIApplication.shared.open(url)
    }
}

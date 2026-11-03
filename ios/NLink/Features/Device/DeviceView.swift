//
//  DeviceView.swift
//  N-Link
//
//  设备页。对应安卓 `DashboardFragment`（2014 行）+ `fragment_dashboard.xml`。
//
//  这一页承载了全 App 最复杂的文案逻辑：连接状态有 18 种组合（无线 6 + USB 6 + AP 5 + 取消后 1），
//  每一种都要说清「现在怎么了」和「下一步做什么」。安卓把这条逻辑收在
//  `renderStatusLine`（DashboardFragment.kt:754-783）一个函数里，这里同样只有一个出口：
//  `statusLine`。**不要在别处拼状态文案**，否则两端文案会立刻漂移。
//
//  一条容易写错的规则：断连 ≠ 为空。相机没连上时**不能**把参数格摆成「—」就完事，
//  要说明为什么是「—」。
//

import SwiftUI

struct DeviceView: View {
    @EnvironmentObject private var env: AppEnvironment

    @State private var showCameraDetail = false
    @State private var showTutorial = true
    @State private var recentExpanded = true
    @State private var candidates: [CameraCandidate] = []
    @State private var isScanning = false
    @State private var manualIP: String = ""
    @State private var showManualIPSheet = false
    @State private var showSTAInfo = false

    private var client: CameraConnectionClient { env.client }

    var body: some View {
        VStack(spacing: 0) {
            topChrome
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGapLarge) {
                    modePanel
                    actionRow
                    if showsCancelConnect { cancelRow }
                    if env.connectionPanel == .wifiSta { staLists }
                    deviceCard
                    parameterGrid
                    cameraDetailGroup
                    if env.connectionPanel == .usb { usbHint }
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.top, NlSpacing.moduleGap)
                .padding(.bottom, NlSpacing.moduleGapLarge + NlSize.dockBaselineHeight)
            }
            .scrollIndicators(.hidden)
            .refreshable { await rescan() }
        }
        .background(NlColor.background)
        .sheet(isPresented: $showManualIPSheet) { manualIPSheet }
        .sheet(isPresented: $showSTAInfo) { staInfoSheet }
    }

    // MARK: - 顶栏

    private var topChrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Text("我的设备")
                    .font(NlText.title.weight(.bold))
                    .foregroundStyle(NlColor.textPrimary)
                Spacer()
                NlIconButton(systemName: "gearshape") {
                    env.tab = .settings
                }
                .accessibilityLabel("设置")
            }
            .padding(.leading, NlSpacing.pageMargin)
            .padding(.trailing, NlSpacing.s12)
            .frame(height: NlSize.topBarHeight)

            NlDivider()

            NlPageTabs(
                items: ConnectionPanel.allCases.map(\.title),
                selection: Binding(
                    get: { ConnectionPanel.allCases.firstIndex(of: env.connectionPanel) ?? 0 },
                    set: { env.connectionPanel = ConnectionPanel.allCases[$0] }
                )
            )
            .padding(.horizontal, NlSpacing.pageMargin)
            .padding(.bottom, NlSpacing.groupGap)
        }
        .background(NlColor.background)
    }

    // MARK: - 连接方式面板

    @ViewBuilder
    private var modePanel: some View {
        switch env.connectionPanel {
        case .wifiAp: apPanel
        case .wifiSta: staPanel
        case .usb: usbPanel
        }
    }

    private var apPanel: some View {
        NlCard {
            VStack(alignment: .leading, spacing: NlSpacing.groupGap) {
                HStack(spacing: NlSpacing.groupGapSmall) {
                    Image(systemName: "wifi")
                        .font(.system(size: 15))
                        .foregroundStyle(NlColor.textPrimary)
                    Text("WiFi AP 直连教程")
                        .font(NlText.subtitle)
                        .foregroundStyle(NlColor.textPrimary)
                }

                Text(apStatusText)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                if showTutorial {
                    VStack(alignment: .leading, spacing: NlSpacing.groupGapSmall) {
                        stepRow("1", "在相机菜单里打开「连接至智能设备」，选「WiFi 连接」")
                        stepRow("2", "记下机身屏幕上显示的 SSID 与密码")
                        stepRow("3", "回到本页点「连接相机 Wi-Fi」，在系统弹窗里允许加入")
                        stepRow("4", "连上后相机会自动确认本机，等待机身屏幕出现确认提示")
                    }
                    Text("连不上时：先在系统「设置 → 无线局域网」里忘掉旧的相机网络，再回来重试。相机会把上一次的连接残留约 10 秒，急着连只会一直失败。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                Button {
                    withAnimation(.easeOut(duration: NlMotion.base)) { showTutorial.toggle() }
                } label: {
                    HStack(spacing: 4) {
                        Text(showTutorial ? "收起教程" : "展开教程")
                        Image(systemName: showTutorial ? "chevron.up" : "chevron.down")
                            .font(.system(size: 10, weight: .semibold))
                    }
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var staPanel: some View {
        NlCard {
            VStack(alignment: .leading, spacing: NlSpacing.groupGap) {
                HStack(spacing: NlSpacing.groupGapSmall) {
                    Image(systemName: "wifi.router")
                        .font(.system(size: 15))
                        .foregroundStyle(NlColor.textPrimary)
                    Text("WiFi STA 连接教程")
                        .font(NlText.subtitle)
                        .foregroundStyle(NlColor.textPrimary)
                }

                Text("让手机和相机接在同一个路由器上。相机会主动找本机，不用手动填地址。")
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                Text(stareRegistrationGuidance)
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)

                // 主机注册：未注册时用强调色填充（这是页面里唯一需要用户先做的事）
                Button {
                    Task { await registerHost() }
                } label: {
                    Text(registerButtonTitle)
                        .font(NlText.body.weight(.bold))
                        .foregroundStyle(staregistered ? NlColor.textPrimary : NlColor.onNikonYellow)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .background {
                            RoundedRectangle(cornerRadius: NlRadius.control, style: .continuous)
                                .fill(staregistered ? .clear : NlColor.nikonYellow)
                                .overlay(
                                    RoundedRectangle(cornerRadius: NlRadius.control, style: .continuous)
                                        .stroke(NlColor.outline, lineWidth: staregistered ? 1 : 0)
                                )
                        }
                }
                .buttonStyle(.nlPress)
                .disabled(isScanning || client.state.isBusy)

                HStack(spacing: NlSpacing.groupGapSmall) {
                    Text("同一 WiFi")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                    Text("相机与手机接同一个路由器；本机地址由路由器分配，换路由器要重新注册。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var usbPanel: some View {
        NlCard {
            VStack(alignment: .leading, spacing: NlSpacing.groupGap) {
                HStack(spacing: NlSpacing.groupGapSmall) {
                    Image(systemName: "cable.connector")
                        .font(.system(size: 15))
                        .foregroundStyle(NlColor.textPrimary)
                    Text("USB 有线连接教程")
                        .font(NlText.subtitle)
                        .foregroundStyle(NlColor.textPrimary)
                }
                stepRow("1", "用相机的数据线连接手机。充电线不传数据，必须有数据线芯。")
                stepRow("2", "在相机菜单里把 USB 设为「MTP/PTP」。")
                stepRow("3", "连上后在系统弹窗里允许访问。iPhone 需要 Lightning/USB-C 相机转换器，且相机需支持 MFi。")
            }
        }
    }

    private func stepRow(_ index: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: NlSpacing.groupGapSmall) {
            Text(index)
                .font(NlText.foot.weight(.bold))
                .foregroundStyle(NlColor.textPrimary)
                .frame(width: 18, height: 18)
                .background(NlColor.surfaceVariant, in: Circle())
            Text(text)
                .font(NlText.caption)
                .foregroundStyle(NlColor.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - 动作区

    private var actionRow: some View {
        HStack(spacing: NlSpacing.groupGapSmall) {
            NlOutlineButton(title: isScanning ? "扫描中…" : "扫描相机", height: 48) {
                Task { await rescan() }
            }
            .frame(maxWidth: .infinity)
            .disabled(isScanning)

            switch env.connectionPanel {
            case .wifiAp:
                NlPrimaryButton(title: apActionTitle, enabled: !client.state.isBusy) {
                    connectAP()
                }
            case .wifiSta:
                NlOutlineButton(title: "手动输入相机 IP", height: 48) {
                    showManualIPSheet = true
                }
                .frame(maxWidth: .infinity)
            case .usb:
                NlPrimaryButton(title: "连接相机", enabled: false) { }
            }
        }
    }

    private var cancelRow: some View {
        NlOutlineButton(title: "取消连接", height: 42) {
            // 语义是「停止重试并抑制自动重连」，不是断开已有会话
            client.cancelPendingWork()
            env.showToast("已停止重试。自动重连在本次操作后暂停，重新点连接即可恢复。")
        }
    }

    private var showsCancelConnect: Bool {
        client.state.isBusy || isScanning
    }

    // MARK: - STA 列表

    @ViewBuilder
    private var staLists: some View {
        VStack(alignment: .leading, spacing: NlSpacing.groupGap) {
            Button {
                withAnimation(.easeOut(duration: NlMotion.base)) { recentExpanded.toggle() }
            } label: {
                HStack {
                    Text("最近连接设备")
                        .font(NlText.caption.weight(.bold))
                        .foregroundStyle(NlColor.textSecondary)
                    Spacer()
                    Image(systemName: recentExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(NlColor.textTertiary)
                }
            }
            .buttonStyle(.plain)

            if recentExpanded {
                let recents = RecentlyConnectedStore.shared.recent()
                if recents.isEmpty {
                    Text("还没有连接记录。连上一次之后，这里会留一条快捷入口。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                } else {
                    ForEach(recents, id: \.id) { entry in
                        recentRow(entry)
                    }
                }
            }

            Text("已发现相机")
                .font(NlText.caption.weight(.bold))
                .foregroundStyle(NlColor.textSecondary)
                .padding(.top, NlSpacing.groupGapSmall)

            if candidates.isEmpty {
                Text(isScanning ? "正在搜索相机（约 8 秒）…" : "点上方「扫描相机」开始搜索同一 WiFi 下的机身。")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
            } else {
                ForEach(candidates) { candidate in
                    candidateRow(candidate)
                }
            }
        }
    }

    private func recentRow(_ entry: RecentDevice) -> some View {
        HStack(spacing: NlSpacing.groupGap) {
            Image(systemName: "clock")
                .font(.system(size: 16))
                .foregroundStyle(NlColor.textTertiary)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.name)
                    .font(NlText.caption.weight(.bold))
                    .foregroundStyle(NlColor.textPrimary)
                Text("\(entry.host):\(entry.port)")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
            }
            Spacer(minLength: NlSpacing.groupGap)
            Button {
                connectSTA(host: entry.host, port: entry.port, name: entry.name)
            } label: {
                Text("快速连接")
                    .font(NlText.foot.weight(.medium))
                    .foregroundStyle(NlColor.textPrimary)
                    .frame(minWidth: 88, minHeight: 44)
                    .overlay(Capsule().stroke(NlColor.outline, lineWidth: 1))
            }
            .buttonStyle(.nlPress)
        }
    }

    private func candidateRow(_ candidate: CameraCandidate) -> some View {
        HStack(spacing: NlSpacing.groupGap) {
            Image(systemName: candidate.needsWake ? "wifi.exclamationmark" : "wifi")
                .font(.system(size: 16))
                .foregroundStyle(NlColor.textSecondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(candidate.name)
                    .font(NlText.caption.weight(.bold))
                    .foregroundStyle(NlColor.textPrimary)
                Text("\(candidate.ipAddress):\(candidate.port)")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
            }
            Spacer(minLength: NlSpacing.groupGap)
            Button {
                connectSTA(host: candidate.ipAddress, port: candidate.port, name: candidate.name)
            } label: {
                Text("连接")
                    .font(NlText.foot.weight(.medium))
                    .foregroundStyle(NlColor.textPrimary)
                    .frame(minWidth: 72, minHeight: 44)
                    .overlay(Capsule().stroke(NlColor.outline, lineWidth: 1))
            }
            .buttonStyle(.nlPress)
        }
    }

    // MARK: - 设备卡

    private var deviceCard: some View {
        // 一次求值拿住状态行，下面两处都用它。
        // 计算属性每次访问都会重跑整个 switch —— 同一个 body 里读两次是白算两遍。
        let line = statusLine
        return VStack(alignment: .leading, spacing: NlSpacing.groupGap) {
            HStack(spacing: NlSpacing.groupGapSmall) {
                NlStatusDot(kind: statusDotKind)
                Text(line.title)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.onDarkCard)
                if client.state.isBusy {
                    ProgressView()
                        .controlSize(.mini)
                        .tint(NlColor.onDarkCardVariant)
                }
                Spacer(minLength: NlSpacing.groupGapSmall)
                if let detail = line.detail {
                    Text(detail)
                        .font(NlText.nav)
                        .foregroundStyle(NlColor.onDarkCardVariant)
                        .lineLimit(1)
                        .frame(maxWidth: 160, alignment: .trailing)
                }
            }

            Text(cameraName)
                .font(.system(size: NlFontSize.display, weight: .black))
                .foregroundStyle(NlColor.onDarkCard)
                .lineLimit(1)
                .truncationMode(.tail)

            if client.state.isConnected {
                NlContrastButton(title: "更多相机信息", filled: false, height: 44) {
                    showCameraDetail = true
                }
            } else if !client.state.isBusy {
                Text("选好上面的连接方式，连上之后这里会显示机身型号与状态。")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.onDarkCardVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(NlColor.darkCard, in: RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous)
                .stroke(NlColor.darkCardSurface, lineWidth: 1)
        )
        .sheet(isPresented: $showCameraDetail) {
            CameraDetailView()
                .environmentObject(env)
        }
    }

    // MARK: - 参数四宫格

    private var parameterGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: NlSpacing.groupGapSmall),
                            GridItem(.flexible(), spacing: NlSpacing.groupGapSmall)],
                  spacing: NlSpacing.groupGapSmall) {
            tile(icon: "camera.aperture",
                 label: "快门次数",
                 value: env.parameters.cameraInfo.shutterCountText,
                 sub: shutterCountSub,
                 tappable: true) {
                Task { await env.parameters.queryShutterCount() }
            }
            tile(icon: "camera.macro",
                 label: "镜头",
                 value: env.parameters.cameraInfo.lensText,
                 sub: nil,
                 tappable: false,
                 action: nil)
            tile(icon: "battery.100",
                 label: "电池电量",
                 value: env.parameters.cameraInfo.batteryText,
                 sub: nil,
                 tappable: false,
                 action: nil)
            tile(icon: "sdcard",
                 label: "存储卡",
                 value: env.parameters.cameraInfo.storageText,
                 sub: nil,
                 tappable: false,
                 action: nil,
                 storageRatio: env.parameters.cameraInfo.storageFreeRatio)
        }
    }

    private var shutterCountSub: String? {
        let info = env.parameters.cameraInfo
        if info.shutterQueryState == .querying { return "查询中…" }
        if info.shutterQueryState == .failed, !info.shutterFailReason.guidance.isEmpty {
            return info.shutterFailReason.guidance
        }
        if info.shutterCountSource == .cloud { return "云端解析 · 未校验" }
        if info.shutterCount >= 0, !info.shutterVerified { return "未交叉校验" }
        return nil
    }

    private func tile(
        icon: String,
        label: String,
        value: String,
        sub: String?,
        tappable: Bool,
        action: (() -> Void)?,
        storageRatio: Double? = nil
    ) -> some View {
        Button {
            action?()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Image(systemName: icon)
                        .font(.system(size: 14))
                        .foregroundStyle(NlColor.textTertiary)
                    Text(label)
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textSecondary)
                }
                Text(value)
                    .font(NlText.sectionTitle.weight(.bold))
                    .foregroundStyle(NlColor.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .monospacedDigit()
                if let sub {
                    Text(sub)
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let storageRatio, storageRatio >= 0 {
                    NlProgressBar(value: storageRatio)
                        .padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 96, alignment: .topLeading)
            .padding(NlSpacing.s12)
            .background(NlColor.surface, in: RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous)
                    .stroke(NlColor.outline, lineWidth: 1)
            )
        }
        .buttonStyle(tappable ? .nlPress : .plain)
        .disabled(!tappable)
        .accessibilityHint(tappable ? "查询机身快门次数" : "")
    }

    // MARK: - 相机详情折叠组

    private var cameraDetailGroup: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(.easeOut(duration: NlMotion.base)) { showCameraDetail.toggle() }
            } label: {
                HStack {
                    Text("相机详情")
                        .font(NlText.caption.weight(.bold))
                        .foregroundStyle(NlColor.textSecondary)
                    Spacer()
                    Image(systemName: showCameraDetail ? "chevron.up" : "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(NlColor.textTertiary)
                }
                .frame(height: 52)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if showCameraDetail {
                VStack(spacing: 0) {
                    NlDivider()
                    NlKeyValueRow(key: "固件版本", value: emptyOr(env.parameters.cameraInfo.firmwareVersion))
                    NlKeyValueRow(key: "机身序列号", value: emptyOr(env.parameters.cameraInfo.serialNumber))
                    NlKeyValueRow(key: "WiFi 频段", value: connectionDetailBand)
                    NlKeyValueRow(key: "重连次数", value: "\(client.reconnectCount)")
                }
                .padding(.bottom, NlSpacing.s8)
            }
        }
        .padding(.horizontal, NlSpacing.s16)
        .background(NlColor.surface, in: RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous)
                .stroke(NlColor.outline, lineWidth: 1)
        )
    }

    private var usbHint: some View {
        NlStateBar(
            title: "iOS 端没有可用的 USB 通道",
            desc: "iOS 不允许第三方 App 直接以 PTP 访问 USB 设备，需要 MFi 认证配件 + ExternalAccessory 协议。本版本只提供 WiFi 两条链路。",
            icon: "info",
            actionTitle: nil,
            action: nil,
            onDismiss: nil
        )
    }

    // MARK: - 弹层

    private var manualIPSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                Text("填入相机在路由器上分到的地址。机身屏幕「网络」页能看到。")
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textSecondary)
                TextField("192.168.1.100", text: $manualIP)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                Spacer()
            }
            .padding(NlSpacing.pageMargin)
            .navigationTitle("手动连接")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showManualIPSheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("连接") {
                        let host = manualIP.trimmingCharacters(in: .whitespacesAndNewlines)
                        showManualIPSheet = false
                        guard let endpoint = WifiEndpoint.parse(host) else {
                            env.showToast("地址格式不对。请填 192.168.1.100 这样的 IPv4 地址。", isError: true)
                            return
                        }
                        connectSTA(host: endpoint.host, port: endpoint.port, name: nil)
                    }
                    .disabled(manualIP.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.height(240)])
    }

    private var staInfoSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    Text("为什么要做主机注册")
                        .font(NlText.sectionTitle)
                    Text("相机只接受它「记住」的设备。第一次用某个 WiFi 连它时，需要在机身上确认一次本机身份，之后就不用再做了。")
                        .font(NlText.body)
                        .foregroundStyle(NlColor.textSecondary)
                    Text("注册时会发生什么")
                        .font(NlText.sectionTitle)
                    Text("App 会向相机发一个注册请求，机身屏幕弹出确认提示，需要你手动按 OK。这一步有 8.5 秒的等待窗口——不是 App 卡住了，是在等你按机身按钮。")
                        .font(NlText.body)
                        .foregroundStyle(NlColor.textSecondary)
                    Text("什么时候要重做")
                        .font(NlText.sectionTitle)
                    Text("换了路由器、路由器改过网段、或相机被重置过网络设置之后。其它时候不需要。")
                        .font(NlText.body)
                        .foregroundStyle(NlColor.textSecondary)
                }
                .padding(NlSpacing.pageMargin)
            }
            .navigationTitle("主机注册说明")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("知道了") { showSTAInfo = false }
                }
            }
        }
    }

    // MARK: - 状态文案（唯一出口）

    /// 连接状态的唯一文案出口。**不要在其他地方拼状态文案**。
    private var statusLine: (title: String, detail: String?) {
        switch client.state {
        case .disconnected:
            return ("未连接", nil)
        case .discovering:
            return ("正在搜索相机…", nil)
        case .connecting(let phase):
            return (phase, nil)
        case .awaitingHostConfirm:
            return ("等待相机确认", "请在相机的主机确认界面上按 OK 确认本机")
        case .connected(_, let deviceInfo):
            let model = deviceInfo.model.isEmpty ? nil : deviceInfo.model
            return ("已连接", model)
        case .recovering:
            return ("自动恢复中", "连接波动，正在自动恢复…")
        case .failed(let error):
            return ("连接失败", error.message)
        }
    }

    private var statusDotKind: NlStatusDotKind {
        switch client.state {
        case .connected: return .connected
        case .discovering, .connecting, .recovering, .awaitingHostConfirm: return .pending
        case .disconnected, .failed: return .idle
        }
    }

    private var cameraName: String {
        if let info = client.state.deviceInfo {
            let model = info.model.trimmingCharacters(in: .whitespaces)
            if !model.isEmpty { return model }
        }
        if !env.parameters.cameraInfo.modelName.isEmpty { return env.parameters.cameraInfo.modelName }
        return "Nikon"
    }

    private var apStatusText: String {
        switch client.state {
        case .connected(let endpoint, _) where endpoint.service == "ap":
            return "已连接相机热点。"
        case .connecting, .recovering, .awaitingHostConfirm:
            return "正在连接相机热点…"
        default:
            return "未连接相机热点。"
        }
    }

    private var apActionTitle: String {
        switch client.state {
        case .connected(let endpoint, _) where endpoint.service == "ap": return "重新连接"
        case .connecting, .recovering, .awaitingHostConfirm: return "连接中…"
        default: return "连接相机 Wi-Fi"
        }
    }

    private var staregistered: Bool { client.staHostRegistered }

    private var registerButtonTitle: String {
        staregistered ? "重新注册本机（已注册）" : "STA 主机注册（让相机记住本机）"
    }

    private var stareRegistrationGuidance: String {
        if !staregistered {
            return "相机没有记住本机——需要先做一次主机注册（全程只需一次）：点下面的按钮，然后看机身屏幕按 OK。"
        }
        return "本机已注册。若仍连不上，多半是相机还没停在 STA 等待画面，请回机身菜单重新进入一次 WiFi 连接。"
    }

    private var connectionDetailBand: String {
        if let band = client.currentBandDescription { return band }
        return "—"
    }

    private func emptyOr(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespaces).isEmpty ? NlPlaceholder.value : text
    }

    // MARK: - 动作

    private func rescan() async {
        isScanning = true
        defer { isScanning = false }
        let found = await client.discoverSta()
        candidates = found
        if found.isEmpty {
            env.showToast("这一轮没找到相机。请确认手机与相机在同一个 WiFi，且相机已停在等待连接画面。")
        }
    }

    private func connectAP() {
        // `connect` 是同步入口（内部自起 Task 跑整个握手），所以这里没有 await。
        client.connect(
            method: .wifiAp(host: Ptp.defaultApHost, port: Ptp.defaultPtpIpPort),
            endpoint: nil
        )
    }

    private func connectSTA(host: String, port: Int, name: String?) {
        let endpoint = CameraEndpoint(displayName: name ?? "Nikon", host: host, port: port, service: "sta")
        RecentlyConnectedStore.shared.remember(host: host, port: port, name: name ?? host)
        client.connect(method: .wifiSta(endpoint: endpoint), endpoint: endpoint)
    }

    private func registerHost() async {
        guard let endpoint = client.state.endpoint ?? client.lastStaEndpoint else {
            showSTAInfo = true
            return
        }
        await client.registerStaHost(endpoint: endpoint)
    }
}

// MARK: - 最近设备存储

struct RecentDevice: Identifiable, Equatable {
    let host: String
    let port: Int
    let name: String
    var id: String { "\(host):\(port)" }
}

/// 最近连接过的 STA 相机。安卓存在 Room，这里只需要一个很小的列表，用 UserDefaults 足够。
@MainActor
final class RecentlyConnectedStore {
    static let shared = RecentlyConnectedStore()

    private let key = "nl_recent_devices"
    private let limit = 5

    func recent() -> [RecentDevice] {
        guard let raw = UserDefaults.standard.array(forKey: key) as? [[String: Any]] else { return [] }
        return raw.compactMap { dict in
            guard let host = dict["host"] as? String,
                  let port = dict["port"] as? Int,
                  let name = dict["name"] as? String else { return nil }
            return RecentDevice(host: host, port: port, name: name)
        }
    }

    func remember(host: String, port: Int, name: String) {
        var list = recent().filter { $0.host != host || $0.port != port }
        list.insert(RecentDevice(host: host, port: port, name: name), at: 0)
        if list.count > limit { list = Array(list.prefix(limit)) }
        let raw = list.map { ["host": $0.host, "port": $0.port, "name": $0.name] }
        UserDefaults.standard.set(raw, forKey: key)
    }
}

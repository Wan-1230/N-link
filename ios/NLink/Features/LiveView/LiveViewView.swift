//
//  LiveViewView.swift
//  N-Link
//
//  全屏监看页 —— 对应安卓 `camera/liveview/LiveViewFragment.kt`（1231 行）
//  与 `activity_liveview.xml` + `fragment_liveview.xml`。
//
//  版面只有三区（UI 规格 :194-234，**没有独立的左侧栏**）：
//    · 上栏：关闭 44 / 弹性 / 模式芯片 / AF 芯片 / 弹性 / 性能读数 / 状态胶囊 / 显示辅助入口 44
//    · 右上：直方图卡 128×56 + RGB 波形卡 128×56（默认关）
//    · 下簇：参数快览五格 → 工具栏五项 → 快门行（相册 46 / 快门 72 内芯 54 / 旋转 44）
//
//  五面板与工具栏**互斥**：开面板时工具栏那格高亮，面板从底部升起；
//  「画面」面板不是浮层而是同时承载缩放与起停，所以它也算互斥项。
//
//  设计纪律：
//    · 页面恒黑（`liveViewPageBackground`），刻意无深色主题分支 —— 这是全 App
//      唯一「画面即内容」的页面，深灰底会让用户误判照片暗部。
//    · 尼康黄在这里有 4 处已登记例外：刻度尺游标、分段选中段、跟焦轮驱动位、
//      「监看中」状态点。理由：取景画面亮度不可控，中性色会被画面吃掉。
//    · 控件隐藏 = 纯净看画面；单击唤醒。横屏 + 监看中 + 控件可见 3.5s 无触摸自动隐藏。
//

import SwiftUI

struct LiveViewView: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    /// 从拍摄页进来时带的模式（照片/视频），只用于决定快门行是否显示录像态。
    let initialMode: RemoteCaptureMode

    // MARK: 状态

    @State private var chromeVisible = true
    @State private var activePanel: LivePanel?
    @State private var showAssistSheet = false
    @State private var hideWorkItem: DispatchWorkItem?

    /// 缩放/平移的视图层状态。与 `LiveViewManager.zoomLevel`（语义缩放）分开：
    /// 这里管的是「怎么看画面」，那里管的是「发给相机的数字变焦」。
    @State private var viewScale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var viewOffset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    /// 触摸对焦的视觉回执（点击处，750ms 呼吸一次后消失）。
    @State private var focusTapPoint: CGPoint?
    @State private var focusTapTask: Task<Void, Never>?

    @State private var showProgramMenu = false
    @State private var showAfMenu = false

    /// 跟焦轮（仅 MF 下发包）
    @State private var focusWheelPosition: Double = 0.5

    init(initialMode: RemoteCaptureMode = .photo) {
        self.initialMode = initialMode
    }

    // MARK: - Body

    var body: some View {
        ZStack {
            NlColor.liveViewPageBackground.ignoresSafeArea()
            frameLayer
            focusTapLayer
            overlays
            if chromeVisible { chrome }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .contentShape(Rectangle())
        .gesture(singleTapGesture)
        .simultaneousGesture(magnificationGesture)
        .simultaneousGesture(panGesture)
        .simultaneousGesture(doubleTapGesture)
        .onAppear {
            scheduleAutoHide()
            startIfNeeded()
            syncAnalysis()
        }
        .onDisappear {
            hideWorkItem?.cancel()
            // 离开监看页就把统计关掉：拍摄页不需要直方图，白算一帧都是浪费。
            env.liveView.setAnalysisEnabled(false)
        }
        .onChange(of: env.liveView.state) { state in
            if state == .error { chromeVisible = true }
        }
        .onChange(of: env.settings.histogramEnabled) { _ in syncAnalysis() }
        .onChange(of: env.settings.waveformEnabled) { _ in syncAnalysis() }
        .sheet(isPresented: $showAssistSheet) {
            assistSheet.presentationDetents([.medium])
        }
    }

    // MARK: - 画面层

    private var frameLayer: some View {
        GeometryReader { geo in
            Group {
                if let frame = env.liveView.latestFrame, let image = frame.image() {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .scaleEffect(viewScale)
                        .offset(viewOffset)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                } else {
                    placeholder
                }
            }
        }
        .ignoresSafeArea()
    }

    private var placeholder: some View {
        VStack(spacing: NlSpacing.s12) {
            switch env.liveView.state {
            case .starting:
                ProgressView().tint(NlColor.liveViewTextDim)
                Text("正在启动实时取景…")
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.liveViewTextDim)
            case .error:
                Text(env.liveView.errorMessage ?? "实时取景不可用")
                    .font(NlText.body)
                    .foregroundStyle(NlColor.liveViewTextDim)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, NlSpacing.pageMargin)
                Button("重试") {
                    Task { await env.liveView.startLiveView() }
                }
                .font(NlText.body)
                .foregroundStyle(NlColor.liveViewAccent)
            default:
                Text(env.client.isConnected ? "实时取景未启动" : "连接相机后显示取景画面")
                    .font(NlText.body)
                    .foregroundStyle(NlColor.liveViewTextDim)
            }
        }
    }

    // MARK: - 覆盖层：AF 框、网格、水平仪、斑马纹等

    @ViewBuilder
    private var overlays: some View {
        // 相机回显的 AF 区域（来自监看帧头，不是我们自己猜的）
        if let area = env.liveView.cameraAfArea {
            GeometryReader { geo in
                let rect = areaInView(area, size: geo.size)
                Rectangle()
                    .stroke(NlColor.liveViewAccent, lineWidth: 1.5)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
            }
            .allowsHitTesting(false)
            .transition(.opacity)
            .animation(.easeOut(duration: NlMotion.fast), value: env.liveView.cameraAfArea)
        }

        // 构图网格
        if env.liveView.gridOverlay != .off {
            GeometryReader { geo in
                GridOverlayShape(kind: env.liveView.gridOverlay)
                    .stroke(NlColor.liveViewText.opacity(0.35), lineWidth: 0.5)
                    .frame(width: geo.size.width, height: geo.size.height)
            }
            .allowsHitTesting(false)
        }

        // 水平仪（显示辅助里的一项，安卓用自绘 LevelView）
        if env.settings.assistLevelEnabled {
            GeometryReader { geo in
                LevelIndicator()
                    .stroke(NlColor.liveViewText.opacity(0.5), lineWidth: 1)
                    .frame(width: min(geo.size.width * 0.5, 220), height: 24)
                    .position(x: geo.size.width / 2, y: geo.size.height * 0.18)
            }
            .allowsHitTesting(false)
        }

        // 右上：直方图 + 波形
        if chromeVisible && (env.settings.histogramEnabled || env.settings.waveformEnabled) {
            VStack(alignment: .trailing, spacing: NlLiveViewSize.cellGap) {
                if env.settings.histogramEnabled {
                    HistogramCard()
                }
                if env.settings.waveformEnabled {
                    WaveformCard()
                }
            }
            .padding(.top, NlLiveViewSize.barVPadding + 44)
            .padding(.trailing, NlLiveViewSize.barHPadding)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
            .transition(.opacity)
        }
    }

    private func areaInView(_ rect: NormalizedRect, size: CGSize) -> CGRect {
        // 归一化矩形相对「监看 JPEG 显示内容」，而画面是 fit 进来的，四周可能有黑边。
        let jpeg = displayedFrameRect(in: size)
        return CGRect(
            x: jpeg.minX + jpeg.width * rect.left,
            y: jpeg.minY + jpeg.height * rect.top,
            width: jpeg.width * rect.width,
            height: jpeg.height * rect.height
        )
    }

    /// 画面 fit 之后在视口里占的矩形（含黑边修正）。
    private func displayedFrameRect(in size: CGSize) -> CGRect {
        guard let frame = env.liveView.latestFrame,
              let image = frame.image(),
              image.size.width > 0, image.size.height > 0 else {
            return CGRect(origin: .zero, size: size)
        }
        let scale = min(size.width / image.size.width, size.height / image.size.height)
        let w = image.size.width * scale
        let h = image.size.height * scale
        return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
    }

    // MARK: - 触摸对焦回执

    @ViewBuilder
    private var focusTapLayer: some View {
        if let point = focusTapPoint {
            RoundedRectangle(cornerRadius: 4)
                .stroke(NlColor.liveViewAccent, lineWidth: 1.5)
                .frame(width: 64, height: 64)
                .position(point)
                .allowsHitTesting(false)
                .transition(.opacity)
        }
    }

    // MARK: - Chrome（上栏 + 下簇）

    private var chrome: some View {
        VStack(spacing: 0) {
            topBar
            Spacer(minLength: 0)
            bottomCluster
        }
        .transition(.opacity)
    }

    private var topBar: some View {
        HStack(spacing: NlSpacing.s8) {
            iconButton("xmark") { close() }

            Spacer(minLength: NlSpacing.s4)

            modeChip
            afChip

            Spacer(minLength: NlSpacing.s4)

            if verticalSizeClass != .compact {
                perfReadout
            }
            statusPill
            iconButton(env.settings.anyAssistEnabled ? "slider.horizontal.3" : "square.grid.2x2") {
                showAssistSheet = true
            }
        }
        .padding(.horizontal, NlLiveViewSize.barHPadding)
        .padding(.vertical, NlLiveViewSize.barVPadding)
        .background(
            LinearGradient(colors: [.black.opacity(0.55), .clear],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .top)
                .allowsHitTesting(false)
        )
    }

    private var modeChip: some View {
        Menu {
            ForEach(env.parameters.exposureProgram.availableValues, id: \.self) { label in
                Button(label) {
                    Task { await env.parameters.setExposureProgram(label: label) }
                }
            }
            if env.parameters.exposureProgram.availableValues.isEmpty {
                Text("相机还没有回报可选项")
            }
        } label: {
            chipLabel("模式 \(shortProgram)", chevron: true)
        }
        .disabled(env.parameters.modeSwitchUnsupported)
    }

    private var afChip: some View {
        Menu {
            ForEach(env.parameters.focusMode.availableValues, id: \.self) { label in
                Button(label) {
                    Task { await env.parameters.setFocusMode(label: label) }
                }
            }
        } label: {
            chipLabel(shortFocus, chevron: true)
        }
    }

    /// `M (手动)` → `M`；读不到时给 `--`（不编造）。
    private var shortProgram: String {
        let text = env.parameters.exposureProgram.currentValue
        if text == "--" { return "--" }
        return text.split(separator: " ").first.map(String.init) ?? text
    }

    /// `AF-C (连续)` → `AF-C`；`MF (手动)` → `MF`
    private var shortFocus: String {
        let text = env.parameters.focusMode.currentValue
        if text == "--" { return "--" }
        return text.split(separator: " ").first.map(String.init) ?? text
    }

    private var perfReadout: some View {
        Text(String(format: "%.0f FPS · %.0f ms",
                    env.liveView.metrics.fps,
                    env.liveView.latestFrame?.latencyMs ?? 0))
            .font(NlText.foot)
            .monospacedDigit()
            .foregroundStyle(NlColor.liveViewTextDim)
    }

    private var statusPill: some View {
        HStack(spacing: 4) {
            Circle()
                .fill(statusDotColor)
                .frame(width: 6, height: 6)
            Text(statusText)
                .font(NlText.foot)
                .foregroundStyle(NlColor.liveViewText)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(NlColor.liveViewChip))
    }

    private var statusDotColor: Color {
        switch env.liveView.state {
        case .running: return NlColor.liveViewAccent   // 已登记例外：监看中状态点
        case .starting: return NlColor.liveViewTextDim
        case .error: return NlColor.liveViewBadgeRed
        case .stopped: return NlColor.liveViewTextDim
        }
    }

    private var statusText: String {
        switch env.liveView.state {
        case .running: return "监看中"
        case .starting: return "启动中"
        case .error: return "异常"
        case .stopped: return "未监看"
        }
    }

    // MARK: - 下簇

    private var bottomCluster: some View {
        VStack(spacing: 0) {
            if let panel = activePanel {
                panelView(panel)
                    .transition(.opacity)
            }
            paramStrip
            toolRow
            shutterRow
        }
        .background(
            LinearGradient(colors: [.clear, .black.opacity(0.72)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea(edges: .bottom)
                .allowsHitTesting(false)
        )
    }

    private var paramStrip: some View {
        HStack(spacing: NlLiveViewSize.paramCellMargin) {
            quickCell("快门", env.parameters.shutterSpeed.currentValue) { showShutterQuick() }
            quickCell("光圈", env.parameters.aperture.currentValue) { showApertureQuick() }
            quickCell("ISO", env.parameters.iso.currentValue) { showIsoQuick() }
            quickCell("EV", env.parameters.evCompensation.currentValue) { showEvQuick() }
            quickCell("WB", env.parameters.whiteBalance.currentValue) { showWbQuick() }
        }
        .padding(.horizontal, NlLiveViewSize.barHPadding)
        .padding(.vertical, NlLiveViewSize.paramPaddingV)
    }

    private func quickCell(_ label: String, _ value: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 1) {
                Text(value)
                    .font(NlText.monoDigits(size: 13, weight: .semibold))
                    .foregroundStyle(NlColor.liveViewText)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Text(label)
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.liveViewTextDim)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, NlLiveViewSize.paramCellPaddingV)
            .background(RoundedRectangle(cornerRadius: NlRadius.control).fill(NlColor.liveViewCard))
        }
        .buttonStyle(.nlPress)
    }

    private var toolRow: some View {
        HStack(spacing: 0) {
            ForEach(LivePanel.allCases) { panel in
                Button {
                    toggle(panel)
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: panel.icon)
                            .font(.system(size: 17, weight: .regular))
                            .frame(height: NlLiveViewSize.toolIconBoxHeight)
                        Text(panel.title).font(NlText.foot)
                    }
                    .foregroundStyle(activePanel == panel ? NlColor.liveViewAccent : NlColor.liveViewTextDim)
                    .frame(maxWidth: .infinity)
                    .frame(height: NlLiveViewSize.toolHeight - NlLiveViewSize.toolPaddingTop - NlLiveViewSize.toolPaddingBottom)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.top, NlLiveViewSize.toolPaddingTop)
        .padding(.bottom, NlLiveViewSize.toolPaddingBottom)
        .padding(.horizontal, NlLiveViewSize.barHPadding)
    }

    private var shutterRow: some View {
        HStack {
            Button {
                // 退出四路之一：快门行相册按钮 → 跳到相册页
                env.tab = .album
                dismiss()
            } label: {
                Image(systemName: "photo.on.rectangle")
                    .font(.system(size: 18))
                    .foregroundStyle(NlColor.liveViewText)
                    .frame(width: 46, height: 46)
                    .background(Circle().fill(NlColor.liveViewChip))
            }
            .buttonStyle(.plain)

            Spacer()

            shutterButton

            Spacer()

            Button {
                rotateToggle()
            } label: {
                Image(systemName: "rotate.right")
                    .font(.system(size: 18))
                    .foregroundStyle(NlColor.liveViewText)
                    .frame(width: NlLiveViewSize.iconButton, height: NlLiveViewSize.iconButton)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, NlLiveViewSize.barHPadding)
        .padding(.top, NlLiveViewSize.shutterRowPaddingTop)
        .padding(.bottom, NlLiveViewSize.shutterRowPaddingBottom)
    }

    private var shutterButton: some View {
        Button {
            fireShutter()
        } label: {
            ZStack {
                Circle()
                    .stroke(shutterRingColor, lineWidth: 3)
                    .frame(width: NlLiveViewSize.shutter, height: NlLiveViewSize.shutter)
                Circle()
                    .fill(shutterFillColor)
                    .frame(width: NlLiveViewSize.shutterInner, height: NlLiveViewSize.shutterInner)
                if env.remote.state == .videoRecording {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(NlColor.liveViewOnSolid)
                        .frame(width: 18, height: 18)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(env.remote.state.isBusy && env.remote.state != .videoRecording && env.remote.state != .bulbExposing)
    }

    private var shutterRingColor: Color {
        switch env.remote.state {
        case .videoRecording: return NlColor.liveViewBadgeRed
        case .bulbExposing: return NlColor.liveViewBadgeRed
        default: return NlColor.liveViewText
        }
    }

    private var shutterFillColor: Color {
        switch env.remote.state {
        case .videoRecording: return NlColor.liveViewBadgeRed
        case .bulbExposing: return NlColor.liveViewBadgeRed
        default: return NlColor.liveViewText.opacity(0.92)
        }
    }

    // MARK: - 五面板

    private func panelView(_ panel: LivePanel) -> some View {
        VStack(spacing: NlSpacing.s12) {
            switch panel {
            case .focus: focusPanel
            case .exposure: exposurePanel
            case .whiteBalance: whiteBalancePanel
            case .metering: meteringPanel
            case .assist: assistPanel
            case .screen: screenPanel
            }
        }
        .padding(.horizontal, NlLiveViewSize.sheetPaddingH)
        .padding(.top, NlLiveViewSize.sheetPaddingTop)
        .padding(.bottom, NlLiveViewSize.sheetPaddingBottom)
        .background(
            RoundedRectangle(cornerRadius: NlRadius.overlay)
                .fill(NlColor.liveViewPanel)
        )
        .padding(.horizontal, NlLiveViewSize.barHPadding)
    }

    private var focusPanel: some View {
        VStack(spacing: NlSpacing.s12) {
            NlSegmentBar(
                items: env.parameters.focusMode.availableValues,
                selection: Binding(
                    get: { env.parameters.focusMode.currentIndex },
                    set: { index in
                        let labels = env.parameters.focusMode.availableValues
                        guard labels.indices.contains(index) else { return }
                        Task { await env.parameters.setFocusMode(label: labels[index]) }
                    }
                )
            )

            focusWheel

            Text(focusHint)
                .font(NlText.foot)
                .foregroundStyle(NlColor.liveViewTextDim)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// 跟焦轮：−126°~−54° 共 36 格，**只在 MF 下发包**。
    ///
    /// 为什么不总可拖：AF 模式下相机会自己动镜头，这里再发 `0x9204` 会与
    /// AF 抢步进电机，真机上表现为画面持续抖动。
    private var focusWheel: some View {
        let isMF = env.parameters.focusMode.currentValue.hasPrefix("MF")
        return VStack(spacing: NlSpacing.s8) {
            GeometryReader { geo in
                ZStack {
                    ArcWheelShape()
                        .stroke(NlColor.liveViewOutline, lineWidth: 2)
                    ArcWheelTicks()
                        .stroke(NlColor.liveViewTextDim.opacity(0.5), lineWidth: 1)
                    // 驱动位：已登记例外之一
                    Capsule()
                        .fill(isMF ? NlColor.liveViewAccent : NlColor.liveViewTextDim)
                        .frame(width: 3, height: 18)
                        .offset(y: -geo.size.height * 0.30)
                        .rotationEffect(.degrees(-126 + 72 * focusWheelPosition))
                        .offset(y: geo.size.height * 0.30)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            guard isMF else { return }
                            let ratio = min(max((value.location.x / geo.size.width), 0), 1)
                            let delta = ratio - focusWheelPosition
                            guard abs(delta) > 0.02 else { return }
                            focusWheelPosition = ratio
                            Task {
                                await env.liveView.manualFocusDrive(direction: delta > 0 ? 1 : 2, speed: 2)
                            }
                        }
                )
            }
            .frame(height: 96)

            Text(isMF ? "拖动跟焦轮微调焦点" : "切到 MF 后可用跟焦轮手动微调")
                .font(NlText.foot)
                .foregroundStyle(NlColor.liveViewTextDim)
        }
    }

    private var focusHint: String {
        if env.parameters.focusMode.currentValue.hasPrefix("MF") {
            return "MF 模式下点击画面不再触发对焦，请用上方跟焦轮。"
        }
        return "点击画面任意位置对焦。对焦框会先显示触碰点，相机回显后换成真实 AF 区。"
    }

    private var exposurePanel: some View {
        VStack(spacing: NlSpacing.s8) {
            rulerRow(title: "快门", value: env.parameters.shutterSpeed.currentValue,
                     raw: env.parameters.shutterSpeed) { raw in
                Task { await env.parameters.setShutterSpeed(rawX10000: raw) }
            }
            rulerRow(title: "光圈", value: env.parameters.aperture.currentValue,
                     raw: env.parameters.aperture) { raw in
                Task { await env.parameters.setAperture(fX100: raw) }
            }
            rulerRow(title: "EV", value: env.parameters.evCompensation.currentValue,
                     raw: env.parameters.evCompensation) { raw in
                Task { await env.parameters.setEv(raw) }
            }
        }
    }

    /// 一条刻度尺：拖动改变档位。
    ///
    /// EV 用 `+0.3` 这种一位小数显示，而写回要 1/6 EV 整数，所以统一走 `availableRaw`。
    private func rulerRow(title: String, value: String, raw: CameraParam,
                          onPick: @escaping (Int) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.liveViewTextDim)
                Spacer()
                Text(value)
                    .font(NlText.monoDigits(size: 13, weight: .semibold))
                    .foregroundStyle(NlColor.liveViewText)
            }
            GeometryReader { geo in
                let count = max(raw.availableValues.count, 1)
                let index = raw.currentIndex
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(NlColor.liveViewSegTrack)
                        .frame(height: 4)
                    // 游标：已登记例外之一
                    Circle()
                        .fill(NlColor.liveViewAccent)
                        .frame(width: 12, height: 12)
                        .offset(x: count <= 1 ? 0 : (geo.size.width - 12) * CGFloat(index) / CGFloat(count - 1))
                }
                .frame(height: 16)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onEnded { drag in
                            guard count > 1 else { return }
                            let ratio = min(max(drag.location.x / geo.size.width, 0), 1)
                            let pick = Int((ratio * CGFloat(count - 1)).rounded())
                            if raw.availableRaw.indices.contains(pick) {
                                onPick(raw.availableRaw[pick])
                            }
                        }
                )
            }
            .frame(height: 16)
        }
    }

    private var whiteBalancePanel: some View {
        rulerRow(title: "白平衡", value: env.parameters.whiteBalance.currentValue,
                 raw: env.parameters.whiteBalance) { raw in
            Task { await env.parameters.setWhiteBalance(raw) }
        }
    }

    private var meteringPanel: some View {
        NlSegmentBar(
            items: env.parameters.meteringMode.availableValues,
            selection: Binding(
                get: { env.parameters.meteringMode.currentIndex },
                set: { index in
                    let labels = env.parameters.meteringMode.availableValues
                    guard labels.indices.contains(index) else { return }
                    Task { await env.parameters.setMeteringMode(label: labels[index]) }
                }
            )
        )
    }

    private var assistPanel: some View {
        // 9 项三行三列（网格 / 水平仪 / 直方图 / 伪彩 / 波形 / DISP / 斑马纹 / 峰值 / 安全框）
        //
        // 每项显式写成三元组 `(标题, 是否开启, 动作)`：Swift 里「数组元素后面直接跟尾随闭包」
        // 会被解析成函数调用的尾随闭包，而不是元组的第三段，于是报
        // 「cannot convert '(String, Bool)' to '(String, Bool, () -> Void)'」。
        // 把它们放进括号里、或显式标注类型，才能让编译器按元组读。
        let items: [(title: String, on: Bool, toggle: () -> Void)] = [
            ("网格", env.settings.assistGridEnabled, { env.settings.assistGridEnabled.toggle() }),
            ("水平仪", env.settings.assistLevelEnabled, { env.settings.assistLevelEnabled.toggle() }),
            ("直方图", env.settings.histogramEnabled, { env.settings.histogramEnabled.toggle() }),
            ("伪彩", env.settings.pseudoColorEnabled, { env.settings.pseudoColorEnabled.toggle() }),
            ("波形", env.settings.waveformEnabled, { env.settings.waveformEnabled.toggle() }),
            ("DISP", env.settings.assistCleanEnabled, { env.settings.assistCleanEnabled.toggle() }),
            ("斑马纹", env.settings.zebraEnabled, { env.settings.zebraEnabled.toggle() }),
            ("峰值", env.settings.peakingEnabled, { env.settings.peakingEnabled.toggle() }),
            ("安全框", env.settings.safeAreaEnabled, { env.settings.safeAreaEnabled.toggle() })
        ]
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: NlSpacing.s8), count: 3),
                         spacing: NlSpacing.s8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                Button(action: item.toggle) {
                    Text(item.title)
                        .font(NlText.foot)
                        .foregroundStyle(item.on ? NlColor.liveViewOnSolid : NlColor.liveViewText)
                        .frame(maxWidth: .infinity)
                        .frame(height: 32)
                        .background(
                            RoundedRectangle(cornerRadius: NlRadius.control)
                                .fill(item.on ? NlColor.liveViewText : NlColor.liveViewChip)
                        )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var screenPanel: some View {
        HStack(spacing: NlSpacing.s8) {
            panelAction(env.liveView.isRunning ? "停止监看" : "开始监看") {
                Task {
                    if env.liveView.isRunning {
                        await env.liveView.stopLiveView()
                    } else {
                        await env.liveView.startLiveView()
                    }
                }
            }
            panelAction("放大") {
                guard viewScale < 5 else { return }
                withAnimation(.easeOut(duration: NlMotion.fast)) {
                    viewScale = min(5, viewScale + 0.5)
                }
            }
            panelAction("缩小") {
                guard viewScale > 1 else { return }
                withAnimation(.easeOut(duration: NlMotion.fast)) {
                    viewScale = max(1, viewScale - 0.5)
                    if viewScale == 1 { viewOffset = .zero }
                }
            }
            panelAction("重置") {
                withAnimation(.easeOut(duration: NlMotion.base)) {
                    viewScale = 1; viewOffset = .zero
                }
            }
        }
    }

    private func panelAction(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(NlText.foot)
                .foregroundStyle(NlColor.liveViewText)
                .frame(maxWidth: .infinity)
                .frame(height: 32)
                .background(RoundedRectangle(cornerRadius: NlRadius.control).fill(NlColor.liveViewChip))
        }
        .buttonStyle(.plain)
    }

    // MARK: - 显示辅助 sheet（上栏右上入口）

    private var assistSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: NlSpacing.s12) {
                    Text("显示辅助只影响本机画面，不会改相机设置。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    assistPanel
                        .padding(12)
                        .background(
                            RoundedRectangle(cornerRadius: NlRadius.card)
                                .fill(NlColor.liveViewCard)
                        )
                }
                .padding(NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("显示辅助")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { showAssistSheet = false }
                }
            }
        }
    }

    // MARK: - 手势

    private var singleTapGesture: some Gesture {
        SpatialTapGesture()
            .onEnded { value in
                if activePanel != nil {
                    // 三态单击之二：面板开着时，点画面只关面板
                    withAnimation(.easeOut(duration: NlMotion.fast)) { activePanel = nil }
                    return
                }
                if !chromeVisible {
                    // 三态单击之一：控件隐藏时，点画面只唤醒
                    withAnimation(.easeOut(duration: NlMotion.base)) { chromeVisible = true }
                    scheduleAutoHide()
                    return
                }
                // 三态单击之三：对焦
                focusAt(value.location)
            }
    }

    private var doubleTapGesture: some Gesture {
        TapGesture(count: 2)
            .onEnded {
                withAnimation(.easeOut(duration: NlMotion.base)) {
                    viewScale = 1; viewOffset = .zero
                }
            }
    }

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                viewScale = min(max(lastScale * value, 1), 5)
            }
            .onEnded { _ in
                lastScale = viewScale
                if viewScale <= 1 { viewOffset = .zero; lastOffset = .zero }
            }
    }

    private var panGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                // 单指平移只在放大后成立（1× 时画面无处可移）
                guard viewScale > 1.01 else { return }
                viewOffset = clampedOffset(
                    CGSize(width: lastOffset.width + value.translation.width,
                           height: lastOffset.height + value.translation.height)
                )
            }
            .onEnded { _ in lastOffset = viewOffset }
    }

    private func clampedOffset(_ proposed: CGSize) -> CGSize {
        // 没有几何信息时只能按比例钳位：允许移出 (scale-1)/2 的视口比例。
        let limit = max(0, (viewScale - 1) / 2)
        let width = UIScreen.main.bounds.width * limit
        let height = UIScreen.main.bounds.height * limit
        return CGSize(
            width: min(max(proposed.width, -width), width),
            height: min(max(proposed.height, -height), height)
        )
    }

    private func focusAt(_ point: CGPoint) {
        guard env.liveView.isRunning else { return }
        let screen = UIScreen.main.bounds
        let jpeg = displayedFrameRect(in: CGSize(width: screen.width, height: screen.height))
        guard jpeg.contains(point), jpeg.width > 0, jpeg.height > 0 else { return }

        let nx = Double((point.x - jpeg.minX) / jpeg.width)
        let ny = Double((point.y - jpeg.minY) / jpeg.height)

        focusTapTask?.cancel()
        focusTapPoint = point
        focusTapTask = Task {
            await env.liveView.touchFocus(normalizedX: nx, normalizedY: ny)
            // 750ms 呼吸回执（点一下 -> 明 -> 灭）
            try? await Clock.sleep(ms: 750)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: NlMotion.fast)) { focusTapPoint = nil }
        }
        scheduleAutoHide()
    }

    // MARK: - 动作

    private func startIfNeeded() {
        guard env.client.isConnected else { return }
        guard !env.liveView.isRunning, env.liveView.state != .starting else { return }
        Task {
            env.parameters.startModeWatch(streaming: true)
            await env.liveView.startLiveView()
        }
    }

    /// 只有真开了直方图或波形才做统计。
    private func syncAnalysis() {
        env.liveView.setAnalysisEnabled(env.settings.histogramEnabled || env.settings.waveformEnabled)
    }

    private func fireShutter() {
        switch env.remote.state {
        case .bulbExposing:
            Task { await env.remote.stopBulb() }
        case .videoRecording:
            Task { await env.remote.stopVideoRecording() }
        default:
            Task { await env.remote.capture() }
        }
        scheduleAutoHide()
    }

    private func rotateToggle() {
        // 安卓是裁切/旋转监看帧的显示方向；iOS 走同一份显示状态。
        env.settings.liveViewRotated.toggle()
    }

    private func toggle(_ panel: LivePanel) {
        withAnimation(.easeOut(duration: NlMotion.slow)) {
            activePanel = (activePanel == panel) ? nil : panel
        }
        scheduleAutoHide()
    }

    private func close() {
        // 退出四路之一：关闭按钮。不停流 —— 回到拍摄页还要看画面。
        dismiss()
    }

    private func scheduleAutoHide() {
        hideWorkItem?.cancel()
        // 横屏 + 监看中才自动隐藏；竖屏隐藏会让用户「找不到按钮」。
        guard verticalSizeClass == .compact, env.liveView.isRunning else { return }
        let work = DispatchWorkItem {
            guard activePanel == nil else { return }
            withAnimation(.easeOut(duration: NlMotion.base)) { chromeVisible = false }
        }
        hideWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5, execute: work)
    }

    // MARK: - 小工具

    private func chipLabel(_ text: String, chevron: Bool) -> some View {
        HStack(spacing: 4) {
            Text(text).font(NlText.foot).foregroundStyle(NlColor.liveViewText)
            if chevron {
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold))
                    .foregroundStyle(NlColor.liveViewTextDim)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule().fill(NlColor.liveViewChip))
    }

    private func iconButton(_ name: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name)
                .font(.system(size: NlLiveViewSize.iconButton == 44 ? 18 : 18))
                .foregroundStyle(NlColor.liveViewText)
                .frame(width: NlLiveViewSize.iconButton, height: NlLiveViewSize.iconButton)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 面板枚举

enum LivePanel: String, CaseIterable, Identifiable {
    case focus, exposure, whiteBalance, metering, assist, screen

    var id: String { rawValue }

    var title: String {
        switch self {
        case .focus: return "对焦"
        case .exposure: return "曝光"
        case .whiteBalance: return "白平衡"
        case .metering: return "测光"
        case .assist: return "显示辅助"
        case .screen: return "画面"
        }
    }

    var icon: String {
        switch self {
        case .focus: return "scope"
        case .exposure: return "slider.horizontal.3"
        case .whiteBalance: return "sun.max"
        case .metering: return "circle.dotted"
        case .assist: return "square.on.square.dashed"
        case .screen: return "rectangle.inset.filled"
        }
    }
}

// MARK: - 绘制

/// 三分 / 对角 / 黄金比网格。
struct GridOverlayShape: Shape {
    let kind: GridOverlay

    func path(in rect: CGRect) -> Path {
        var path = Path()
        switch kind {
        case .off:
            break
        case .thirds:
            for i in 1...2 {
                let x = rect.minX + rect.width * CGFloat(i) / 3
                path.move(to: CGPoint(x: x, y: rect.minY))
                path.addLine(to: CGPoint(x: x, y: rect.maxY))
                let y = rect.minY + rect.height * CGFloat(i) / 3
                path.move(to: CGPoint(x: rect.minX, y: y))
                path.addLine(to: CGPoint(x: rect.maxX, y: y))
            }
        case .diagonal:
            path.move(to: CGPoint(x: rect.minX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.move(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        case .golden:
            let gx = rect.width * 0.382
            let gy = rect.height * 0.382
            for x in [rect.minX + gx, rect.maxX - gx] {
                path.move(to: CGPoint(x: x, y: rect.minY))
                path.addLine(to: CGPoint(x: x, y: rect.maxY))
            }
            for y in [rect.minY + gy, rect.maxY - gy] {
                path.move(to: CGPoint(x: rect.minX, y: y))
                path.addLine(to: CGPoint(x: rect.maxX, y: y))
            }
        }
        return path
    }
}

/// 水平仪：一条水平基准 + 一段可倾斜的指示。
struct LevelIndicator: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let midY = rect.midY
        path.move(to: CGPoint(x: rect.minX, y: midY))
        path.addLine(to: CGPoint(x: rect.maxX, y: midY))
        let center = rect.midX
        path.move(to: CGPoint(x: center - 14, y: midY - 6))
        path.addLine(to: CGPoint(x: center, y: midY))
        path.addLine(to: CGPoint(x: center + 14, y: midY - 6))
        return path
    }
}

/// 跟焦轮弧：−126°~−54°。
struct ArcWheelShape: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let radius = min(rect.width, rect.height * 2) * 0.42
        let center = CGPoint(x: rect.midX, y: rect.midY + radius * 0.55)
        path.addArc(center: center, radius: radius,
                    startAngle: .degrees(216), endAngle: .degrees(324), clockwise: false)
        return path
    }
}

struct ArcWheelTicks: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        let radius = min(rect.width, rect.height * 2) * 0.42
        let center = CGPoint(x: rect.midX, y: rect.midY + radius * 0.55)
        for i in 0...36 {
            // 显式用 Double 与 Foundation 的三角重载：`cos` 在 Darwin 里既有 Double 也有
            // Float 重载，上下文不足以推断时会报「ambiguous use of 'cos'」。
            let angle: Double = 216.0 + 108.0 * Double(i) / 36.0
            let rad: Double = angle * Double.pi / 180.0
            let inner: CGFloat = radius - (i % 6 == 0 ? 8 : 4)
            let cosRad: CGFloat = CGFloat(cos(rad))
            let sinRad: CGFloat = CGFloat(sin(rad))
            let p1 = CGPoint(x: center.x + cosRad * inner, y: center.y + sinRad * inner)
            let p2 = CGPoint(x: center.x + cosRad * radius, y: center.y + sinRad * radius)
            path.move(to: p1)
            path.addLine(to: p2)
        }
        return path
    }
}

// MARK: - 直方图 / 波形

/// 直方图卡。数据来自最新帧的亮度采样（复用 `FrameSampler`）。
struct HistogramCard: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("直方图")
                .font(NlText.foot)
                .foregroundStyle(NlColor.liveViewTextDim)
            ZStack {
                RoundedRectangle(cornerRadius: NlRadius.control).fill(NlColor.liveViewCardSolid)
                if let bins = env.liveView.luminanceBins, !bins.isEmpty {
                    HistogramShape(bins: bins)
                        .fill(NlColor.liveViewText.opacity(0.75))
                        .padding(.horizontal, 2)
                        .padding(.vertical, 4)
                }
            }
            .frame(width: NlLiveViewSize.graphWidth, height: NlLiveViewSize.graphHeight)
        }
        .padding(NlLiveViewSize.cardPadding)
        .background(RoundedRectangle(cornerRadius: NlRadius.card).fill(NlColor.liveViewCard))
    }
}

struct HistogramShape: Shape {
    let bins: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard bins.count > 1 else { return path }
        let maxValue = max(bins.max() ?? 1, 1)
        for (i, value) in bins.enumerated() {
            let x = rect.minX + rect.width * CGFloat(i) / CGFloat(bins.count - 1)
            let y = rect.maxY - rect.height * CGFloat(value / maxValue)
            if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
            else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// RGB 波形卡（128×64 采样 → 三条曲线）。
struct WaveformCard: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("RGB 波形")
                .font(NlText.foot)
                .foregroundStyle(NlColor.liveViewTextDim)
            ZStack {
                RoundedRectangle(cornerRadius: NlRadius.control).fill(NlColor.liveViewCardSolid)
                if let rows = env.liveView.waveformRows, !rows.isEmpty {
                    WaveformShape(rows: rows)
                        .stroke(NlColor.liveViewText.opacity(0.7), lineWidth: 1)
                        .padding(4)
                }
            }
            .frame(width: NlLiveViewSize.graphWidth, height: NlLiveViewSize.graphHeight)
        }
        .padding(NlLiveViewSize.cardPadding)
        .background(RoundedRectangle(cornerRadius: NlRadius.card).fill(NlColor.liveViewCard))
    }
}

struct WaveformShape: Shape {
    let rows: [Double]
    func path(in rect: CGRect) -> Path {
        var path = Path()
        guard rows.count > 1 else { return path }
        let maxValue = max(rows.max() ?? 1, 1)
        for (i, value) in rows.enumerated() {
            let x = rect.minX + rect.width * CGFloat(i) / CGFloat(rows.count - 1)
            let y = rect.maxY - rect.height * CGFloat(value / maxValue)
            if i == 0 { path.move(to: CGPoint(x: x, y: y)) }
            else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        return path
    }
}

// MARK: - 快速选择器

private extension LiveViewView {

    func showShutterQuick() {
        let param = env.parameters.shutterSpeed
        guard !param.availableValues.isEmpty else {
            env.showToast("相机还没有回报快门档位，稍等参数轮询完成。", isError: true)
            return
        }
        pickerSheet(title: "快门", values: param.availableValues, current: param.currentValue) { label in
            Task { await env.parameters.setShutterSpeed(label: label) }
        }
    }

    func showApertureQuick() {
        let param = env.parameters.aperture
        guard !param.availableValues.isEmpty else {
            env.showToast("相机还没有回报光圈档位，稍等参数轮询完成。", isError: true)
            return
        }
        pickerSheet(title: "光圈", values: param.availableValues, current: param.currentValue) { label in
            Task { await env.parameters.setAperture(label: label) }
        }
    }

    func showIsoQuick() {
        let param = env.parameters.iso
        pickerSheet(title: "ISO", values: param.availableValues, current: param.currentValue) { label in
            Task { await env.parameters.setIso(label: label) }
        }
    }

    func showEvQuick() {
        let param = env.parameters.evCompensation
        pickerSheet(title: "曝光补偿", values: param.availableValues, current: param.currentValue) { label in
            guard let index = param.availableValues.firstIndex(of: label),
                  param.availableRaw.indices.contains(index) else { return }
            let raw = param.availableRaw[index]
            Task { await env.parameters.setEv(raw) }
        }
    }

    func showWbQuick() {
        let param = env.parameters.whiteBalance
        pickerSheet(title: "白平衡", values: param.availableValues, current: param.currentValue) { label in
            Task { await env.parameters.setWhiteBalance(label: label) }
        }
    }

    /// 统一的选择弹窗：只有一列，当前项打勾。
    ///
    /// 不做滚轮（`Picker(.wheel)`）：监看页在暗环境里举着手机，滚轮需要精确对位，
    /// 一列点击项反而是这场景里最省力的。
    func pickerSheet(title: String, values: [String], current: String,
                     onPick: @escaping (String) -> Void) {
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let root = scene.keyWindow?.rootViewController else { return }
        let alert = UIAlertController(title: title, message: nil, preferredStyle: .actionSheet)
        for value in values {
            let mark = value == current ? "✓ " : ""
            alert.addAction(UIAlertAction(title: mark + value, style: .default) { _ in onPick(value) })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = root.view
            popover.sourceRect = CGRect(x: root.view.bounds.midX, y: root.view.bounds.maxY, width: 0, height: 0)
        }
        root.present(alert, animated: true)
    }
}

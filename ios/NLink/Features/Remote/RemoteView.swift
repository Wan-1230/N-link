import SwiftUI

/// 拍摄页。对应安卓 `RemoteFragment`（1160 行）+ `RemoteShootingManager`。
///
/// 版面（竖屏，自上而下）：
/// 1. 顶部行 52：照片/视频双段药丸 · 弹性 · 「联动画面 ▾」· 状态文字
/// 2. 取景区（3:2，`remoteMinHeight`…`remoteMaxHeight`）
/// 3. 参数区（weight 1）：工具行（全屏/直方图/网格/刷新）· 参数五格 · 快门行
/// 4. 底部「已拍 N 张」
///
/// 设计口径（见 PRODUCT.md 与 dimens 注释）：
/// - 取景区高度**只由宽度决定**（3:2 fitCenter）。左右留白收到 8pt，
///   画面因此从 322×214 涨到 354×235——相机画幅不该被页面边距裁掉。
/// - 参数五格顺序锁死：模式 → 光圈 → 快门 → ISO → 白平衡。EV 不可调，故不入格。
/// - 快门 72pt（内芯 54），按下 0.90、松手过冲 1.06、落定才下发。
///   过冲是「机械回弹」的隐喻，落定才发命令是为了不让手指抖动连拍两张。
struct RemoteView: View {

    @EnvironmentObject private var env: AppEnvironment

    @State private var showTimerMenu = false
    @State private var showActionMenu = false
    @State private var showPresetMenu = false
    @State private var shutterScale: CGFloat = 1
    @State private var shutterFlash = false
    @State private var timerSeconds: Int = 0
    @State private var bulbSeconds: Int = 30
    @State private var showLiveView = false
    @State private var liveViewMode: RemoteCaptureMode = .photo

    private var params: CameraParameterManager { env.parameters }
    private var remote: RemoteShootingManager { env.remote }
    private var isVideo: Bool { liveViewMode == .video }

    var body: some View {
        ZStack(alignment: .bottom) {
            NlColor.background.ignoresSafeArea()

            VStack(spacing: 0) {
                topRow
                liveContainer
                parameterArea
            }

            // 快门白闪：只盖画面，不盖控件（README 里的「白闪延后」实现）
            if shutterFlash {
                Color.white
                    .opacity(0.75)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .transition(.opacity)
            }
        }
        .fullScreenCover(isPresented: $showLiveView) {
            LiveViewView(initialMode: liveViewMode)
                .environmentObject(env)
        }
        .onChange(of: env.remote.state) { state in
            if state == .idle {
                // 拍摄落定后再刷新相册，避免在快门窗口内抢命令通道
                env.scheduleAlbumRefreshAfterCapture()
            }
        }
    }

    // MARK: - 顶部行

    private var topRow: some View {
        HStack(spacing: NlSpacing.s8) {
            NlSegmentBar(
                items: RemoteCaptureMode.allCases.map(\.title),
                selection: Binding(
                    get: { RemoteCaptureMode.allCases.firstIndex(of: liveViewMode) ?? 0 },
                    set: { newValue in
                        guard !remote.state.isBusy else { return }
                        withAnimation(NlMotion.fastAnim) {
                            liveViewMode = RemoteCaptureMode.allCases[newValue]
                        }
                    }
                ),
                trackColor: NlColor.surfaceVariant,
                selectedBackground: NlColor.primary,
                onColor: NlColor.onPrimary
            )
            .frame(width: 132, height: 34)

            Spacer(minLength: NlSpacing.s8)

            Menu {
                ForEach(AppSettings.DisplayMode.allCases, id: \.self) { mode in
                    Button {
                        env.settings.remoteDisplayMode = mode
                    } label: {
                        Label(mode.title, systemImage: env.settings.remoteDisplayMode == mode ? "checkmark" : "rectangle.on.rectangle")
                    }
                }
            } label: {
                HStack(spacing: 3) {
                    Text(env.settings.remoteDisplayMode.title)
                        .font(NlText.nav)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                }
                .foregroundStyle(NlColor.textSecondary)
                .padding(.horizontal, NlSpacing.s12)
                .frame(height: 34)
                .background(NlColor.surfaceVariant, in: Capsule())
            }

            Text(statusText)
                .font(NlText.foot)
                .foregroundStyle(remote.state.isBusy ? NlColor.textPrimary : NlColor.textTertiary)
                .lineLimit(1)
        }
        .padding(.horizontal, NlSpacing.pageMargin)
        .frame(height: 52)
    }

    private var statusText: String {
        switch remote.state {
        case .idle:
            return env.client.isConnected ? "就绪" : "未连接"
        case .capturing: return "拍摄中…"
        case .timerCountdown:
            return "倒计时 \(remote.timerCountdown)s"
        case .intervalShooting:
            if let p = remote.intervalProgress {
                return "间隔 \(p.completedShots)/\(p.totalShots)"
            }
            return "间隔拍摄中"
        case .bulbExposing:
            return String(format: "B 门 %.1fs", remote.bulbExposureTime)
        case .videoPreparing: return "准备录像…"
        case .videoRecording: return "录制中"
        }
    }

    // MARK: - 取景区

    private var liveContainer: some View {
        GeometryReader { geo in
            let width = geo.size.width - NlLiveViewSize.remoteMargin * 2
            // 画面能有多高只由画面有多宽决定：3:2 恒定，不随可用高度拉伸
            let frameHeight = min(
                max(width * 2 / 3, NlLiveViewSize.remoteMinHeight),
                NlLiveViewSize.remoteMaxHeight
            )
            ZStack {
                RoundedRectangle(cornerRadius: NlRadius.overlay)
                    .fill(Color.black)

                if !env.client.isConnected {
                    VStack(spacing: NlSpacing.s12) {
                        Image(systemName: "camera.metering.unknown")
                            .font(.system(size: 26, weight: .light))
                            .foregroundStyle(NlColor.liveViewTextDim)
                        Text("连接相机后显示取景画面")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.liveViewTextDim)
                        Button("去连接") { env.tab = .device }
                            .font(NlText.nav)
                            .foregroundStyle(NlColor.onNikonYellow)
                            .padding(.horizontal, NlSpacing.s16)
                            .frame(height: 32)
                            .background(NlColor.nikonYellow, in: Capsule())
                    }
                } else {
                    LiveFramePreview()
                }

                // 右上角：进全屏监看的入口。取景区本身只有 235pt 高，
                // 不做触摸对焦（会与「点开全屏」抢手势），对焦统一在全屏页做。
                VStack {
                    HStack {
                        Spacer()
                        Button {
                            showLiveView = true
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.up.left.and.arrow.down.right")
                                    .font(.system(size: 12, weight: .semibold))
                                Text("全屏")
                                    .font(.system(size: 11, weight: .semibold))
                            }
                            .foregroundStyle(NlColor.liveViewText)
                            .padding(.horizontal, NlSpacing.s8)
                            .frame(height: 28)
                            .background(NlColor.liveViewChip, in: Capsule())
                        }
                        .padding(NlSpacing.s8)
                    }
                    Spacer()
                }
            }
            .frame(height: frameHeight)
            .frame(maxWidth: .infinity)
        }
        .frame(height: liveContainerHeight)
        .onAppear { syncLiveViewState() }
    }

    private var liveContainerHeight: CGFloat {
        let screenWidth = UIScreen.main.bounds.width
        let width = screenWidth - NlLiveViewSize.remoteMargin * 2
        return min(
            max(width * 2 / 3, NlLiveViewSize.remoteMinHeight),
            NlLiveViewSize.remoteMaxHeight
        )
    }

    private func syncLiveViewState() {
        // 拍摄页要画面，进页面就开始监看（安卓同样在 onResume 起流）。
        // 不在全屏页时才停：那样用户从全屏返回会黑一下，体验更差。
        guard env.client.isConnected, env.liveView.state != .running else { return }
        Task {
            // 推流期间模式轮询要放慢到 ≥1.5s，否则 0x500E 的 500ms 轮询会挤占命令通道。
            env.parameters.startModeWatch(streaming: true)
            await env.liveView.startLiveView()
        }
    }

    // MARK: - 参数区

    private var parameterArea: some View {
        VStack(spacing: 0) {
            toolRow
            paramRow
            shutterRow
            Text("已拍 \(remote.shotCount) 张")
                .font(NlText.foot)
                .foregroundStyle(NlColor.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.bottom, NlSize.dockBaselineHeight + NlSpacing.s4)
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private var toolRow: some View {
        HStack(spacing: NlSpacing.s8) {
            toolChip("直方图", "chart.bar", on: env.settings.histogramEnabled) {
                env.settings.histogramEnabled.toggle()
            }
            toolChip("网格", "grid", on: env.liveView.gridOverlay != .off) {
                env.liveView.cycleGrid()
            }
            toolChip("刷新参数", "arrow.clockwise", on: false) {
                Task { await env.parameters.readAll() }
            }
            Spacer()
            Menu {
                ForEach(storedPresets, id: \.id) { preset in
                    Button(preset.name) { applyPreset(preset) }
                }
            } label: {
                toolChipLabel("预设", "slider.horizontal.3", on: false)
            }
        }
        .padding(.horizontal, NlSpacing.pageMargin)
        .padding(.top, NlSpacing.s8)
    }

    private func toolChip(_ title: String, _ icon: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { toolChipLabel(title, icon, on: on) }
            .buttonStyle(.plain)
    }

    private func toolChipLabel(_ title: String, _ icon: String, on: Bool) -> some View {
        VStack(spacing: 2) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(on ? NlColor.textPrimary : NlColor.textTertiary)
                .frame(width: 40, height: 28)
                .background(on ? NlColor.surfaceVariant : Color.clear, in: RoundedRectangle(cornerRadius: NlRadius.control))
            Text(title)
                .font(.system(size: 10))
                .foregroundStyle(on ? NlColor.textSecondary : NlColor.textTertiary)
        }
    }

    private var storedPresets: [ShootingPreset] {
        env.presets.presets
    }

    private var paramRow: some View {
        HStack(spacing: 8) {
            paramCell(
                label: "模式",
                value: isVideo ? "视频" : CameraParameterManager.describeExposureProgram(params.exposureProgram.rawValue),
                enabled: !isVideo && !remote.state.isBusy
            ) { showProgramPicker() }

            paramCell(
                label: "光圈",
                value: params.aperture.currentValue,
                enabled: !isVideo && !remote.state.isBusy && !params.paramsLocked
            ) { showAperturePicker() }

            paramCell(
                label: "快门",
                value: params.shutterSpeed.currentValue,
                enabled: !isVideo && !remote.state.isBusy && !params.paramsLocked
            ) { showShutterPicker() }

            paramCell(
                label: "ISO",
                value: params.iso.currentValue,
                enabled: !isVideo && !remote.state.isBusy && !params.paramsLocked
            ) { showIsoPicker() }

            paramCell(
                label: "白平衡",
                value: params.whiteBalance.currentValue,
                enabled: !isVideo && !remote.state.isBusy && !params.paramsLocked
            ) { showWhiteBalancePicker() }
        }
        .padding(.horizontal, NlSpacing.pageMargin)
        .padding(.vertical, NlSpacing.s12)
    }

    private func paramCell(label: String, value: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Text(value)
                    .font(NlText.monoDigits(size: 14, weight: .bold))
                    .foregroundStyle(enabled ? NlColor.textPrimary : NlColor.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(label)
                    .font(.system(size: 10))
                    .foregroundStyle(NlColor.textTertiary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(NlColor.surface, in: RoundedRectangle(cornerRadius: NlRadius.control))
            .overlay(
                RoundedRectangle(cornerRadius: NlRadius.control)
                    .stroke(NlColor.outline, lineWidth: 1)
            )
        }
        .buttonStyle(NlPressStyle())
        .disabled(!enabled)
        .accessibilityLabel("\(label)：\(value)")
    }

    // MARK: - 快门行

    private var shutterRow: some View {
        HStack(alignment: .center, spacing: NlSpacing.s16) {
            // 左：定时
            Menu {
                Button("关闭定时") { timerSeconds = 0 }
                Button("2 秒") { timerSeconds = 2 }
                Button("5 秒") { timerSeconds = 5 }
                Button("10 秒") { timerSeconds = 10 }
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: timerSeconds > 0 ? "timer.circle.fill" : "timer")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(timerSeconds > 0 ? NlColor.textPrimary : NlColor.textTertiary)
                    Text(timerSeconds > 0 ? "\(timerSeconds)s" : "定时")
                        .font(.system(size: 10))
                        .foregroundStyle(timerSeconds > 0 ? NlColor.textSecondary : NlColor.textTertiary)
                }
                .frame(width: 48, height: 48)
            }
            .disabled(remote.state.isBusy)

            Spacer(minLength: 0)

            // 快门
            Button {
                fireShutter()
            } label: {
                ZStack {
                    Circle()
                        .stroke(NlColor.textPrimary.opacity(0.25), lineWidth: 3)
                        .frame(width: NlLiveViewSize.shutter, height: NlLiveViewSize.shutter)
                    Circle()
                        .fill(interlockColor)
                        .frame(width: NlLiveViewSize.shutterInner, height: NlLiveViewSize.shutterInner)
                }
                .scaleEffect(shutterScale)
                .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .disabled(remote.state == .bulbExposing ? false : remote.state.isBusy)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in
                        withAnimation(.easeOut(duration: NlMotion.instant)) { shutterScale = 0.90 }
                    }
                    .onEnded { _ in
                        // 机械回弹：过冲到 1.06 再落 1.0，落定后才熄
                        withAnimation(.spring(response: 0.18, dampingFraction: 0.6)) { shutterScale = 1.06 }
                        DispatchQueue.main.asyncAfter(deadline: .now() + NlMotion.instant) {
                            withAnimation(NlMotion.fastAnim) { shutterScale = 1 }
                        }
                    }
            )
            .accessibilityLabel(remote.state == .bulbExposing ? "结束曝光" : (isVideo ? "开始录像" : "快门"))

            Spacer(minLength: 0)

            // 右：分体动作（间隔 / B 门）
            Menu {
                if env.settings.remoteActionMode == .interval {
                    ForEach(IntervalConfig.presets, id: \.totalShots) { preset in
                        Button(preset.title) {
                            startInterval(preset)
                        }
                    }
                    if remote.state == .intervalShooting {
                        Button("停止间隔拍摄", role: .destructive) { remote.stopInterval() }
                    }
                    Button("切换为「B 门长曝光」") {
                        env.settings.remoteActionMode = .bulb
                        showActionMenu = true
                    }
                } else {
                    if remote.state == .bulbExposing {
                        Button("结束曝光") { Task { await remote.stopBulb() } }
                    } else {
                        Button("B 门（手动结束）") { startBulb(duration: nil) }
                        ForEach([15, 30, 60, 120, 300], id: \.self) { seconds in
                            Button("长曝光 \(seconds) 秒") { startBulb(duration: TimeInterval(seconds)) }
                        }
                    }
                    Button("切换为「间隔拍摄」") {
                        env.settings.remoteActionMode = .interval
                        showActionMenu = true
                    }
                }
            } label: {
                VStack(spacing: 2) {
                    Image(systemName: env.settings.remoteActionMode == .interval ? "square.stack.3d.down.right" : "clock.badge")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(remote.state.isBusy ? NlColor.textTertiary : NlColor.textSecondary)
                    Text(env.settings.remoteActionMode == .interval ? "间隔" : "B 门")
                        .font(.system(size: 10))
                        .foregroundStyle(NlColor.textTertiary)
                }
                .frame(width: 48, height: 48)
            }
            .disabled(remote.state.isBusy && remote.state != .bulbExposing && remote.state != .intervalShooting)
        }
        .padding(.horizontal, NlSpacing.s24)
        .padding(.bottom, NlSpacing.s12)
    }

    private var interlockColor: Color {
        if remote.state == .bulbExposing { return NlColor.liveViewBadgeRed }
        if isVideo {
            return remote.state == .videoRecording ? NlColor.liveViewBadgeRed : NlColor.textPrimary
        }
        return NlColor.textPrimary
    }

    // MARK: - 快门动作

    private func fireShutter() {
        if remote.state == .bulbExposing {
            Task { await remote.stopBulb() }
            return
        }

        if isVideo {
            Task {
                if remote.state == .videoRecording {
                    await remote.stopVideoRecording()
                } else {
                    await remote.startVideoRecording()
                }
            }
            return
        }

        // 白闪必须在快门落定**之后**：先闪再等命令会让用户以为已经拍到了
        flashThenFire()

        Task {
            if timerSeconds > 0 {
                await remote.startTimerCapture(delaySeconds: timerSeconds)
            } else {
                _ = await remote.capture()
            }
        }
    }

    private func flashThenFire() {
        withAnimation(.easeOut(duration: NlMotion.instant)) { shutterFlash = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.10) {
            withAnimation(.easeIn(duration: NlMotion.fast)) { shutterFlash = false }
        }
    }

    private func startInterval(_ preset: IntervalConfig) {
        Task { await remote.startInterval(preset) }
    }

    private func startBulb(duration: TimeInterval?) {
        remote.bulbDuration = duration
        Task { await remote.startBulb() }
    }

    private func applyPreset(_ preset: ShootingPreset) {
        Task {
            let failed = await params.applyPreset(preset)
            if failed.isEmpty {
                env.showToast("已套用预设「\(preset.name)」")
            } else {
                // 部分成功的预设不能报「已套用」：用户会以为参数全对，拍出来却不是那个档。
                env.showToast("预设「\(preset.name)」未完全套用：\(failed.joined(separator: "、"))没写进去", isError: true)
            }
        }
    }

    // MARK: - 参数选择器

    private func showProgramPicker() {
        guard !params.modeSwitchUnsupported else {
            env.showToast("这台机身不支持从 App 切换曝光模式，请用机身拨盘。", isError: true)
            return
        }
        showPicker(
            title: "曝光模式",
            values: params.exposureProgram.availableValues,
            current: params.exposureProgram.currentValue
        ) { picked in
            Task { await params.setExposureProgram(label: picked) }
        }
    }

    private func showAperturePicker() {
        showPicker(
            title: "光圈",
            values: params.aperture.availableValues,
            current: params.aperture.currentValue
        ) { picked in
            Task { await params.setAperture(label: picked) }
        }
    }

    private func showShutterPicker() {
        // 快门档位末档追加「B门」——它是机身真实状态，不是本机臆造
        var values = params.shutterSpeed.availableValues
        if !values.contains(where: { $0.contains("B") || $0.contains("门") }) {
            values.append("B门")
        }
        showPicker(title: "快门速度", values: values, current: params.shutterSpeed.currentValue) { picked in
            Task { await params.setShutterSpeed(label: picked) }
        }
    }

    private func showIsoPicker() {
        showPicker(
            title: "ISO 感光度",
            values: params.iso.availableValues,
            current: params.iso.currentValue
        ) { picked in
            Task { await params.setIso(label: picked) }
        }
    }

    private func showWhiteBalancePicker() {
        showPicker(
            title: "白平衡",
            values: params.whiteBalance.availableValues,
            current: params.whiteBalance.currentValue
        ) { picked in
            Task { await params.setWhiteBalance(label: picked) }
        }
    }

    private func showPicker(
        title: String,
        values: [String],
        current: String,
        onPick: @escaping (String) -> Void
    ) {
        guard !values.isEmpty else {
            env.showToast("相机还没有回报可选项，稍等参数轮询完成。", isError: true)
            return
        }
        pickerRequest = PickerRequest(title: title, values: values, current: current, onPick: onPick)
    }

    @State private var pickerRequest: PickerRequest?
}

/// 拍摄页的照片/视频切换。
enum RemoteCaptureMode: String, CaseIterable, Identifiable {
    case photo
    case video

    var id: String { rawValue }
    var title: String { self == .photo ? "照片" : "视频" }
}

/// 参数选择请求（用于弹 sheet）。
struct PickerRequest: Identifiable {
    let id = UUID()
    let title: String
    let values: [String]
    let current: String
    let onPick: (String) -> Void
}

/// 取景区里的画面。
///
/// 单独抽出来是因为它必须**只依赖 `latestFrame`**：把这个视图放进
/// `RemoteView` 的 body 里会让整个页面在 15fps 下重算（参数格、快门行都跟着重建）。
/// SwiftUI 里把高频刷新面缩到最小子视图是唯一能保住帧率的做法。
struct LiveFramePreview: View {

    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        Group {
            if let frame = env.liveView.latestFrame, let image = frame.image(inSample: 2) {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                VStack(spacing: NlSpacing.s8) {
                    if env.liveView.state == .starting {
                        ProgressView().tint(NlColor.liveViewTextDim)
                        Text("正在启动实时取景…")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.liveViewTextDim)
                    } else if env.liveView.state == .error {
                        Text(env.liveView.errorMessage ?? "实时取景不可用")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.liveViewTextDim)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, NlSpacing.s24)
                    } else if env.liveView.state == .stopped {
                        Text("实时取景未启动")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.liveViewTextDim)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

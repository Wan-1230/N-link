//
//  CameraDetailView.swift
//  N-Link
//
//  相机详情页 —— 对应安卓 `device/CameraDetailActivity.kt`（424 行）+ `activity_camera_detail.xml`。
//
//  版面（UI 规格 :273-282）：
//    顶栏（返回 + 标题 + 刷新）→ 身份卡（24sp 名称 + 状态）
//    → 「相机信息」7 行 KV（型号/固件/序列号/镜头/快门次数/电池/存储卡）
//    → 「连接」4 行（方式/频段/时长/重连次数）
//      （安卓版第 5 行是蓝牙信号；本项目 iOS 端没有 BLE 通道，故整行移除）
//    → 「位置同步」（状态 + 立即写入 + 与分享 GPS 的区别）
//    → 「本机支持的能力」2 列网格
//    → 「固件更新」（检查 + 打开官方下载中心）
//    → 「诊断」（复制相机信息）
//    → 页脚「N-Link 不下载、不刷写相机固件」
//
//  设计纪律：只读为主。这一页不是控制台，是"这台机器现在是什么状态"的快照，
//  所以除了「刷新」「写入位置」「复制诊断」三个动作，其余全部不可点。
//

import SwiftUI
import CoreLocation

struct CameraDetailView: View {

    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var refreshing = false
    @State private var writingLocation = false
    @State private var showFirmwareNote = false

    private var info: CameraInfo { env.parameters.cameraInfo }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: NlSpacing.moduleGapLarge) {
                    identityCard
                    cameraInfoSection
                    connectionSection
                    locationSection
                    capabilitySection
                    firmwareSection
                    diagnosticsSection
                    footNote
                }
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.vertical, NlSpacing.s8)
            }
            .background(NlColor.background)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("相机详情")
                        .font(NlText.title)
                        .foregroundStyle(NlColor.textPrimary)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await refresh() }
                    } label: {
                        if refreshing {
                            ProgressView().scaleEffect(0.8)
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .disabled(refreshing || !env.client.isConnected)
                }
            }
            .alert("固件更新", isPresented: $showFirmwareNote) {
                Button("知道了", role: .cancel) {}
            } message: {
                Text("本 App 只负责检查与指引：不会下载、不会刷写固件。请用官方渠道更新。")
            }
        }
    }

    // MARK: - 身份卡

    private var identityCard: some View {
        NlCard {
            VStack(alignment: .leading, spacing: NlSpacing.s8) {
                Text(displayName)
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(NlColor.textPrimary)
                    .lineLimit(2)
                HStack(spacing: NlSpacing.s8) {
                    NlStatusDot(kind: statusDotKind)
                    Text(statusText)
                        .font(NlText.caption)
                        .foregroundStyle(NlColor.textSecondary)
                }
                if let endpoint = env.client.state.endpoint {
                    Text(endpoint.display)
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var displayName: String {
        if let model = env.client.state.deviceInfo?.displayModel, !model.isEmpty { return model }
        if !info.modelName.isEmpty { return info.modelName }
        return "未连接相机"
    }

    private var statusText: String {
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

    private var statusDotKind: NlStatusDotKind {
        switch env.client.state {
        case .connected: return .connected
        case .discovering, .connecting, .recovering, .awaitingHostConfirm: return .pending
        case .disconnected, .failed: return .idle
        }
    }

    // MARK: - 相机信息（7 行）

    private var cameraInfoSection: some View {
        NlSection(title: "相机信息") {
            VStack(spacing: 0) {
                NlKeyValueRow(key: "型号", value: dash(info.modelName))
                NlDivider()
                NlKeyValueRow(key: "固件", value: dash(info.firmwareVersion))
                NlDivider()
                NlKeyValueRow(key: "序列号", value: dash(info.serialNumber))
                NlDivider()
                NlKeyValueRow(key: "镜头", value: info.lensText)
                NlDivider()
                shutterCountRow
                NlDivider()
                NlKeyValueRow(key: "电池", value: info.batteryText)
                NlDivider()
                NlKeyValueRow(key: "存储卡", value: info.storageText)
            }
        }
    }

    /// 快门次数这一行必须能解释"为什么没有数字"。
    ///
    /// 尼康不提供快门计数的 PTP 属性，唯一来源是照片 EXIF 的 MakerNote；
    /// 没有照片、读不到字节、解不出来，是三种完全不同的处境，用户该做的事也不同。
    private var shutterCountRow: some View {
        VStack(alignment: .leading, spacing: NlSpacing.s4) {
            HStack(alignment: .top, spacing: NlSpacing.groupGap) {
                Text("快门次数")
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textTertiary)
                Spacer(minLength: NlSpacing.groupGap)
                Text(info.shutterCountText)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textPrimary)
            }
            if let sub = shutterSub {
                Text(sub)
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, NlSpacing.s8)
        .frame(minHeight: 40)
    }

    private var shutterSub: String? {
        switch info.shutterQueryState {
        case .querying: return "正在从最近三张照片的 EXIF 里读…"
        case .failed: return info.shutterFailReason.guidance
        case .success:
            if info.shutterCountSource == .cloud { return "云端解析 · 未交叉校验" }
            if info.shutterCountRange != nil { return "三张样本不一致，显示区间" }
            if !info.shutterVerified { return "未通过固件恒等式交叉校验" }
            // 走到这里：有区间说明前面已经拦掉了，所以只剩「三样本一致」一种情况。
            return "已交叉校验 · 三样本一致"
        case .none: return "点设备页的快门次数格开始读取"
        }
    }

    // MARK: - 连接（4 行）

    private var connectionSection: some View {
        NlSection(title: "连接") {
            VStack(spacing: 0) {
                NlKeyValueRow(key: "方式", value: methodText)
                NlDivider()
                NlKeyValueRow(key: "频段", value: env.client.currentBandDescription ?? "iOS 无法读取")
                NlDivider()
                NlKeyValueRow(key: "连接时长", value: connectedDurationText)
                NlDivider()
                NlKeyValueRow(key: "重连次数", value: "\(env.client.reconnectCount)")
            }
        }
    }

    private var methodText: String {
        guard let endpoint = env.client.state.endpoint else { return "—" }
        switch endpoint.service {
        case "ap": return "WiFi AP（相机热点）"
        case "sta": return env.client.staHostRegistered ? "WiFi STA（已注册）" : "WiFi STA"
        case "usb": return "USB"
        default: return endpoint.service
        }
    }

    private var connectedDurationText: String {
        guard let since = env.client.connectedSince else { return "—" }
        let seconds = Int(Date().timeIntervalSince1970 - since)
        guard seconds > 0 else { return "刚刚" }
        let m = seconds / 60
        let s = seconds % 60
        if m >= 60 {
            return "\(m / 60) 小时 \(m % 60) 分"
        }
        return m > 0 ? "\(m) 分 \(s) 秒" : "\(s) 秒"
    }

    // MARK: - 位置同步

    private var locationSection: some View {
        NlSection(title: "位置同步") {
            VStack(alignment: .leading, spacing: NlSpacing.s12) {
                HStack {
                    Text(env.settings.cameraLocationEnabled ? "已开启" : "未开启")
                        .font(NlText.body)
                        .foregroundStyle(NlColor.textPrimary)
                    Spacer()
                    Button {
                        Task { await writeLocation() }
                    } label: {
                        if writingLocation {
                            ProgressView().scaleEffect(0.75)
                        } else {
                            Text("立即写入")
                                .font(NlText.caption)
                                .foregroundStyle(NlColor.primary)
                        }
                    }
                    .disabled(writingLocation || !env.client.isConnected)
                }
                Text("写进相机的位置会影响此后由机身拍摄的照片。它与「分享副本保留 GPS」互不相干：后者只管从 App 分享出去的那份副本。")
                    .font(NlText.foot)
                    .foregroundStyle(NlColor.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, NlSpacing.s8)
        }
    }

    private func writeLocation() async {
        // 位置写入需要系统定位授权；这里不静默失败，把三种结果都说清。
        guard env.client.isConnected, let session = env.client.sessionOrNil() else {
            env.showToast("相机未连接，无法写入位置。", isError: true)
            return
        }
        writingLocation = true
        defer { writingLocation = false }

        guard let coordinate = await LocationProvider.shared.current() else {
            env.showToast("拿不到当前位置：请在系统设置里允许 N-Link 使用定位。", isError: true)
            return
        }
        // PTP UPDATE_LOCATION（0x9523）：载荷是标准 PTP 字符串数组
        // （纬度、经度、海拔、坐标系、日期时间）。这里只写前两项加空位，
        // 相机会用自己已有的时区补全。
        let writer = PtpDatasetWriter()
        writer.string(String(format: "%.6f", coordinate.latitude))
        writer.string(String(format: "%.6f", coordinate.longitude))
        writer.string("")
        writer.string("WGS84")
        writer.string(ISO8601DateFormatter().string(from: Date()))
        do {
            let result = try await session.rawTransact(
                opcode: Ptp.opNikonUpdateLocation,
                params: [],
                dataOut: writer.data(),
                timeoutMs: 8_000
            )
            if result.isOk {
                env.showToast("已把当前位置写入相机")
            } else {
                env.showToast("写入失败：\(Ptp.responseName(result.code))。这台机身可能不支持位置写入。", isError: true)
            }
        } catch {
            // 传输层断了也要给一句人话，不能只吞掉。
            let message = (error as? CameraError)?.message ?? error.localizedDescription
            env.showToast("写入失败：\(message)", isError: true)
        }
    }

    // MARK: - 本机支持的能力

    private var capabilitySection: some View {
        NlSection(title: "本机支持的能力") {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: NlSpacing.s8),
                                GridItem(.flexible(), spacing: NlSpacing.s8)],
                      spacing: NlSpacing.s8) {
                ForEach(capabilities, id: \.name) { item in
                    HStack(spacing: NlSpacing.s8) {
                        Image(systemName: item.supported ? "checkmark.circle.fill" : "minus.circle")
                            .font(.system(size: 13))
                            .foregroundStyle(item.supported ? NlColor.textPrimary : NlColor.textTertiary)
                        Text(item.name)
                            .font(NlText.foot)
                            .foregroundStyle(item.supported ? NlColor.textPrimary : NlColor.textTertiary)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .frame(height: 32)
                }
            }
            .padding(.vertical, NlSpacing.s8)
        }
    }

    private struct Capability {
        let name: String
        let supported: Bool
    }

    /// 只用 `DeviceInfo.givenOperations` 判断 —— 这是相机自己声明的那张表，
    /// 比"我们猜它能干什么"可靠。
    private var capabilities: [Capability] {
        let device = env.client.state.deviceInfo
        func supports(_ op: UInt16) -> Bool { device?.supportsOperation(op) ?? false }
        return [
            Capability(name: "实时取景", supported: supports(Ptp.opNikonStartLiveView)),
            Capability(name: "相册直读", supported: supports(Ptp.opGetObjectInfo)),
            Capability(name: "分段读取", supported: supports(Ptp.opGetPartialObject)),
            Capability(name: "缩略图", supported: supports(Ptp.opGetThumb)),
            Capability(name: "对焦驱动", supported: supports(Ptp.opNikonAfDrive)),
            Capability(name: "手动对焦", supported: supports(Ptp.opNikonMfDrive)),
            Capability(name: "B 门", supported: supports(Ptp.opNikonInitiateCaptureRecInMedia)),
            Capability(name: "录像", supported: supports(Ptp.opNikonStartMovieRecInCard))
        ]
    }

    // MARK: - 固件更新

    private var firmwareSection: some View {
        NlSection(title: "固件更新") {
            VStack(spacing: 0) {
                NlSettingRow(
                    title: "检查更新",
                    value: dash(info.firmwareVersion),
                    action: { showFirmwareNote = true }
                )
                NlDivider()
                NlSettingRow(
                    title: "打开官方下载中心",
                    showsChevron: true
                ) {
                    if let url = URL(string: "https://downloadcenter.nikonimglib.com/") {
                        UIApplication.shared.open(url)
                    }
                }
            }
        }
    }

    // MARK: - 诊断

    private var diagnosticsSection: some View {
        NlSection(title: "诊断") {
            Button {
                UIPasteboard.general.string = diagnosticsText
                env.showToast("相机信息已复制")
            } label: {
                HStack {
                    Text("复制相机信息")
                        .font(NlText.body)
                        .foregroundStyle(NlColor.textPrimary)
                    Spacer()
                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 13))
                        .foregroundStyle(NlColor.textTertiary)
                }
                .frame(minHeight: 52)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var diagnosticsText: String {
        """
        型号：\(dash(info.modelName))
        固件：\(dash(info.firmwareVersion))
        序列号：\(dash(info.serialNumber))
        镜头：\(info.lensText)
        快门次数：\(info.shutterCountText)
        电池：\(info.batteryText)
        存储卡：\(info.storageText)
        连接：\(methodText)
        服务：\(env.client.state.endpoint?.service ?? "—")
        地址：\(env.client.state.endpoint?.display ?? "—")
        操作码数：\(env.client.state.deviceInfo?.operationsSupported.count ?? 0)
        """
    }

    private var footNote: some View {
        Text("N-Link 不下载、不刷写相机固件。")
            .font(NlText.foot)
            .foregroundStyle(NlColor.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 动作

    private func refresh() async {
        guard env.client.isConnected else { return }
        refreshing = true
        await env.parameters.readAll()
        await env.parameters.refreshCameraInfoNow()
        refreshing = false
    }

    private func dash(_ text: String) -> String {
        text.isEmpty ? NlPlaceholder.value : text
    }
}

// MARK: - 定位

/// 相机详情页「立即写入位置」用的定位封装。
///
/// 只要一次授权、一个坐标，不需要持续订阅 —— 常驻的 `CLLocationManager`
/// 会白白耗电，而这里一天最多点一次。
@MainActor
final class LocationProvider: NSObject {

    static let shared = LocationProvider()

    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocationCoordinate2D?, Never>?

    override private init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    func current() async -> CLLocationCoordinate2D? {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            return nil
        default:
            break
        }
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            manager.requestLocation()
        }
    }

    fileprivate func finish(_ coordinate: CLLocationCoordinate2D?) {
        continuation?.resume(returning: coordinate)
        continuation = nil
    }
}

extension LocationProvider: CLLocationManagerDelegate {

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let coordinate = locations.last?.coordinate
        Task { @MainActor in finish(coordinate) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        Task { @MainActor in finish(nil) }
    }
}

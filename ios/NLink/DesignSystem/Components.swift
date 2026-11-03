//
//  Components.swift
//  N-Link
//
//  设计系统的可复用视图。对应安卓 `shared/ui/widget/*` 与 `values/styles.xml`。
//
//  三条贯穿全文件的纪律（照抄安卓 PRODUCT.md）：
//  1. 尼康黄只允许出现在两个地方——连接状态指示灯、主按钮填充。别的任何地方都不许用。
//  2. 填充 = 浮起，描边 = 贴平。这个是主次区分手段，不是装饰；
//     所以描边按钮刻意没有阴影，而主按钮有。
//  3. 禁用态不能静默。安卓的做法是 `alpha 0.5 + isEnabled=false + isClickable=false`
//     ——三通道一起关。这里用 `.disabled()` 关掉交互，同时把透明度压到 0.5，
//     并尽量在文案里说清为什么不能点（见各页面里的禁用说明）。
//

import SwiftUI

// MARK: - 文字样式

extension View {
    func nlTitle() -> some View { font(NlText.title).foregroundStyle(NlColor.textPrimary) }
    func nlSectionTitle() -> some View { font(NlText.sectionTitle).foregroundStyle(NlColor.textPrimary) }
    func nlSubtitle() -> some View { font(NlText.subtitle).foregroundStyle(NlColor.textPrimary) }
    func nlGroupTitle() -> some View { font(NlText.groupTitle).foregroundStyle(NlColor.textSecondary) }
    func nlBody() -> some View { font(NlText.body).foregroundStyle(NlColor.textPrimary) }
    func nlCaption() -> some View { font(NlText.caption).foregroundStyle(NlColor.textSecondary) }
    func nlFoot() -> some View { font(NlText.foot).foregroundStyle(NlColor.textTertiary) }
}

// MARK: - 按压反馈

/// 全局按压反馈：0.95 缩放，80ms（INSTANT）。
///
/// 安卓的 `PressEffect` 由 `stateListAnimator @null` 接管，系统不参与。
/// SwiftUI 里用 ButtonStyle 做同一件事，并且同样受「减弱动效」开关约束。
struct NlPressStyle: ButtonStyle {
    var scale: CGFloat = NlMotion.pressScale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? scale : 1)
            .animation(reduceMotion ? nil : .easeOut(duration: NlMotion.instant), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == NlPressStyle {
    static var nlPress: NlPressStyle { NlPressStyle() }
}

// MARK: - 按钮

/// `NlButton.Primary`：黄底 + 近黑字。
///
/// 白字在黄上只有 1.31:1，禁用；黑字 16.02:1，这是唯一合法组合。
struct NlPrimaryButton: View {
    let title: String
    var enabled: Bool = true
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NlText.body.weight(.bold))
                .foregroundStyle(NlColor.onNikonYellow)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(NlColor.nikonYellow, in: RoundedRectangle(cornerRadius: NlRadius.control, style: .continuous))
        }
        .buttonStyle(.nlPress)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        // 主按钮的 2dp 浮起：填充 = 浮起
        .shadow(color: .black.opacity(0.12), radius: 2, y: 1)
    }
}

/// `NlButton.Outline`：描边 + 主文字色，**没有阴影**。
struct NlOutlineButton: View {
    let title: String
    var enabled: Bool = true
    var height: CGFloat = 48
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NlText.body)
                .foregroundStyle(NlColor.textPrimary)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .overlay(
                    RoundedRectangle(cornerRadius: NlRadius.control, style: .continuous)
                        .stroke(NlColor.outline, lineWidth: 1)
                )
        }
        .buttonStyle(.nlPress)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// 深色卡内的对比按钮（`NlButton.Contrast`）。
struct NlContrastButton: View {
    let title: String
    var filled: Bool = true
    var height: CGFloat = 48
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NlText.body.weight(filled ? .bold : .regular))
                .foregroundStyle(NlColor.onDarkCard)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .background {
                    if filled {
                        RoundedRectangle(cornerRadius: NlRadius.control, style: .continuous)
                            .fill(NlColor.darkCardInner)
                    } else {
                        RoundedRectangle(cornerRadius: NlRadius.control, style: .continuous)
                            .stroke(NlColor.onDarkCard.opacity(0.28), lineWidth: 1)
                    }
                }
        }
        .buttonStyle(.nlPress)
    }
}

// MARK: - 卡片

/// `NlCard`：surface 底 + 12 圆角 + 1dp 描边 + **零阴影**。
struct NlCard<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(NlColor.surface, in: RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous)
                    .stroke(NlColor.outline, lineWidth: 1)
            )
    }
}

// MARK: - 图标按钮

struct NlIconButton: View {
    let systemName: String
    var size: CGFloat = NlSize.iconButton
    var glyphSize: CGFloat = NlSize.iconGlyph
    var tint: Color = NlColor.textPrimary
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: glyphSize, weight: .regular))
                .foregroundStyle(tint)
                .frame(width: size, height: size)
                .contentShape(Rectangle())
        }
        .buttonStyle(.nlPress)
    }
}

// MARK: - 顶栏

struct NlTopBar<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 0) {
            Text(title)
                .font(NlText.title.weight(.bold))
                .foregroundStyle(NlColor.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: NlSpacing.s8)
            trailing
        }
        .padding(.horizontal, NlSpacing.pageMargin)
        .frame(height: NlSize.topBarHeight)
        .background(NlColor.background)
    }
}

extension NlTopBar where Trailing == EmptyView {
    init(title: String) {
        self.title = title
        self.trailing = EmptyView()
    }
}

// MARK: - 空态

/// `NlEmptyState`：断连 ≠ 为空。
///
/// 这个组件只负责「真的没有东西」这一类；断连请用 NlStateBar 或转圈 + 文案，
/// 摆空态插画会让用户以为是自己把照片删光了。
struct NlEmptyState: View {
    let title: String
    var desc: String?
    var systemImage: String?
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 28, weight: .regular))
                    .foregroundStyle(NlColor.textTertiary)
                    .frame(width: NlSize.emptyIconBox, height: NlSize.emptyIconBox)
                    .background(NlColor.surfaceVariant, in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .padding(.bottom, NlSpacing.s16)
                    .accessibilityHidden(true)
            }
            Text(title)
                .font(NlText.title.weight(.bold))
                .foregroundStyle(NlColor.textPrimary)
                .multilineTextAlignment(.center)
            if let desc {
                Text(desc)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, NlSpacing.s4)
            }
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .font(NlText.caption.weight(.medium))
                        .foregroundStyle(NlColor.onPrimary)
                        .padding(.horizontal, 20)
                        .frame(height: NlSize.emptyActionHeight)
                        .background(NlColor.primary, in: Capsule())
                }
                .buttonStyle(.nlPress)
                .padding(.top, NlSpacing.s20)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, NlSpacing.pageMargin)
    }
}

// MARK: - 状态条

/// `NlStateBar`：内联提示条。**全组件黑白灰**——错误与成功都用文字表达，不上色。
///
/// 安卓的理由：这个 App 里色彩是稀缺资源，红色一旦用作「错误」，
/// 就会挤掉尼康黄唯一的两个位置；而且色盲用户读不出红绿。
struct NlStateBar: View {
    let title: String
    var desc: String?
    var icon: String = "exclamationmark"
    var actionTitle: String?
    var action: (() -> Void)?
    var onDismiss: (() -> Void)?

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Text(icon)
                .font(NlText.caption.weight(.bold))
                .foregroundStyle(NlColor.textPrimary)
                .frame(width: 36, height: 36)
                .background(NlColor.chipUnselected, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(.trailing, NlSpacing.groupGap)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if let desc {
                    Text(desc)
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .font(NlText.foot.weight(.medium))
                        .foregroundStyle(NlColor.textPrimary)
                        .padding(.horizontal, 14)
                        .frame(height: NlSize.stateBarActionHeight)
                        .overlay(Capsule().stroke(NlColor.outline, lineWidth: 1))
                }
                .buttonStyle(.nlPress)
                .padding(.leading, NlSpacing.groupGap)
            }
        }
        .padding(.leading, 12)
        .padding(.trailing, 8)
        .padding(.vertical, 12)
        .background(NlColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(NlColor.outline, lineWidth: 1)
        )
        .contentShape(Rectangle())
        .onTapGesture { onDismiss?() }
    }
}

// MARK: - 分段条

/// `SegmentBar`：分段药丸。选中段是**尼康黄字 + 34% 白底**，未选中段没有底。
struct NlSegmentBar: View {
    let items: [String]
    @Binding var selection: Int
    var onColor: Color = NlColor.liveViewAccent
    var trackColor: Color = NlColor.liveViewSegTrack
    /// 选中段底色与未选中字色。默认值服务全屏监看的暗底场景；
    /// 浅色页面（拍摄页的照片/视频切换）必须显式传入，否则黄字落在白底上只有 1.28:1。
    var selectedBackground: Color = NlColor.liveViewSegSelected
    var unselectedForeground: Color = NlColor.liveViewText.opacity(0.9)

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { index in
                let selected = index == selection
                Button {
                    withAnimation(.easeOut(duration: NlMotion.slow)) { selection = index }
                } label: {
                    Text(items[index])
                        .font(NlText.caption.weight(.bold))
                        .foregroundStyle(selected ? onColor : unselectedForeground)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background {
                            if selected {
                                RoundedRectangle(cornerRadius: 6, style: .continuous).fill(selectedBackground)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
                .accessibilityLabel("\(items[index])，\(selected ? "已选中" : "未选中")")
            }
        }
        .padding(4)
        .background(trackColor, in: Capsule())
    }
}

/// 页面级分段（底部 dock 用的不是它，是自定义的 TabDock）。
struct NlPageTabs: View {
    let items: [String]
    @Binding var selection: Int

    var body: some View {
        HStack(spacing: 4) {
            ForEach(items.indices, id: \.self) { index in
                let selected = index == selection
                Button {
                    selection = index
                } label: {
                    Text(items[index])
                        .font(NlText.nav.weight(selected ? .bold : .regular))
                        .foregroundStyle(selected ? NlColor.textPrimary : NlColor.textTertiary)
                        .frame(maxWidth: .infinity)
                        .frame(height: 40)
                        .background {
                            if selected {
                                Capsule().fill(NlColor.navSelectedBackground)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .padding(4)
        .background(NlColor.surfaceVariant, in: Capsule())
    }
}

// MARK: - 筛选芯片

struct NlChip: View {
    let title: String
    var selected: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NlText.caption)
                .foregroundStyle(selected ? NlColor.onPrimary : NlColor.textPrimary)
                .lineLimit(1)
                .padding(.horizontal, 16)
                .padding(.vertical, 7)
                .background(
                    selected ? NlColor.primary : NlColor.chipUnselected,
                    in: Capsule()
                )
        }
        .buttonStyle(.nlPress)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

// MARK: - 状态指示灯

/// 连接状态点。**尼康黄的第一处合法用途**。
///
/// 三档（照抄 `DashboardFragment.kt:249-257`）：
/// - connected：黄芯
/// - pending：中性芯
/// - idle：空芯（只有描边环）
enum NlStatusDotKind { case connected, pending, idle }

struct NlStatusDot: View {
    let kind: NlStatusDotKind
    var size: CGFloat = 8

    var body: some View {
        ZStack {
            Circle()
                .fill(kind == .idle ? .clear : fillColor)
                .overlay(Circle().stroke(strokeColor, lineWidth: 1))
                .frame(width: size, height: size)
        }
        .accessibilityHidden(true)
    }

    private var fillColor: Color {
        switch kind {
        case .connected: return NlColor.nikonYellow
        case .pending: return NlColor.statusDisconnected
        case .idle: return .clear
        }
    }

    private var strokeColor: Color {
        switch kind {
        case .connected: return NlColor.nikonYellow
        case .pending: return NlColor.statusDisconnected
        case .idle: return NlColor.statusDisconnected
        }
    }
}

// MARK: - 进度条

struct NlProgressBar: View {
    var value: Double
    var height: CGFloat = 3

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(NlColor.surfaceVariant)
                Capsule()
                    .fill(NlColor.textPrimary)
                    .frame(width: max(0, min(1, value)) * proxy.size.width)
            }
        }
        .frame(height: height)
    }
}

// MARK: - 设置行

struct NlSettingRow: View {
    let title: String
    var value: String?
    var subtitle: String?
    var showsChevron: Bool = true
    var action: (() -> Void)?

    var body: some View {
        Button {
            action?()
        } label: {
            HStack(spacing: NlSpacing.groupGap) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(NlText.body)
                        .foregroundStyle(NlColor.textPrimary)
                    if let subtitle {
                        Text(subtitle)
                            .font(NlText.foot)
                            .foregroundStyle(NlColor.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if let value {
                    Text(value)
                        .font(NlText.caption)
                        .foregroundStyle(NlColor.textTertiary)
                        .lineLimit(1)
                }
                if showsChevron {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(NlColor.textTertiary)
                }
            }
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct NlSettingToggleRow: View {
    let title: String
    var subtitle: String?
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: NlSpacing.groupGap) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(NlText.body)
                    .foregroundStyle(NlColor.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Toggle("", isOn: $isOn)
                .labelsHidden()
                .tint(NlColor.nikonYellow)
        }
        .frame(minHeight: 52)
    }
}

// MARK: - 分组容器

struct NlSection<Content: View>: View {
    let title: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: NlSpacing.groupGap) {
            if let title {
                Text(title)
                    .font(NlText.caption.weight(.bold))
                    .foregroundStyle(NlColor.textSecondary)
            }
            VStack(spacing: 0) {
                content
            }
            .padding(.horizontal, NlSpacing.s16)
            .background(NlColor.surface, in: RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: NlRadius.card, style: .continuous)
                    .stroke(NlColor.outline, lineWidth: 1)
            )
        }
    }
}

struct NlDivider: View {
    var body: some View {
        Rectangle()
            .fill(NlColor.divider)
            .frame(height: NlSpacing.dividerHeight)
    }
}

// MARK: - 键值行

struct NlKeyValueRow: View {
    let key: String
    let value: String

    var body: some View {
        HStack(alignment: .top, spacing: NlSpacing.groupGap) {
            Text(key)
                .font(NlText.caption)
                .foregroundStyle(NlColor.textTertiary)
            Spacer(minLength: NlSpacing.groupGap)
            Text(value)
                .font(NlText.caption)
                .foregroundStyle(NlColor.textPrimary)
                .multilineTextAlignment(.trailing)
        }
        .frame(minHeight: 40)
    }
}

// MARK: - 兜底占位

/// 数值未知时统一写「—」，不写「--」也不留空。
enum NlPlaceholder {
    static let value = "—"
}

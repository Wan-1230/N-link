//
//  RootView.swift
//  N-Link
//
//  应用骨架：内容区 + 自定义底部 dock。对应安卓 `activity_main.xml` + `MainActivityDock`。
//
//  **为什么不用 `TabView`**：安卓那边的 dock 是自绘的 LinearLayout，不是
//  `BottomNavigationView`——选中态是胶囊底 + 实心图标 + 加粗文字（`MainActivity.kt:386-402`），
//  这套规格 TabView 表达不出来（它的 item 只能图 + 字，不能给选中项单独画胶囊）。
//  这里沿用同样的自绘做法，代价是四页要自己管生命周期。
//
//  **切 Tab 不做淡出**（`MainActivity.kt:404-417` 用 `hide/show + commitNow`）：
//  淡出期间两页都在屏上，用户会看到旧页面的残影，读起来像「点了没反应」。
//

import SwiftUI

struct RootView: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        ZStack(alignment: .bottom) {
            NlColor.background.ignoresSafeArea()

            // 内容区：四页常驻，靠 opacity 0 / allowsHitTesting 关掉非当前页。
            // 用 ZStack 而不是 switch，是为了保住各页的滚动位置与内部状态——
            // 安卓那边 Fragment 是用 add/hide/show 保活的，行为一致。
            ZStack {
                DeviceView()
                    .opacity(env.tab == .device ? 1 : 0)
                    .allowsHitTesting(env.tab == .device)
                GalleryView(client: env.client)
                    .opacity(env.tab == .album ? 1 : 0)
                    .allowsHitTesting(env.tab == .album)
                RemoteView()
                    .opacity(env.tab == .remote ? 1 : 0)
                    .allowsHitTesting(env.tab == .remote)
                SettingsView()
                    .opacity(env.tab == .settings ? 1 : 0)
                    .allowsHitTesting(env.tab == .settings)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            TabDock(selection: $env.tab)
        }
        .overlay(alignment: .top) {
            ToastOverlay()
        }
    }
}

// MARK: - 底部 dock

/// 自定义 dock。选中项 = 胶囊底 + 实心图标 + 加粗 12pt。
///
/// 尺寸：高 60（`dockBaselineHeight`），图标本体 22，文字 12。
/// 安卓端 dock 文字用的是 `font_nav` 12sp，这里同样取 12，没有用系统的 tab 字号。
struct TabDock: View {
    @Binding var selection: AppTab
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.allCases) { tab in
                let selected = tab == selection
                Button {
                    guard selection != tab else { return }
                    // 不加动画：同步换页
                    selection = tab
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: selected ? tab.iconFilled : tab.icon)
                            .font(.system(size: 22, weight: selected ? .semibold : .regular))
                            .foregroundStyle(selected ? NlColor.navActiveIcon : NlColor.navInactiveIcon)
                            .frame(height: 24)
                        Text(tab.title)
                            .font(.system(size: NlFontSize.nav, weight: selected ? .bold : .regular))
                            .foregroundStyle(selected ? NlColor.textPrimary : NlColor.textTertiary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: NlSize.dockBaselineHeight)
                    .background {
                        if selected {
                            Capsule()
                                .fill(NlColor.navSelectedBackground)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
            }
        }
        .frame(height: NlSize.dockBaselineHeight)
        .background(NlColor.navBackground)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(NlColor.divider)
                .frame(height: NlSpacing.dividerHeight)
        }
    }
}

// MARK: - 轻提示

struct ToastOverlay: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        if let toast = env.toast {
            Text(toast.text)
                .font(NlText.caption)
                .foregroundStyle(NlColor.onDarkCard)
                .multilineTextAlignment(.center)
                .padding(.horizontal, NlSpacing.s16)
                .padding(.vertical, NlSpacing.s12)
                .background(
                    RoundedRectangle(cornerRadius: NlRadius.overlay, style: .continuous)
                        .fill(NlColor.darkCard)
                )
                .padding(.horizontal, NlSpacing.pageMargin)
                .padding(.top, NlSpacing.s8)
                .transition(.move(edge: .top).combined(with: .opacity))
                .id(toast.id)
        }
    }
}

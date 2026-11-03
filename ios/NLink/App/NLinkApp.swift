//
//  NLinkApp.swift
//  N-Link
//
//  应用入口。
//

import SwiftUI

@main
struct NLinkApp: App {

    @StateObject private var env = AppEnvironment()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(env)
                .preferredColorScheme(nil)   // 跟随系统，与安卓 DayNight 一致
                .onAppear {
                    env.start()
                    observeAccessibility()
                }
        }
    }

    /// 「减弱动态效果」改变时要通知令牌层。
    ///
    /// SwiftUI 里 `@Environment(\.accessibilityReduceMotion)` 会自己更新，
    /// 但 `MotionSettings` 是命令式的（给非视图代码用），需要手工同步一次。
    private func observeAccessibility() {
        NotificationCenter.default.addObserver(
            forName: UIAccessibility.reduceMotionStatusDidChangeNotification,
            object: nil,
            queue: .main
        ) { _ in
            Task { @MainActor in MotionSettings.shared.refresh() }
        }
    }
}

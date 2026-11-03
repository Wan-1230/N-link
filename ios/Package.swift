// swift-tools-version: 5.9
//
//  Package.swift
//  N-Link (iOS)
//
//  交付形态说明：
//
//  安卓端是 Gradle 工程（`app/build.gradle.kts`）。iOS 这边的等价物有两种：
//  ① `NLink.xcodeproj` —— 一个二进制 zip 包，必须用 Xcode 生成，不适合以文本形式交付/审阅；
//  ② `Package.swift`（SwiftPM）—— 纯文本、可 diff、Xcode 15 起能直接「Open Package」
//     并 Run 到真机。
//
//  这里选 ②。理由不是"省事"，是因为本轮开发环境里**没有 Xcode / xcodebuild**
//  （交付时已如实声明未编译），而一份可读、可 diff 的 SwiftPM 清单比一个
//  我无法生成、你也无法审阅的 .xcodeproj 诚实得多。
//
//  要用 Xcode 图形界面跑：
//    File ▸ Open… ▸ 选中本文件所在目录 → Scheme 选 NLink → 选真机 → Run。
//  想转成 .xcodeproj：
//    Xcode 打开后 File ▸ New ▸ Target 也能加，但更推荐保持 SwiftPM 单一真相源。
//
//  **App 形态注意**：SwiftPM 默认产出 framework/library 而非 .app，
//  真机安装需要宿主 App。因此本清单定义的是**可编译的 Swift Package（library）**，
//  供宿主 App target 直接依赖；`Info.plist` 与 `LaunchScreen` 见同目录 `Resources/`。
//  若需要独立可运行的 .app，请按 `ios/docs/接入宿主工程.md` 建一个空壳 App target
//  并把 `NLink` 作为本地 Swift Package 依赖引入（会随后续构建脚本补齐）。
//

import PackageDescription

let package = Package(
    name: "NLink",
    // iOS 16：`SpatialTapGesture`（监看页三态单击）、`NavigationStack`、
    // `.persistentSystemOverlays`、`Grid` 都是 16 起。
    // 安卓端 minSdk 29（Android 10）大致对应 iOS 15/16 这一档的老机型覆盖，
    // 取 16 是因为再往下要多写三处手势的手工实现，收益不足以抵消。
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(name: "NLink", targets: ["NLink"])
    ],
    targets: [
        .target(
            name: "NLink",
            path: "NLink",
            exclude: [
                // 纯资源目录（Info.plist、LaunchScreen、AppIcon）不打进编译单元。
                "Resources"
            ],
            swiftSettings: [
                // 与安卓端 Kotlin 2.1 的严格度对齐：不做隐式转换、要求完整并发检查。
                // `.enableUpcomingFeature` 里没有与 Kotlin 等价的全集，
                // 这里只打开对本次代码实际有约束的两项。
                .enableUpcomingFeature("ExistentialAny"),
                .enableUpcomingFeature("BareSlashRegexLiterals")
            ]
        )
    ]
)

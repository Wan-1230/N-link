//
//  SettingsSheets.swift
//  N-Link
//
//  设置页的说明性弹窗 —— 对应安卓 `fragment_settings.xml` 里那几个
//  「点开看长解释」的行（位置选项区别 / 使用教程 / 常见问题 / 意见反馈 / 关于 / 相机详情）。
//
//  这些内容在安卓端散落在 SettingsFragment 的多个 Dialog 里，iOS 侧集中到本文件：
//  它们全是静态文案，与业务逻辑无关，混在 SettingsView 里只会让那一千行更难读。
//

import SwiftUI

// MARK: - 两个位置选项的区别

/// 「向相机发送位置信息」与「分享副本保留 GPS」是两条完全不同的链路。
///
/// 用户最常见的误解是「我关了分享 GPS，为什么相机拍的照片还有位置」——
/// 因为那条位置是相机自己带 GPS、或者是我们写进相机的，与分享副本无关。
struct LocationHelpSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    helpBlock(
                        title: "向相机发送位置信息",
                        body: """
                        把手机的当前位置写进相机（尼康 PTP 的 UPDATE_LOCATION 操作码）。\
                        此后由相机拍摄的照片会自己带上 GPS 信息 —— 即使手机不在旁边。

                        这条链路影响的是**相机拍的原图**。关闭后相机不再接收新位置，\
                        但已经写进去的旧位置不会消失。
                        """
                    )
                    helpBlock(
                        title: "分享副本保留 GPS",
                        body: """
                        只影响从 App 分享出去的那份「预览副本」。

                        预览副本默认走 2048 长边 + JPEG 90%，并且**剥离全部 GPS 字段**，\
                        只保留拍摄参数（相机、镜头、光圈、快门、ISO、时间）。\
                        打开这一项后，副本会带上原始照片里的 GPS。
                        """
                    )
                    helpBlock(
                        title: "为什么默认剥离",
                        body: """
                        社交平台上带 GPS 的照片等于公开住址。拍摄参数对看图的人有用，\
                        坐标通常只会带来风险，所以两者分开处理：参数保留，坐标默认不给。

                        要原图原样分享时选「原图」就好，那条路径不做任何重编码，\
                        也不会动元数据。
                        """
                    )
                }
                .padding(NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("位置与隐私")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func helpBlock(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: NlSpacing.s8) {
            Text(title)
                .font(NlText.subtitle)
                .foregroundStyle(NlColor.textPrimary)
            Text(body)
                .font(NlText.caption)
                .foregroundStyle(NlColor.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(4)
        }
    }
}

// MARK: - 使用教程

struct TutorialSheet: View {
    @Environment(\.dismiss) private var dismiss

    private struct Step: Identifiable {
        let id = UUID()
        let index: String
        let title: String
        let body: String
    }

    private let steps: [Step] = [
        Step(index: "1", title: "让手机和相机连上同一个网络",
             body: """
             去「设备」页选一种连接方式：

             · **WiFi AP**：相机自己开热点，手机直连相机网络。最快，但手机暂时没有互联网。
             · **WiFi STA**：相机和手机连同一个路由器。能同时上网，但要求路由器不隔离客户端。
             · **USB**：iOS 端暂不可用（需要 MFi 认证与 ExternalAccessory 授权）。
             """),
        Step(index: "2", title: "首次连接要做主机注册",
             body: """
             相机第一次见到这台手机时，需要你按下机身上的确认键。\
             注册流程里有 8.5 秒的等待窗口 —— 那不是 App 卡住，是在等你按键。

             注册成功后相机会记住这台机器，以后不用再做。
             """),
        Step(index: "3", title: "看相册、下载、分享",
             body: """
             「相机照片」页直接读相机的存储卡。三列网格，长按进入多选，\
             可以连续滑动多选。

             下载是单文件断点续传：中断后重新下载会从断点接着走。\
             超过 2GB 的文件机身不支持从中间续传，会整份重下。
             """),
        Step(index: "4", title: "遥控与监看",
             body: """
             「拍摄」页可以改曝光三要素、白平衡、对焦模式，也能触发快门。\
             点取景区右上角的全屏按钮进入监看页：点画面对焦、双指缩放、\
             横屏时控件会自动隐去。

             尼康没有半按保持这种协议，所以"两段式快门"实际是\
             「AF 驱动 → 等 1.2 秒 → 释放」。B 门与间隔拍摄在快门右侧的菜单里。
             """)
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    ForEach(steps) { step in
                        HStack(alignment: .top, spacing: NlSpacing.s12) {
                            Text(step.index)
                                .font(NlText.caption.weight(.bold))
                                .foregroundStyle(NlColor.onPrimary)
                                .frame(width: 24, height: 24)
                                .background(Circle().fill(NlColor.primary))
                            VStack(alignment: .leading, spacing: NlSpacing.s8) {
                                Text(step.title)
                                    .font(NlText.subtitle)
                                    .foregroundStyle(NlColor.textPrimary)
                                Text(.init(step.body))
                                    .font(NlText.caption)
                                    .foregroundStyle(NlColor.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .lineSpacing(4)
                            }
                        }
                    }
                }
                .padding(NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("使用教程")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 常见问题

struct FAQSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var expanded: Set<Int> = []

    private struct Item: Identifiable {
        let id: Int
        let q: String
        let a: String
    }

    private let items: [Item] = [
        Item(id: 0, q: "连接一直转圈，相机没反应",
             a: """
             三种最常见原因，按可能性排序：

             1. **相机在休眠**。按一下机身的快门键把它唤醒，再重试。
             2. **上一次连接没释放**。尼康同一时刻只允许一个 PTP/IP 客户端；\
                失败连接残留约 10 秒，等一会儿再点，别连续快点（连点会延长恢复时间）。
             3. **相机被别的设备占着**。手机上的 SnapBridge 之类如果还连着，先断开。

             如果都排除了，去「设置 → 相机设置 → 连接诊断」看当前状态。
             """),
        Item(id: 1, q: "连上了但相册是空的",
             a: """
             先看「相机照片」页顶部的筛选芯片 —— 是不是筛到了某个空档（比如只选了「视频」）。

             如果确实没有筛选，可能是相机处于「连接到电脑」模式：该模式下固件会把\
             读取文件信息的标准命令全部拒绝。本 App 已经为这个模式实现了替代的\
             直读链路，但如果你仍看不到文件，请回到相机把 USB 选项改成「MTP/PTP」。
             """),
        Item(id: 2, q: "下载速度只有几百 KB/s",
             a: """
             尼康的 PTP/IP 命令通道是**全局串行**的：一张图在传的时候，别的请求都排队。\
             所以下载期间看缩略图会变慢是正常的，不是 bug。

             提速手段按效果排序：换成相机热点直连（AP）比走路由器快；\
             相机热点支持 5GHz 时在设置里打开「5GHz 频段优先」；\
             别在下载时同时开着监看（监看每秒要拉 15 帧，抢的是同一条通道）。
             """),
        Item(id: 3, q: "监看画面卡住/黑屏",
             a: """
             先看监看页上栏的状态胶囊：显示「异常」说明连续失败已达阈值。

             快门触发瞬间的短暂黑屏是正常现象（我们在快门窗口内主动丢弃黑帧）。\
             录像模式切换同理。若持续黑屏：确认相机拨盘在照片档（P/A/S/M），\
             并且没有停留在回放或菜单界面 —— 这种状态相机会拒绝启动实时取景。
             """),
        Item(id: 4, q: "为什么不能更改曝光模式（P/A/S/M）",
             a: """
             部分机身不允许通过 PTP 写 `0x500E`（曝光模式属性）。\
             我们写完会回读一次断言，读回来说没变就说明这台机器不支持。

             遇到这种情况 App 会明确告诉你「请用机身拨盘」，而不是假装改成功了。\
             其余参数（光圈、快门、ISO、白平衡、对焦模式、测光）通常仍可远程改。
             """),
        Item(id: 5, q: "「已标记」页一直是空的",
             a: """
             尼康的保护位（`0xDC03`）在多数连接方式下读不到，写入更是普遍不支持。

             本 App 不会假装标记成功：点「标记」会直接告诉你「需要相机上设置」。\
             「已标记」页只在相机确实回报了保护位时才有内容。
             """)
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(items) { item in
                        VStack(alignment: .leading, spacing: 0) {
                            Button {
                                withAnimation(.easeOut(duration: NlMotion.fast)) {
                                    if expanded.contains(item.id) { expanded.remove(item.id) }
                                    else { expanded.insert(item.id) }
                                }
                            } label: {
                                HStack(alignment: .top, spacing: NlSpacing.s12) {
                                    Text(item.q)
                                        .font(NlText.body)
                                        .foregroundStyle(NlColor.textPrimary)
                                        .multilineTextAlignment(.leading)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Image(systemName: expanded.contains(item.id) ? "minus" : "plus")
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(NlColor.textTertiary)
                                }
                                .frame(minHeight: 52)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)

                            if expanded.contains(item.id) {
                                Text(item.a)
                                    .font(NlText.caption)
                                    .foregroundStyle(NlColor.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .lineSpacing(4)
                                    .padding(.bottom, NlSpacing.s16)
                            }
                            NlDivider()
                        }
                    }
                }
                .padding(.horizontal, NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("常见问题")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 意见反馈

struct FeedbackSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var env: AppEnvironment
    @State private var text = ""
    @State private var includeDiagnostics = true

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    Text("反馈会带上当前连接与机型信息，方便定位。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)

                    TextEditor(text: $text)
                        .font(NlText.body)
                        .frame(minHeight: 160)
                        .padding(NlSpacing.s12)
                        .scrollContentBackground(.hidden)
                        .background(
                            RoundedRectangle(cornerRadius: NlRadius.card)
                                .fill(NlColor.surface)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: NlRadius.card)
                                .stroke(NlColor.outline, lineWidth: 1)
                        )

                    NlSettingToggleRow(
                        title: "附带诊断信息",
                        subtitle: "连接方式、相机型号、固件版本。不含照片与位置。",
                        isOn: $includeDiagnostics
                    )

                    NlOutlineButton(title: "复制反馈内容", height: 44) {
                        UIPasteboard.general.string = composed
                        env.showToast("已复制到剪贴板")
                    }

                    NlPrimaryButton(title: "在 GitHub 上提交") {
                        UIPasteboard.general.string = composed
                        if let url = URL(string: "https://github.com/") {
                            UIApplication.shared.open(url)
                        }
                        env.showToast("内容已复制，粘贴到 Issue 里即可")
                    }
                }
                .padding(NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("意见反馈")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private var composed: String {
        var lines = ["【反馈】", text]
        if includeDiagnostics {
            lines.append("")
            lines.append("--- 诊断 ---")
            lines.append("连接：\(env.client.isConnected ? "已连接" : "未连接")")
            lines.append("相机：\(env.client.state.deviceInfo?.displayModel ?? "未知")")
            lines.append("固件：\(env.parameters.cameraInfo.firmwareVersion.isEmpty ? "未知" : env.parameters.cameraInfo.firmwareVersion)")
        }
        return lines.joined(separator: "\n")
    }
}

// MARK: - 关于

struct AboutSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: NlSpacing.moduleGap) {
                    VStack(alignment: .leading, spacing: NlSpacing.s8) {
                        Text("N-Link")
                            .font(NlText.title)
                            .foregroundStyle(NlColor.textPrimary)
                        Text("尼康相机遥控与传输 · iOS 版")
                            .font(NlText.caption)
                            .foregroundStyle(NlColor.textSecondary)
                        Text(versionText)
                            .font(NlText.foot)
                            .foregroundStyle(NlColor.textTertiary)
                    }

                    Text("""
                    这是一个非官方的第三方客户端，通过相机公开的 PTP/IP 协议\
                    与尼康私有操作码工作。它与尼康公司没有任何关系。

                    App 不上传照片、不采集使用数据、没有埋点 SDK。\
                    唯一的外发请求是「快门次数云端解析」—— 且只在你明确同意后才会发生。
                    """)
                    .font(NlText.caption)
                    .foregroundStyle(NlColor.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .lineSpacing(4)

                    Text("N-Link 不下载、不刷写相机固件。")
                        .font(NlText.foot)
                        .foregroundStyle(NlColor.textTertiary)
                }
                .padding(NlSpacing.pageMargin)
            }
            .background(NlColor.background)
            .navigationTitle("关于应用")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private var versionText: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "版本 \(version)（\(build)）"
    }
}

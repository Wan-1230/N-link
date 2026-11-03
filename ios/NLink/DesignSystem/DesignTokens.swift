//
//  DesignTokens.swift
//  NLink
//
//  设计令牌 · 全工程唯一真相源
//
//  与 Android 端 `app/src/main/res/values/{colors,dimens,styles}.xml` 逐值对齐。
//  任何颜色、字号、圆角、间距、动效时长都必须从这里取，页面层禁止出现字面量。
//
//  设计纪律（继承 PRODUCT.md，不得自行发挥）：
//  - accent 只有尼康黄 #FFE100，全 App 只允许两处使用：①连接状态指示灯 ②主按钮填充。
//    黄色绝不可作浅底文字或细线（黄/浅底 1.28:1，不可读）。
//  - 双主题跟随系统；深色面板靠明度分层，不靠阴影。
//  - 字号只有 6 档；圆角只有 6 档；动效只有 4 档。
//  - 不打包第三方字体，用系统字体（iOS 与 Android 的 sans 家族差异见 docs/差异说明.md）。
//

import SwiftUI

// MARK: - 颜色

/// 语义色板。深浅两套值直接对应 Android 的 `values/colors.xml` 与 `values-night/colors.xml`。
///
/// 实现说明：Android 用 `@color/xxx` 在 XML 里按主题取色；iOS 无法在 `Color` 上挂动态值，
/// 因此统一用 `dynamic(light:dark:)` 构造 `UIColor(dynamicProvider:)`，
/// 再由 `.init(uiColor:)` 转成 `Color`。这样 `Color` 在颜色跟随系统切换时自动更新，
/// 调用点不需要知道当前是深色还是浅色。
enum NlColor {

    // MARK: 基础面

    /// 页面底色。浅 `#F7F7F6` / 深 `#171716`
    static let background = dynamic(0xF7F7F6, 0x171716)
    /// 卡片面。浅 `#FCFCFB` / 深 `#262625`
    static let surface = dynamic(0xFCFCFB, 0x262625)
    /// 次级面 / 面板底。浅 `#EFEFEE` / 深 `#373736`
    static let surfaceVariant = dynamic(0xEFEFEE, 0x373736)
    /// 描边。浅 10% 黑 / 深 12% 白
    static let outline = dynamic(0x1A000000, 0x1FFFFFFF)
    /// 分割线。浅 8% 黑 / 深 8% 白
    static let divider = dynamic(0x14000000, 0x14FFFFFF)

    // MARK: 反色卡片（设备卡、监看浮层之外的深色块）

    /// 浅色主题下的深色卡片 `#171716`，深色主题下反过来是 `#F7F7F6`
    static let darkCard = dynamic(0x171716, 0xF7F7F6)
    static let darkCardInner = dynamic(0x1F1F1E, 0xEFEFEE)
    static let darkCardSurface = dynamic(0x2A2A28, 0xE2E2E0)
    static let onDarkCard = dynamic(0xFCFCFB, 0x171716)
    static let onDarkCardVariant = dynamic(0x9D9D99, 0x4A4A47)

    // MARK: 文字

    /// 17.48:1
    static let textPrimary = dynamic(0x171716, 0xF7F7F6)
    /// 8.66:1
    static let textSecondary = dynamic(0x4A4A47, 0xCBCBC8)
    /// 6.54:1（深色取 TN300↔400 内插值，实测 5.73:1；TN400 仅 4.38:1 不达标）
    static let textTertiary = dynamic(0x5C5C59, 0xB4B4B0)

    // MARK: 主色（中性黑/白，不承载品牌色）

    static let primary = dynamic(0x171716, 0xF7F7F6)
    static let primaryVariant = dynamic(0x1F1F1E, 0xEFEFEE)
    static let onPrimary = dynamic(0xFCFCFB, 0x171716)
    static let accentSoft = dynamic(0xEFEFEE, 0x373736)
    static let secondary = dynamic(0x262625, 0xCBCBC8)
    static let secondaryVariant = dynamic(0x373736, 0x9D9D99)

    // MARK: 状态

    /// 未连接灰点 `#777773` / 深 `#9D9D99`
    static let statusDisconnected = dynamic(0x777773, 0x9D9D99)
    static let wifiIndicator = dynamic(0x171716, 0xF7F7F6)
    static let statusDotRing = dynamic(0x00000000, 0x171716)

    /// 尼康黄 · 品牌唯一彩色。**夜间同值**——两支黄会让品牌色漂移。
    static let nikonYellow = dynamic(0xFFE100, 0xFFE100)
    /// 黄底上的文字必须是近黑（黑字在黄上 16.02:1，白字只有 1.31:1）
    static let onNikonYellow = dynamic(0x171716, 0x171716)

    // MARK: 控件

    static let chipUnselected = dynamic(0xE2E2E0, 0x3E3E3C)
    static let focusRing = dynamic(0x5C5C59, 0xCBCBC8)
    static let switchTrackOff = dynamic(0x777773, 0x777773)
    static let switchThumbOff = dynamic(0xFCFCFB, 0xCBCBC8)

    // MARK: 按压/悬停（iOS 没有 hover，保留定义以便与 Android 对齐语义）

    static let stateHoverLight = dynamic(0x0A000000, 0x0A000000)
    static let stateActiveLight = dynamic(0x14000000, 0x14000000)
    static let stateHoverChip = dynamic(0x0A000000, 0x0AFFFFFF)
    static let stateActiveChip = dynamic(0x14000000, 0x14FFFFFF)
    static let stateHoverPrimary = dynamic(0x14FFFFFF, 0x0A000000)
    static let stateActivePrimary = dynamic(0x0AFFFFFF, 0x14000000)

    // MARK: 全屏监看（恒黑，刻意无夜间对应值——已登记豁免）

    static let liveViewScrim = Color(hex: 0x99000000)
    static let liveViewPageBackground = Color(hex: 0x000000)
    static let liveViewPanel = Color(hex: 0xF5141416)
    static let liveViewCard = Color(hex: 0xF21B1B1D)
    static let liveViewCardSolid = Color(hex: 0xF21B1B1D)
    static let liveViewChip = Color(hex: 0xF2252527)
    static let liveViewChipActive = Color(hex: 0x33FFFFFF)
    static let liveViewText = Color(hex: 0xFFFFFFFF)
    static let liveViewTextDim = Color(hex: 0x8CFFFFFF)
    static let liveViewOutline = Color(hex: 0x26FFFFFF)
    static let liveViewBadgeRed = Color(hex: 0x8A2B26)
    static let liveViewSegTrack = Color(hex: 0x1FFFFFFF)
    static let liveViewSegSelected = Color(hex: 0x33FFFFFF)
    /// 监看页黄色（与主色板同一个黄，夜间不变）
    static let liveViewAccent = Color(hex: 0xFFE100)
    static let liveViewAccentSoft = Color(hex: 0x26FFE100)
    static let liveViewOnSolid = Color(hex: 0x000000)

    // MARK: 相册网格

    static let photoScrimSelect = Color(hex: 0x33000000)
    static let photoBadge = Color(hex: 0xAA000000)
    static let photoName = Color(hex: 0xCC000000)
    static let photoTrack = Color(hex: 0x44FFFFFF)

    // MARK: Dock

    static let navBackground = dynamic(0xF7F7F6, 0x171716)
    static let navSelectedBackground = dynamic(0x14000000, 0x26FFFFFF)
    static let navActiveIcon = dynamic(0x171716, 0xF7F7F6)
    static let navInactiveIcon = dynamic(0x777773, 0x9D9D99)

    // MARK: Hero 插画（冷灰蓝，刻意不并入中性 ramp）

    static let heroBodyTop = dynamic(0x4C525B, 0x7A808A)
    static let heroBodyMid = dynamic(0x33383F, 0x565C66)
    static let heroBodyBottom = dynamic(0x1E2226, 0x3A3F46)
    static let heroEdge = dynamic(0x7C828C, 0xA8AEB8)
    static let heroLensRim = dynamic(0x5A606A, 0x868C96)
    static let heroLensBody = dynamic(0x24282D, 0x454B53)
    static let heroGlassOuter = dynamic(0x0D1013, 0x15191D)
    static let heroGlassInner = dynamic(0x3B444F, 0x58626F)
    static let heroDetail = dynamic(0x6E747E, 0x9AA0AA)
    static let heroShadow = dynamic(0x1F000000, 0x24FFFFFF)

    // MARK: - 构造工具

    /// 构造随系统主题变化的颜色。参数是 0xRRGGBB 或 0xAARRGGBB。
    ///
    /// 说明：Android 的 `#1A000000` 是「ARGB 8 位十六进制」；这里用同一个字面量，
    /// 通过 `Color(hex:)` 的分支判断（> 0xFFFFFF 视为带 alpha）解析，
    /// 保证两端写的是同一串数字，review 时能逐值对上。
    static func dynamic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}

extension Color {
    /// 解析 0xRRGGBB / 0xAARRGGBB。
    init(hex: UInt32) {
        self.init(uiColor: UIColor(hex: hex))
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        let hasAlpha = hex > 0xFFFFFF
        let a = hasAlpha ? CGFloat((hex >> 24) & 0xFF) / 255.0 : 1.0
        let r = CGFloat((hex >> 16) & 0xFF) / 255.0
        let g = CGFloat((hex >> 8) & 0xFF) / 255.0
        let b = CGFloat(hex & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b, alpha: a)
    }
}

// MARK: - 字号

/// 字号阶梯 · 全 App 只允许这 6 档。
///
/// Android 用 sp（24/16/14/13/12/11），iOS 用 pt。
/// 关键差异：Android 的 sp 会跟随系统「字体大小」缩放，iOS 默认不跟随。
/// 这里统一用 `Font.system(size:weight:)`（固定 pt，不缩放），
/// 理由与取舍写在 docs/差异说明.md「字体与动态字体」一节。
enum NlFontSize {
    static let display: CGFloat = 24
    static let title: CGFloat = 16
    static let body: CGFloat = 14
    static let caption: CGFloat = 13
    static let nav: CGFloat = 12
    static let foot: CGFloat = 11
}

/// 文本样式（对应 Android `NlText.*`）。
enum NlText {
    /// 页面主标题：24 / bold / 单行截断
    static let title = Font.system(size: NlFontSize.display, weight: .bold)
    /// 区块标题：16 / bold
    static let sectionTitle = Font.system(size: NlFontSize.title, weight: .bold)
    /// 小节副标题：14 / bold
    static let subtitle = Font.system(size: NlFontSize.body, weight: .bold)
    /// 组标题：13 / bold
    static let groupTitle = Font.system(size: NlFontSize.caption, weight: .bold)
    /// 正文：14 / regular
    static let body = Font.system(size: NlFontSize.body, weight: .regular)
    /// 说明文字：13 / regular
    static let caption = Font.system(size: NlFontSize.caption, weight: .regular)
    /// Dock 文字：12 / regular
    static let nav = Font.system(size: NlFontSize.nav, weight: .regular)
    /// 最小一级说明：11 / regular
    static let foot = Font.system(size: NlFontSize.foot, weight: .regular)

    /// 曝光参数专用：等宽数字。
    ///
    /// 这是全 App 唯一的字体例外，功能理由而非风格理由：
    /// 五个参数格并排时，比例数字（1/125 f/2.8 2.5s …）会让各格基线抖动；
    /// 等宽数字让竖线对齐。Android 侧用 `fontFeatureSettings="tnum"`，
    /// iOS 对应 `Font.monospacedDigit()`。
    static let monoDigits = Font.system(size: NlFontSize.caption, weight: .semibold).monospacedDigit()

    /// 指定字号的等宽数字。
    ///
    /// 只给曝光参数用。默认档是 13（caption），但取景区五格用的是 14（body）——
    /// 那五格在竖屏下是页面的信息主体，降到 13 会让 `1/1250` 这种六字符值挤在一起。
    static func monoDigits(size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        Font.system(size: size, weight: weight).monospacedDigit()
    }
}

// MARK: - 圆角

/// 圆角阶梯 · 全工程唯一真相源。
///
/// 按「越靠近用户越圆」排：控件 8 / 卡片 12 / 浮层 16 / 抽屉 20 / 对话框 28 / 胶囊 999。
/// Android 的 dp 与 iOS 的 pt 在视觉上不等价（Android 2.75x 密度下 dp 更「小」），
/// 但两端都基于 8pt 栅格设计，这里按 1:1 沿用原值，不做换算——
/// 理由：两端同机型宽度下卡片圆角的视觉比例一致，放大反而破坏「工具类 App 的克制」。
enum NlRadius {
    static let control: CGFloat = 8
    static let card: CGFloat = 12
    static let overlay: CGFloat = 16
    static let sheet: CGFloat = 20
    static let dialog: CGFloat = 28
    static let pill: CGFloat = 999
}

// MARK: - 间距

/// 页面级语义间距（对应 Android `page_margin` / `module_gap*`）。
enum NlSpacing {
    /// 页面左右留白 24
    static let pageMargin: CGFloat = 24
    /// 模块间距（大）24
    static let moduleGapLarge: CGFloat = 24
    /// 模块间距 16
    static let moduleGap: CGFloat = 16
    /// 组间距 12
    static let groupGap: CGFloat = 12
    /// 组内间距 8
    static let groupGapSmall: CGFloat = 8
    /// 分割线厚度 1
    static let dividerHeight: CGFloat = 1

    /// 中性间距标尺 4 / 8 / 12 / 16 / 20 / 24
    static let s4: CGFloat = 4
    static let s8: CGFloat = 8
    static let s12: CGFloat = 12
    static let s16: CGFloat = 16
    static let s20: CGFloat = 20
    static let s24: CGFloat = 24

    /// 网格格间距 4。
    ///
    /// 原先只定义在 `NlLiveViewSize` 里（监看工具行用它），但相册网格与监看网格
    /// 用的是同一个值、同一个语义（「相邻两格之间那条缝」）。提到通用间距层后
    /// 两处都从这里读——否则相册页要引用一个名字里带 `LiveView` 的命名空间，
    /// 读起来像在借别人的东西。`NlLiveViewSize.cellGap` 保留为别名。
    static let cellGap: CGFloat = 4
}

// MARK: - 尺寸

/// 组件尺寸（对应 Android `dimens.xml` 的具体条目）。
enum NlSize {
    /// 顶栏高 56
    static let topBarHeight: CGFloat = 56
    /// 顶栏图标按钮 44（触控下限；图标仍是 18）
    static let iconButton: CGFloat = 44
    /// 图标本体 18
    static let iconGlyph: CGFloat = 18
    /// 空态图标容器 72
    static let emptyIconBox: CGFloat = 72
    /// 空态动作按钮高 40
    static let emptyActionHeight: CGFloat = 40
    /// 状态条动作按钮高 36
    static let stateBarActionHeight: CGFloat = 36
    /// Dock 基线高（关玻璃时）60
    static let dockBaselineHeight: CGFloat = 60
    /// 触控最小热区 44
    static let hitTarget: CGFloat = 44
}

/// 全屏监看尺寸族（对应 `lv_*` 与 `remote_lv_*`）。
enum NlLiveViewSize {
    static let barHPadding: CGFloat = 12
    static let barVPadding: CGFloat = 8
    /// 别名：格间距已提升到通用层 NlSpacing.cellGap，这里保留名字避免改十几处调用点。
    static let cellGap: CGFloat = NlSpacing.cellGap
    /// 工具行高 58（横屏 50）
    static let toolHeight: CGFloat = 58
    static let toolHeightLandscape: CGFloat = 50
    /// 快门 72 / 内圈 54
    static let shutter: CGFloat = 72
    static let shutterInner: CGFloat = 54
    /// 顶栏图标键 44（36→44，单手举起场景的触控下限）
    static let iconButton: CGFloat = 44
    /// 直方图 128 × 56
    static let graphWidth: CGFloat = 128
    static let graphHeight: CGFloat = 56
    static let cardPadding: CGFloat = 10
    static let sheetPaddingH: CGFloat = 18
    static let sheetPaddingTop: CGFloat = 14
    static let sheetPaddingBottom: CGFloat = 18
    static let paramPaddingV: CGFloat = 6
    static let paramCellPaddingV: CGFloat = 6
    static let paramCellMargin: CGFloat = 3
    static let shutterRowPaddingTop: CGFloat = 6
    static let shutterRowPaddingBottom: CGFloat = 12
    static let toolPaddingTop: CGFloat = 6
    static let toolPaddingBottom: CGFloat = 4
    static let toolIconBoxHeight: CGFloat = 28
    static let toolIconHeight: CGFloat = 25

    /// 拍摄页取景区左右留白 8（不是 page_margin 24——那会把画面挤窄 9.8%）
    static let remoteMargin: CGFloat = 8
    /// 取景区下限 272：只兜「还没帧」的空态
    static let remoteMinHeight: CGFloat = 272
    /// 取景区上限 380：兜 1:1 / 竖幅等非常规比例
    static let remoteMaxHeight: CGFloat = 380
    /// 直方图预留 66 = 条 56 + 下边距 10
    static let remoteHistogramReserve: CGFloat = 66
}

// MARK: - 动效

/// 动效令牌 · 全 App 只允许 4 档（对应 Android `Motion`）。
///
/// 归档口径（与 Android 一致，改时长必须同步改这里和文档）：
/// ≤90 → instant（闪光/焦点框）· ≤160 → fast（按压缩放/勾选/小控件淡入）
/// ≤260 → base（常规淡入淡出/缩放复位）· 其余 → slow（胶囊滑动/页面级位移）
enum NlMotion {
    /// 80ms
    static let instant: Double = 0.080
    /// 150ms
    static let fast: Double = 0.150
    /// 220ms
    static let base: Double = 0.220
    /// 380ms
    static let slow: Double = 0.380

    static var instantAnim: Animation { .easeOut(duration: instant) }
    static var fastAnim: Animation { .easeOut(duration: fast) }
    static var baseAnim: Animation { .easeOut(duration: base) }
    static var slowAnim: Animation { .easeInOut(duration: slow) }

    /// 按压缩放（对应 Android `PressEffect.SCALE = 0.95`）
    static let pressScale: CGFloat = 0.95

    /// 展开时列表项错峰 90ms（自上而下）
    static let staggerExpand: Double = 0.090
    /// 收起时列表项错峰 40ms（**自下而上**——展开要看得清，收起要利落）
    static let staggerCollapse: Double = 0.040
    /// 入场错峰 40ms
    static let staggerEnter: Double = 0.040
    /// 入场重播抑制 1.5s
    static let replaySuppress: Double = 1.5
}

// MARK: - 减弱动效

/// 动效开关（对应 Android `UiFlags`）。
///
/// Android 侧是用户在设置里手动关动画；iOS 侧接系统「减弱动态效果」
/// （`UIAccessibility.isReduceMotionEnabled`），不另设开关——
/// 系统开关是用户对整机的统一意图，App 里再给一个只会制造两套状态。
@MainActor
final class MotionSettings: ObservableObject {
    static let shared = MotionSettings()

    @Published private(set) var motionEnabled: Bool
    @Published private(set) var pressMotionEnabled: Bool

    private init() {
        let reduce = UIAccessibility.isReduceMotionEnabled
        motionEnabled = !reduce
        pressMotionEnabled = !reduce
    }

    func refresh() {
        let reduce = UIAccessibility.isReduceMotionEnabled
        motionEnabled = !reduce
        pressMotionEnabled = !reduce
    }
}

// MARK: - 主题环境

/// 由系统深色模式驱动，页面不需要自己判断。
///
/// Android 侧用 `Theme.Material3.DayNight` 让系统切换；iOS 侧由
/// `UIColor(dynamicProvider:)` + SwiftUI 环境的 `colorScheme` 自动完成，
/// 因此这里只提供读取入口，不提供切换开关。
enum NlTheme {
    static func schemeName(_ scheme: ColorScheme) -> String {
        scheme == .dark ? "深色" : "浅色"
    }
}

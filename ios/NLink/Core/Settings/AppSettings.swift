//
//  AppSettings.swift
//  N-Link
//
//  设置项单一真相源。键名与默认值逐条对齐安卓 `shared/common/AppSettings.kt`
//  （SharedPreferences `nl_settings`）。iOS 侧换成 UserDefaults，语义等价：
//  都是「进程外持久的小键值」。
//
//  为什么不直接用 @AppStorage：@AppStorage 绑定在 View 上，而这里有一半
//  读取发生在非视图层（传输队列、遥控管理器、参数轮询）。用一个
//  ObservableObject 包住 UserDefaults，读写路径只有一条。
//

import Foundation
import Combine

@MainActor
final class AppSettings: ObservableObject {

    static let shared = AppSettings()

    // MARK: 常量（与安卓逐字一致）

    /// 下载画质
    ///
    /// 这一族枚举都是「`rawValue` 就是显示名」，但仍单独给 `title`：
    /// 设置行与选择器一律读 `title`，将来某一条要换成更长的说明时，
    /// 只改这一处，不用去追所有 `rawValue` 的读取点。
    enum DownloadQuality: String, CaseIterable, Identifiable {
        case original = "原图"
        case compressed = "压缩"
        var id: String { rawValue }
        var title: String { rawValue }
        /// 压缩模式只对 JPG 重编码，RAW 原样存
        var detail: String { self == .original ? "按机身原始文件保存" : "JPG 重新编码，RAW 不受影响" }
    }

    /// 保存位置。iOS 只有「相册」一个落点，另一个是「文件 App → N-Link」
    enum SaveLocation: String, CaseIterable, Identifiable {
        case photos = "系统相册"
        case files = "文件 App（N-Link）"
        var id: String { rawValue }
        var title: String { rawValue }
    }

    /// 连接偏好（安卓默认 USB 优先；iOS 无 USB，见差异文档）
    enum ConnectionPreference: String, CaseIterable, Identifiable {
        case usb = "USB 优先"
        case wifi = "WiFi 优先"
        var id: String { rawValue }
        var title: String { rawValue }
    }

    enum RawPairMode: String, CaseIterable, Identifiable {
        case both = "成对下载"
        case rawOnly = "只下 RAW"
        case jpegOnly = "只下 JPG"
        var id: String { rawValue }
        var title: String { rawValue }
    }

    enum RemoteActionMode: String, CaseIterable, Identifiable {
        case interval = "interval"
        case bulb = "bulb"
        var id: String { rawValue }
        var title: String { self == .interval ? "间隔拍摄" : "B 门" }
    }

    enum DisplayMode: String, CaseIterable, Identifiable {
        case linked = "linked"
        case remote = "remote"
        var id: String { rawValue }
        var title: String { self == .linked ? "联动" : "遥控" }
        /// 联动：手机与机身共用一套曝光参数；遥控：手机接管机身控制
        var detail: String {
            self == .linked
                ? "手机改参数会写回机身，机身改参数手机同步"
                : "手机接管机身控制，适合 B 门与长曝光"
        }
    }

    enum StaSubMode: String, CaseIterable, Identifiable {
        case sameWifi = "same_wifi"
        case phoneHotspot = "phone_hotspot"
        var id: String { rawValue }
        var title: String { self == .sameWifi ? "同一 Wi-Fi" : "手机热点" }
    }

    /// 深浅色三选。安卓只有「跟随系统」（DayNight），这里是三选是为了
    /// 让「夜里举着手机拍」能锁死深色，不用去系统设置里改整机。
    enum AppearanceMode: String, CaseIterable, Identifiable {
        case system = "system"
        case light = "light"
        case dark = "dark"

        var id: String { rawValue }

        var title: String {
            switch self {
            case .system: return "跟随系统"
            case .light: return "浅色"
            case .dark: return "深色"
            }
        }
    }

    // MARK: 键名

    private enum Key {
        static let quality = "quality"
        static let savePath = "save_path"
        static let connectionPreference = "conn_pref"
        static let preferWifi5GHz = "wifi_band_5g_prefer"
        static let backgroundRunPromptShown = "background_run_prompt_shown"
        static let histogram = "histogram_enabled"
        static let pseudoColor = "pseudo_color_enabled"
        static let waveform = "waveform_enabled"
        static let zebra = "zebra_enabled"
        static let peaking = "peaking_enabled"
        static let safeArea = "safe_area_enabled"
        // iOS 新增的监看辅助三项（安卓用 UiFlags 内存态管理，iOS 落成持久键：
        // 举着手机调一次取景辅助，退出再进不该又回到全关）
        static let assistGrid = "assist_grid_enabled"
        static let assistLevel = "assist_level_enabled"
        static let assistClean = "assist_clean_enabled"
        static let liveViewRotated = "liveview_rotated"
        static let appearanceMode = "appearance_mode"
        static let rawPairMode = "raw_pair_mode"
        static let autoDownload = "auto_download"
        static let albumSortDimension = "album_sort_dimension"
        static let albumSortDirection = "album_sort_direction"
        static let markAutoDownload = "mark_auto_download"
        static let shareKeepGps = "share_keep_gps"
        static let cameraLocation = "camera_location_enabled"
        static let remoteActionMode = "remote_action_mode"
        static let bulbDuration = "bulb_duration_seconds"
        static let shutterCloudConsent = "shutter_cloud_consent"
        static let remoteDisplayMode = "remote_display_mode"
        static let staSubMode = "sta_sub_mode"
        static let staArchitecture = "sta_arch"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - 传输

    var downloadQuality: DownloadQuality {
        get {
            DownloadQuality(rawValue: defaults.string(forKey: Key.quality) ?? "")
                ?? .original
        }
        set { set(newValue.rawValue, Key.quality) }
    }

    var saveLocation: SaveLocation {
        get {
            SaveLocation(rawValue: defaults.string(forKey: Key.savePath) ?? "") ?? .photos
        }
        set { set(newValue.rawValue, Key.savePath) }
    }

    var rawPairMode: RawPairMode {
        get { RawPairMode(rawValue: defaults.string(forKey: Key.rawPairMode) ?? "") ?? .both }
        set { set(newValue.rawValue, Key.rawPairMode) }
    }

    /// 相机拍摄新照片后自动同步（需连接就绪）
    var autoDownload: Bool {
        get { defaults.bool(forKey: Key.autoDownload) }
        set { set(newValue, Key.autoDownload) }
    }

    /// 相册内标记照片后立即下载原图（默认关：标记只做清单）
    var markAutoDownload: Bool {
        get { defaults.bool(forKey: Key.markAutoDownload) }
        set { set(newValue, Key.markAutoDownload) }
    }

    var albumSortDimension: String? {
        get { defaults.string(forKey: Key.albumSortDimension) }
        set { set(newValue, Key.albumSortDimension) }
    }

    var albumSortDirection: String? {
        get { defaults.string(forKey: Key.albumSortDirection) }
        set { set(newValue, Key.albumSortDirection) }
    }

    /// 相册排序的读写入口。
    ///
    /// 底层仍是安卓那两个字符串键，因为排序是「用户上次怎么排的」，换端后
    /// 不该突然回到默认——同一账号同一台相机的人会立刻觉得「它忘了我设的」。
    /// 存的仍是原始字符串而不是 Codable：以后加维度时旧值还能被 `fromPersistence` 认出。
    var albumSort: AlbumSort {
        get {
            AlbumSort.fromPersistence(
                dimension: albumSortDimension,
                direction: albumSortDirection
            )
        }
        set {
            albumSortDimension = newValue.dimension.rawValue
            albumSortDirection = newValue.direction.rawValue
        }
    }

    // MARK: - 连接

    /// 安卓默认「USB 优先」。iOS 没有 USB 通道，默认给 WiFi，
    /// 但把枚举保留下来，避免设置页少一行（见差异文档 §3.2）。
    var connectionPreference: ConnectionPreference {
        get {
            ConnectionPreference(rawValue: defaults.string(forKey: Key.connectionPreference) ?? "")
                ?? .wifi
        }
        set { set(newValue.rawValue, Key.connectionPreference) }
    }

    /// 相机 AP 双频时优先连 5GHz，失败自动回落 2.4GHz
    var preferWifi5GHz: Bool {
        get { defaults.bool(forKey: Key.preferWifi5GHz) }
        set { set(newValue, Key.preferWifi5GHz) }
    }

    var remoteDisplayMode: DisplayMode {
        get { DisplayMode(rawValue: defaults.string(forKey: Key.remoteDisplayMode) ?? "") ?? .linked }
        set { set(newValue.rawValue, Key.remoteDisplayMode) }
    }

    var staSubMode: StaSubMode {
        get { StaSubMode(rawValue: defaults.string(forKey: Key.staSubMode) ?? "") ?? .sameWifi }
        set { set(newValue.rawValue, Key.staSubMode) }
    }

    /// 安卓有 PTP / FTP 两种 STA 架构。iOS 首版只做 PTP，键保留以便将来扩展。
    var staArchitecture: String {
        get { defaults.string(forKey: Key.staArchitecture) ?? "ptp" }
        set { set(newValue, Key.staArchitecture) }
    }

    // MARK: - 监看辅助（默认全关：进阶工具，默认铺开会挡取景）

    var histogramEnabled: Bool {
        get { bool(Key.histogram) }
        set { set(newValue, Key.histogram) }
    }

    var pseudoColorEnabled: Bool {
        get { bool(Key.pseudoColor) }
        set { set(newValue, Key.pseudoColor) }
    }

    var waveformEnabled: Bool {
        get { bool(Key.waveform) }
        set { set(newValue, Key.waveform) }
    }

    var zebraEnabled: Bool {
        get { bool(Key.zebra) }
        set { set(newValue, Key.zebra) }
    }

    var peakingEnabled: Bool {
        get { bool(Key.peaking) }
        set { set(newValue, Key.peaking) }
    }

    var safeAreaEnabled: Bool {
        get { bool(Key.safeArea) }
        set { set(newValue, Key.safeArea) }
    }

    /// 构图网格（监看页的独立开关，与 `liveView.gridOverlay` 的三分/对角/黄金比档位并存：
    /// 后者管画哪种网格，这里管开不开）。安卓对应 `UiFlags` 的网格项，iOS 落成持久键。
    var assistGridEnabled: Bool {
        get { bool(Key.assistGrid) }
        set { set(newValue, Key.assistGrid) }
    }

    /// 电子水平仪
    var assistLevelEnabled: Bool {
        get { bool(Key.assistLevel) }
        set { set(newValue, Key.assistLevel) }
    }

    /// DISP 纯净模式：隐藏全部覆盖层，只留画面
    var assistCleanEnabled: Bool {
        get { bool(Key.assistClean) }
        set { set(newValue, Key.assistClean) }
    }

    /// 监看画面旋转（对应安卓 LiveView 的 `rotate_lv`）
    var liveViewRotated: Bool {
        get { bool(Key.liveViewRotated) }
        set { set(newValue, Key.liveViewRotated) }
    }

    /// 是否有任意一项显示辅助开着（决定监看页上栏右侧图标）
    var anyAssistEnabled: Bool {
        assistGridEnabled || assistLevelEnabled || histogramEnabled || pseudoColorEnabled
            || waveformEnabled || assistCleanEnabled || zebraEnabled || peakingEnabled || safeAreaEnabled
    }

    // MARK: - 隐私

    /// 分享预览副本保留 GPS（默认关：剥离位置，只留拍摄参数）
    var shareKeepGps: Bool {
        get { defaults.bool(forKey: Key.shareKeepGps) }
        set { set(newValue, Key.shareKeepGps) }
    }

    /// 向相机发送手机位置（默认关）。写进相机，影响此后拍摄的照片；
    /// 与 shareKeepGps 是两条互不相干的链路。
    var cameraLocationEnabled: Bool {
        get { defaults.bool(forKey: Key.cameraLocation) }
        set { set(newValue, Key.cameraLocation) }
    }

    /// 快门次数云端解析授权（默认关）
    var shutterCloudConsent: Bool {
        get { defaults.bool(forKey: Key.shutterCloudConsent) }
        set { set(newValue, Key.shutterCloudConsent) }
    }

    // MARK: - 拍摄

    var remoteActionMode: RemoteActionMode {
        get { RemoteActionMode(rawValue: defaults.string(forKey: Key.remoteActionMode) ?? "") ?? .interval }
        set { set(newValue.rawValue, Key.remoteActionMode) }
    }

    /// B 门默认曝光时长（秒），记住上次选择
    var bulbDurationSeconds: Int {
        get {
            let v = defaults.integer(forKey: Key.bulbDuration)
            return v > 0 ? v : 30
        }
        set { set(newValue, Key.bulbDuration) }
    }

    /// 首次启动的后台提示是否问过（iOS 无对应概念，保留键以免迁移丢失）
    var backgroundRunPromptShown: Bool {
        get { defaults.bool(forKey: Key.backgroundRunPromptShown) }
        set { set(newValue, Key.backgroundRunPromptShown) }
    }

    // MARK: - 外观

    /// 深浅色模式。返回 nil 表示跟随系统（安卓 DayNight 的等价物）。
    ///
    /// iOS 的 `preferredColorScheme(nil)` 就是跟随系统，所以这里存三态而不是两个 bool。
    var appearanceMode: AppearanceMode {
        get { AppearanceMode(rawValue: defaults.string(forKey: Key.appearanceMode) ?? "") ?? .system }
        set { set(newValue.rawValue, Key.appearanceMode) }
    }

    // MARK: - 写入辅助

    private func bool(_ key: String) -> Bool { defaults.bool(forKey: key) }

    /// 写值并广播：设置页改任何一项都要让监看/相册立刻重算。
    private func set<T>(_ value: T, _ key: String) {
        defaults.set(value, forKey: key)
        objectWillChange.send()
    }
}

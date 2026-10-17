package com.nikonlink.app.settings

import com.nikonlink.app.shared.ui.ControlStates
import com.nikonlink.app.shared.ui.NlFeedback
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.MainActivity
import com.nikonlink.app.shared.ui.glass.DepthLift
import com.nikonlink.app.shared.ui.glass.DockInset
import com.nikonlink.app.shared.ui.glass.GlassChrome
import com.nikonlink.app.shared.ui.glass.GlassInsetAware
import com.nikonlink.app.shared.ui.glass.GlassTopBar
import com.nikonlink.app.shared.ui.glass.NlGlass
import com.nikonlink.app.shared.ui.glass.UiFlags
import com.nikonlink.app.BuildConfig
import com.nikonlink.app.R
import com.nikonlink.app.databinding.FragmentSettingsBinding
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.common.AppSettings
import com.nikonlink.app.shared.ui.pressEffect
import com.nikonlink.app.shared.update.UpdateChecker
import com.nikonlink.app.shared.update.UpdatePrompt
import com.nikonlink.app.shared.update.UpdateResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Tab4 设置与更多（分组列表式布局 · 黑白开关）
 * 传输设置 / 相机设置 / 通用设置 / 帮助与反馈
 *
 * 功能整改: 移除无实际逻辑的入口（账号/固件更新/语言/RAW处理/GPS同步），
 * 落地画质/保存路径/连接偏好/5GHz优先/自动下载设置项（AppSettings 读写一体），
 * 意见反馈改为系统邮件意图，通用设置新增「导出日志」（AppEventLogger 链路日志）、
 * 「检查更新」（UpdateChecker 查 GitHub Releases + 失败降级，不做 APK 自下载安装）、
 * 「夸克网盘下载」（与 GitHub 并列的国内直连下载入口，PRD 夸克网盘更新通道）。
 */
@AndroidEntryPoint
class SettingsFragment : Fragment(), GlassInsetAware {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    /** dock 悬浮进内容区后，设置列表底部要自己让出一条 */
    private var dockInset: DockInset? = null

    /** 顶栏玻璃随滚动浮现 */
    private var topBarFx: GlassTopBar? = null

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var eventLogger: AppEventLogger

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var connFlags: com.nikonlink.app.device.connect.ConnFlags

    @Inject
    lateinit var preflight: com.nikonlink.app.device.connect.PreflightGate

    @Inject
    lateinit var connFunnel: com.nikonlink.app.device.connect.ConnFunnel

    @Inject
    lateinit var baselineMetrics: com.nikonlink.app.shared.metrics.BaselineMetrics

    /** 检查更新防抖时间戳：1.5s 内重复点击忽略（PRD S3 / AC-5） */
    private var lastUpdateClickAt = 0L

    /** 请求进行中：避免防抖窗口过后又叠加请求 */
    private var checkingUpdate = false

    private val logExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val packed = eventLogger.packLogsForExport() ?: return@registerForActivityResult
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                // 顺序固定：声明 → 基线 → 原始日志。声明必须在最前面，用户自己打开也能先看到边界
                out.write(
                    com.nikonlink.app.shared.privacy.DiagnosticsDisclosure
                        .text(settings.dataHandoffs()).toByteArray()
                )
                out.write(baselineMetrics.render().toByteArray())
                packed.inputStream().use { it.copyTo(out) }
            }
            NlGlass.dialog(requireContext())
                .setTitle("导出成功")
                .setMessage("导出内容说明、指标基线与日志已保存；文件开头写明了这份东西里有哪些字段。")
                .setPositiveButton("确定", null)
                .show()
        }.onFailure { e ->
            Timber.w(e, "Export log failed")
            NlGlass.dialog(requireContext())
                .setTitle("导出失败")
                .setMessage("日志导出失败：${e.message}")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDockFloat()
        restoreState()
        setupRows()
    }

    private fun restoreState() {
        binding.switchAutoDownload.isChecked = settings.autoDownload
        binding.switchWifi5G.isChecked = settings.preferWifi5GHz
        // v2.2 回退总闸：关闭后所有新连接逻辑回到 v2.1.1 行为
        binding.switchConn22.isChecked = connFlags.masterEnabled()
        // v2.3 玻璃回退总闸 + 可访问性开关（PRD §9.4）
        binding.switchGlass.isChecked = UiFlags.glassEnabled(requireContext())
        binding.switchReduceTransparency.isChecked = UiFlags.reduceTransparency(requireContext())
        binding.switchMarkAutoDownload.isChecked = settings.markAutoDownload
        binding.switchShareKeepGps.isChecked = settings.shareKeepGps
        binding.tvQualityValue.text = settings.downloadQuality
        binding.tvSavePathValue.text = settings.savePath
        binding.tvConnPrefValue.text = settings.connectionPreference
        binding.tvThemeValue.text = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            AppCompatDelegate.MODE_NIGHT_YES -> "深色"
            else -> "跟随系统"
        }
        binding.tvCacheValue.text = formatCacheSize(requireContext())
        binding.tvUpdateValue.text = BuildConfig.VERSION_NAME
        binding.tvUpdateValue.setTextColor(resolveColor(R.color.text_tertiary))
        restoreQuarkRow()
        restoreBaiduRow()
    }

    /** 夸克网盘入口副文案（PRD 夸克网盘更新通道 §4.2）：优先展示缓存版本，无缓存给引导提示 */
    private fun restoreQuarkRow() {
        val cached = updateChecker.cachedQuarkLink()
        binding.tvQuarkValue.text = when {
            cached == null -> "暂无网盘链接"
            cached.versionLabel != null -> "可下载 ${cached.versionLabel}"
            else -> "可下载"
        }
        binding.tvQuarkValue.setTextColor(resolveColor(R.color.text_tertiary))
    }

    /** 百度网盘入口副文案（与夸克入口同构）：优先展示缓存版本，无缓存给引导提示 */
    private fun restoreBaiduRow() {
        val cached = updateChecker.cachedBaiduShare()
        binding.tvBaiduValue.text = when {
            cached == null -> "暂无网盘链接"
            cached.second != null -> "可下载 ${cached.second}"
            else -> "可下载"
        }
        binding.tvBaiduValue.setTextColor(resolveColor(R.color.text_tertiary))
    }

    private fun setupRows() {
        // 传输设置
        binding.rowQuality.pressEffect()
        binding.rowQuality.setOnClickListener {
            singleChoice(
                "下载画质",
                arrayOf(AppSettings.QUALITY_ORIGINAL, AppSettings.QUALITY_COMPRESSED),
                settings.downloadQuality
            ) {
                settings.downloadQuality = it
                binding.tvQualityValue.text = it
            }
        }

        binding.rowSavePath.pressEffect()
        binding.rowSavePath.setOnClickListener {
            singleChoice(
                "默认保存路径",
                arrayOf(AppSettings.SAVE_PATH_DCIM, AppSettings.SAVE_PATH_DOWNLOAD),
                settings.savePath
            ) {
                settings.savePath = it
                binding.tvSavePathValue.text = it
            }
        }

        // RAW+JPEG 下载方式（FR-17）：闸门开着、相册真的会合并，这一项才有可作用的对象。
        // 闸门关着时整行不出现——摆一个不生效的选择只会让人以为它管着点什么。
        binding.tvRawPairValue.text = settings.rawPairDownloadMode
        if (UiFlags.rawPairEnabled(requireContext())) {
            binding.dividerRawPair.visibility = View.VISIBLE
            binding.rowRawPair.visibility = View.VISIBLE
            binding.rowRawPair.pressEffect()
            binding.rowRawPair.setOnClickListener {
                singleChoice(
                    "RAW+JPEG 下载方式",
                    arrayOf(
                        AppSettings.RAW_PAIR_MODE_BOTH,
                        AppSettings.RAW_PAIR_MODE_RAW,
                        AppSettings.RAW_PAIR_MODE_JPEG
                    ),
                    settings.rawPairDownloadMode
                ) {
                    settings.rawPairDownloadMode = it
                    binding.tvRawPairValue.text = it
                    eventLogger.event("setting", "key" to "raw_pair_mode", "value" to it)
                }
            }
        }

        binding.switchAutoDownload.setOnCheckedChangeListener { _, checked ->
            settings.autoDownload = checked
            eventLogger.event("setting", "key" to "auto_download", "value" to checked)
        }

        // F1 可选增强：标记后自动入队下载原图（默认关）
        binding.switchMarkAutoDownload.setOnCheckedChangeListener { _, checked ->
            settings.markAutoDownload = checked
            eventLogger.event("setting", "key" to "mark_auto_download", "value" to checked)
        }

        // F4：分享副本默认剥离 GPS，开启后按原样保留
        binding.switchShareKeepGps.setOnCheckedChangeListener { _, checked ->
            settings.shareKeepGps = checked
            eventLogger.event("setting", "key" to "share_keep_gps", "value" to checked)
        }

        // 相机设置
        binding.rowConnPref.pressEffect()
        binding.rowConnPref.setOnClickListener {
            singleChoice(
                "连接偏好",
                arrayOf(AppSettings.CONN_PREF_USB, AppSettings.CONN_PREF_WIFI),
                settings.connectionPreference
            ) {
                settings.connectionPreference = it
                binding.tvConnPrefValue.text = it
            }
        }

        // 5GHz 优先：API≥30 生效；相机 AP 不支持 5GHz 时自动回退 2.4GHz
        binding.switchWifi5G.setOnCheckedChangeListener { _, checked ->
            settings.preferWifi5GHz = checked
            eventLogger.event("setting", "key" to "wifi_band_5g_prefer", "value" to checked)
        }

        // v2.2 连接改造总闸。多数开关是每次调用时读取、立即生效；
        // 热点网络观察在启动时注册，需要重进 App 才切换 —— 提示里说清楚。
        binding.switchConn22.setOnCheckedChangeListener { _, checked ->
            connFlags.setMasterEnabled(checked)
            eventLogger.event("setting", "key" to "conn22_enabled", "value" to checked)
            NlFeedback.show(requireContext(), if (checked) "已启用 v2.2 连接改进"
                else "已回退到 v2.1.1 连接行为（热点网络部分需重进 App 生效）", long = true)
        }

        // v2.3 液态玻璃总闸。即时生效，不重启界面 —— 材质走覆盖层，回退不换代码。
        binding.switchGlass.setOnCheckedChangeListener { _, checked ->
            UiFlags.set(requireContext(), UiFlags.CLASSIC, !checked)
            eventLogger.event("setting", "key" to "glass_enabled", "value" to checked)
            NlFeedback.show(requireContext(), if (checked) "已启用液态玻璃视觉" else "已回到经典外观（v2.2 视觉）")
            // 不 recreate()：整页销毁重建会先闪一帧 windowBackground（纯白），
            // 视觉上就是"闪屏"。各页通过 UiFlags.observe 注册自己的重涂回调，原地刷新。
        }

        // 降低透明度：保留层级（描边/圆角/阴影），但不透明、不模糊
        binding.switchReduceTransparency.setOnCheckedChangeListener { _, checked ->
            UiFlags.set(requireContext(), UiFlags.REDUCE_TRANSPARENCY, checked)
            eventLogger.event("setting", "key" to "glass_reduce_transparency", "value" to checked)
        }

        // v2.2（PRD §6.1 / §7.3 / AC-7）：环境预检 + 连接漏斗，文本可选中便于复制给开发者
        binding.rowConnDiag.pressEffect()
        binding.rowConnDiag.setOnClickListener {
            val body = buildString {
                appendLine("【环境检查】（✗ 会直接阻断连接，! 为提醒）")
                preflight.checkAll(com.nikonlink.app.device.connect.PreflightGate.CHANNEL_WIFI)
                    .forEach { item ->
                        val mark = when {
                            item.ok -> "✓"
                            item.blocking -> "✗"
                            else -> "!"
                        }
                        appendLine("$mark ${item.label}")
                    }
                appendLine()
                appendLine("【最近连接尝试】（每台设备保留 12 次，仅存内存）")
                appendLine(connFunnel.render())
            }
            val text = android.widget.TextView(requireContext()).apply {
                this.text = body
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimensionPixelSize(R.dimen.font_nav).toFloat())
                setPadding(48, 24, 48, 24)
                setTextIsSelectable(true)
            }
            NlGlass.dialog(requireContext())
                .setTitle("连接诊断")
                .setView(android.widget.ScrollView(requireContext()).apply { addView(text) })
                .setPositiveButton("关闭", null)
                .show()
        }

        // 开发选项：把回退闸门摆成能点的开关。正式包里这一行连人都见不到（见 setupLabFlags）。
        setupLabFlags()

        // 通用设置
        binding.rowTheme.pressEffect()
        binding.rowTheme.setOnClickListener {
            val options = arrayOf("跟随系统", "浅色", "深色")
            NlGlass.dialog(requireContext())
                .setTitle("深浅色模式")
                .setItems(options) { _, which ->
                    AppCompatDelegate.setDefaultNightMode(
                        when (which) {
                            1 -> AppCompatDelegate.MODE_NIGHT_NO
                            2 -> AppCompatDelegate.MODE_NIGHT_YES
                            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        }
                    )
                    binding.tvThemeValue.text = options[which]
                }
                .show()
        }

        binding.rowClearCache.pressEffect()
        binding.rowClearCache.setOnClickListener {
            NlGlass.dialog(requireContext())
                .setTitle("清除缓存")
                .setMessage("将清除缩略图缓存与临时下载文件，不会影响已保存的照片。")
                .setPositiveButton("清除") { _, _ ->
                    runCatching { requireContext().cacheDir.deleteRecursively() }
                    binding.tvCacheValue.text = "0 MB"
                    eventLogger.event("setting", "key" to "clear_cache")
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.rowExportLog.pressEffect()
        binding.rowExportLog.setOnClickListener {
            eventLogger.event("setting", "key" to "export_log")
            logExportLauncher.launch("n-link_logs_${System.currentTimeMillis()}.txt")
        }

        // 检查更新：查 GitHub Releases latest，任何失败只降级提示、不误报新版本
        binding.rowCheckUpdate.pressEffect()
        binding.rowCheckUpdate.setOnClickListener { checkUpdate() }

        // 夸克网盘下载：与 GitHub 通道并列的国内直连下载入口（PRD 夸克网盘更新通道 §4.2）
        binding.rowQuarkUpdate.pressEffect()
        binding.rowQuarkUpdate.setOnClickListener { openQuarkDownload() }

        // 百度网盘下载：与夸克并列的国内直连下载入口（照夸克入口新增）
        binding.rowBaiduUpdate.pressEffect()
        binding.rowBaiduUpdate.setOnClickListener { openBaiduDownload() }

        binding.rowAbout.pressEffect()
        binding.rowAbout.setOnClickListener {
            val header = "为尼康 Z 系列微单打造的第三方连接应用：" +
                "永不断联的双通道连接、高速传输、遥控拍摄与实时监看。\n\n"
            val dialog = NlGlass.dialog(requireContext())
                .setTitle("关于 N-Link")
                .setMessage(header + "正在读取本机安装包…")
                .setPositiveButton("确定", null)
                .create()
            dialog.show()
            // 哈希要扫完整个 APK（约 4MB），不能压在点击线程上，所以先出框、算完再补
            viewLifecycleOwner.lifecycleScope.launch {
                val text = header + withContext(Dispatchers.Default) { packageSelfCheckText() }
                runCatching { if (dialog.isShowing) dialog.setMessage(text) }
            }
        }

        // F7：打赏支持页（开源免费声明 + 自愿打赏，打赏不附带任何权益）
        binding.rowSupport.pressEffect()
        binding.rowSupport.setOnClickListener {
            eventLogger.event("setting", "key" to "open_support")
            startActivity(Intent(requireContext(), SupportActivity::class.java))
        }

        // 帮助与反馈
        // 2026-10-17：教程逐条对着当前界面重走了一遍 ——
        // 「连接相机 Wi-Fi」与「手动输入相机 IP」现在都并排在「连接相机」同一行，
        // 设备页底部四格换成了相机参数；「STA 主机注册」这颗按钮一直在 STA 页签里，
        // 旧文案写成"在 WiFi-AP 模式下完成"会把人引到没有这个按钮的页面上去。
        binding.rowTutorial.pressEffect()
        binding.rowTutorial.setOnClickListener {
            NlGlass.dialog(requireContext()).setTitle("使用教程")
                .setMessage(
                    "【设备】连接相机\n" +
                        " · WiFi AP：相机开启热点 → 点同一行的「连接相机 Wi-Fi」连上相机热点" +
                        "（拉系统 Wi-Fi 面板，密码见相机屏幕）→ 点「连接相机」，" +
                        "在相机配对画面按 OK；已经在热点上时直接点「连接相机」即可，不必先扫描\n" +
                        " · WiFi STA：相机连上路由器或手机热点 → 点「连接相机」自动搜索并接续；" +
                        "扫不到时用同一行的「手动输入相机 IP」直接填地址\n" +
                        "   选「连接手机热点」时，手机不用连着任何 WiFi —— 手机自己就是热点，" +
                        "让相机加进来即可\n" +
                        "   首次使用要先做一次「STA 主机注册」（按钮就在 WiFi STA 页签里，" +
                        "详见设备页的 STA 连接教程）\n" +
                        " · USB：用支持数据的 C to C 线连接，相机 USB 模式设为 MTP/PTP，" +
                        "在系统弹窗里允许访问\n" +
                        " · 设备页下方四格是机身读数：快门次数 / 镜头 / 电池电量 / 存储卡" +
                        "（含剩余可拍张数），连上后自动填\n\n" +
                        "【相册】浏览与下载\n" +
                        " · 底部标签切换 相机照片 / 已标记 / 本地照片\n" +
                        " · 长按照片进入多选，可批量下载 / 标记 / 分享 / 删除\n" +
                        " · 下载画质、保存路径在「设置 → 传输设置」里改\n\n" +
                        "【拍摄】遥控与监看\n" +
                        " · 点「开始监看」进入实时取景，可调光圈 / 快门 / ISO / 白平衡\n" +
                        " · B 门长曝光与间隔拍摄在「更多动作」里\n\n" +
                        "连接遇到问题时，设备页每个连接模式卡片里都有对应的排查提示。"
                )
                .setPositiveButton("确定", null)
                .show()
        }
        binding.rowFaq.pressEffect()
        binding.rowFaq.setOnClickListener {
            NlGlass.dialog(requireContext()).setTitle("常见问题")
                .setMessage(
                    "Q: WiFi 搜不到相机？\n" +
                        "A: ① 相机是否停在 WiFi 等待画面（AP 是热点已开启，STA 是「正在等待连接」）；\n" +
                        "   ② 手机和相机是否在同一网络（AP 连相机热点，STA 连同一路由器或同一热点）；\n" +
                        "   ③ 手机是否开了 VPN、路由器是否开了 AP 隔离 —— 两者都会挡掉设备发现；\n" +
                        "   ④ 都确认过还是搜不到，点「手动输入相机 IP」按机身「网络设置」里的地址直连。\n\n" +
                        "Q: 用「连接手机热点」时，手机 WiFi 是关着的，能连吗？\n" +
                        "A: 能。那种接法下手机自己就是热点，不需要连着任何 WiFi —— " +
                        "让相机加入手机热点、停在等待画面，回设备页点「连接相机」即可。\n\n" +
                        "Q: STA 连接被相机拒绝（提示「相机未接受本次连接」）？\n" +
                        "A: 相机只接受已注册的主机。在设备页「WiFi STA」标签点「STA 主机注册」" +
                        "完成一次注册（相机屏幕按 OK，约 10 秒，全程只需一次），再回 STA 连接。\n" +
                        "   机身只开自己热点、连不上路由器时，可先在「WiFi AP」标签连上相机热点再回来注册。\n\n" +
                        "Q: 连接成功但相机没反应 / 操作不生效？\n" +
                        "A: AP 直连要在相机配对画面按 OK 确认；USB 要在系统弹窗里允许访问。" +
                        "遥控拍摄需要相机处于联机状态，回放或熄屏时操作不会生效。\n\n" +
                        "Q: USB 连接失败 / 完全没反应？\n" +
                        "A: ① 相机 USB 模式设为 MTP / PTP；\n" +
                        "   ② 换一根确认能传数据的 C to C 线（很多充电线不传数据）；\n" +
                        "   ③ 系统弹窗里允许 N-Link 访问 USB 设备。\n\n" +
                        "Q: 下载的照片保存在哪？\n" +
                        "A: 看「设置 → 传输设置 → 默认保存路径」：系统相册（DCIM/N-Link）" +
                        "或 Download 目录（Download/N-Link）。\n\n" +
                        "Q: NEF（RAW）照片打不开？\n" +
                        "A: 手机自带相册对 NEF 的支持有限。需要 RAW 时可在" +
                        "「设置 → 传输设置 → RAW+JPEG 下载方式」选择成对下载：" +
                        "JPEG 用来看，NEF 用来存。"
                )
                .setPositiveButton("确定", null)
                .show()
        }
        binding.rowFeedback.pressEffect()
        binding.rowFeedback.setOnClickListener { showFeedbackDialog() }
    }

    /**
     * FR-08a：让用户能自证「我装的是哪一版、和网盘里那个是不是同一个包」。
     * 调用方负责放到 IO/Default 线程上（要扫完整个 APK）。
     */
    /**
     * 开发选项（**仅调试包**）：把回退闸门摆成能点的开关。
     *
     * 2026-10-07 闸门评判：35 个开关裁到 5 个。判定口径——
     * ① 有真机日志实锤、关掉只会回到已知更差行为的修复（FR-02/06/19/20/21/23/24/26/27/28、
     *    FR-32 五条、FR-33、FR-34 七条、FR-35①④、FR-37、FR-38、FR-03、FR-10）全部
     *    **锁定为默认值并移除开关**（`ConnFlags.LOCKED`，isEnabled 无视 prefs 残留）；
     * ② 仍未定论、需要现场二分的保留开关：
     *    - EVENT_SILENCE_HOLD（FR-04）：传输期容忍 event 静默，真机数据不足；
     *    - GIVEUP_FLOOR（FR-22）：120s 下限救过"慢相机"，也与蜂窝黑洞空转有过纠葛
     *      （黑洞已由 no_camera_subnet 快速收口接管），保留二分能力；
     *    - PROTECT_SELECT / TONE_TOOLS / RAW_PAIR：默认关的功能实验，均未全量验收
     *      （「已保护」的文案测试当前还是红的）。
     */
    private fun setupLabFlags() {
        if (!BuildConfig.DEBUG) return
        binding.dividerLabs.visibility = View.VISIBLE
        binding.rowLabs.visibility = View.VISIBLE
        binding.rowLabs.pressEffect()
        binding.rowLabs.setOnClickListener { showLabFlagsDialog() }
    }

    /** 一行一个闸门。读写分属 `ConnFlags` / `UiFlags` 两套 prefs，所以传的是闭包不是 key。 */
    private class LabFlag(
        val key: String,
        val label: String,
        val read: () -> Boolean,
        val write: (Boolean) -> Unit
    )

    private fun labFlags(): List<LabFlag> {
        val c = requireContext()
        return listOf(
            LabFlag(
                ConnFlags.EVENT_SILENCE_HOLD, "传输期间容忍 event 静默（FR-04，默认关）",
                { connFlags.isEnabled(ConnFlags.EVENT_SILENCE_HOLD) },
                { connFlags.setEnabled(ConnFlags.EVENT_SILENCE_HOLD, it) }
            ),
            LabFlag(
                ConnFlags.GIVEUP_FLOOR, "放弃前先等够 120s（FR-22；关=连续 3 次不可达立即判死）",
                { connFlags.isEnabled(ConnFlags.GIVEUP_FLOOR) },
                { connFlags.setEnabled(ConnFlags.GIVEUP_FLOOR, it) }
            ),
            // ── 批次 C（2026-10-12 vivo AP 日志）：五条默认开，逐条可拨回做 A/B ──
            LabFlag(
                ConnFlags.FUNNEL_CLOSE_ON_IGNORE, "C-N1 拦下的自动重连也收口漏斗（关=回到留下 59s 幽灵轮次）",
                { connFlags.isEnabled(ConnFlags.FUNNEL_CLOSE_ON_IGNORE) },
                { connFlags.setEnabled(ConnFlags.FUNNEL_CLOSE_ON_IGNORE, it) }
            ),
            LabFlag(
                ConnFlags.TOPO_FAIL_NO_COOLDOWN, "C-N2 掉线/DHCP 类失败不记兜底地址冷却（关=自动重连被 180s 拦死）",
                { connFlags.isEnabled(ConnFlags.TOPO_FAIL_NO_COOLDOWN) },
                { connFlags.setEnabled(ConnFlags.TOPO_FAIL_NO_COOLDOWN, it) }
            ),
            LabFlag(
                ConnFlags.AP_REDISCOVER_SAME_HOST, "C-N3 相机热点上允许接续同一地址（关=扫到同址相机当没发现）",
                { connFlags.isEnabled(ConnFlags.AP_REDISCOVER_SAME_HOST) },
                { connFlags.setEnabled(ConnFlags.AP_REDISCOVER_SAME_HOST, it) }
            ),
            LabFlag(
                ConnFlags.AP_ENONET_FAST_CLOSE, "C-N4 ENONET 即刻收口（关=掉线后仍烧满 28~32s 才报）",
                { connFlags.isEnabled(ConnFlags.AP_ENONET_FAST_CLOSE) },
                { connFlags.setEnabled(ConnFlags.AP_ENONET_FAST_CLOSE, it) }
            ),
            LabFlag(
                ConnFlags.AP_REDISCOVER_REARM, "C-N5 用户点一次就重新武装自动重发现（关=一辈子只跑一次）",
                { connFlags.isEnabled(ConnFlags.AP_REDISCOVER_REARM) },
                { connFlags.setEnabled(ConnFlags.AP_REDISCOVER_REARM, it) }
            ),
            LabFlag(
                UiFlags.PROTECT_SELECT, "「已保护」筛选（FR-16，默认关）",
                { UiFlags.protectSelectEnabled(c) },
                { UiFlags.set(c, UiFlags.PROTECT_SELECT, it) }
            ),
            LabFlag(
                UiFlags.TONE_TOOLS, "影调工具·伪彩/波形（FR-13，默认关）",
                { UiFlags.toneToolsEnabled(c) },
                { UiFlags.set(c, UiFlags.TONE_TOOLS, it) }
            ),
            LabFlag(
                UiFlags.RAW_PAIR, "RAW+JPEG 合成一条（FR-17，默认关）",
                { UiFlags.rawPairEnabled(c) },
                { UiFlags.set(c, UiFlags.RAW_PAIR, it) }
            )
        )
    }

    private fun showLabFlagsDialog() {
        val column = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 8, 48, 8)
        }
        labFlags().forEach { flag ->
            column.addView(android.widget.CheckBox(requireContext()).apply {
                text = flag.label
                textSize = 14f
                setPadding(0, 12, 0, 12)
                isChecked = flag.read()
                setOnCheckedChangeListener { _, checked ->
                    flag.write(checked)
                    eventLogger.event("lab_flag", "key" to flag.key, "value" to checked)
                }
            })
        }
        NlGlass.dialog(requireContext())
            .setTitle("开发选项（仅调试包）")
            .setMessage("关掉即回到该功能之前的行为，用于真机 A/B 对照。" +
                    "连接类还要设置页的「v2.2 连接改进」总闸开着才生效；" +
                    "相册标签与快门类改动要重进对应页面或重连一次才看得出来。")
            .setView(android.widget.ScrollView(requireContext()).apply { addView(column) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun packageSelfCheckText(): String {
        val ctx = requireContext()
        val apk = java.io.File(ctx.applicationInfo.sourceDir)
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        return com.nikonlink.app.shared.metrics.PackageSelfCheck.describe(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            debuggable = BuildConfig.DEBUG,
            apkBytes = if (apk.isFile) apk.length() else 0L,
            apkDigest = com.nikonlink.app.shared.metrics.PackageSelfCheck.sha256(apk),
            installedAtMillis = info?.lastUpdateTime ?: 0L
        )
    }

    /**
     * 意见反馈（QQ 交流群）。
     *
     * 三个出口：
     * - 复制群号 → 系统剪贴板（Android 13+ 由系统自带复制提示，低版本自行 Toast）
     * - 加入 QQ 群 → mqqwpa:// 群会话 scheme 拉起 QQ 的群资料 / 申请加群页
     * - 未安装 QQ → 明确提示 + 引导复制群号后手动搜索添加
     *
     * Android 11+ 的包可见性限制要求在 manifest 里用 `<queries>` 声明 QQ 包名与该 scheme，
     * 否则 [android.content.pm.PackageManager.queryIntentActivities] 查不到 QQ，会误判为未安装。
     */
    private fun showFeedbackDialog() {
        val groupNumber = getString(R.string.feedback_qq_group_number)
        NlGlass.dialog(requireContext())
            .setTitle(R.string.feedback_qq_group_title)
            .setMessage(getString(R.string.feedback_qq_group_message, groupNumber))
            .setPositiveButton("加入 QQ 群") { _, _ -> joinQqGroup(groupNumber) }
            .setNeutralButton(R.string.feedback_copy_group_number) { _, _ -> copyQqGroupNumber(groupNumber) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 复制群号到剪贴板；Android 13+ 系统自带复制气泡，只在低版本或失败时提示 */
    private fun copyQqGroupNumber(groupNumber: String) {
        val ok = runCatching {
            val manager = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("QQ 群号", groupNumber))
        }.isSuccess
        if (!ok || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(if (ok) getString(R.string.feedback_copied) else getString(R.string.feedback_copy_failed))
        }
    }

    /**
     * 拉起 QQ 加群。
     *
     * `mqqwpa://im/chat?chat_type=group&uin=<群号>` 是 QQ 对外公开的群会话协议，
     * 尚未加群时打开的是群资料页并给出「申请加群」入口，QQ / TIM 均可响应。
     * 查不到可响应的应用即判定为未安装，转提示引导用户复制群号手动添加。
     */
    private fun joinQqGroup(groupNumber: String) {
        eventLogger.event("setting", "key" to "feedback_join_qq")
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("mqqwpa://im/chat?chat_type=group&uin=$groupNumber&version=1")
        )
        val hasQq = runCatching {
            requireContext().packageManager.queryIntentActivities(intent, 0)
        }.getOrDefault(emptyList()).isNotEmpty()

        if (hasQq && runCatching { startActivity(intent) }.isSuccess) return
        Timber.w("Launch QQ group failed or QQ not installed, fallback to manual hint")
        showQqNotInstalledDialog(groupNumber)
    }

    /** 未安装 QQ（或拉起失败）：明确告知 + 给出复制群号出口 */
    private fun showQqNotInstalledDialog(groupNumber: String) {
        NlGlass.dialog(requireContext())
            .setTitle(R.string.feedback_qq_not_installed)
            .setMessage(getString(R.string.feedback_qq_not_installed_message, groupNumber))
            .setPositiveButton(R.string.feedback_copy_group_number) { _, _ -> copyQqGroupNumber(groupNumber) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 检查更新（PRD 4.4 S3/S4/S5）
     * 主线程只切 UI 态，网络与解析在 UpdateChecker 内跑 Dispatchers.IO，不阻塞主线程（AC-6）。
     */
    private fun checkUpdate() {
        val now = System.currentTimeMillis()
        if (checkingUpdate || now - lastUpdateClickAt < UPDATE_CHECK_DEBOUNCE_MS) return
        lastUpdateClickAt = now
        checkingUpdate = true

        binding.tvUpdateValue.text = "检查中…"
        ControlStates.setEnabled(binding.rowCheckUpdate, false)
        eventLogger.event("setting", "key" to "check_update")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = updateChecker.check(BuildConfig.VERSION_NAME)
            checkingUpdate = false
            // 回调可能在视图销毁后才到达，必须判空
            val b = _binding ?: return@launch
            ControlStates.setEnabled(b.rowCheckUpdate, true)
            when (result) {
                UpdateResult.UpToDate -> {
                    b.tvUpdateValue.text = "已是最新"
                    b.tvUpdateValue.setTextColor(resolveColor(R.color.text_tertiary))
                    toast("已是最新版本")
                }

                is UpdateResult.Available -> {
                    b.tvUpdateValue.text = "发现 ${result.versionLabel}"
                    b.tvUpdateValue.setTextColor(resolveColor(R.color.accent))
                    // 本次解析到夸克链接时顺带刷新网盘入口副文案（缓存已在 UpdateChecker 落盘）
                    if (result.quarkUrl != null) {
                        b.tvQuarkValue.text = "可下载 ${result.versionLabel}"
                    }
                    // 本次解析到百度链接时同样刷新副文案
                    if (result.baiduUrl != null) {
                        b.tvBaiduValue.text = "可下载 ${result.versionLabel}"
                    }
                    showUpdateDialog(result)
                }

                is UpdateResult.Failed -> {
                    b.tvUpdateValue.text = BuildConfig.VERSION_NAME
                    b.tvUpdateValue.setTextColor(resolveColor(R.color.text_tertiary))
                    toast(result.reason.message)
                    // 失败也给用户一条手动通路（404「暂无发布版本」除外）
                    if (result.reason.offerReleasePage) showReleasePageDialog()
                }
            }
        }
    }

    /**
     * 夸克网盘下载入口（PRD 夸克网盘更新通道 §4.2）。
     * 链接来源：最近一次检查更新解析到的缓存；无缓存时只明确提示，不影响任何其他流程。
     */
    private fun openQuarkDownload() {
        val link = updateChecker.cachedQuarkLink()
        if (link == null) {
            toast("暂无网盘链接，请先检查更新")
            return
        }
        eventLogger.event(
            "update_check",
            "action" to "quark_open",
            "version" to link.versionLabel
        )
        openUrl(link.url)
    }

    /**
     * 百度网盘下载入口（与 [openQuarkDownload] 同构）。
     * 链接来源：最近一次检查更新解析到的缓存；无缓存时只明确提示，不影响任何其他流程。
     */
    private fun openBaiduDownload() {
        val link = updateChecker.cachedBaiduShare()
        if (link == null) {
            toast("暂无网盘链接，请先检查更新")
            return
        }
        eventLogger.event(
            "update_check",
            "action" to "baidu_open",
            "version" to link.second
        )
        openUrl(link.first)
    }

    /**
     * 新版本对话框（F6 + 夸克网盘更新通道 §4.3）：实现收敛在 [UpdatePrompt]，
     * 供设置页手动检查与 MainActivity 启动自动检查共用（含防重入）。
     */
    private fun showUpdateDialog(result: UpdateResult.Available) {
        UpdatePrompt.showIfNotShowing(requireContext(), eventLogger, result)
    }

    /** 检查失败后的兜底入口：引导用户自己去发布页看（PRD S5） */
    private fun showReleasePageDialog() {
        NlGlass.dialog(requireContext())
            .setTitle("检查更新")
            .setMessage("可前往 GitHub 发布页手动查看最新版本。")
            .setPositiveButton("前往发布页") { _, _ -> openUrl(UpdateChecker.RELEASES_PAGE_URL) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 跳转下载：优先 apk 直链，无 apk 时是 release 页面（PRD S4）。
     * 无浏览器等场景退化成对话框展示完整 URL 供复制。实现收敛在 [UpdatePrompt]。
     */
    private fun openUrl(url: String) {
        UpdatePrompt.openUrl(requireContext(), eventLogger, url)
    }

    private fun resolveColor(resId: Int): Int = ContextCompat.getColor(requireContext(), resId)

    private fun toast(text: String) {
        NlFeedback.show(requireContext(), text)
    }

    private fun singleChoice(title: String, options: Array<String>, current: String, onPick: (String) -> Unit) {
        val checkedIdx = options.indexOf(current).coerceAtLeast(0)
        var selection = checkedIdx
        NlGlass.dialog(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(options, checkedIdx) { _, which -> selection = which }
            .setPositiveButton("确定") { _, _ -> onPick(options[selection]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatCacheSize(context: Context): String {
        val size = context.cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        return when {
            size >= 1024 * 1024 -> "${size / 1024 / 1024} MB"
            size >= 1024 -> "${size / 1024} KB"
            else -> "0 MB"
        }
    }

    override fun onDestroyView() {
        UiFlags.unobserve(this)
        dockInset = null
        topBarFx = null
        super.onDestroyView()
        _binding = null
    }

    /** dock 悬浮时给列表底部让出一条，并在滚动时驱动 dock 背景重采（内容才看得见从玻璃底下划过） */
    private fun setupDockFloat() {
        dockInset = DockInset(binding.settingsScroll)
        applyDockSpace()
        styleTopBar()
        binding.settingsScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            topBarFx?.onScroll(scrollY)
            // 滚动增量交给采集器，顶栏的采样窗口跟着平移（否则背景是 150ms 一格的幻灯片）
            (activity as? MainActivity)?.dockBackdrop?.requestRefresh(scrollY - oldScrollY)
        }
    }

    /**
     * 顶栏：干净统一底色 —— 几何与底色都在 `NlTopChrome/NlTopBar/NlTopDivider` style 里，
     * 这里只挂标题让位。玻璃底片整块撤掉：它背后就是本页滚动区，采样慢一帧会错位/闪烁/
     * 切页残留，而在白底上透出的是白。
     */
    private fun styleTopBar() {
        topBarFx = GlassTopBar(binding.topChrome, binding.tvTitle)
        topBarFx?.onScroll(binding.settingsScroll.scrollY)
    }

    private fun applyDockSpace() {
        if (_binding == null) return
        dockInset?.applyPadding((activity as? MainActivity)?.dockSpace() ?: 0)
        binding.settingsScroll.clipToPadding = false
        GlassChrome.apply(binding.topChrome, binding.settingsScroll)
        DepthLift.apply(binding.root, requireContext())
    }

    override fun onDockSpaceChanged(dockSpace: Int) = applyDockSpace()

    /**
     * 本页是 add 完立刻 hide 的（`MainActivity.setupFragments`），GONE 期间不参与布局，
     * 首帧里顶栏高度与 `dockSpace()` 都量成 0；切回本页时若不重算，顶部第一项就一直
     * 被覆盖式顶栏压住（2026-10-11 真机反馈）。与 TransferFragment / RemoteFragment
     * 的既有 `onHiddenChanged` 做法同源。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) applyDockSpace()
    }

    companion object {
        /** 检查更新防抖窗口：1.5s 内重复点击只发 1 次请求（PRD S3 / AC-5） */
        private const val UPDATE_CHECK_DEBOUNCE_MS = 1_500L
    }
}
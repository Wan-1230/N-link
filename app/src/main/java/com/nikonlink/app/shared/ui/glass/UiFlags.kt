package com.nikonlink.app.shared.ui.glass

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager

/**
 * v2.3 液态玻璃的运行时开关（回退闸门）+ 能力分层。
 *
 * 语义与 `ConnFlags` 一致：关掉后**逐像素回到 v2.2**，而不是"大致没变"。
 * 玻璃化全部走覆盖层（新 drawable / 新 flag 分支），旧 `bg_*.xml` 一个都不删，
 * 所以回退是换开关，不是 revert 代码。
 */
object UiFlags {

    private const val PREFS_NAME = "nl_settings"

    /** 经典外观（总闸）：开启 = 整个玻璃子系统失效，回到 v2.2 观感 */
    const val CLASSIC = "ui_classic"

    /** 降低透明度：保留层级（rim / 圆角 / 阴影），但 tint 不透明、模糊全关 */
    const val REDUCE_TRANSPARENCY = "ui_reduce_transparency"

    /** 背景模糊：单独关掉用于低端机验证，tint + rim + 阴影仍保留 */
    const val BLUR = "ui_blur"

    /** 动态效果：按压时 rim 削弱 / tint 提浓 / 模态半径联动 */
    const val MOTION = "ui_motion"

    /** 边缘折射（透镜位移 + 中心微放大）。PRD §3.6，本包用 CPU 在降采样纹理上实现 */
    const val LENS = "ui_lens"

    /**
     * 边缘色散（RGB 分离）。
     *
     * **v2.6.3 默认改为开**（强度 0.10）。原来默认关，理由是"色散是彩色，
     * 与 `colors.xml:39` 的灰阶规范冲突"（PRD §3.7 / Q1）。重新核对后这个理由站不住：
     * 灰阶规范管的是 **UI 状态色**（图标、文字、指示器不许带颜色），而色散是**光学伪影**，
     * 颜色来自被透射的内容本身 —— 与"彩色只允许从被透射的内容里来"并不冲突，
     * 反而是"真实玻璃质感"不可缺少的一笔（两个参考项目的默认值都是开的）。
     *
     * 强度压在 0.10：棱边有一点光谱分离，不读作彩虹。不想要的话关掉这个 pref 即可，
     * 或把 [DEFAULTS] 里这一项改回 false。
     */
    const val DISPERSION = "ui_dispersion"

    /**
     * v2.5 FR-03：相册分页期间逐页发射「累积 + 已排序」的前缀，让首屏不必等全量。
     *
     * 关掉 = 回到 v2.3.1 的行为（holdPages：全量拉完才显示一次）。
     * 放在这里是因为 PRD v2.5 §五 把「UI/行为类」闸门统一登记在 UiFlags；
     * 它和玻璃材质无关，只是同一套 prefs 回退机制。
     */
    const val ALBUM_INCR = "v25_album_incr"

    /**
     * v2.5 FR-10：快门次数读出来后，再拿最新几张样张**互验**一次（最多 3 张）。
     *
     * 关掉 = 回到 v2.3.1 行为：命中第一张能解开的样张就返回，"解对了"与
     * "碰巧读到个数"在 UI 上完全同一个样子。
     */
    const val SHUTTER_XCHECK = "v25_shutter_crosscheck"

    /**
     * v2.5 FR-16 / v2.6.4：相册增「已筛选」筛选 —— 只显示相机端已保护或已评级照片。
     *
     * **v2.6.4 起默认开**（原为默认关）：这是第三方快传那类"机内粗选、手机上只处理筛过的"
     * 工作方式，列表加载与传图都直接受益；且保护/评级状态全程**只读**（本 App 不写
     * `0x101A SetObjectProtectionStatus`，尼康机型支持面未验证、横向评估标在 ❓），
     * 开着不会动到卡里任何东西。关掉 = 相册与 v2.3.2 完全一致。
     *
     * 诚实边界：评级列能否从 PTP 拿到随机型/固件而定（尼康机内评级主要写在文件元数据里）。
     * 拿不到时这一档等价于"仅已保护"，界面上的标签与计数都按"已筛选"呈现，不假装支持。
     */
    const val PROTECT_SELECT = "v25_protect_select"

    /**
     * v2.5 FR-13：影调工具（本批先做**伪彩**，RGB 分量波形随后）。
     *
     * **默认关**。关掉 = 取景叠加层只剩网格线与水平仪，与 v2.3.2 逐帧一致
     * （伪彩每帧多花的钱只在开启后才发生，见验收①的帧率要求）。
     * 这一项是本 App 黑白体系的一处有意例外：伪彩靠颜色传信息，改成灰阶等于把工具删了。
     */
    const val TONE_TOOLS = "v25_tone_tools"

    /**
     * v2.5 FR-17：相册把同一次拍摄的 NEF 与 JPG **合成一条**显示。
     *
     * **默认关**。关掉 = 相册与 v2.3.2 逐位一致（两张平铺，谁没传一眼看得见）。
     * 开着的收益是千张卡库里 RAW+JPG 连拍不再占两格；代价是"只传了 JPG 没传 RAW"
     * 这种半完成状态变得要展开才看得出来——所以合并只敢做严格 1:1，
     * 同名下出现两张同格式或只有一张，一律退回平铺，绝不为了少一格而丢文件。
     */
    const val RAW_PAIR = "v25_raw_pair"

    private val DEFAULTS = mapOf(
        CLASSIC to false,
        REDUCE_TRANSPARENCY to false,
        BLUR to true,
        MOTION to true,
        LENS to true,
        DISPERSION to true,
        ALBUM_INCR to true,
        SHUTTER_XCHECK to true,
        PROTECT_SELECT to true,
        TONE_TOOLS to false,
        RAW_PAIR to true,
    )

    /**
     * 页面级的重涂回调（PRD §9.4）。
     *
     * 为什么不让设置页开关直接 `activity.recreate()`：整页销毁重建会先把
     * `windowBackground`（纯白）闪一帧出来，视觉上就是"闪屏"。改成各页注册自己的
     * 重涂函数，开关切换时原地刷新。
     * 用 owner 做键 → 同一个页面重复注册自动覆盖，`unobserve(owner)` 精确摘除，
     * 不会因为 lambda 引用相等性问题漏摘。
     */
    private const val MAX_OBSERVERS = 64

    private val pageObservers = LinkedHashMap<Any, () -> Unit>()

    fun observe(owner: Any, block: () -> Unit) {
        if (pageObservers.size > MAX_OBSERVERS) {
            // 兜底清掉已死对象，避免长期持有（正常路径靠 unobserve）
            pageObservers.entries.removeAll { it.key == null }
        }
        pageObservers[owner] = block
    }

    fun unobserve(owner: Any) {
        pageObservers.remove(owner)
    }

    /** 开关变更后通知所有已注册的界面重涂材质 */
    fun notifyChanged() {
        pageObservers.values.toList().forEach { runCatching(it) }
    }

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun get(c: Context, key: String): Boolean =
        prefs(c).getBoolean(key, DEFAULTS[key] ?: true)

    /** 玻璃总开关：经典外观关闭时才生效 */
    fun glassEnabled(c: Context): Boolean = !get(c, CLASSIC)

    /**
     * 降低透明度 = 用户开关 **或** 系统「高对比度文字」。
     *
     * PRD §9.4 的自动触发（原 C3 偏离）现在能做了，而且不用反射：
     * `AccessibilityManager.isHighTextContrastEnabled()` 确实不在公开 stub 里，
     * 但它背后的设置项是公开的 —— `Settings.Secure` 的
     * `accessibility_high_text_contrast_enabled`，普通应用可读、无需权限。
     * 读一次不贵，但不能进绘制路径，所以走缓存 + [watchSystemHighContrast] 失效。
     */
    fun reduceTransparency(c: Context): Boolean =
        get(c, REDUCE_TRANSPARENCY) || systemHighContrast(c)

    /** 系统高对比度文字设置项（API 34 起有公开常量，同名串在更早版本也能读到） */
    private const val HIGH_CONTRAST_KEY = "accessibility_high_text_contrast_enabled"

    /** -1 = 未读；0/1 = 缓存值。绘制路径只读这个字段，不再打 Settings */
    @Volatile private var highContrastCache = -1
    private var highContrastObserver: android.database.ContentObserver? = null

    fun systemHighContrast(c: Context): Boolean {
        if (highContrastCache < 0) {
            highContrastCache = try {
                if (android.provider.Settings.Secure.getInt(
                        c.applicationContext.contentResolver, HIGH_CONTRAST_KEY, 0
                    ) == 1
                ) 1 else 0
            } catch (e: Exception) {
                0   // ROM 没有这个设置项 = 未开启，不是错误
            }
        }
        return highContrastCache == 1
    }

    /**
     * 监听系统「高对比度文字」与「动画缩放」两个设置项：变了就作废缓存并广播重涂。
     * 在 MainActivity.onCreate 调一次即可（用 applicationContext，重复调用安全）。
     */
    fun watchSystemHighContrast(c: Context) {
        val app = c.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        if (highContrastObserver == null) {
            val o = object : android.database.ContentObserver(handler) {
                override fun onChange(selfChanging: Boolean) {
                    highContrastCache = -1
                    notifyChanged()
                }
            }
            runCatching {
                app.contentResolver.registerContentObserver(
                    android.provider.Settings.Secure.getUriFor(HIGH_CONTRAST_KEY), false, o,
                )
            }.onSuccess { highContrastObserver = o }
            highContrastCache = -1   // 注册后重读一次，别用注册前的旧值
        }

        // v2.6.2：动画缩放同样要跟着变，否则用户中途打开「移除动画」不生效
        if (animObserver == null) {
            val o = object : android.database.ContentObserver(handler) {
                override fun onChange(selfChanging: Boolean) {
                    animOffCache = -1
                    notifyChanged()
                }
            }
            runCatching {
                app.contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor(ANIM_SCALE_KEY), false, o,
                )
            }.onSuccess { animObserver = o }
            animOffCache = -1
        }
    }

    /**
     * 背景模糊总开关。
     *
     * 故意**不看 SDK 档位**：模糊走 CPU 在 1/8 降采样纹理上施算，Android 10 与 Android 16
     * 是同一条路径（这正是放弃 `RenderEffect` 的目的）。只有窗口级模糊才受 API 31 限制，
     * 见 [windowBlurAvailable]。省电模式仍然收摊。
     */
    fun blurEnabled(c: Context): Boolean =
        glassEnabled(c) && get(c, BLUR) && !reduceTransparency(c) && !isPowerSave(c)

    /**
     * 动态效果开关。
     *
     * v2.6.2：Android 的「移除动画」（开发者选项 / 无障碍里把动画程序时长缩放调成 0）
     * 与 iOS 的 Reduce Motion 是同一件事，但原来只看 App 自己的开关 —— 用户在系统层面
     * 关了动画，玻璃的按压过渡、胶囊液态位移、弹窗模糊建立照旧在跑，等于
     * 无障碍设置被这套 UI 无视了。这里补读 `animator_duration_scale`。
     */
    fun motionEnabled(c: Context): Boolean =
        glassEnabled(c) && get(c, MOTION) && !systemAnimationsOff(c)

    /** 系统「动画程序时长缩放」设置项；0 = 用户关闭了动画 */
    private const val ANIM_SCALE_KEY = "animator_duration_scale"

    /** -1 = 未读；0/1 = 缓存值。绘制 / 动画路径只读这个字段，不打 Settings */
    @Volatile private var animOffCache = -1
    private var animObserver: android.database.ContentObserver? = null

    fun systemAnimationsOff(c: Context): Boolean {
        if (animOffCache < 0) {
            animOffCache = try {
                val v = android.provider.Settings.Global.getFloat(
                    c.applicationContext.contentResolver, ANIM_SCALE_KEY, 1f,
                )
                if (v == 0f) 1 else 0
            } catch (e: Exception) {
                0   // ROM 没有这个设置项 / 读不到 = 动画正常，不是错误
            }
        }
        return animOffCache == 1
    }

    /** 相册增量分页开关（与玻璃无关，见 [ALBUM_INCR]） */
    fun albumIncrementalEnabled(c: Context): Boolean = get(c, ALBUM_INCR)

    /** 快门次数多样本互验开关（与玻璃无关，见 [SHUTTER_XCHECK]） */
    fun shutterCrossCheckEnabled(c: Context): Boolean = get(c, SHUTTER_XCHECK)

    /** 相册「已保护」筛选开关（与玻璃无关，见 [PROTECT_SELECT]） */
    fun protectSelectEnabled(c: Context): Boolean = get(c, PROTECT_SELECT)

    /** 影调工具总闸（与玻璃无关，见 [TONE_TOOLS]） */
    fun toneToolsEnabled(c: Context): Boolean = get(c, TONE_TOOLS)

    /** RAW+JPEG 合成一条显示的开关（与玻璃无关，见 [RAW_PAIR]） */
    fun rawPairEnabled(c: Context): Boolean = get(c, RAW_PAIR)

    /** 折射只在"真的采了背景"的面才有意义 */
    fun lensEnabled(c: Context): Boolean = blurEnabled(c) && get(c, LENS)

    fun dispersionEnabled(c: Context): Boolean = lensEnabled(c) && get(c, DISPERSION)

    fun set(c: Context, key: String, value: Boolean) {
        prefs(c).edit().putBoolean(key, value).apply()
        notifyChanged()
    }

    /**
     * 窗口级 blur-behind（弹窗 / sheet）是否可用。
     *
     * 这是唯一真正受 SDK 限制的玻璃能力：`WindowManager.LayoutParams.FLAG_BLUR_BEHIND`
     * 与 `Window.setBackgroundBlurRadius` 都要 API 31。不可用时静默退化为不透明面板。
     *
     * PRD §9.1 设想的 T1/T2/T3 三档在当前实现下塌缩成一个布尔 —— 因为背景模糊已改为
     * CPU 在小尺寸降采样纹理上施算，Android 29 到 36 是同一条路径，不必再分层。
     * 等 AGSL 折射（真·lensing，要 API 33）落地时再恢复分层。
     */
    fun windowBlurAvailable(c: Context): Boolean =
        blurEnabled(c) && Build.VERSION.SDK_INT >= 31

    fun isPowerSave(c: Context): Boolean = try {
        c.applicationContext
            .getSystemService(PowerManager::class.java)
            ?.isPowerSaveMode == true
    } catch (e: Exception) {
        false
    }
}

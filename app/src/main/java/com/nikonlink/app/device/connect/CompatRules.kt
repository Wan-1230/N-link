package com.nikonlink.app.device.connect

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import com.nikonlink.app.shared.device.readSystemProperty
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 逐 ROM 兼容矩阵（PRD v2.2 §6.1 / §6.2，落地路线 10.1 第 7 条）。
 *
 * **为什么要有这个类**：「同一份代码在不同机型上表现不同」这件事过去只有一条写死在
 * `RomDetector` 里的小米文案，新增机型或新增一个系统入口就得发一版。改成
 * `assets/compat/rom_rules.json` 驱动之后，矩阵本身是数据。
 *
 * 三条硬约束：
 *  1. **只影响提示文案与系统入口，绝不改变连接 / 重试 / 绑定行为** —— 与 `RomDetector`
 *     同一立场；否则兼容表会变成第二条隐形的连接状态机。
 *  2. `component` 只是**候选**入口，一律 `resolveActivity()` 校验通过才给出去 —— 系统 OTA
 *     改名或被 ROM 裁掉时自动退回纯文案（PRD §6.2.3 要求的「失效能单独降级」）。
 *  3. 表读不到 / 解析失败 / 闸门 [ConnFlags.COMPAT_RULES] 关闭 → 一律返回空，
 *     调用方沿用代码内的兜底文案，行为等价于 v2.2.0。
 */
@Singleton
class CompatRules @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connFlags: ConnFlags
) {

    /** 预检跑在 UI 线程，把这次资产读取推迟到真有人问起时（一生一次）。 */
    private val rules: List<RomRule> by lazy { load() }

    private val deviceRule: RomRule? by lazy {
        runCatching {
            rules.matchFor(Build.MANUFACTURER, Build.BRAND, Build.VERSION.SDK_INT)
        }.getOrNull()
    }

    /** 该 ROM 的查杀强度（dontkillmyapp 口径 0..5）；没命中规则返回 0。 */
    val killRating: Int get() = if (enabled) deviceRule?.killRating ?: 0 else 0

    fun trick(id: String): RomRule.Trick? =
        if (enabled) deviceRule?.tricks.orEmpty().firstOrNull { it.id == id } else null

    /** 引导文案；表里没有该 ROM 的这一条时返回 null，由调用方决定兜底文案。 */
    fun hint(id: String): String? = trick(id)?.text.takeUnless { it.isNullOrBlank() }

    /**
     * 可直接 `startActivity` 的系统入口。
     *
     * 返回 null 的三种情况（调用方据此退化为纯文案、不给按钮）：表里没写组件、
     * 组件在这台机器上不存在（OTA / 区域变体 / 精简 ROM）、闸门关闭。
     */
    fun intent(id: String): Intent? {
        val spec = trick(id)?.component ?: return null
        val intent = runCatching { componentIntent(spec) }.getOrNull() ?: return null
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
        if (!resolvable) {
            Timber.tag(TAG).d("入口不可用，降级为文案指引：id=%s component=%s", id, spec)
            return null
        }
        return intent
    }

    /** 上次验证距今太久 —— 系统大概率已经 OTA 过，面板上要补一句「可能已失效」。 */
    private fun isStale(id: String): Boolean {
        val verified = trick(id)?.lastVerified ?: return false
        return runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(verified), LocalDate.now()) > STALE_AFTER_DAYS
        }.getOrDefault(false)
    }

    /** 直接拼在文案尾巴上的失效提醒；不 stale 时是空串，调用方无需判空。 */
    fun staleSuffix(id: String): String =
        if (isStale(id)) "（该入口可能已随系统更新变化）" else ""

    private val enabled: Boolean get() = connFlags.isEnabled(ConnFlags.COMPAT_RULES)

    private fun load(): List<RomRule> = runCatching {
        val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        parse(json).also { Timber.tag(TAG).i("兼容矩阵加载：rules=%d", it.size) }
    }.recover { e ->
        // 资产缺失 / JSON 写坏只意味着一件事：回到内置文案，不影响任何连接行为
        Timber.tag(TAG).w(e, "兼容矩阵读取失败，退回内置文案")
        emptyList()
    }.getOrThrow()

    companion object {
        private const val TAG = "CompatRules"
        private const val ASSET = "compat/rom_rules.json"

        /** 超过这个天数就当作可能已被系统 OTA 改掉（PRD §6.2.3 的降级条件） */
        const val STALE_AFTER_DAYS = 180L

        /**
         * 解析资产。**一条规则写坏只丢那一条**，不把整张表一起废掉 ——
         * 手工维护的 JSON 里少一个括号，代价应该是少一条提示，而不是所有提示都没了。
         */
        internal fun parse(json: String): List<RomRule> =
            runCatching { Gson().fromJson(json, RomRulesFile::class.java)?.rules.orEmpty() }
                .getOrDefault(emptyList())
                .filter { !it.id.isNullOrBlank() }

        /** `pkg/class`；class 以 `.` 开头时按 pkg 补全（AndroidManifest 的相对写法）。 */
        internal fun componentIntent(spec: String): Intent {
            val parts = spec.split('/')
            require(parts.size == 2 && parts.none { it.isBlank() }) {
                "component 应为 pkg/class 写法：$spec"
            }
            val pkg = parts[0]
            val cls = if (parts[1].startsWith(".")) pkg + parts[1] else parts[1]
            require(cls.contains('.')) { "类名不完整：$spec" }
            return Intent()
                .setComponent(ComponentName(pkg, cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/** 资产文件结构。Gson 会忽略它不认识的键（`_comment` / `sources` 因此可以留在表里）。 */
data class RomRulesFile(
    val rules: List<RomRule>? = null,
)

/**
 * 一条 ROM 规则。字段全部按「可空 + 缺省」建模：表是手写的，任何一格没填都不该炸。
 *
 * JSON 里的 `version` / `updatedAt` / `impact` / `family` 不建模 —— 它们是写给维护者看的
 * 表头与表注释，Gson 直接跳过，读表日志靠不到它们也无须靠。
 */
data class RomRule(
    val id: String? = null,
    /** dontkillmyapp 口径的后台查杀强度：0 = 原生行为，5 = 查杀最重 */
    val killRating: Int = 0,
    val match: Match? = null,
    val tricks: List<Trick>? = null,
) {
    data class Match(
        val manufacturer: List<String>? = null,
        val brand: List<String>? = null,
        /** 这些系统属性里任一读到非空值即命中（MIUI / ColorOS 的旁证） */
        val props: List<String>? = null,
        val apiMin: Int = 0,
        val apiMax: Int = Int.MAX_VALUE,
    )

    data class Trick(
        val id: String? = null,
        val text: String? = null,
        val component: String? = null,
        /** `yyyy-MM-dd`；null = 从未真机验证，因此只当候选入口看待 */
        val lastVerified: String? = null,
    )
}

/**
 * manufacturer / brand / 系统属性三者取**或**，任一命中即算这条规则。
 * 属性读取复用 [readSystemProperty]，避免两处对「是不是小米」各说一套。
 */
internal fun RomRule.matches(
    manufacturer: String?,
    brand: String?,
    sdk: Int,
    propReader: (String) -> String = ::readSystemProperty,
): Boolean {
    val m = match ?: return false
    if (sdk < m.apiMin || sdk > m.apiMax) return false
    val mf = manufacturer.orEmpty().lowercase()
    val bd = brand.orEmpty().lowercase()
    if (m.manufacturer.orEmpty().filterNotNull().any { it.lowercase() == mf }) return true
    if (m.brand.orEmpty().filterNotNull().any { it.lowercase() == bd }) return true
    return m.props.orEmpty().filterNotNull().any {
        runCatching { propReader(it).isNotBlank() }.getOrDefault(false)
    }
}

/** 按顺序取第一条命中：越具体的规则写在越前面（`stock-android` 这类兜底放最后）。 */
internal fun List<RomRule>.matchFor(
    manufacturer: String?,
    brand: String?,
    sdk: Int,
    propReader: (String) -> String = ::readSystemProperty,
): RomRule? = firstOrNull { it.matches(manufacturer, brand, sdk, propReader) }

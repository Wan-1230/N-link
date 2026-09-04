package com.nikonlink.app.shared.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.nikonlink.app.shared.common.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 检查更新的失败类型：message 直接给用户看，offerReleasePage 决定是否附带「前往发布页」 */
enum class FailReason(val message: String, val offerReleasePage: Boolean) {
    /** 无网络 / 超时 / 连接失败 */
    NETWORK("无法连接服务器，请检查网络后重试", true),

    /** GitHub 未认证限流（403 / 429，60 次每小时每 IP） */
    RATE_LIMIT("检查过于频繁，请稍后再试", true),

    /** 仓库还没有任何 release（404） */
    NOT_FOUND("暂无发布版本", false),

    /** 服务端 5xx */
    SERVER("服务器异常，请稍后再试", true),

    /** 响应不是预期 JSON */
    PARSE("检查更新失败，请稍后再试", true)
}

/**
 * 夸克网盘下载链接（PRD「夸克网盘更新通道」§3：每版本新分享）。
 * [url]/[code] 来自 Release body 固定标记（发版流程写入），[versionLabel] 为当时的版本名。
 */
data class QuarkLink(
    val url: String,
    val code: String?,
    val versionLabel: String?
)

/** 检查结果：`Failed` 一律代表「没查出来」，调用方不得据此提示有更新 */
sealed class UpdateResult {
    /** 已是最新（含：latest 是 prerelease/draft 且未开启预览） */
    object UpToDate : UpdateResult()

    /**
     * 发现新版本。
     * [versionUnknown] = true 表示 tag 不是语义化版本（如 `release-2026`），
     * 无法自动判断，按 PRD S2-4 保守提示并引导跳网页由用户自行判断。
     * [publishedAtLabel] = 发布日期（yyyy-MM-dd，解析失败为 null，F6 更新日志展示用）
     * [releaseUrl] = Release 网页（F6「查看完整日志」入口；无网页链接时为 null）
     */
    data class Available(
        val versionLabel: String,
        val notes: String,
        val url: String,
        val versionUnknown: Boolean = false,
        val publishedAtLabel: String? = null,
        val releaseUrl: String? = null,
        /** 夸克网盘下载链接（Release body 固定标记解析结果，无标记为 null） */
        val quarkUrl: String? = null,
        /** 夸克提取码（与 [quarkUrl] 同源，无标记为 null） */
        val quarkCode: String? = null
    ) : UpdateResult()

    data class Failed(val reason: FailReason) : UpdateResult()
}

/**
 * 检查更新（PRD 4.4 S1/S3/S5）
 *
 * - 数据源：GitHub Releases latest 接口，只读公开仓库，无需 token
 * - 全程 [Dispatchers.IO]，连接/读取各 8s 超时，UI 不阻塞
 * - 任一失败路径都返回 [UpdateResult.Failed]，**绝不误报新版本**
 * - 所有行为写 AppEventLogger（phase = update_check），可在「导出日志」中回溯
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLogger: AppEventLogger
) {

    companion object {
        private const val TAG = "UpdateChecker"

        /** 接口地址集中管理：仓库改名/API 变更只改这一处 */
        const val RELEASES_API_URL = "https://api.github.com/repos/Wan-1230/N-link/releases/latest"

        /** 兜底地址：不依赖 API，任何失败路径都保证用户有手动获取更新的通路 */
        const val RELEASES_PAGE_URL = "https://github.com/Wan-1230/N-link/releases"

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000

        /** 更新说明最长展示长度（PRD AC-1：body 前 500 字） */
        private const val NOTES_MAX_LEN = 500

        /**
         * Release body 中夸克网盘标记的解析规则（发版流程按固定格式写入）：
         * `夸克网盘：https://pan.quark.cn/s/xxxx 提取码：xxxx`
         * 解析不到 / 格式变化都返回 null，不影响主流程。
         */
        private val QUARK_URL_REGEX = Regex("https?://pan\\.quark\\.cn/s/[A-Za-z0-9]+")
        private val QUARK_CODE_REGEX = Regex("提取码[:：\\s]*([A-Za-z0-9]{4})")

        /** 夸克链接缓存：GitHub 不可达时设置页夸克入口的离线兜底 */
        private const val QUARK_PREFS = "update_channel"
        private const val QUARK_KEY_URL = "quark_url"
        private const val QUARK_KEY_CODE = "quark_code"
        private const val QUARK_KEY_VERSION = "quark_version"
    }

    private val gson = Gson()

    /**
     * 检查最新版本。
     * @param currentVersion 本地版本号（BuildConfig.VERSION_NAME，可为 `0.1.2-debug`）
     *
     * @param includePreRelease 是否把 prerelease/draft 视为可更新版本。
     *
     * **恒为 false 是有意设计，不是漏接开关**（2026-08-31 由用户拍板）：
     * 普通用户只应收到正式版提示，beta 之类的预发布不打扰他们。
     * PRD 的 AC-11 原文为「默认不提示；打开设置开关后提示」，后半句明确不做，
     * 所以本参数没有任何调用方传入 —— 保留形参是为了将来若要做内测分发，
     * 接上一个开关即可，不必动这里的过滤逻辑。
     *
     * 注意 GitHub 的 `/releases/latest` **会把 prerelease 一并算入**，
     * 因此这一层过滤必须在客户端做，不能指望接口。
     */
    suspend fun check(currentVersion: String, includePreRelease: Boolean = false): UpdateResult =
        withContext(Dispatchers.IO) {
            eventLogger.event("update_check", "action" to "start", "current" to currentVersion)
            val startedAt = System.currentTimeMillis()

            val result = runCatching {
                // 无网络直接短路，避免等满 8s 超时（AC-4 要求 800ms 内给出反馈）
                if (!hasNetwork()) return@runCatching UpdateResult.Failed(FailReason.NETWORK)

                val raw = requestLatestJson()
                    ?: return@runCatching UpdateResult.Failed(FailReason.PARSE)

                val release = parseRelease(raw)
                    ?: return@runCatching UpdateResult.Failed(FailReason.PARSE)

                if ((release.prerelease || release.draft) && !includePreRelease) {
                    eventLogger.event(
                        "update_check",
                        "action" to "skip_unstable",
                        "tag" to release.tagName,
                        "prerelease" to release.prerelease,
                        "draft" to release.draft
                    )
                    return@runCatching UpdateResult.UpToDate
                }
                evaluate(release, currentVersion)
            }.getOrElse { e ->
                Timber.tag(TAG).w(e, "Check update failed")
                UpdateResult.Failed(e.toFailReason())
            }

            val cost = System.currentTimeMillis() - startedAt
            when (result) {
                is UpdateResult.Available -> eventLogger.event(
                    "update_check",
                    "action" to "available",
                    "tag" to result.versionLabel,
                    "unknown" to result.versionUnknown,
                    "cost_ms" to cost
                )

                UpdateResult.UpToDate -> eventLogger.event(
                    "update_check",
                    "action" to "up_to_date",
                    "current" to currentVersion,
                    "cost_ms" to cost
                )

                is UpdateResult.Failed -> eventLogger.event(
                    "update_check",
                    "action" to "failed",
                    "reason" to result.reason.name,
                    "cost_ms" to cost
                )
            }
            result
        }

    /**
     * 比对版本：远端 tag 与本地 versionName。
     * 任一侧解析失败都走保守策略（AC-1 的补充：宁可让用户自己看网页，也不能静默漏报）。
     */
    private fun evaluate(release: ReleaseInfo, currentVersion: String): UpdateResult {
        val remote = SemVer.parse(release.tagName)
        val local = SemVer.parse(currentVersion)
        val remoteTag = release.tagName?.trim().orEmpty()

        val hasUpdate = when {
            // 远端 tag 非语义化：字面量与本地不同即保守提示，相同则视为最新
            remote == null -> remoteTag.isNotEmpty() && !remoteTag.equals(currentVersion.trim(), ignoreCase = true)
            // 本地版本解析不出来：远端能解析就按「有新版」处理
            local == null -> true
            else -> remote > local
        }
        if (!hasUpdate) return UpdateResult.UpToDate

        val fullBody = release.body?.trim().orEmpty()
        val notes = fullBody.let {
            if (it.length > NOTES_MAX_LEN) it.substring(0, NOTES_MAX_LEN) + "…" else it
        }
        val versionLabel = remote?.let { "v$it" } ?: remoteTag.ifEmpty { release.name ?: "新版本" }

        // 夸克通道（PRD §4.1）：从完整 body 解析固定标记，成功即落缓存供离线兜底；
        // 解析失败只意味着本版本没有网盘链接，绝不动主结果
        val quark = parseQuarkLink(fullBody)?.let { (url, code) ->
            QuarkLink(url, code, versionLabel).also(::persistQuarkCache)
        }

        return UpdateResult.Available(
            versionLabel = versionLabel,
            notes = notes,
            url = release.apkDownloadUrl() ?: release.htmlUrl ?: RELEASES_PAGE_URL,
            versionUnknown = remote == null,
            publishedAtLabel = release.publishedAt?.let(::formatPublishDate),
            releaseUrl = release.htmlUrl,
            quarkUrl = quark?.url,
            quarkCode = quark?.code
        )
    }

    /**
     * 最近一次缓存的夸克网盘链接。
     * 检查更新成功解析到标记时写入；GitHub 不可达时设置页夸克入口靠它兜底，无缓存返回 null。
     */
    fun cachedQuarkLink(): QuarkLink? = runCatching {
        val sp = context.getSharedPreferences(QUARK_PREFS, Context.MODE_PRIVATE)
        val url = sp.getString(QUARK_KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        QuarkLink(url, sp.getString(QUARK_KEY_CODE, null), sp.getString(QUARK_KEY_VERSION, null))
    }.getOrNull()

    /** Release body 固定标记 → (链接, 提取码)；无标记返回 null */
    private fun parseQuarkLink(body: String): Pair<String, String?>? {
        val url = QUARK_URL_REGEX.find(body)?.value ?: return null
        val code = QUARK_CODE_REGEX.find(body)?.groupValues?.getOrNull(1)
        return url to code
    }

    /** 夸克链接缓存落盘；缓存失败只影响兜底能力，不影响本次更新结果 */
    private fun persistQuarkCache(link: QuarkLink) {
        runCatching {
            context.getSharedPreferences(QUARK_PREFS, Context.MODE_PRIVATE).edit()
                .putString(QUARK_KEY_URL, link.url)
                .putString(QUARK_KEY_CODE, link.code)
                .putString(QUARK_KEY_VERSION, link.versionLabel)
                .apply()
        }.onFailure { e -> Timber.tag(TAG).w(e, "Persist quark cache failed") }
    }

    /** ISO8601 时间戳 → yyyy-MM-dd（F6 弹窗日期展示；解析失败返回 null 不阻塞更新流程） */
    private fun formatPublishDate(raw: String): String? = runCatching {
        val instant = java.time.Instant.parse(raw)
        java.time.LocalDate.ofInstant(instant, java.time.ZoneId.systemDefault()).toString()
    }.getOrNull()

    /** 发起请求，返回响应体；非 200 抛 [UpdateHttpException]，读不到正文返回 null */
    private fun requestLatestJson(): String? {
        val conn = (URL(RELEASES_API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw UpdateHttpException(code)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** JSON → ReleaseInfo；结构不符预期一律按解析失败处理 */
    private fun parseRelease(raw: String): ReleaseInfo? = runCatching {
        gson.fromJson(raw, ReleaseInfo::class.java)?.takeIf { !it.tagName.isNullOrBlank() }
    }.onFailure { e ->
        if (e !is JsonSyntaxException) Timber.tag(TAG).w(e, "Parse release failed")
        else Timber.tag(TAG).w("Release json syntax error: ${e.message}")
    }.getOrNull()

    /** 网络可用性探测：探测本身失败不阻断请求，交由异常路径处理 */
    private fun hasNetwork(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    private fun Throwable.toFailReason(): FailReason = when (this) {
        is UpdateHttpException -> when {
            code == 403 || code == 429 -> FailReason.RATE_LIMIT
            code == 404 -> FailReason.NOT_FOUND
            code >= 500 -> FailReason.SERVER
            else -> FailReason.PARSE
        }

        // SocketTimeoutException / UnknownHostException / 其他 IO 统一按「无法连接服务器」处理
        else -> FailReason.NETWORK
    }

    private class UpdateHttpException(val code: Int) : IOException("HTTP $code")
}

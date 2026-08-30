package com.nikonlink.app.shared.update

import com.google.gson.annotations.SerializedName

/**
 * 版本更新：GitHub Release 数据模型 + 语义化版本号解析比对
 * PRD 4.4 S1 / S2
 *
 * 数据来自 `GET /repos/Wan-1230/N-link/releases/latest`（只读公开仓库，无需 token）。
 * 字段全部可空：接口任一字段缺失都不能让解析抛异常，否则会把「接口变更」变成「App 崩溃」。
 */

/** Release 附件（只关心 APK 直链） */
data class ReleaseAsset(
    @SerializedName("name") val name: String?,
    @SerializedName("browser_download_url") val browserDownloadUrl: String?,
    @SerializedName("size") val size: Long = 0L
)

/** Release 详情 */
data class ReleaseInfo(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("html_url") val htmlUrl: String?,
    @SerializedName("assets") val assets: List<ReleaseAsset>?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("prerelease") val prerelease: Boolean = false,
    @SerializedName("draft") val draft: Boolean = false
) {
    /** 取第一个 .apk 资产的直链；没有 apk 返回 null（调用方退化为 html_url） */
    fun apkDownloadUrl(): String? = assets
        ?.firstOrNull { it.browserDownloadUrl != null && it.name?.endsWith(".apk", ignoreCase = true) == true }
        ?.browserDownloadUrl
}

/**
 * 语义化版本号：`major.minor.patch[-preRelease]`
 *
 * - 支持 `v1.2.3` / `1.2.3` / `1.2.3-debug` / `v1.2` / `1.2.3-beta.2`
 * - 缺失位补 0（`v1.2` → `1.2.0`）
 * - 预发布低于同号正式版（`1.0.0-beta` < `1.0.0`）；双方皆预发布按字典序
 * - `-debug` 是 Gradle `versionNameSuffix` 的构建后缀不是预发布标识，解析时直接剥离，
 *   否则 debug 包（0.1.2-debug）会永远低于线上 tag（v0.1.2）而误报更新
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String?
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // 同号时：正式版 > 预发布版；双方皆预发布按字典序比较标识符
        val mine = preRelease
        val yours = other.preRelease
        return when {
            mine == null && yours == null -> 0
            mine == null -> 1
            yours == null -> -1
            else -> mine.compareTo(yours)
        }
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease != null) append('-').append(preRelease)
    }

    companion object {
        /** 核心版本号：1~3 段纯数字 */
        private val CORE = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""")
        /** 构建后缀（debug 变体的 versionNameSuffix），非语义化预发布标识 */
        private val DEBUG_SUFFIX = Regex("""-debug$""", RegexOption.IGNORE_CASE)

        /**
         * 解析版本号，失败返回 null（如 tag 写成 `release-2026`）。
         * 返回 null 时调用方不得判定为「有更新」，PRD S2-4 走保守提示。
         */
        fun parse(raw: String?): SemVer? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            // 1) 去 v/V 前缀
            var s = if (text[0] == 'v' || text[0] == 'V') text.substring(1) else text
            // 2) 剥掉 -debug 构建后缀
            s = s.replace(DEBUG_SUFFIX, "")
            // 3) 第一个 '-' 之前是核心版本号，之后是预发布标识
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1).trim().ifEmpty { null } else null

            val m = CORE.matchEntire(core) ?: return null
            return SemVer(
                major = m.groupValues[1].toIntOrNull() ?: return null,
                minor = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
                patch = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0,
                preRelease = pre
            )
        }

        private fun compareValues(a: Int, b: Int): Int = when {
            a > b -> 1
            a < b -> -1
            else -> 0
        }
    }
}

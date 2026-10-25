package com.nikonlink.app.device.model

/**
 * 相机固件比对清单（离线）。
 *
 * 为什么不做"自动查官网"：尼康下载中心没有公开的按机型查询 API，抓 HTML 既脆弱
 * 又会在页面改版时静默给出错误结论。这里用一份**带核对日期**的内置快照做比对，
 * UI 必须把它表述为"清单记录"，并始终提供官方下载中心入口；不把快照包装成
 * "官方最新版"。
 *
 * 固件版本数据核对日期：2026-10-25。以尼康下载中心为准。
 * N-Link 只做比对与跳转，不下载、不刷写固件。
 */
object FirmwareCatalog {

    const val CHECKED_AT = "2026-10-25"
    const val OFFICIAL_URL = "https://downloadcenter.nikonimglib.com/zh-cn/index.html"
    const val NOTE = "内置清单核对于 $CHECKED_AT，仅供比对；请以尼康官方下载中心为准"

    data class Entry(
        val modelKey: String,
        val displayName: String,
        val latestVersion: String,
        val releasedOn: String
    )

    sealed interface Result {
        val currentVersion: String
        val officialUrl: String

        data class UpdateAvailable(
            override val currentVersion: String,
            val entry: Entry
        ) : Result {
            override val officialUrl: String get() = OFFICIAL_URL
        }

        data class NotOlderThanCatalog(
            override val currentVersion: String,
            val entry: Entry
        ) : Result {
            override val officialUrl: String get() = OFFICIAL_URL
        }

        data class UnknownModel(override val currentVersion: String) : Result {
            override val officialUrl: String get() = OFFICIAL_URL
        }

        data class NoVersion(override val currentVersion: String = "") : Result {
            override val officialUrl: String get() = OFFICIAL_URL
        }
    }

    private val entries = listOf(
        Entry("Z6III", "Z6III", "2.01", "2026-10-14"),
        Entry("Z8", "Z8", "3.11", "2026-09-16"),
        Entry("Z5II", "Z5II", "1.20", "2026-08-18"),
        Entry("Z50II", "Z50II", "1.11", "2026-07-01"),
        Entry("ZFC", "Zfc", "1.81", "2026-05-14"),
        Entry("Z9", "Z9", "5.32", "2026-03-17"),
        Entry("ZF", "Zf", "3.01", "2026-03-17"),
        Entry("ZR", "ZR", "1.11", "2026-03-17"),
        Entry("Z5", "Z5", "1.50", "2025-06-03"),
        Entry("Z6II", "Z6II", "1.70", "2025-05-13"),
        Entry("Z7II", "Z7II", "1.70", "2025-05-13"),
        Entry("Z7", "Z7", "3.80", "2025-05-07"),
        Entry("Z6", "Z6", "3.80", "2025-04-23"),
        Entry("Z30", "Z30", "1.20", "2025-04-22"),
        Entry("Z50", "Z50", "2.60", "2025-04-22")
    )

    /** 机身的 PTP Model 有多种写法（NIKON Z 6II / Z6_2 / Z 6II），先归一再精确匹配。 */
    fun check(modelName: String, firmwareVersion: String): Result {
        val current = firmwareVersion.trim()
        if (current.isBlank()) return Result.NoVersion()
        val key = normalizeModel(modelName)
        val aliased = when (key) {
            "Z62" -> "Z6II"
            "Z72" -> "Z7II"
            "Z52" -> "Z5II"
            "Z502" -> "Z50II"
            "Z63" -> "Z6III"
            else -> key
        }
        val entry = entries.firstOrNull { it.modelKey == aliased }
            ?: return Result.UnknownModel(current)
        return if (compareVersions(current, entry.latestVersion) < 0) {
            Result.UpdateAvailable(current, entry)
        } else {
            Result.NotOlderThanCatalog(current, entry)
        }
    }

    fun normalizeModel(modelName: String): String =
        modelName.uppercase()
            .replace("NIKON", "")
            .replace("CORPORATION", "")
            .replace(Regex("[^A-Z0-9]"), "")

    /**
     * 语义化版本比较：忽略前缀 V/VER.，逐段按数字比；缺段按 0。
     * 返回 <0 = a 更旧，0 = 相同，>0 = a 更新。
     */
    fun compareVersions(a: String, b: String): Int {
        val left = versionParts(a)
        val right = versionParts(b)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val x = left.getOrElse(i) { 0 }
            val y = right.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> =
        version.uppercase()
            .replace(Regex("^[^0-9]*"), "")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: 0 }
}

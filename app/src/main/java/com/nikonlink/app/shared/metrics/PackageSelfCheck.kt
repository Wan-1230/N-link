package com.nikonlink.app.shared.metrics

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 「我装的到底是哪一版」自证信息（PRD v2.5 FR-08a）。
 *
 * 格式化部分是纯函数，取值由调用方（设置页）负责 —— 这样"说了什么、没说什么"能被单测钉住。
 *
 * 口径提醒：我们**发布的就是签好名的同一个文件**，所以本机 `base.apk` 的 SHA-256 通常等于
 * `version-info.txt` 里公布的那个。但两者不是一回事：本机这个是安装落地后重新读出来的，
 * 分包安装（split APK）、厂商二次打包都会让它不同。前 8 位对不上时，先问"怎么装的"，
 * 再下"包被动过"的结论。
 */
object PackageSelfCheck {

    private const val READ_BUFFER = 64 * 1024

    /** 流式算文件 SHA-256，不把 4MB 包读进内存。读不了返回 null，不抛。 */
    fun sha256(file: File): ByteArray? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(READ_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest()
    }.getOrNull()

    /** 摘要取前 [chars] 位小写十六进制，供人眼比对 */
    fun fingerprint(digest: ByteArray, chars: Int = 8): String =
        digest.joinToString("") { "%02x".format(it) }.take(chars)

    fun describe(
        versionName: String,
        versionCode: Int,
        debuggable: Boolean,
        apkBytes: Long,
        apkDigest: ByteArray?,
        installedAtMillis: Long
    ): String = buildString {
        appendLine("版本：v$versionName（versionCode $versionCode，${if (debuggable) "调试包" else "正式包"}）")
        appendLine("安装包：${formatBytes(apkBytes)}")
        appendLine(
            if (apkDigest == null) "本机包 SHA-256 读不到"
            else "本机包 SHA-256：${fingerprint(apkDigest)}…"
        )
        if (installedAtMillis > 0) {
            appendLine(
                "最近安装：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    .format(java.util.Date(installedAtMillis))
            )
        }
        append("渠道：GitHub Releases / 夸克网盘 / 百度网盘。")
        append("对得上 version-info.txt 里公布的 SHA-256，就是同一个包；")
        append("对不上先想「怎么装的」（分包、二次打包），别直接下结论说包被动过。")
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

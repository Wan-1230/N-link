package com.nikonlink.app.shared.common

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全链路事件日志（设置页可导出）。
 *
 * - 结构化打点：event(phase="download", "handle" to 1, "chunk" to 4MB, ...)，落盘为
 *   「时间 phase key=value key=value」行，便于问题排查与回归分析。
 * - 环形文件：logs/ 下最多 3 个文件 × 512KB，滚动覆盖，避免无限增长。
 * - 崩溃落盘：installCrashHandler() 后未捕获异常写 crash 段，导出的日志可直接定位。
 */
@Singleton
class AppEventLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EventLogger"
        private const val MAX_FILES = 3
        private const val MAX_FILE_BYTES = 512 * 1024L
    }

    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
    private val writerLock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun activeFile(): File {
        return logDir.listFiles { f -> f.name.startsWith("log_") && f.extension == "log" }
            ?.maxByOrNull { it.lastModified() }
            ?: File(logDir, "log_0.log")
    }

    /** 链路打点：phase 为链路节点（connect/init/event/opensession/capture/lv/list/thumb/download/save） */
    fun event(phase: String, vararg pairs: Pair<String, Any?>) {
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ').append(phase)
            pairs.forEach { (k, v) ->
                append(' ').append(k).append('=').append(v)
            }
        }
        Timber.tag(TAG).i(line)
        synchronized(writerLock) {
            runCatching {
                var file = activeFile()
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    rotate()
                    file = activeFile()
                }
                FileWriter(file, true).use { it.appendLine(line) }
            }.onFailure { Timber.tag(TAG).w("log write failed: ${it.message}") }
        }
    }

    private fun rotate() {
        val files = logDir.listFiles { f -> f.name.startsWith("log_") } ?: return
        for (f in files.sortedByDescending { it.name }) {
            val num = f.nameWithoutExtension.substringAfter("log_").toIntOrNull() ?: return
            if (num >= MAX_FILES - 1) {
                f.delete()
            } else {
                f.renameTo(File(logDir, "log_${num + 1}.log"))
            }
        }
    }

    fun logFileList(): List<File> {
        return logDir.listFiles { f -> f.name.startsWith("log_") && f.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** 打包当日全部日志为单文件路径（供导出），失败返回 null */
    fun packLogsForExport(): File? {
        return try {
            val out = File(logDir, "n-link_logs_export.txt")
            synchronized(writerLock) {
                out.outputStream().use { stream ->
                    logFileList().sortedBy { it.name }.forEach { f ->
                        stream.write("\n===== ${f.name} =====\n".toByteArray())
                        f.inputStream().use { it.copyTo(stream) }
                    }
                }
            }
            if (out.length() > 0) out else null
        } catch (e: Exception) {
            Timber.tag(TAG).w("pack logs failed: ${e.message}")
            null
        }
    }

    /** 安装全局崩溃处理器：崩溃栈落盘到日志目录，保留系统默认行为 */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                synchronized(writerLock) {
                    FileWriter(File(logDir, "crash.log"), true).use { it.appendLine(sw.toString()) }
                }
            }
            previous?.uncaughtException(thread, throwable)
        }
        Timber.tag(TAG).i("Crash handler installed")
    }
}
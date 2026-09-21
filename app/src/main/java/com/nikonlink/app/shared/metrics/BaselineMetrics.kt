package com.nikonlink.app.shared.metrics

import com.nikonlink.app.shared.common.AppEventLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRD v2.5 FR-01：连接耗时 / 传输速率 / 监看掉帧三项基线的进程内聚合。
 *
 * 口径的唯一真源是 `docs/指标口径-v2.5.md`，本文件里的常量与之一一对应；
 * 纯采集、不改变任何行为、不上传（原始记录同时以 `metric_*` 行写入既有日志环）。
 */
@Singleton
class BaselineMetrics @Inject constructor(
    private val eventLogger: AppEventLogger
) {
    companion object {
        /** 百分位的样本上限：超出即丢弃最旧，保证内存与导出文本都有界。 */
        const val CONN_LIMIT = 200
        const val TRANSFER_LIMIT = 500

        /** 监看取帧的目标间隔：`LiveViewManager` 的帧间隔直接引用本常量，二者不会漂移。口径见文档 §3。 */
        const val LV_TARGET_INTERVAL_MS = 66L

        private const val MB = 1024.0 * 1024.0
    }

    data class ConnSample(
        val channel: String,
        val outcome: String,
        val totalMs: Long,
        val stageMs: Map<String, Long>
    )

    data class TransferSample(
        val channel: String,
        val bytes: Long,
        val elapsedMs: Long,
        val outcome: String
    )

    private val connSamples = ArrayDeque<ConnSample>()
    private val transferSamples = ArrayDeque<TransferSample>()

    // 监看计数只在取帧协程里自增，但导出在主线程读 —— 给可见性而不是给原子性
    @Volatile private var lvRounds = 0L
    @Volatile private var lvLateRounds = 0L
    @Volatile private var lvFrames = 0L
    @Volatile private var lvFailures = 0L

    @Volatile
    private var lvStartedAtMs = 0L

    fun recordConn(sample: ConnSample) {
        synchronized(connSamples) {
            connSamples.addLast(sample)
            while (connSamples.size > CONN_LIMIT) connSamples.removeFirst()
        }
        eventLogger.event(
            "metric_conn",
            "channel" to sample.channel,
            "outcome" to sample.outcome,
            "total" to sample.totalMs,
            "stages" to sample.stageMs.entries.joinToString(",") { "${it.key}=${it.value}" }
        )
    }

    fun recordTransfer(sample: TransferSample) {
        synchronized(transferSamples) {
            transferSamples.addLast(sample)
            while (transferSamples.size > TRANSFER_LIMIT) transferSamples.removeFirst()
        }
        eventLogger.event(
            "metric_transfer",
            "channel" to sample.channel,
            "bytes" to sample.bytes,
            "ms" to sample.elapsedMs,
            "mbps" to mbps(sample),
            "outcome" to sample.outcome
        )
    }

    fun lvSessionStarted() {
        if (lvStartedAtMs == 0L) lvStartedAtMs = System.currentTimeMillis()
    }

    /** 一轮取帧的耗时；[late] 由调用方在间隔控制点判定（含失败轮）。 */
    fun lvRound(elapsedMs: Long) {
        lvRounds++
        if (elapsedMs >= LV_TARGET_INTERVAL_MS) lvLateRounds++
    }

    fun lvFrameDelivered() {
        lvFrames++
    }

    fun lvFrameFailed() {
        lvFailures++
    }

    fun lvSessionEnded() {
        val started = lvStartedAtMs
        if (started <= 0L) return
        lvStartedAtMs = 0L
        val durationMs = System.currentTimeMillis() - started
        eventLogger.event(
            "metric_lv",
            "rounds" to lvRounds,
            "late" to lvLateRounds,
            "frames" to lvFrames,
            "failed" to lvFailures,
            "ms" to durationMs,
            "fps" to if (durationMs > 0) lvFrames * 1000.0 / durationMs else 0.0,
            "droprate" to lvDropRate()
        )
    }

    /** 掉帧率 = 取帧耗时 ≥ 目标间隔的轮次 / 总轮次（口径见文档 §3）。 */
    fun lvDropRate(): Double = if (lvRounds > 0) lvLateRounds * 100.0 / lvRounds else 0.0

    fun render(): String = buildString {
        appendLine("===== 指标基线（v2.5 FR-01）=====")
        appendLine("导出时刻：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("窗口：本次启动以来；连接≤$CONN_LIMIT 条、传输≤$TRANSFER_LIMIT 条，重启后百分比重置。")
        appendLine("口径：docs/指标口径-v2.5.md（p50/p95 = 最近秩法，非插值）")
        appendLine("包含：机型与 ROM 家族、通道名、阶段耗时、字节数、失败原因码")
        appendLine("不包含：文件路径与文件名、SSID/BSSID、IP/MAC、相机序列号、GPS、任何账号标识")
        appendLine()
        appendConnSection()
        appendLine()
        appendTransferSection()
        appendLine()
        appendLvSection()
    }

    private fun StringBuilder.appendConnSection() {
        val samples = synchronized(connSamples) { connSamples.toList() }
        appendLine("【连接】样本 n=${samples.size}")
        if (samples.isEmpty()) {
            appendLine("  无样本")
            return
        }
        samples.groupBy { it.channel }.forEach { (channel, list) ->
            val total = list.map { it.totalMs }
            appendLine("  $channel：总耗时 p50=${ms(total, 0.50)}ms p95=${ms(total, 0.95)}ms（n=${list.size}）")
            val stages = list.flatMap { it.stageMs.keys }.distinct()
            for (stage in stages) {
                val values = list.mapNotNull { it.stageMs[stage] }
                if (values.isNotEmpty()) {
                    appendLine("    - $stage p50=${ms(values, 0.50)}ms p95=${ms(values, 0.95)}ms")
                }
            }
        }
        appendLine("  结果分布：${distribution(samples.map { it.outcome }, samples.size)}")
    }

    private fun StringBuilder.appendTransferSection() {
        val samples = synchronized(transferSamples) { transferSamples.toList() }
        appendLine("【传输】样本 n=${samples.size}（按单对象计）")
        if (samples.isEmpty()) {
            appendLine("  无样本")
            return
        }
        samples.groupBy { it.channel }.forEach { (channel, list) ->
            val ok = list.filter { it.outcome == "ok" }
            if (ok.isNotEmpty()) {
                val speeds = ok.map { mbps(it) }
                appendLine(
                    "  $channel：MB/s p50=${fmt(percentileD(speeds, 0.50))} p95=${fmt(percentileD(speeds, 0.95))}（n=${ok.size}）" +
                        "，平均单对象 ${fmt(ok.map { it.bytes }.average() / MB)}MB"
                )
            } else {
                appendLine("  $channel：无成功样本（n=${list.size}）")
            }
        }
        appendLine("  结果分布：${distribution(samples.map { it.outcome }, samples.size)}")
    }

    private fun StringBuilder.appendLvSection() {
        appendLine("【监看】目标间隔 ${LV_TARGET_INTERVAL_MS}ms（≈${1000 / LV_TARGET_INTERVAL_MS}fps）")
        if (lvRounds == 0L) {
            appendLine("  本次启动未开启监看")
            return
        }
        appendLine("  轮次=$lvRounds 交付=$lvFrames 超轮=$lvLateRounds 失败=$lvFailures")
        appendLine("  掉帧率=${fmt(lvDropRate())}%")
    }

    private fun distribution(labels: List<String>, total: Int): String =
        labels.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            .joinToString("  ") { "${it.key}=${it.value}(${fmt(it.value * 100.0 / total)}%)" }

    private fun mbps(sample: TransferSample): Double =
        if (sample.elapsedMs > 0) sample.bytes / MB / (sample.elapsedMs / 1000.0) else 0.0

    /** 最近秩法：秩 = ceil(p·n)，第 1 个样本即 p50（口径见 docs/指标口径-v2.5.md §1）。 */
    private fun ms(values: List<Long>, p: Double): String =
        percentileD(values.map { it.toDouble() }, p)?.toLong()?.toString() ?: "—"

    private fun percentileD(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun fmt(v: Double?): String =
        v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
}

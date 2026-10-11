package com.nikonlink.app.shared.common

/**
 * 事件日志的同键去重判定（纯逻辑、无 Android 依赖，因此可以走 JVM 单测）。
 *
 * 立项起因（2026-10-11 真机导出）：一份 3132 行的日志里有 3011 行
 * （96.1%）是同一句 `data_fail op=0x1008 code=访问被拒绝…`，把连接、会话、
 * 设置变更全部冲出 3×512KB 环形缓冲 —— 「相机为什么拒绝」这一问因此无从回答。
 *
 * 规则刻意做得很窄，保证**不重复的事件一行都不会掉**：
 * - 某个键（phase + 全部 key=value）**首次出现照写**，真实时间戳保留；
 * - 只有同键在 [windowMs] 内的第 2 次及以后才并入计数；
 * - 换键 / 窗口到期 / [flush] 时，把累计值冲成一行 `<phase>_burst count= first= last=`，
 *   且**只在 count>=2 时才写**（单发事件本来已经写过一行，再补摘要就是凭空翻倍）。
 *
 * 因此非风暴场景的落盘结果与去重前逐字节一致，只有真正的刷屏被压成摘要。
 */
class LogBurstWindow(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private var pendingPhase = ""
    private var pendingBody = ""
    private var pendingCount = 0
    private var pendingFirstTs = ""
    private var pendingLastTs = ""
    private var pendingStart = 0L

    /** 当前正在累积的键重复了几次（测试与自观测用）。 */
    val pendingRepeats: Int get() = pendingCount

    /**
     * @param line 已排好版的完整正常行（含时间戳），首次出现时原样返回给调用方落盘
     * @return 本次需要写出的行（0~2 行）；空表示这次只并入计数
     */
    fun accept(phase: String, body: String, ts: String, line: String, now: Long): List<String> {
        val out = ArrayList<String>(2)
        val sameKey = pendingCount > 0 && pendingPhase == phase && pendingBody == body
        val expired = pendingCount > 0 && now - pendingStart >= windowMs
        // 换键或窗口到期都要先把上一段冲出去 —— 计数丢了就等于那次事故没发生过。
        // 到期后同键会另起一段并再写一行，于是一场持续 129 秒的刷屏落在日志上是
        // 「每 10 秒一行 + 一行摘要」，而不是 3011 行，也不是彻底看不见的一行。
        if (pendingCount > 0 && (!sameKey || expired)) drainBurst(out)
        if (pendingCount == 0) {
            pendingPhase = phase
            pendingBody = body
            pendingCount = 1
            pendingFirstTs = ts
            pendingLastTs = ts
            pendingStart = now
            out.add(line)
        } else {
            pendingCount++
            pendingLastTs = ts
        }
        return out
    }

    /** 导出/关闭前收尾：未冲刷的计数不能被丢掉，否则风暴刚停就导出等于什么都没发生。 */
    fun flush(): List<String> {
        val out = ArrayList<String>(1)
        drainBurst(out)
        return out
    }

    private fun drainBurst(out: MutableList<String>) {
        if (pendingCount >= 2) {
            out.add(
                "$pendingLastTs ${pendingPhase}_burst count=$pendingCount" +
                    " first=" + clockOf(pendingFirstTs) + " last=" + clockOf(pendingLastTs)
            )
        }
        pendingPhase = ""
        pendingBody = ""
        pendingCount = 0
        pendingFirstTs = ""
        pendingLastTs = ""
        pendingStart = 0L
    }

    /** 时间戳形如 `MM-dd HH:mm:ss.SSS`，字段值只取时分秒那半段，避免带空格打断 key=value。 */
    private fun clockOf(ts: String): String = ts.substringAfter(' ')

    companion object {
        /** 同键合并窗口：窗口内的重复只计数，不落盘 */
        const val DEFAULT_WINDOW_MS = 10_000L
    }
}

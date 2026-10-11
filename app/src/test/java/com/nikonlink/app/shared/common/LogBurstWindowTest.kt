package com.nikonlink.app.shared.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志同键去重的回归（专项 v1 §4.1）。
 *
 * 这块逻辑的性质很特殊：**掉一行日志就等于那次事故从未发生过**，
 * 而它保护的正是 2026-10-11 那种「3011 行同一句 data_fail 把 3×512KB 日志环
 * 整个冲掉、连『相机为什么拒绝』的现场一起冲走」的场景。
 * 所以既要用例证明"风暴被压住了"，也要用例证明"正常事件一行都不会少"。
 */
class LogBurstWindowTest {

    private val w = LogBurstWindow.DEFAULT_WINDOW_MS

    private fun line(ts: String, phase: String, body: String) = "$ts $phase$body"

    /** 不重复的事件必须逐行原样落盘 —— 去重不能吃掉任何一条独立证据。 */
    @Test
    fun `不重复的事件一行都不会掉，也不会多出摘要行`() {
        val win = LogBurstWindow()
        val out = mutableListOf<String>()
        for (i in 0 until 50) {
            val ts = "10-11 12:00:%02d.000".format(i % 60)
            out += win.accept(
                phase = "ptp_tx", body = " op=0x1002 tx=$i", ts = ts,
                line = line(ts, "ptp_tx", " op=0x1002 tx=$i"), now = i.toLong()
            )
        }
        out += win.flush()
        assertEquals(50, out.size)
        assertTrue("不该出现 burst 行", out.none { it.contains("_burst") })
    }

    /** 窗口内的同键重复：只有首次那一条落盘，其余并入计数。 */
    @Test
    fun `窗口内同键重复只留首行`() {
        val win = LogBurstWindow()
        val ts = "10-11 12:21:46.271"
        val first = win.accept("data_fail", " op=0x1008 code=拒绝", ts, line(ts, "data_fail", " op=0x1008 code=拒绝"), 0L)
        assertEquals(1, first.size)
        var written = 0
        for (i in 1 until 100) {
            // 全部落在同一个 10s 窗口内
            written += win.accept("data_fail", " op=0x1008 code=拒绝", ts, line(ts, "data_fail", " op=0x1008 code=拒绝"), i.toLong()).size
        }
        assertEquals("窗口内的 99 次重复一行都不该写", 0, written)
        assertEquals(100, win.pendingRepeats)
    }

    /** 换键时把上一段冲成一行摘要，count 必须等于真实次数——计数丢了就等于事故没发生过。 */
    @Test
    fun `换键后补出 burst 且计数准确`() {
        val win = LogBurstWindow()
        val ts = "10-11 12:23:55.708"
        repeat(3011) {
            win.accept("data_fail", " op=0x1008 code=拒绝", ts, line(ts, "data_fail", " op=0x1008 code=拒绝"), 0L)
        }
        val flushed = win.accept("conn_result", " stage=READY", "10-11 12:24:00.000", line("10-11 12:24:00.000", "conn_result", " stage=READY"), w + 1)
        // 先冲上一段的摘要，再写这一条本身 —— 顺序反了会让 burst 排在它统计的事件后面
        assertEquals(2, flushed.size)
        assertTrue("第二条才是本次事件本身: $flushed", flushed[1].startsWith("10-11 12:24:00.000 conn_result"))
        val burst = flushed[0]
        assertTrue("第一条应是上一段的摘要: $burst", burst.contains("data_fail_burst"))
        assertTrue("burst 必须带上真实次数: $burst", burst.contains("count=3011"))
        assertTrue("first/last 不能带空格（会打断 key=value 解析）: $burst",
            burst.substringAfter("first=").substringBefore(" last=").contains(" ").not())
    }

    /** 持续风暴按窗口切段：3011 次跨 129 秒 ≈ 13 段，每段一行正文 + 一行摘要。 */
    @Test
    fun `长风暴被压成每窗口两行而不是三千行`() {
        val win = LogBurstWindow()
        var written = 0
        val steps = 3011
        val stepMs = 129_000L / steps
        for (i in 0 until steps) {
            val now = i * stepMs
            val ts = "10-11 12:2%d:%02d.000".format(i % 4, (now / 1000) % 60)
            written += win.accept("data_fail", " op=0x1008 code=拒绝", ts, line(ts, "data_fail", " op=0x1008 code=拒绝"), now).size
        }
        written += win.flush().size
        // 129s / 10s ≈ 13 段，每段 1 正文 + 1 摘要；末段可能没有摘要
        assertTrue("落盘行数应远小于事件数，实测 $written", written in 1..40)
    }

    /** 导出前收尾：只出现过一次的事件不该补摘要，两次以上必须补出来。 */
    @Test
    fun `flush 只在攒到两次以上才补摘要`() {
        val single = LogBurstWindow()
        val ts = "10-11 12:00:00.000"
        single.accept("lv_stop", " reason=max_errors", ts, line(ts, "lv_stop", " reason=max_errors"), 0L)
        assertTrue("单发事件不该有摘要", single.flush().isEmpty())

        val doubled = LogBurstWindow()
        doubled.accept("lv_stop", " reason=max_errors", ts, line(ts, "lv_stop", " reason=max_errors"), 0L)
        doubled.accept("lv_stop", " reason=max_errors", "10-11 12:00:01.000", line("10-11 12:00:01.000", "lv_stop", " reason=max_errors"), 1000L)
        val bursts = doubled.flush()
        assertEquals(1, bursts.size)
        assertTrue(bursts[0].contains("lv_stop_burst count=2"))
        assertTrue("摘要要用最后一次的时间戳", bursts[0].startsWith("10-11 12:00:01.000"))
        assertTrue("flush 之后状态要复位", doubled.flush().isEmpty())
    }

    /** 同 phase 不同内容（不同句柄/不同码）是两条不同的证据，不许合并。 */
    @Test
    fun `同 phase 不同 body 不合并`() {
        val win = LogBurstWindow()
        var written = 0
        for (handle in 0 until 30) {
            val ts = "10-11 12:00:00.000"
            written += win.accept("download_fail", " handle=$handle reason=timeout", ts,
                line(ts, "download_fail", " handle=$handle reason=timeout"), 0L).size
        }
        assertEquals("每条都是独立证据", 30, written)
    }
}

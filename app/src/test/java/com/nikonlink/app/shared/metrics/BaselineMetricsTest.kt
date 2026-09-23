package com.nikonlink.app.shared.metrics

import com.nikonlink.app.shared.common.AppEventLogger
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineMetricsTest {

    private fun metrics(): BaselineMetrics = BaselineMetrics(mockk<AppEventLogger>(relaxed = true))

    private fun conn(channel: String = "WiFi/AP", total: Long, outcome: String = "ok") =
        BaselineMetrics.ConnSample(channel, outcome, total, mapOf("TCP" to total / 2))

    @Test
    fun `p50 与 p95 用最近秩法，不做插值`() {
        val m = metrics()
        (1L..20L).forEach { m.recordConn(conn(total = it * 100)) }
        val text = m.render()
        // 秩 = ceil(p·n)：p50 → 第 10 个 = 1000ms，p95 → 第 19 个 = 1900ms
        assertTrue("实际输出：\n$text", text.contains("总耗时 p50=1000ms p95=1900ms"))
    }

    @Test
    fun `连接样本超出窗口后丢弃最旧`() {
        val m = metrics()
        repeat(BaselineMetrics.CONN_LIMIT + 25) { m.recordConn(conn(total = it.toLong())) }
        assertTrue(m.render().contains("【连接】样本 n=${BaselineMetrics.CONN_LIMIT}"))
    }

    @Test
    fun `按通道分组统计，不混在一起`() {
        val m = metrics()
        repeat(5) { m.recordConn(conn(channel = "WiFi/AP", total = 1000)) }
        repeat(5) { m.recordConn(conn(channel = "USB", total = 300)) }
        val text = m.render()
        assertTrue(text.contains("WiFi/AP：总耗时 p50=1000ms"))
        assertTrue(text.contains("USB：总耗时 p50=300ms"))
    }

    @Test
    fun `速率按单对象的字节与耗时算 MB 每秒`() {
        val m = metrics()
        val oneMeg = 1024L * 1024L
        m.recordTransfer(BaselineMetrics.TransferSample("WiFi", oneMeg, 500L, "ok"))
        val text = m.render()
        assertTrue("实际输出：\n$text", text.contains("MB/s p50=2.0"))
        assertTrue(text.contains("平均单对象 1.0MB"))
    }

    @Test
    fun `失败样本不进速率，只进结果分布`() {
        val m = metrics()
        m.recordTransfer(BaselineMetrics.TransferSample("WiFi", 0L, 1200L, "incomplete"))
        val text = m.render()
        assertTrue(text.contains("无成功样本"))
        assertTrue(text.contains("incomplete=1(100.0%)"))
    }

    @Test
    fun `掉帧率按超轮次数比总轮次`() {
        val m = metrics()
        m.lvSessionStarted()
        m.lvRound(BaselineMetrics.LV_TARGET_INTERVAL_MS - 1)
        m.lvRound(BaselineMetrics.LV_TARGET_INTERVAL_MS)
        m.lvRound(BaselineMetrics.LV_TARGET_INTERVAL_MS + 40)
        m.lvRound(10)
        assertEquals(50.0, m.lvDropRate(), 0.001)
        assertTrue(m.render().contains("轮次=4 交付=0 超轮=2 失败=0"))
    }

    @Test
    fun `没有样本时导出文本明确说无，不编数字`() {
        val text = metrics().render()
        assertTrue(text.contains("【连接】样本 n=0"))
        assertTrue(text.contains("无样本"))
        assertTrue(text.contains("本次启动未开启监看"))
    }

    @Test
    fun `导出文本给出口径出处并把字段范围交给统一声明`() {
        val text = metrics().render()
        assertTrue(text.contains("docs/指标口径-v2.5.md"))
        assertTrue(text.contains("字段范围：见本文件开头的「导出内容声明」"))
        // 字段清单只能有一处真源（FR-08d），这里不得再抄一份
        assertFalse("指标块不应重复声明字段范围", text.contains("不包含："))
    }

    @Test
    fun `百分位在空集合上返回横而不是崩溃`() {
        val text = metrics().apply {
            recordTransfer(BaselineMetrics.TransferSample("USB", 10L, 0L, "ok"))
        }.render()
        // elapsed=0 的样本按 0 速率计入，不得抛异常
        assertTrue(text.contains("MB/s p50=0.0"))
    }
}

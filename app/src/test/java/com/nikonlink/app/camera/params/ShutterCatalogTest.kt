package com.nikonlink.app.camera.params

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 快门档位表回归测试。
 *
 * 背景：旧版用 `10000 / raw` 反算显示名，而机身档位是"1/3 档取整后的干净值"
 * （1/160 → raw 60，而非 62.5），反算会得出机身根本不存在的 `1/167`。
 * 本测试锁住"显示名由档位表给出"这一约定，防止再退化。
 */
class ShutterCatalogTest {

    // ---------- 档位显示名 ----------

    @Test
    fun `档位名不再出现机身没有的 1斜杠167`() {
        // raw 60 是机身真实的 1/160 档（0.006s），旧版显示成 1/167
        assertEquals("1/160", ShutterCatalog.format(60))
        ShutterCatalog.STOPS.forEach { stop ->
            assertNotContains(stop.label, "167")
        }
    }

    @Test
    fun `其余标称档位显示正确`() {
        mapOf(
            2 to "1/4000",
            3 to "1/3200",
            8 to "1/1250",
            10 to "1/1000",
            13 to "1/800",
            15 to "1/640",
            30 to "1/320",
            40 to "1/250",
            160 to "1/60",
            320 to "1/30",
            640 to "1/15",
            800 to "1/13",
            4000 to "1/2.5"
        ).forEach { (raw, label) ->
            assertEquals("raw=$raw", label, ShutterCatalog.format(raw))
        }
    }

    @Test
    fun `长曝光仍按秒显示`() {
        // 1 秒为界：<1s 仍显示分数（0.5s → 1/2，与 1/2.5 同族），≥1s 才显示秒数
        assertEquals("1/2", ShutterCatalog.format(5000))
        assertEquals("1s", ShutterCatalog.format(10000))
        assertEquals("1.3s", ShutterCatalog.format(13000))
        assertEquals("30s", ShutterCatalog.format(300000))
    }

    @Test
    fun `表外 raw 走数值兜底`() {
        // 机身回读到档位表外的值时仍需给出可读文本，而不是 "--"
        assertEquals("1/100", ShutterCatalog.format(100))
        assertEquals("1/2000", ShutterCatalog.format(5))
        assertNull(ShutterCatalog.matchByLabel("1/9999"))
    }

    @Test
    fun `档位表严格升序且无重复`() {
        val values = ShutterCatalog.VALUES
        values.forEachIndexed { i, v ->
            if (i > 0) assert(v > values[i - 1]) { "档位表在 index=$i 处未严格升序: $v" }
        }
        assertEquals(values.size, values.distinct().size)
    }

    @Test
    fun `最快档为 1斜杠4000 最慢为 30s`() {
        assertEquals("1/4000", ShutterCatalog.format(ShutterCatalog.VALUES.first()))
        assertEquals("30s", ShutterCatalog.format(ShutterCatalog.VALUES.last()))
    }

    // ---------- 手动输入解析 ----------

    @Test
    fun `解析分数与裸整数写法`() {
        assertEquals(0.004, ShutterCatalog.parseSeconds("1/250")!!, 1e-9)
        assertEquals(0.004, ShutterCatalog.parseSeconds("250")!!, 1e-9)
        assertEquals(0.00025, ShutterCatalog.parseSeconds("1/4000")!!, 1e-9)
        assertEquals(0.00025, ShutterCatalog.parseSeconds("4000")!!, 1e-9)
    }

    @Test
    fun `解析秒数与带 s 后缀`() {
        assertEquals(0.6, ShutterCatalog.parseSeconds("0.6")!!, 1e-9)
        assertEquals(2.5, ShutterCatalog.parseSeconds("2.5")!!, 1e-9)
        assertEquals(30.0, ShutterCatalog.parseSeconds("30")!!, 1e-9)
        assertEquals(30.0, ShutterCatalog.parseSeconds("30s")!!, 1e-9)
        assertEquals(1.3, ShutterCatalog.parseSeconds("1.3s")!!, 1e-9)
    }

    @Test
    fun `非法输入一律返回 null`() {
        listOf("", "  ", "abc", "1/", "/250", "1/0", "0", "-5", "1/9000", "60s", "1//2")
            .forEach { input ->
                assertNull("应当判非法: '$input'", ShutterCatalog.parseSeconds(input))
            }
    }

    // ---------- 就近匹配 ----------

    @Test
    fun `标称名精确命中优先于距离匹配`() {
        // 1/4000 的精确 raw 是 2.5，整数表只能存 2；
        // 纯距离匹配会落到 raw 3（1/3200），必须靠名字命中
        assertEquals(2, ShutterCatalog.matchByLabel("1/4000"))
        assertEquals(2, ShutterCatalog.matchByLabel("1 / 4000"))
        assertEquals(60, ShutterCatalog.matchByLabel("1/160"))
        assertEquals(3, ShutterCatalog.matchByLabel("1/3200"))
    }

    @Test
    fun `档位表外的输入切到最接近档`() {
        // 1/300 → 最接近 1/320（raw 30）
        assertEquals(30, ShutterCatalog.matchNearest(1.0 / 300.0))
        // 1/90 → 介于 1/100(raw 100) 与 1/80(raw 125) 之间，取更近的 1/100
        assertEquals(100, ShutterCatalog.matchNearest(1.0 / 90.0))
        // 45s 超出 30s 上限 → clamp 行为由调用方保证，这里只验最慢档
        assertEquals(300000, ShutterCatalog.matchNearest(30.0))
    }

    private fun assertNotContains(actual: String, forbidden: String) {
        assert(!actual.contains(forbidden)) { "'$actual' 不应包含 '$forbidden'" }
    }
}

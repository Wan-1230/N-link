package com.nikonlink.app.device.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v2.2.1（PRD §6.2）逐 ROM 兼容矩阵的纯规则测试。
 *
 * 只测不依赖 Android framework 的部分：解析、匹配、组件写法。
 * `resolveActivity()` 与真实深链跳转需要设备，列在 PRD §12 的待实机清单里。
 */
class CompatRulesTest {

    private val noProps: (String) -> String = { "" }

    @Test
    fun `按厂商、品牌与系统属性三种旁证命中对应规则`() {
        val rules = CompatRules.parse(assetText())

        assertEquals("xiaomi-hyperos", rules.matchFor("Xiaomi", "Redmi", 36, noProps)?.id)
        assertEquals("xiaomi-hyperos", rules.matchFor("oem", "poco", 34, noProps)?.id)
        assertEquals("vivo", rules.matchFor("vivo", "iQOO", 35, noProps)?.id)
        assertEquals("samsung", rules.matchFor("SAMSUNG", "samsung", 34, noProps)?.id)
        // 品牌名不认识的机型靠系统属性旁证（ColorOS 机器的 brand 未必就是 oppo/oneplus/realme）
        assertEquals(
            "oppo-family",
            rules.matchFor("OEM", "nothing", 34) { name ->
                if (name == "ro.build.version.opporom") "V15" else ""
            }?.id
        )
        // 原生/近原生兜底条必须排在最后：它按厂商列表命中，写前面会把真 ROM 抢掉
        assertEquals("stock-android", rules.matchFor("google", "Pixel", 36, noProps)?.id)
        assertNull(rules.matchFor("Unknown OEM", "whatever", 34, noProps)?.id)
    }

    @Test
    fun `低于 apiMin 的设备不命中，避免给范围外机型出主意`() {
        val rules = CompatRules.parse(assetText())
        assertNull(rules.matchFor("xiaomi", "xiaomi", 28, noProps))
    }

    @Test
    fun `一条规则写坏只丢那一条，整表语法坏才整体作废`() {
        val broken = "{\"rules\":[" +
            "{\"id\":\"\",\"tricks\":[]}," +
            "{\"id\":\"ok-rule\",\"match\":{\"brand\":[\"nokia\"]},\"tricks\":[]}" +
            "]}"
        assertEquals(listOf("ok-rule"), CompatRules.parse(broken).map { it.id })

        // 截断 / 根本不是 JSON：预检不能崩，也不能崩在 lazy 上
        assertTrue(CompatRules.parse("{\"rules\":[{\"id\":\"oops\",").isEmpty())
        assertTrue(CompatRules.parse("not json at all").isEmpty())
    }

    @Test
    fun `表里每条规则都可解析：有匹配条件、组件写法为 pkg-class、日期为 ISO`() {
        val rules = CompatRules.parse(assetText())
        assertTrue("表不该是空的", rules.size >= 5)
        rules.forEach { rule ->
            val match = rule.match
            if (match == null) {
                fail("${rule.id} 必须有 match，否则这条规则永远命中不了")
                return@forEach
            }
            val hasSignal = !match.manufacturer.isNullOrEmpty() ||
                !match.brand.isNullOrEmpty() || !match.props.isNullOrEmpty()
            assertTrue("${rule.id} 至少要有一种命中旁证", hasSignal)
            assertTrue("${rule.id} 的 killRating 越界", rule.killRating in 0..5)
            rule.tricks.orEmpty().forEach { trick ->
                assertTrue("${rule.id} 有无 id 的条目", !trick.id.isNullOrBlank())
                assertTrue("${rule.id}/${trick.id} 没有文案", !trick.text.isNullOrBlank())
                trick.component?.let {
                    val parts = it.split('/')
                    assertEquals("${rule.id}/${trick.id} 组件应为 pkg/class：$it", 2, parts.size)
                    assertTrue("${rule.id}/${trick.id} 包名为空", parts[0].isNotBlank())
                    assertTrue("${rule.id}/${trick.id} 类名为空", parts[1].isNotBlank())
                }
                trick.lastVerified?.let {
                    assertTrue("${rule.id}/${trick.id} 日期不是 yyyy-MM-dd：$it", DATE.matches(it))
                }
            }
        }
    }

    /**
     * `otg` 条目的覆盖面必须与 `RomDetector.otgHint()` 一致。
     *
     * 不是洁癖：预检里「USB 总线为空」算不算硬阻断，判断依据就是这台机器有没有
     * OTG 独立开关这条提示。表里多写一家，就多一批本来该继续重试却被拦下的用户。
     */
    @Test
    fun `OTG 引导的覆盖面与代码内枚举一致`() {
        val rules = CompatRules.parse(assetText())
        assertEquals(
            setOf("xiaomi-hyperos", "vivo", "oppo-family"),
            rules.filter { r -> r.tricks.orEmpty().any { it.id == "otg" } }.map { it.id }.toSet()
        )
    }

    @Test
    fun `查杀严厉的 ROM 必须给出至少一条引导，否则提醒那一行会静默消失`() {
        val rules = CompatRules.parse(assetText())
        rules.filter { it.killRating >= 3 }.forEach { rule ->
            assertTrue("${rule.id} killRating=${rule.killRating} 却没有任何条目", rule.tricks.orEmpty().isNotEmpty())
        }
    }

    private companion object {
        val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

        fun assetText(): String {
            // Gradle 跑单测时工作目录是 app/，IDE 直接跑时通常是仓库根
            val file = listOf(
                "src/main/assets/compat/rom_rules.json",
                "app/src/main/assets/compat/rom_rules.json"
            ).map(::File).firstOrNull { it.isFile }
                ?: error("找不到 rom_rules.json —— 测试与资产必须一起改")
            return file.readText()
        }
    }
}

package com.nikonlink.app.camera.params

import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/**
 * 真机字节回归（PRD v2.4 §六）。
 *
 * 合成测试钉的是**结构**对不对，这一层钉的是**你自己机身的字节**能不能读出正确数字 ——
 * 两者不可互替：R0（MakerNote 其实在 Exif 子 IFD、不在 IFD0）只有真实文件能暴露，
 * 而当初的合成夹具因为和实现犯同一个错，让这个 bug 在绿色测试下活了很久。
 *
 * 夹具与期望值由 `nikon_fixtures/manifest.txt` 声明；清单为空时整组跳过（不是通过）。
 */
class NikonRealFixtureTest {

    private data class Entry(val resource: String, val expected: Int, val note: String)

    private fun read(path: String): ByteArray? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

    private fun manifest(): List<Entry> =
        read("nikon_fixtures/manifest.txt")?.toString(Charsets.UTF_8).orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(",")
                val expected = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                Entry(parts[0].trim(), expected, parts.getOrNull(2)?.trim().orEmpty())
            }
            .toList()

    @Test
    fun `real camera bytes parse to the independently measured count`() {
        val entries = manifest()
        Assume.assumeTrue(
            "无真机夹具：按 app/src/test/resources/nikon_fixtures/manifest.txt 顶部说明投放",
            entries.isNotEmpty()
        )
        for (entry in entries) {
            val bytes = read("nikon_fixtures/${entry.resource}")
            Assume.assumeTrue("清单声明但文件缺失：${entry.resource}", bytes != null)
            val reading = NikonShutterCountParser.parse(bytes!!)
            assertEquals(
                "${entry.resource}${if (entry.note.isBlank()) "" else " (${entry.note})"}",
                entry.expected,
                reading?.shutterCount
            )
        }
    }
}

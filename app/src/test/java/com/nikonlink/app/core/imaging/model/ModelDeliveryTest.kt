package com.nikonlink.app.core.imaging.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模型清单解析与 SHA-256 校验单元测试（PRD 8.5）
 */
class ModelDeliveryTest {

    private val sampleManifest = """
        {
          "models": [
            {
              "id": "enhance_iadapt_lut",
              "capability": "auto_enhance",
              "display_name": "一键增强",
              "version": 1,
              "url": "https://models.nikonlink.example/enhance.tflite",
              "sha256": "abc123",
              "size_bytes": 4194304,
              "tier": "builtin"
            },
            {
              "id": "denoise_nafnet_w64",
              "capability": "denoise",
              "display_name": "降噪",
              "version": 2,
              "url": "https://models.nikonlink.example/denoise.tflite",
              "sha256": "def456",
              "size_bytes": 29360128,
              "tier": "on_demand"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析合法清单`() {
        val specs = ModelManifestParser.parse(sampleManifest)
        assertEquals(2, specs.size)
        assertEquals("auto_enhance", specs[0].capability)
        assertEquals(ModelTier.BUILTIN, specs[0].tier)
        assertEquals(ModelTier.ON_DEMAND, specs[1].tier)
        assertEquals(2, specs[1].version)
        assertEquals(29360128L, specs[1].sizeBytes)
    }

    @Test
    fun `非法 JSON 返回空清单不崩溃`() {
        assertTrue(ModelManifestParser.parse("not json").isEmpty())
        assertTrue(ModelManifestParser.parse("").isEmpty())
        assertTrue(ModelManifestParser.parse("{}").isEmpty())
    }

    @Test
    fun `非法条目被过滤`() {
        val json = """
            {"models": [
              {"id": "", "capability": "x", "version": 1, "url": "", "sha256": "", "size_bytes": 0, "tier": "builtin"},
              {"id": "ok", "capability": "denoise", "version": 0, "url": "", "sha256": "", "size_bytes": 0, "tier": "on_demand"},
              {"id": "valid", "capability": "dehaze", "version": 1, "url": "u", "sha256": "s", "size_bytes": 1, "tier": "on_demand"}
            ]}
        """.trimIndent()
        val specs = ModelManifestParser.parse(json)
        assertEquals(1, specs.size)
        assertEquals("valid", specs[0].id)
    }

    @Test
    fun `SHA-256 摘要计算正确`() {
        // "abc" 的标准 SHA-256 摘要
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelVerifier.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun `文件流式校验与内存校验一致且校验失败可识别`() {
        val temp = File.createTempFile("model_test", ".bin")
        try {
            temp.writeBytes("hello nikonlink model".toByteArray())
            val expected = ModelVerifier.sha256Hex("hello nikonlink model".toByteArray())
            assertEquals(expected, ModelVerifier.sha256Hex(temp))
            assertTrue(ModelVerifier.verify(temp, expected))
            assertTrue("大小写不敏感", ModelVerifier.verify(temp, expected.uppercase()))
            assertFalse(ModelVerifier.verify(temp, "0".repeat(64)))
        } finally {
            temp.delete()
        }
    }
}

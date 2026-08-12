package com.nikonlink.app.core.imaging.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模型交付管线插桩验收测试（PRD-AI修图 8.5 降级链）
 *
 * 覆盖：
 * - assets/models.json 清单加载（方案 D 全部 8 个能力）
 * - 内置模型 assets 缺失时状态为 NotDownloaded（不崩溃）
 * - 占位地址下载立即失败并进入降级（不发起真实网络请求）
 *
 * 运行：connectedDebugAndroidTest（需真机/模拟器）
 */
@RunWith(AndroidJUnit4::class)
class ModelRegistryInstrumentedTest {

    private fun newRegistry(): ModelRegistry {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ModelRegistry(context)
    }

    @Test
    fun 清单加载包含方案D全部能力() {
        val registry = newRegistry()
        registry.ensureInitialized()
        listOf(
            ModelCapability.AUTO_ENHANCE,
            ModelCapability.SCENE_CLASSIFY,
            ModelCapability.DENOISE,
            ModelCapability.DETAIL_RESTORE,
            ModelCapability.FACE_DETECT,
            ModelCapability.FACE_PARSE,
            ModelCapability.LOW_LIGHT,
            ModelCapability.DEHAZE
        ).forEach { cap ->
            assertNotNull("清单应包含能力: $cap", registry.specOf(cap))
        }
    }

    @Test
    fun 模型未发布时能力不可用且不崩溃() {
        val registry = newRegistry()
        registry.ensureInitialized()
        // 内置模型 assets 尚未打包、按需模型未下载 → 全部不可用（降级链前提）
        assertFalse(registry.isAvailable(ModelCapability.AUTO_ENHANCE))
        assertFalse(registry.isAvailable(ModelCapability.DENOISE))
        assertEquals(null, registry.modelFile(ModelCapability.AUTO_ENHANCE))
    }

    @Test
    fun 占位地址下载立即失败并标记Failed() {
        val registry = newRegistry()
        registry.ensureInitialized()
        val spec = registry.specOf(ModelCapability.AUTO_ENHANCE)!!
        runBlocking {
            try {
                registry.download(spec)
                fail("占位地址不应允许下载")
            } catch (e: IllegalStateException) {
                assertTrue("失败原因应说明未发布: ${e.message}", e.message!!.contains("尚未发布"))
            }
        }
        assertTrue(registry.stateOf(spec) is ModelRegistry.ModelState.Failed)
    }
}

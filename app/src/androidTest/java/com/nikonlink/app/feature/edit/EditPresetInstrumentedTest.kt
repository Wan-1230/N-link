package com.nikonlink.app.feature.edit

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nikonlink.app.core.imaging.EditParams
import com.nikonlink.app.data.local.NikonLinkDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自定义预设插桩验收测试（PRD-AI修图 4.5 P1：Room 本地存储往返）
 *
 * 运行：connectedDebugAndroidTest（需真机/模拟器）
 */
@RunWith(AndroidJUnit4::class)
class EditPresetInstrumentedTest {

    private lateinit var db: NikonLinkDatabase
    private lateinit var manager: EditPresetManager

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, NikonLinkDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = EditPresetManager(db.editPresetDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun 预设保存查询删除往返() = runBlocking {
        val params = EditParams(brightness = 12, contrast = -8, clarity = 20, vibrance = 30)
        manager.save("直出优化", params, "film", 80)

        val presets = manager.presets.first()
        assertEquals(1, presets.size)
        val preset = presets.first()
        assertEquals("直出优化", preset.name)
        assertEquals("film", preset.filterId)
        assertEquals(80, preset.filterStrength)

        // 参数反序列化还原一致（PRD 4.5 组合预设）
        val restored = EditPresetManager.parseParams(preset.paramsJson)
        assertNotNull(restored)
        assertEquals(params, restored)

        manager.delete(preset.id)
        assertTrue(manager.presets.first().isEmpty())
    }

    @Test
    fun 空名称兜底为未命名预设() = runBlocking {
        manager.save("   ", EditParams(), "original", 100)
        val preset = manager.presets.first().first()
        assertEquals("未命名预设", preset.name)
    }

    @Test
    fun 损坏的参数JSON解析返回null() {
        assertEquals(null, EditPresetManager.parseParams("not json"))
        assertEquals(null, EditPresetManager.parseParams(""))
    }
}

package com.nikonlink.app.camera.params

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义拍摄预设管理器
 *
 * PRD 2.4: 自定义预设 - 保存/加载多组参数预设（如「人像」「风光」「夜景」）(P2)
 */
@Singleton
class PresetManager @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val TAG = "PresetMgr"
        private const val PREFS_NAME = "shooting_presets"
        private const val KEY_PRESETS = "presets_json"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _presets = MutableStateFlow<List<ShootingPreset>>(emptyList())
    val presets: StateFlow<List<ShootingPreset>> = _presets.asStateFlow()

    init {
        loadPresets()
        if (_presets.value.isEmpty()) {
            initDefaultPresets()
        }
    }

    /**
     * 获取所有预设
     */
    private fun loadPresets() {
        val json = prefs.getString(KEY_PRESETS, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<ShootingPreset>>() {}.type
                _presets.value = gson.fromJson(json, type)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to load presets")
                _presets.value = emptyList()
            }
        }
    }

    private fun savePresets() {
        val json = gson.toJson(_presets.value)
        prefs.edit().putString(KEY_PRESETS, json).apply()
    }

    /**
     * 初始化默认预设
     */
    private fun initDefaultPresets() {
        _presets.value = listOf(
            ShootingPreset(
                id = "portrait",
                name = "人像",
                aperture = 180,       // f/1.8
                shutterSpeed = 200,   // 1/200s
                iso = 100,
                whiteBalance = 2,     // Auto
                focusMode = 0x8010,   // AF-S
                meteringMode = 3      // 矩阵测光
            ),
            ShootingPreset(
                id = "landscape",
                name = "风光",
                aperture = 800,       // f/8.0
                shutterSpeed = 125,   // 1/125s
                iso = 100,
                whiteBalance = 4,     // 晴天
                focusMode = 0x8010,   // AF-S
                meteringMode = 3      // 矩阵测光
            ),
            ShootingPreset(
                id = "night",
                name = "夜景",
                aperture = 280,       // f/2.8
                shutterSpeed = 10,    // 1s (10000/10000)
                iso = 3200,
                whiteBalance = 6,     // 白炽灯
                focusMode = 1,        // MF
                meteringMode = 4      // 点测光
            ),
            ShootingPreset(
                id = "sport",
                name = "运动",
                aperture = 400,       // f/4.0
                shutterSpeed = 1000,  // 1/1000s
                iso = 800,
                whiteBalance = 2,     // Auto
                focusMode = 0x8011,   // AF-C
                meteringMode = 3      // 矩阵测光
            )
        )
        savePresets()
        Timber.tag(TAG).i("Initialized ${_presets.value.size} default presets")
    }

    /**
     * 保存新预设
     */
    fun savePreset(preset: ShootingPreset) {
        val current = _presets.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == preset.id }
        if (existingIdx >= 0) {
            current[existingIdx] = preset
        } else {
            current.add(preset)
        }
        _presets.value = current
        savePresets()
        Timber.tag(TAG).i("Saved preset: ${preset.name}")
    }

    /**
     * 从当前相机参数创建预设
     */
    fun createPresetFromCurrent(
        name: String,
        aperture: Int,
        shutterSpeed: Int,
        iso: Int,
        whiteBalance: Int,
        focusMode: Int,
        meteringMode: Int
    ) {
        val preset = ShootingPreset(
            id = "custom_${System.currentTimeMillis()}",
            name = name,
            aperture = aperture,
            shutterSpeed = shutterSpeed,
            iso = iso,
            whiteBalance = whiteBalance,
            focusMode = focusMode,
            meteringMode = meteringMode
        )
        savePreset(preset)
    }

    /**
     * 删除预设
     */
    fun deletePreset(id: String) {
        _presets.value = _presets.value.filter { it.id != id }
        savePresets()
        Timber.tag(TAG).i("Deleted preset: $id")
    }

    /**
     * 获取预设
     */
    fun getPreset(id: String): ShootingPreset? {
        return _presets.value.find { it.id == id }
    }
}

/**
 * 拍摄预设数据模型
 * PRD 2.4: 保存/加载多组参数预设
 */
data class ShootingPreset(
    val id: String,
    val name: String,
    val aperture: Int,        // f值 x100
    val shutterSpeed: Int,    // 1/10000s 单位
    val iso: Int,
    val whiteBalance: Int,
    val focusMode: Int,
    val meteringMode: Int
)

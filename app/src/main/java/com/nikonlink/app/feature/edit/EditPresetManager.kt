package com.nikonlink.app.feature.edit

import com.google.gson.Gson
import com.nikonlink.app.core.imaging.EditParams
import com.nikonlink.app.data.local.EditPresetDao
import com.nikonlink.app.data.local.EditPresetEntity
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义预设管理（PRD-AI修图 4.5 P1: 滤镜 + 细节参数的组合预设，Room 本地存储）
 *
 * 日志来源: EditPreset 标签输出保存/删除动作。
 */
@Singleton
class EditPresetManager @Inject constructor(
    private val dao: EditPresetDao
) {

    companion object {
        private const val TAG = "EditPreset"

        /** 预设参数反序列化：JSON 损坏时返回 null（调用方跳过该预设） */
        fun parseParams(json: String): EditParams? = runCatching {
            Gson().fromJson(json, EditParams::class.java)
        }.getOrNull()
    }

    val presets: Flow<List<EditPresetEntity>> = dao.getAll()

    suspend fun save(
        name: String,
        params: EditParams,
        filterId: String,
        filterStrength: Int
    ) {
        dao.insert(
            EditPresetEntity(
                name = name.trim().ifBlank { "未命名预设" },
                paramsJson = Gson().toJson(params),
                filterId = filterId,
                filterStrength = filterStrength
            )
        )
        Timber.tag(TAG).i("Preset saved: $name")
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
        Timber.tag(TAG).i("Preset deleted: $id")
    }
}

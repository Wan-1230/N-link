package com.nikonlink.app.camera.data

import com.nikonlink.app.camera.gallery.CameraFile
import com.nikonlink.app.shared.data.PhotoMarkDao
import com.nikonlink.app.shared.data.PhotoMarkEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相册照片标记仓库（PRD v0.2.0 主题 F / F1「已标记」分区的数据层）
 *
 * 职责：
 * - 标记 / 取消标记 / 观察标记集合（「已标记」栏数据源）
 * - 指纹校验：相机列表全量刷新后清理失配标记（handle 复用、机内删卡等场景）
 *
 * 指纹键 = storage_id + object_handle + file_name + file_size + capture_time，
 * 相机重插卡后 handle 会复用，仅凭 handle 会把旧标记错挂到新文件上（PRD AC-8）。
 */
@Singleton
class PhotoMarkRepository @Inject constructor(
    private val dao: PhotoMarkDao
) {

    /** 全部标记（按标记时间倒序），供「已标记」栏与网格星标角标订阅 */
    fun observeAll(): Flow<List<PhotoMarkEntity>> = dao.observeAll()

    suspend fun getAll(): List<PhotoMarkEntity> = dao.getAll()

    /** 该文件当前是否已被标记（预览页星标状态用，指纹口径与列表一致） */
    suspend fun isMarked(file: CameraFile): Boolean {
        val key = PhotoMarkKeys.keyOf(file)
        return dao.getAll().any { PhotoMarkKeys.keyOf(it) == key }
    }

    /** 批量打标：同一指纹已存在时静默忽略（IGNORE 冲突策略） */
    suspend fun mark(files: List<CameraFile>) {
        if (files.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.insertAll(files.map { file ->
            PhotoMarkEntity(
                storageId = file.storageId,
                objectHandle = file.handle,
                fileName = file.fileName,
                fileSize = file.size,
                captureTime = file.captureTimeMillis ?: 0L,
                markedAt = now
            )
        })
    }

    /** 批量取消标记：按指纹键精确删除，不误伤同 handle 的其他卡文件 */
    suspend fun unmark(files: List<CameraFile>) {
        if (files.isEmpty()) return
        val keys = files.map { PhotoMarkKeys.keyOf(it) }.toSet()
        val targets = dao.getAll().filter { PhotoMarkKeys.keyOf(it) in keys }
        dao.delete(targets)
    }

    /** 清除指定标记记录（下载完成后「清除已下载项的标记」入口） */
    suspend fun clear(marks: List<PhotoMarkEntity>) = dao.delete(marks)

    /**
     * 指纹校验（PRD F1 失效自愈）：相机**全量**列表刷新后调用。
     * 键在当前列表中找不到的标记视为失效（机内删除 / 换卡 / handle 复用改写）→ 删除。
     *
     * 注意：仅在列表非空时调用——fetchPhotoList 失败会返回空列表，
     * 对空列表做校验会把全部标记误判失效（调用方负责守卫）。
     */
    suspend fun reconcile(cameraFiles: List<CameraFile>) {
        if (cameraFiles.isEmpty()) return
        val currentKeys = cameraFiles.mapTo(HashSet(cameraFiles.size)) { PhotoMarkKeys.keyOf(it) }
        val stale = dao.getAll().filter { PhotoMarkKeys.keyOf(it) !in currentKeys }
        if (stale.isNotEmpty()) dao.delete(stale)
    }
}

/** 指纹键计算：实体与相机文件共用同一口径，保证校验时能对上 */
object PhotoMarkKeys {

    fun keyOf(mark: PhotoMarkEntity): String = "${mark.storageId}:${mark.objectHandle}:" +
        "${mark.fileName}:${mark.fileSize}:${mark.captureTime}"

    fun keyOf(file: CameraFile): String = "${file.storageId}:${file.handle}:" +
        "${file.fileName}:${file.size}:${file.captureTimeMillis ?: 0L}"
}

package com.nikonlink.app.camera.share

import com.nikonlink.app.camera.gallery.CameraFile
import com.nikonlink.app.camera.gallery.CameraFileFormat

/**
 * 一次分享的输入快照。
 *
 * 为什么不在相册页直接生成 URI：相机源要经过"下载 → 重编码 → FileProvider"，
 * 耗时且可能失败；把这些工作放进独立的 ShareActivity，页面本身先打开，
 * 用户能看到进度与失败原因，也不会因为离开相册导致一次性事件丢失。
 */
data class ShareDraft(
    val items: List<ShareItem>,
    val origin: Origin
) {
    enum class Origin {
        /** 相机相册（含"已标记"工作台） */
        CAMERA,

        /** 手机本地相册 */
        LOCAL
    }

    data class ShareItem(
        val fileName: String,
        val size: Long,
        val format: CameraFileFormat,
        /** 相机源才有；本地源为 null */
        val cameraFile: CameraFile? = null,
        /** 已知的本地 content:// 或 file://（已下载/本地照片） */
        val localUri: String? = null
    )

    val hasCameraItems: Boolean get() = items.any { it.cameraFile != null }
    val count: Int get() = items.size
}

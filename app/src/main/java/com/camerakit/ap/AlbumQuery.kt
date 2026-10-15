package com.camerakit.ap

/** 文件类型（按扩展名归类，供相册过滤用）。 */
enum class ApFileType(val extensions: List<String>, val isImage: Boolean, val isVideo: Boolean) {
    JPEG(listOf("jpg", "jpeg"), isImage = true, isVideo = false),
    NEF(listOf("nef", "nrw"), isImage = true, isVideo = false),
    TIFF(listOf("tif", "tiff"), isImage = true, isVideo = false),
    HEIF(listOf("heif", "heic", "hif"), isImage = true, isVideo = false),
    MP4(listOf("mp4"), isImage = false, isVideo = true),
    MOV(listOf("mov"), isImage = false, isVideo = true),
    RAW_VIDEO(listOf("r3d", "nev"), isImage = false, isVideo = true);

    companion object {
        fun fromExtension(ext: String): ApFileType? =
            entries.find { ext.lowercase() in it.extensions }
    }
}

data class AlbumQuery(val media: Int = 0, val extension: String = "", val filename: String = "",
    val from: Long? = null, val until: Long? = null, val oldestFirst: Boolean = false) {
    fun accepts(info: ApPtpClient.ObjectInfo): Boolean {
        if (info.isDirectory) return false
        val ext = info.filename.substringAfterLast('.', "").lowercase()
        val type = ApFileType.fromExtension(ext)
        return (when (media) { 1 -> type?.isImage == true; 2 -> type == ApFileType.NEF; 3 -> type?.isVideo == true; else -> true }) &&
            (extension.isEmpty() || ext == extension || (extension == "jpg" && ext == "jpeg")) &&
            info.filename.contains(filename.trim(), ignoreCase = true) &&
            (from == null || info.captureTime >= from) && (until == null || info.captureTime < until)
    }

    /** 已知格式码允许相机在取元数据前跳过其它媒体。 */
    fun acceptsFormat(code: Int): Boolean {
        val extensions = when (code) {
            0x3001 -> return false
            0x3801 -> setOf("jpg", "jpeg")
            0x3802 -> setOf("nef", "nrw")
            0x380D -> setOf("tif", "tiff")
            0xB110 -> setOf("heif", "heic", "hif")
            0x300D, 0xB104, 0xB105 -> setOf("mov")
            0xB982 -> setOf("mp4")
            else -> return true // 未知尼康格式保留，靠文件名判断
        }
        return extensions.any { ext ->
            val type = ApFileType.fromExtension(ext)
            (extension.isEmpty() || ext == extension) && when (media) {
                1 -> type?.isImage == true; 2 -> type == ApFileType.NEF; 3 -> type?.isVideo == true; else -> true
            }
        }
    }
}

/** 按实际拍摄日期归并各独立排序的格式/存储流，而非全局句柄值。 */
internal class AlbumIndex(groups: List<List<Long>>, private val oldestFirst: Boolean) {
    private val streams = groups.map { if (oldestFirst) it.distinct().sorted() else it.distinct().sortedDescending() }
    private val positions = IntArray(streams.size)
    private val ordered = mutableListOf<ApPtpClient.ObjectInfo>()
    private val seen = mutableSetOf<Long>()
    val total = streams.flatten().distinct().size

    suspend fun page(offset: Int, count: Int, info: suspend (Long) -> ApPtpClient.ObjectInfo?): List<ApPtpClient.ObjectInfo> {
        while (ordered.size < offset + count) {
            val heads = streams.indices.mapNotNull { group ->
                val handle = streams[group].getOrNull(positions[group]) ?: return@mapNotNull null
                group to (info(handle) ?: throw java.io.IOException("无法读取相机文件信息"))
            }
            if (heads.isEmpty()) break
            val next = if (oldestFirst) heads.minBy { it.second.captureTime } else heads.maxBy { it.second.captureTime }
            positions[next.first]++
            if (seen.add(next.second.handle)) ordered.add(next.second)
        }
        return ordered.drop(offset).take(count)
    }
}

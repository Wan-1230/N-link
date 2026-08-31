package com.nikonlink.app.camera.gallery

/**
 * 相册排序模块
 *
 * 支持两个维度的排序，每个维度都可切换升/降序：
 * - [AlbumSortDimension.CAPTURE_TIME] 拍摄时间
 * - [AlbumSortDimension.FILE_TYPE]    文件类型（按扩展名分组）
 *
 * 设计约定：
 * 1. **时间缺失兜底**：取不到拍摄时间的文件统一排在「有时间数据」的文件**之后**，
 *    升序降序皆如此。这类文件若参与时间比较，会在升序时霸占首屏把真实照片挤到后面。
 * 2. **排序是纯 CPU 操作**（只比较 Long / String），调用方须放到 Dispatchers.Default，
 *    避免大列表时阻塞主线程。
 * 3. **只影响展示顺序**：选择、预览、删除、批量操作都基于 handle 集合，
 *    与列表顺序无关，不会因排序而错乱。
 * 4. **作用于完整数据集**：分页加载时传入的是累积列表，最终结果为全量排序，
 *    而非「仅当前页内有序」。
 */

/** 排序维度 */
enum class AlbumSortDimension(val label: String) {
    CAPTURE_TIME("拍摄时间"),
    FILE_TYPE("文件类型")
}

/** 排序方向 */
enum class AlbumSortDirection(val label: String, val symbol: String) {
    ASC("升序", "↑"),
    DESC("降序", "↓")
}

/**
 * 一条完整的排序规则。
 * @param dimension 排序维度
 * @param direction 排序方向
 */
data class AlbumSort(
    val dimension: AlbumSortDimension = DEFAULT.dimension,
    val direction: AlbumSortDirection = DEFAULT.direction
) {
    /** 工具栏按钮上的短标签，如「时间 ↓」 */
    val shortLabel: String
        get() = when (dimension) {
            AlbumSortDimension.CAPTURE_TIME -> "时间 ${direction.symbol}"
            AlbumSortDimension.FILE_TYPE -> "类型 ${direction.symbol}"
        }

    /** 菜单项上的完整标签，如「拍摄时间 ↓ 降序」 */
    val menuLabel: String get() = "${dimension.label} ${direction.symbol}"

    companion object {
        /** 默认：拍摄时间倒序，即新拍的排在最前面（与常见相册一致） */
        val DEFAULT = AlbumSort(AlbumSortDimension.CAPTURE_TIME, AlbumSortDirection.DESC)

        /** 菜单里的全部可选项 */
        val OPTIONS: List<AlbumSort> = AlbumSortDimension.entries.flatMap { dimension ->
            AlbumSortDirection.entries.map { direction -> AlbumSort(dimension, direction) }
        }

        fun fromPersistence(dimension: String?, direction: String?): AlbumSort {
            val d = runCatching { AlbumSortDimension.valueOf(dimension!!) }.getOrDefault(DEFAULT.dimension)
            val r = runCatching { AlbumSortDirection.valueOf(direction!!) }.getOrDefault(DEFAULT.direction)
            return AlbumSort(d, r)
        }
    }
}

/**
 * 按 [sort] 排列文件列表。
 *
 * 空列表与单元素列表直接返回，省去无谓开销。
 */
fun sortCameraFiles(files: List<CameraFile>, sort: AlbumSort): List<CameraFile> {
    if (files.size <= 1) return files
    return when (sort.dimension) {
        AlbumSortDimension.CAPTURE_TIME -> sortByCaptureTime(files, sort.direction)
        AlbumSortDimension.FILE_TYPE -> sortByFileType(files, sort.direction)
    }
}

/**
 * 拍摄时间排序。
 *
 * 取不到时间的文件（[CameraFile.captureTimeMillis] 为 null）**恒定排在末尾**，
 * 不随升降序翻转；组内按文件名升序做稳定兜底，保证多次刷新顺序一致。
 */
private fun sortByCaptureTime(
    files: List<CameraFile>,
    direction: AlbumSortDirection
): List<CameraFile> {
    val (withTime, withoutTime) = files.partition { it.captureTimeMillis != null }
    val sorted = if (direction == AlbumSortDirection.ASC) {
        withTime.sortedWith(
            compareBy<CameraFile> { it.captureTimeMillis!! }.thenBy { it.fileName }
        )
    } else {
        withTime.sortedWith(
            compareByDescending<CameraFile> { it.captureTimeMillis!! }.thenBy { it.fileName }
        )
    }
    return sorted + withoutTime.sortedBy { it.fileName }
}

/**
 * 文件类型排序：照片 → RAW → 动图 → 视频 → 其他，组内再按扩展名、文件名排列。
 *
 * 分组**以扩展名为准**，而不是 [CameraFileFormat]：
 * 动图（.GIF / .APNG / .WEBP）在 PTP 的 ObjectFormatCode 里没有独立分类，
 * 只会落到 OTHER，靠枚举分不出来；按扩展名才能真正做到「按扩展名分组」。
 * 只有文件名没有扩展名时（少数相机对象）才回退到 format 枚举。
 *
 * 性能：扩展名分组与小写名都**先算一遍存进排序键**，再比较。
 * 若直接写进 Comparator，排序的 O(n log n) 次比较会重复做字符串切分与小写转换，
 * 几千个文件时会产生十万级临时对象。
 */
private fun sortByFileType(
    files: List<CameraFile>,
    direction: AlbumSortDirection
): List<CameraFile> {
    val decorated = ArrayList<Pair<FileTypeKey, CameraFile>>(files.size)
    for (file in files) {
        val ext = extensionOf(file.fileName)
        decorated += FileTypeKey(groupOrder(ext, file.format), ext, file.fileName.lowercase()) to file
    }
    val comparator = compareBy<Pair<FileTypeKey, CameraFile>> { it.first.group }
        .thenBy { it.first.extension }
        .thenBy { it.first.lowerName }
    decorated.sortWith(if (direction == AlbumSortDirection.ASC) comparator else comparator.reversed())
    return decorated.map { it.second }
}

/** 文件类型排序键，预先算好以免比较时重复求值 */
private data class FileTypeKey(
    val group: Int,
    val extension: String,
    val lowerName: String
)

// 分组顺序：照片 → RAW → 动图 → 视频 → 其他
private const val GROUP_PHOTO = 0
private const val GROUP_RAW = 1
private const val GROUP_ANIMATED = 2
private const val GROUP_VIDEO = 3
private const val GROUP_OTHER = 4

private val PHOTO_EXTENSIONS =
    setOf("jpg", "jpeg", "jpe", "png", "heic", "heif", "tif", "tiff", "bmp")
private val RAW_EXTENSIONS =
    setOf("nef", "nrw", "cr2", "cr3", "arw", "dng", "raf", "orf", "rw2", "pef", "srw", "raw")
private val ANIMATED_EXTENSIONS = setOf("gif", "apng", "webp", "avifs")
private val VIDEO_EXTENSIONS =
    setOf("mov", "mp4", "m4v", "avi", "mkv", "webm", "mpg", "mpeg", "ts", "mts")

/**
 * 判定文件所属类型分组。
 * @param extension 已小写化的扩展名，无扩展名时为空串
 */
private fun groupOrder(extension: String, format: CameraFileFormat): Int = when {
    extension in PHOTO_EXTENSIONS -> GROUP_PHOTO
    extension in RAW_EXTENSIONS -> GROUP_RAW
    extension in ANIMATED_EXTENSIONS -> GROUP_ANIMATED
    extension in VIDEO_EXTENSIONS -> GROUP_VIDEO
    // 没有扩展名时才回退到 PTP/MediaStore 给出的格式分类
    format == CameraFileFormat.JPEG -> GROUP_PHOTO
    format == CameraFileFormat.RAW -> GROUP_RAW
    format == CameraFileFormat.VIDEO -> GROUP_VIDEO
    else -> GROUP_OTHER
}

private fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").lowercase()

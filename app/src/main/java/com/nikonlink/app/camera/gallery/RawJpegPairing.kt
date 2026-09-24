package com.nikonlink.app.camera.gallery

/**
 * RAW+JPEG 成对（v2.5 FR-17）——**只按手机侧文件名配对，不新增任何 PTP 往返**。
 *
 * 为什么值得做：机身开 RAW+JPG 时一次拍摄在卡里是两个对象，相册就占两格；千张卡库里
 * 一半格子是重复信息。而判断"这两张是不是一次拍的"根本不需要问相机——
 * 尼康的 NEF 与 JPG 同名不同缀（`DSC_0001.NEF` / `DSC_0001.JPG`），
 * 相册列表里已经有文件名了。反过来，为配对去逐张 `0x1008` 是绝对不做的：
 * 那是把一次批量元数据换来的性能全赔回去。
 *
 * 只做严格 1:1：**任何歧义一律退回平铺**。同名下出现两张 RAW、两张 JPG、
 * 或者只有一张，都不合并。理由很直接——合并的代价是"少了一格"，
 * 而少一格如果意味着某个文件在界面上再也选不到，那就是丢片级的问题，
 * 比多占一格严重得多。
 */
object RawJpegPairing {

    /** 一次拍摄里 RAW 与 JPG 的归属关系 */
    class Plan(
        /** 被合并掉、不再单独占一格的 JPG 句柄 */
        val hiddenJpegHandles: Set<Int>,
        /** RAW 句柄 → 它的 JPG 句柄 */
        val jpegOfRaw: Map<Int, Int>,
        /** JPG 句柄 → 它的 RAW 句柄（用于用户选了 JPG 时反查） */
        val rawOfJpeg: Map<Int, Int>
    ) {
        val isEmpty: Boolean get() = hiddenJpegHandles.isEmpty()
    }

    /** 下载时怎么对待成对的两张 */
    enum class DownloadMode {
        /** 成对下载（默认）：传 RAW 就把 JPG 一起排队，反之亦然 */
        BOTH,

        /** 只传 RAW：省空间，回去电脑转 */
        RAW_ONLY,

        /** 只传 JPG：手机上能直接看、能发，NEF 留在卡里 */
        JPEG_ONLY
    }

    /**
     * 从一次相册列表算出配对方案。
     *
     * 输入是**已经取全的**元数据（`0x9805` 批量或逐个 `0x1008` 都行），
     * 本函数不碰传输层，所以纯 JVM 可测。
     */
    fun plan(files: List<CameraFile>): Plan {
        val byBase = LinkedHashMap<String, MutableList<CameraFile>>()
        for (file in files) {
            val base = baseNameOf(file.fileName) ?: continue
            byBase.getOrPut(base) { mutableListOf() }.add(file)
        }
        val hidden = LinkedHashSet<Int>()
        val jpegOfRaw = LinkedHashMap<Int, Int>()
        val rawOfJpeg = LinkedHashMap<Int, Int>()
        for ((_, group) in byBase) {
            if (group.size != 2) continue       // 一张或三张以上 = 有歧义，不合并
            val raw = group.firstOrNull { it.format == CameraFileFormat.RAW } ?: continue
            val jpeg = group.firstOrNull { it.format == CameraFileFormat.JPEG } ?: continue
            // 两张句柄相同（不该发生，但脏数据下必须平铺而不是把自己藏掉）就不合并
            if (raw.handle == jpeg.handle) continue
            hidden += jpeg.handle
            jpegOfRaw[raw.handle] = jpeg.handle
            rawOfJpeg[jpeg.handle] = raw.handle
        }
        return Plan(hidden, jpegOfRaw, rawOfJpeg)
    }

    /**
     * 把配对信息挂到留下的那些条目上（合并后的 RAW 条目要知道自己有张 JPG）。
     * 被合并掉的 JPG 直接不出现；其余条目只是 [CameraFile.pairedJpegHandle] 为 null，
     * 与现在的行为一致。
     */
    fun applyMerge(files: List<CameraFile>, plan: Plan): List<CameraFile> {
        if (plan.isEmpty) return files
        return files.mapNotNull { file ->
            if (file.handle in plan.hiddenJpegHandles) return@mapNotNull null
            val partner = plan.jpegOfRaw[file.handle]
            if (partner == null) file else file.copy(pairedJpegHandle = partner)
        }
    }

    /**
     * 按 [mode] 把选中的条目展开成真正的入队清单。
     *
     * 展开发生在入队这一刻，不改界面上选了什么：用户选中一格 = 选中"这一次拍摄"，
     * 至于落成几个下载任务由模式决定。[lookup] 用来把句柄换回完整的 CameraFile
     * （大小、存储号都要给传输层）；查不到就退回手里这一张——宁可不合并也不能漏传。
     */
    fun expand(
        selected: List<CameraFile>,
        plan: Plan,
        mode: DownloadMode,
        lookup: (Int) -> CameraFile?
    ): List<CameraFile> {
        if (mode == DownloadMode.BOTH && plan.isEmpty) return selected
        val out = ArrayList<CameraFile>(selected.size * 2)
        val seen = HashSet<Int>(selected.size * 2)
        for (file in selected) {
            if (mode == DownloadMode.BOTH && file.pairedJpegHandle == null &&
                plan.jpegOfRaw[file.handle] == null && plan.rawOfJpeg[file.handle] == null
            ) {
                add(out, seen, file)   // 没配对的条目（视频、单格式拍摄）走最快路径，不做任何查表
                continue
            }
            val raw = rawOf(file, plan, lookup)
            val jpeg = jpegOf(file, plan, lookup)
            when (mode) {
                // 两张都要。没有另一张就只下这一张，绝不因为"配对不完整"而不下载
                DownloadMode.BOTH -> {
                    add(out, seen, file)
                    val twin = if (file.format == CameraFileFormat.RAW) jpeg else raw
                    if (twin != null && twin.handle != file.handle) add(out, seen, twin)
                }
                DownloadMode.RAW_ONLY -> add(out, seen, raw ?: file)
                DownloadMode.JPEG_ONLY -> add(out, seen, jpeg ?: file)
            }
        }
        return out
    }

    /** 这一格所属那一次拍摄的 RAW；没有 RAW 返回 null */
    private fun rawOf(
        file: CameraFile,
        plan: Plan,
        lookup: (Int) -> CameraFile?
    ): CameraFile? = when {
        file.format == CameraFileFormat.RAW -> file
        else -> plan.rawOfJpeg[file.handle]?.let(lookup)
    }

    /** 这一格所属那一次拍摄的 JPG；没有 JPG 返回 null */
    private fun jpegOf(
        file: CameraFile,
        plan: Plan,
        lookup: (Int) -> CameraFile?
    ): CameraFile? = when {
        file.format == CameraFileFormat.JPEG -> file
        else -> (plan.jpegOfRaw[file.handle] ?: file.pairedJpegHandle)?.let(lookup)
    }

    private fun add(out: MutableList<CameraFile>, seen: MutableSet<Int>, file: CameraFile) {
        if (seen.add(file.handle)) out.add(file)
    }

    /**
     * 去掉扩展名后的主干（大写）。没有点、或以点结尾的脏名字返回 null（不参与配对）。
     */
    fun baseNameOf(fileName: String): String? {
        val trimmed = fileName.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.length - 1) return null
        return trimmed.substring(0, dot).uppercase()
    }
}

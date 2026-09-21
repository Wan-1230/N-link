package com.nikonlink.app.camera.gallery

/**
 * 分页拉取期间的累积列表（PRD v2.5 FR-03）。
 *
 * **为什么需要它**：相册按 18 张一页往回拉，而 `onPage` 拿到的是**当前这一页**
 * （机身按 handle 顺序返回，与用户看到的「拍摄时间倒序」几乎完全逆序）。
 * 直接把这一页塞进列表，等于每来一页就把网格换成一堆旧照片 ——
 * DiffUtil 的视口锚点被拖走，刷新完再弹回顶部。v2.3 之前为了避免这件事，
 * 干脆关掉逐页发射、等全量拉完才显示，代价是**首屏时间 = 全量时间**，千张卡库要空转十几秒。
 *
 * 这里把两件事分开：累积 + 按当前排序规则出快照。于是每次发射都是
 * 「到目前为止的完整前缀」，且顺序与最终列表同构 —— 后到的页只会在尾部补，
 * 不会再出现整体翻转。渲染侧一行都不用改。
 */
class AlbumPageAccumulator {

    private val byHandle = LinkedHashMap<Int, CameraFile>()

    val size: Int get() = byHandle.size

    /** 并入一页。同一 handle 重复出现以最后一次为准（事件增量与分页重入都走这里）。 */
    fun addAll(page: List<CameraFile>) {
        for (file in page) byHandle[file.handle] = file
    }

    /** 当前累积结果按 [sort] 排好的快照。调用方负责节流，别每页都排。 */
    fun snapshot(sort: AlbumSort): List<CameraFile> =
        sortCameraFiles(byHandle.values.toList(), sort)
}

package com.nikonlink.app.camera.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.nikonlink.app.shared.common.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缩略图两级缓存（相册预览性能优化核心）。
 *
 * - 内存层：LRU（按 Bitmap 字节数 64MB 上限），滚动复用零解压；
 * - 磁盘层：cacheDir/thumbnails/<handle>.jpg，二次进入相册免 PTP 往返。
 * - 解码统一在该组件内处理（Dispatchers.Default/IO），UI 线程不触碰解码；
 *   超大缩略图按长边降采样，目标档位由请求方指定（[tileMaxEdgePx] 网格档 /
 *   [MAX_EDGE_PX] 预览档）。
 *
 * 档位策略（2026-10 性能调优）：磁盘缓存的 key 与内容不变（永远存最高质量的
 * 原始字节），只把**解码**按用途分档——网格请求按格子实际像素解码（解码像素
 * 约为预览档的 1/8，单张位图内存同比例缩小，64MB LRU 能容下的格子数翻数倍），
 * 预览/大图请求保留 1024。内存里同一 handle 只保留一份位图（后写覆盖）：
 * 网格档小图被预览读取时按预览档从磁盘现解大图并回填（见 [get]）。
 *
 * 用途：替换旧实现的 ViewModel 全量 ByteArray Map + Adapter BitmapCache 双缓存，
 * 消除「缩略图与照片数线性增长的内存」与「每次进相册全量串行拉取」问题。
 */
@Singleton
class ThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLogger: AppEventLogger
) {
    companion object {
        private const val TAG = "ThumbCache"
        private const val MAX_MEMORY_KB = 64 * 1024   // 64MB

        /**
         * 预览档解码目标长边。相册网格单格 300px+（高 dpi 下更高），旧值 512 之下源图常常只有
         * 160×120，放大显示必然模糊；配合 0x90C4 高清缩略图提到 1024，同时保留
         * inSampleSize 降采样控制内存。
         */
        private const val MAX_EDGE_PX = 1024

        /** 网格档解码目标的上下限：小屏不低于 256（够 3 列方格），平板不超过 512 */
        private const val TILE_EDGE_MIN_PX = 256
        private const val TILE_EDGE_MAX_PX = 512

        /** 缓存命中率打点的冲刷周期：每 64 次 get 汇总一行累计值，避免刷爆环形日志 */
        private const val STAT_FLUSH_EVERY = 64L
    }

    /**
     * 网格档解码目标长边：3 列网格单格 ≈ 屏宽/3（item_photo_grid 为 match_parent 方格）。
     *
     * [decode] 的 inSampleSize 是 2 的幂，实际解码结果落在 (目标, 2×目标] 区间——
     * 以格子实际像素为目标即可保证不解码出小于显示尺寸的位图（无放大模糊），
     * 同时把解码像素量从预览档的 (1024, 2048] 压回格子量级（约 1/8）。
     * 每次访问现算：横竖屏切换后屏宽变化能立即生效。
     */
    val tileMaxEdgePx: Int
        get() = (context.resources.displayMetrics.widthPixels / 3)
            .coerceIn(TILE_EDGE_MIN_PX, TILE_EDGE_MAX_PX)

    // v2：旧目录缓存的是 0x100A 小图字节，升级后整体作废，避免新代码读到旧的小图
    private val diskDir = File(context.cacheDir, "thumbnails_v2").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** LruCache 的 put/get 内部同步，跨线程安全 */
    private val memory = object : LruCache<Int, Bitmap>(MAX_MEMORY_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun hasInMemory(handle: Int): Boolean = memory.get(handle) != null

    /** 同步取内存缓存（UI 线程安全） */
    fun fromMemory(handle: Int): Bitmap? = memory.get(handle)

    // ---------- 缓存命中率打点（AppEventLogger，phase=thumb_cache） ----------

    private val statRequests = AtomicLong(0)
    private val statMemHits = AtomicLong(0)
    private val statDiskHits = AtomicLong(0)
    private val statMisses = AtomicLong(0)

    /**
     * 每 [STAT_FLUSH_EVERY] 次 [get] 汇总一行累计值。语义：
     * mem_hit = 内存位图长边满足请求档位直接命中；disk_hit = 磁盘解码命中；
     * miss = 两级缓存都没有可用的对应档位（后续会走 PTP）。
     */
    private fun maybeFlushThumbStats() {
        val n = statRequests.get()
        if (n % STAT_FLUSH_EVERY != 0L) return
        val mem = statMemHits.get()
        val disk = statDiskHits.get()
        eventLogger.event(
            "thumb_cache",
            "req" to n,
            "hit" to (mem + disk),
            "miss" to statMisses.get(),
            "mem_hit" to mem,
            "disk_hit" to disk
        )
    }

    /**
     * 缓存未命中时阻塞读取：内存 → 磁盘（含解码）。
     *
     * @param maxEdgePx 解码目标长边：网格请求传 [tileMaxEdgePx]（按格子实际像素解码，
     *   解码快、内存省），预览/大图请求用默认 [MAX_EDGE_PX]。
     *   内存命中仅在位图长边已满足请求档位时直接返回；否则（典型：内存里还是网格档
     *   小图、预览来要大图）继续按请求档位从磁盘现解并回填内存。磁盘也没有更大图时
     *   退回内存里已有的小图（低清兜底好过让预览页误判为加载失败）。
     */
    suspend fun get(handle: Int, maxEdgePx: Int = MAX_EDGE_PX): Bitmap? = withContext(Dispatchers.IO) {
        val cached = memory.get(handle)
        val fromDisk = if (cached != null && maxOf(cached.width, cached.height) >= maxEdgePx) {
            null
        } else {
            runCatching {
                val file = diskFile(handle)
                if (!file.exists()) return@runCatching null
                val bitmap = decode(file.readBytes(), maxEdgePx) ?: return@runCatching null
                memory.put(handle, bitmap)
                bitmap
            }.getOrNull()
        }
        statRequests.incrementAndGet()
        when {
            fromDisk != null -> statDiskHits.incrementAndGet()
            cached != null -> statMemHits.incrementAndGet()
            else -> statMisses.incrementAndGet()
        }
        maybeFlushThumbStats()
        fromDisk ?: cached
    }

    /**
     * 网络/本地资产字节入缓存：解码 + 内存直存，磁盘写后台异步；返回解码结果便于调用方即时展示。
     *
     * @param maxEdgePx 内存位图的解码档位：网格侧高清升级传 [tileMaxEdgePx]
     *   （格子量级位图），预览/大图字节用默认 [MAX_EDGE_PX]。磁盘始终原样写入
     *   传入字节，档位不影响磁盘内容。
     */
    suspend fun putBytes(
        handle: Int,
        bytes: ByteArray,
        maxEdgePx: Int = MAX_EDGE_PX
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bitmap = decode(bytes, maxEdgePx) ?: return@withContext null
        memory.put(handle, bitmap)
        scope.launch {
            runCatching { diskFile(handle).writeBytes(bytes) }
                .onFailure { Timber.tag(TAG).w("thumb disk write failed for $handle: ${it.message}") }
        }
        bitmap
    }

    /**
     * 已解码 Bitmap 入缓存（本地相册路径；LruCache 内部同步，跨线程安全）。
     *
     * persist=true 时后台编码 JPEG 落盘：本地缩略图旧版只进内存，进程重启后
     * 内存清空、磁盘又没有，二次打开必须逐张重走 MediaStore 解码——这是
     * 「本地相册再次打开转圈」的成因之一。落盘后二次打开可直接读盘秒出。
     */
    fun putBitmap(handle: Int, bitmap: Bitmap, persist: Boolean = false) {
        memory.put(handle, bitmap)
        if (!persist) return
        scope.launch {
            runCatching {
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.toByteArray()
                }
                diskFile(handle).writeBytes(bytes)
            }.onFailure {
                Timber.tag(TAG).w("thumb persist failed for $handle: ${it.message}")
            }
        }
    }

    fun diskFile(handle: Int): File = File(diskDir, "$handle.jpg")

    fun clear() {
        memory.evictAll()
        diskDir.listFiles()?.forEach { runCatching { it.delete() } }
        Timber.tag(TAG).i("Thumbnail cache cleared")
    }

    private fun decode(bytes: ByteArray, maxEdgePx: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > maxEdgePx) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Timber.tag(TAG).w("thumb decode failed: ${e.message}")
            null
        }
    }
}
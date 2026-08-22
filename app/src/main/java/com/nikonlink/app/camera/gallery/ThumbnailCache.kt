package com.nikonlink.app.camera.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 缩略图两级缓存（相册预览性能优化核心）。
 *
 * - 内存层：LRU（按 Bitmap 字节数 64MB 上限），滚动复用零解压；
 * - 磁盘层：cacheDir/thumbnails/<handle>.jpg，二次进入相册免 PTP 往返。
 * - 解码统一在该组件内处理（Dispatchers.Default/IO），UI 线程不触碰解码；
 *   超大缩略图按长边 512 降采样。
 *
 * 用途：替换旧实现的 ViewModel 全量 ByteArray Map + Adapter BitmapCache 双缓存，
 * 消除「缩略图与照片数线性增长的内存」与「每次进相册全量串行拉取」问题。
 */
@Singleton
class ThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ThumbCache"
        private const val MAX_MEMORY_KB = 64 * 1024   // 64MB
        private const val MAX_EDGE_PX = 512
    }

    private val diskDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** LruCache 的 put/get 内部同步，跨线程安全 */
    private val memory = object : LruCache<Int, Bitmap>(MAX_MEMORY_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun hasInMemory(handle: Int): Boolean = memory.get(handle) != null

    /** 同步取内存缓存（UI 线程安全） */
    fun fromMemory(handle: Int): Bitmap? = memory.get(handle)

    /** 缓存未命中时阻塞读取：内存 → 磁盘（含解码） */
    suspend fun get(handle: Int): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(handle) ?: runCatching {
            val file = diskFile(handle)
            if (!file.exists()) return@runCatching null
            val bitmap = decode(file.readBytes()) ?: return@runCatching null
            memory.put(handle, bitmap)
            bitmap
        }.getOrNull()
    }

    /** 网络/本地资产字节入缓存：解码 + 内存直存，磁盘写后台异步；返回解码结果便于调用方即时展示 */
    suspend fun putBytes(handle: Int, bytes: ByteArray): Bitmap? = withContext(Dispatchers.IO) {
        val bitmap = decode(bytes) ?: return@withContext null
        memory.put(handle, bitmap)
        scope.launch {
            runCatching { diskFile(handle).writeBytes(bytes) }
                .onFailure { Timber.tag(TAG).w("thumb disk write failed for $handle: ${it.message}") }
        }
        bitmap
    }

    /** 已解码 Bitmap 入缓存（本地相册路径；LruCache 内部同步，跨线程安全） */
    fun putBitmap(handle: Int, bitmap: Bitmap) {
        memory.put(handle, bitmap)
    }

    fun diskFile(handle: Int): File = File(diskDir, "$handle.jpg")

    fun clear() {
        memory.evictAll()
        diskDir.listFiles()?.forEach { runCatching { it.delete() } }
        Timber.tag(TAG).i("Thumbnail cache cleared")
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) > MAX_EDGE_PX) {
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
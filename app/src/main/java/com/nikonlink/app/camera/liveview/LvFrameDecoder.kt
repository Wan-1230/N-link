package com.nikonlink.app.camera.liveview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 监看帧解码器（B1 / R1 / R4）。
 *
 * **解决的问题**：此前每帧都是 `lifecycleScope`（Main）里 `BitmapFactory.decodeByteArray`
 * 全尺寸解码 + `setImageBitmap`，15fps 就是每秒 15 次「主线程全尺寸 JPEG 解码」。
 * 中低端机（群友主力 Z8 + 小米 15 Pro 场景）表现为掉帧与触控迟滞，慢解码时是 ANR 风险。
 *
 * **三条设计约束**：
 * 1. **解码不在主线程**：跑在专属的单线程解码器上；
 * 2. **保序且不过期**：单线程消费 + CONFLATED 队列——解码跟不上时丢掉中间的旧帧，
 *    只解最新的一帧。既不乱序，也不会把积压的过期帧逐帧补完（那会让延迟越滚越大）；
 * 3. **按需降采样**：按 ImageView 的实际显示尺寸（含缩放）算 `inSampleSize`，
 *    不再每帧解码 640×424 全尺寸。
 *
 * [onFrame] 在**主线程**回调，调用方可以直接操作 View。
 *
 * 与 [FrameSampler] 的分工：这里负责「把 JPEG 变成 Bitmap」，直方图/波形那套
 * 降采样取数仍由 FrameSampler 负责，喂进去的是同一张 Bitmap，不做二次解码。
 */
class LvFrameDecoder(
    private val scope: CoroutineScope,
    private val onFrame: (Bitmap) -> Unit,
) {

    /** CONFLATED：永远只留最新一帧，旧帧直接被顶掉，不会积压 */
    private val pending = Channel<ByteArray>(Channel.CONFLATED)
    private var dispatcher: ExecutorCoroutineDispatcher = newDecodeDispatcher()
    private var dispatcherClosed = false
    private var consumer: Job? = null

    /** 目标显示尺寸（含缩放倍率）；<=0 表示未知，此时不降采样 */
    @Volatile
    private var targetWidth = 0
    @Volatile
    private var targetHeight = 0

    /** 源帧尺寸缓存：监看期间分辨率固定，只需解析一次 bounds */
    private var srcWidth = 0
    private var srcHeight = 0

    private val submitted = AtomicInteger(0)
    private val delivered = AtomicInteger(0)

    fun start() {
        if (consumer?.isActive == true) return
        if (dispatcherClosed) {
            dispatcher = newDecodeDispatcher()
            dispatcherClosed = false
        }
        consumer = scope.launch(dispatcher) {
            for (data in pending) {
                if (!isActive) break
                val bitmap = runCatching { decode(data) }.getOrNull() ?: continue
                // 上屏必须回主线程：ImageView 只能在 Main 上被触碰
                withContext(Dispatchers.Main.immediate) {
                    delivered.incrementAndGet()
                    onFrame(bitmap)
                }
            }
        }
    }

    /** 更新显示目标尺寸（页面布局变化、缩放变化时调用） */
    fun setTargetSize(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
    }

    /** 提交一帧。旧帧若还没开始解码会被这一帧顶掉（这是刻意的，见类注释第 2 条）。 */
    fun submit(data: ByteArray) {
        submitted.incrementAndGet()
        pending.trySend(data)
    }

    /** 停止消费（页面视图销毁时调用），释放解码线程 */
    fun stop() {
        consumer?.cancel()
        consumer = null
        runCatching { pending.tryReceive().getOrNull() }
        runCatching { dispatcher.close() }
        dispatcherClosed = true
        srcWidth = 0
        srcHeight = 0
    }

    /** 诊断快照：提交 / 已上屏 / 被顶掉的帧数 + 当前降采样倍率 */
    fun stats(): String {
        val s = submitted.get()
        val d = delivered.get()
        val sample = LvFrameDecodeOptions.sampleSizeFor(srcWidth, srcHeight, targetWidth, targetHeight)
        return "submitted=$s delivered=$d skipped=${(s - d).coerceAtLeast(0)} " +
            "src=${srcWidth}x${srcHeight} target=${targetWidth}x${targetHeight} sample=$sample"
    }

    private fun decode(data: ByteArray): Bitmap? {
        val start = LvFrame.findJpegStart(data)
        val length = data.size - start
        if (length <= 0) return null

        if (srcWidth <= 0 || srcHeight <= 0) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, start, length, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                srcWidth = bounds.outWidth
                srcHeight = bounds.outHeight
            }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = LvFrameDecodeOptions.sampleSizeFor(
                srcWidth, srcHeight, targetWidth, targetHeight
            )
        }
        val bitmap = BitmapFactory.decodeByteArray(data, start, length, options)
        if (bitmap == null) {
            // 解不出来就把尺寸缓存清掉：可能是分辨率真的变了，下一帧重新解析 bounds
            srcWidth = 0
            srcHeight = 0
        }
        return bitmap
    }

    private fun newDecodeDispatcher(): ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "nl-lv-decode").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}

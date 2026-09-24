package com.nikonlink.app.camera.liveview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import kotlin.math.max

/**
 * 监看帧 → 降采样像素数组（直方图与波形共用一份取数代码）。
 *
 * 两处都要干同一件琐碎的事：把一帧 640×424 缩到一百来像素宽、读进一个可复用的
 * IntArray、并且不能每帧新建 Bitmap/数组（15fps 下 GC 会直接把帧率吃掉）。
 * 分开写就是两份会各自腐化的缓存管理，所以抽在这里。
 *
 * **返回的数组是复用的**：下一次 [sample] 会覆盖它。调用方只能在本次调用内使用，
 * 想留就自己拷走。
 */
class FrameSampler(private val sampleWidth: Int) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val canvas = Canvas()
    private val dst = RectF()
    private var scratch: Bitmap? = null
    private var pixels: IntArray? = null

    /** 最近一次 [sample] 的有效尺寸 */
    var sampledWidth = 0
        private set
    var sampledHeight = 0
        private set

    fun sample(source: Bitmap): IntArray? {
        val w = sampleWidth
        val h = max(1, source.height * w / max(1, source.width))
        val target = ensureBitmap(w, h) ?: return null
        val buffer = ensurePixels(w * h) ?: return null

        canvas.setBitmap(target)
        // 先清底：源帧带透明区域时，残留像素会被当成上一帧的内容算进来
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        canvas.drawBitmap(source, null, dst, paint)
        target.getPixels(buffer, 0, w, 0, 0, w, h)
        sampledWidth = w
        sampledHeight = h
        return buffer
    }

    private fun ensureBitmap(w: Int, h: Int): Bitmap? {
        val current = scratch
        if (current != null && current.width == w && current.height == h && !current.isRecycled) return current
        current?.recycle()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { scratch = it }
        } catch (e: Throwable) {
            scratch = null
            null
        }
    }

    private fun ensurePixels(size: Int): IntArray? {
        val current = pixels
        if (current != null && current.size >= size) return current
        return try {
            IntArray(size).also { pixels = it }
        } catch (e: Throwable) {
            null
        }
    }
}

package com.nikonlink.app.core.imaging

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import timber.log.Timber

/**
 * 图像编辑引擎（PRD 6.1 core/imaging）
 *
 * 职责：将 EditParams 参数集渲染到位图。
 * - 预览层：降采样图整幅处理（≤1080p，满足 PRD 9.1 预览调节 <100ms）
 * - 导出层：全分辨率按行带（band）原地处理，避免同时持有两份全尺寸位图（PRD 8.4）
 *
 * 日志来源: EditEngine 统一输出渲染耗时与解码信息（Timber tag "EditEngine"）。
 */
object EditEngine {

    private const val TAG = "EditEngine"

    /** 导出时行带高度：一次 getPixels/setPixels 处理的行数 */
    private const val EXPORT_BAND_ROWS = 512

    /**
     * 预览渲染：返回应用参数后的新位图（不修改 source）。
     * 参数为默认时直接返回 source 副本由调用方决定，这里返回 source 本身。
     */
    fun renderPreview(source: Bitmap, params: EditParams): Bitmap {
        if (params.isDefault) return source
        val start = System.currentTimeMillis()
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        applyInPlace(output, params)
        Timber.tag(TAG).d("renderPreview ${source.width}x${source.height} in ${System.currentTimeMillis() - start}ms")
        return output
    }

    /**
     * 原位应用参数（逐块 getPixels/setPixels）。
     * 全分辨率导出时按 EXPORT_BAND_ROWS 行带分批，控制单次像素数组内存。
     * 管线顺序: 基础调节 → 色彩增强 → 清晰度（PRD 4.3）。
     */
    fun applyInPlace(bitmap: Bitmap, params: EditParams) {
        if (params.isDefault) return
        require(bitmap.isMutable) { "applyInPlace 需要可变位图" }
        // M5 验收测量基础：全分辨率渲染耗时打点（PRD 9.1/9.4）
        val start = System.currentTimeMillis()
        val ctx = EditMath.buildContext(params)
        if (!ctx.isNoOp) {
            forEachBand(bitmap) { pixels, count ->
                EditMath.applyContext(pixels, count, ctx)
            }
        }
        applyDenoiseInPlace(bitmap, params.denoise)
        applyVibranceInPlace(bitmap, params.vibrance)
        applyClarityInPlace(bitmap, params.clarity)
        Timber.tag(TAG).d("applyInPlace ${bitmap.width}x${bitmap.height} in ${System.currentTimeMillis() - start}ms")
    }

    /**
     * 降噪传统算法兜底（PRD 8.5 降级链：NAFNet 模型未就绪时使用）。
     * 5x5 均值模糊与原图按强度混合，滚动行缓冲实现，内存恒定（PRD 8.4）；
     * 强度上限保留纹理，避免塑料感（PRD 4.3：强度越高细节损失越多）。
     */
    fun applyDenoiseInPlace(bitmap: Bitmap, amount: Int) {
        val k = EditMath.denoiseBlend(amount)
        if (k == 0f) return
        require(bitmap.isMutable) { "applyDenoiseInPlace 需要可变位图" }

        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return

        // 滚动 5 行像素缓冲
        val rows = Array(5) { IntArray(w) }
        fun loadRow(slot: Int, y: Int) {
            bitmap.getPixels(rows[slot], 0, w, 0, y.coerceIn(0, h - 1), w, 1)
        }
        for (i in 0 until 5) loadRow(i, i - 2)

        val outRow = IntArray(w)
        for (y in 0 until h) {
            if (y > 0) {
                // 滚动：丢弃第 0 行，其余上移，底部载入新行
                val tmp = rows[0]
                for (i in 0 until 4) rows[i] = rows[i + 1]
                rows[4] = tmp
                loadRow(4, y + 2)
            }
            val center = rows[2]
            System.arraycopy(center, 0, outRow, 0, w)
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in 0 until 5) {
                    val row = rows[dy]
                    for (dx in -2..2) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val px = row[xx]
                        r += (px shr 16) and 0xFF
                        g += (px shr 8) and 0xFF
                        b += px and 0xFF
                        n++
                    }
                }
                val px = center[x]
                val a = px and 0xFF000000.toInt()
                val nr = EditMath.denoiseMix((px shr 16) and 0xFF, r / n, k)
                val ng = EditMath.denoiseMix((px shr 8) and 0xFF, g / n, k)
                val nb = EditMath.denoiseMix(px and 0xFF, b / n, k)
                outRow[x] = a or (nr shl 16) or (ng shl 8) or nb
            }
            bitmap.setPixels(outRow, 0, w, 0, y, w, 1)
        }
    }

    /**
     * 色彩增强（Vibrance，PRD 4.3）：低饱和像素优先提升，保护肤色与已饱和色。
     * 逐像素无邻域依赖，行带分批安全。
     */
    fun applyVibranceInPlace(bitmap: Bitmap, amount: Int) {
        if (amount <= 0) return
        require(bitmap.isMutable) { "applyVibranceInPlace 需要可变位图" }
        forEachBand(bitmap) { pixels, count ->
            for (i in 0 until count) {
                val px = pixels[i]
                val a = px and 0xFF000000.toInt()
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val boost = EditMath.vibranceBoost(maxC - minC, amount)
                if (boost == 1f) continue
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                val nr = (lum + (r - lum) * boost).toInt().coerceIn(0, 255)
                val ng = (lum + (g - lum) * boost).toInt().coerceIn(0, 255)
                val nb = (lum + (b - lum) * boost).toInt().coerceIn(0, 255)
                pixels[i] = a or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /**
     * 清晰度（Clarity，PRD 4.3 局部对比度增强，避免全局锐化白边）。
     * 滚动三行亮度缓冲实现 3x3 unsharp，任意分辨率内存恒定（PRD 8.4）；
     * 按亮度比例缩放 RGB 以保持色相不变。
     */
    fun applyClarityInPlace(bitmap: Bitmap, amount: Int) {
        val gain = EditMath.clarityGain(amount)
        if (gain == 0f) return
        require(bitmap.isMutable) { "applyClarityInPlace 需要可变位图" }

        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return

        val rowBuf = IntArray(w)

        fun lumRow(y: Int): IntArray {
            val yy = y.coerceIn(0, h - 1)
            bitmap.getPixels(rowBuf, 0, w, 0, yy, w, 1)
            return IntArray(w) { x ->
                val px = rowBuf[x]
                (0.299f * ((px shr 16) and 0xFF) +
                    0.587f * ((px shr 8) and 0xFF) +
                    0.114f * (px and 0xFF)).toInt()
            }
        }

        var prevLum = lumRow(0)
        var currLum = lumRow(0)
        var nextLum = lumRow(1)

        for (y in 0 until h) {
            if (y > 0) {
                prevLum = currLum
                currLum = nextLum
                nextLum = lumRow(y + 1)
            }
            bitmap.getPixels(rowBuf, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val xl = if (x > 0) x - 1 else 0
                val xr = if (x < w - 1) x + 1 else w - 1
                val blur = (prevLum[xl] + prevLum[x] + prevLum[xr] +
                    currLum[xl] + currLum[x] + currLum[xr] +
                    nextLum[xl] + nextLum[x] + nextLum[xr]) / 9f
                val lum = currLum[x].toFloat()
                val detail = lum - blur
                if (detail == 0f) continue
                val newLum = lum + detail * gain
                // 按亮度比例缩放 RGB（保色相）；极暗像素跳过避免除零放大
                if (lum < 8f) continue
                val factor = (newLum / lum).coerceIn(0.6f, 1.6f)
                if (factor == 1f) continue
                val px = rowBuf[x]
                val a = px and 0xFF000000.toInt()
                val nr = (((px shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val ng = (((px shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
                val nb = ((px and 0xFF) * factor).toInt().coerceIn(0, 255)
                rowBuf[x] = a or (nr shl 16) or (ng shl 8) or nb
            }
            bitmap.setPixels(rowBuf, 0, w, 0, y, w, 1)
        }
    }

    /**
     * 原位应用滤镜（PRD 4.5），同样按行带分批（导出全分辨率路径）。
     * 预览层与导出层共用 FilterEngine，保证视觉一致（PRD 9.2）。
     */
    fun applyFilterInPlace(bitmap: Bitmap, filter: FilterDef, strength: Int) {
        if (strength <= 0 || filter.id == FilterLibrary.ORIGINAL.id) return
        require(bitmap.isMutable) { "applyFilterInPlace 需要可变位图" }

        forEachBand(bitmap) { pixels, count ->
            FilterEngine.apply(pixels, count, filter, strength)
        }
    }

    /** 行带遍历：限制单次像素数组内存（PRD 8.4） */
    private fun forEachBand(bitmap: Bitmap, block: (IntArray, Int) -> Unit) {
        val w = bitmap.width
        val h = bitmap.height
        var y = 0
        while (y < h) {
            val bandH = minOf(EXPORT_BAND_ROWS, h - y)
            val pixels = IntArray(w * bandH)
            bitmap.getPixels(pixels, 0, w, 0, y, w, bandH)
            block(pixels, pixels.size)
            bitmap.setPixels(pixels, 0, w, 0, y, w, bandH)
            y += bandH
        }
    }
}

/**
 * 位图解码工具（PRD 8.2 大图解码约束）
 */
object ImageDecoders {

    private const val TAG = "EditEngine"

    /** 读取原图真实尺寸（不解码像素） */
    fun querySize(resolver: ContentResolver, uri: Uri): android.util.Size? {
        return runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, it)
                }
            }.takeIf { it.outWidth > 0 && it.outHeight > 0 }
                ?.let { android.util.Size(it.outWidth, it.outHeight) }
        }.getOrNull()
    }

    /**
     * 降采样解码：长边不超过 maxEdge。
     * @return 解码后的位图；失败返回 null
     */
    fun decodeDownsampled(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap? {
        val size = querySize(resolver, uri) ?: return null
        val sample = EditMath.calcInSampleSize(size.width, size.height, maxEdge)
        return runCatching {
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }.let { opts ->
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "decodeDownsampled failed: $uri")
        }.getOrNull()
    }

    /**
     * 全分辨率解码（导出管线用）。
     * 45MP ARGB_8888 约 180MB，调用方必须确保在内存预算内（PRD 8.4）。
     */
    fun decodeFull(resolver: ContentResolver, uri: Uri): Bitmap? {
        return runCatching {
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }.let { opts ->
                resolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, opts)
                }
            }
        }.onFailure {
            Timber.tag(TAG).e(it, "decodeFull failed: $uri")
        }.getOrNull()
    }
}

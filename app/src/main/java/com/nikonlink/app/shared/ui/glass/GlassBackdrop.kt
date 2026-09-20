package com.nikonlink.app.shared.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import com.nikonlink.app.BuildConfig
import com.nikonlink.app.R
import timber.log.Timber
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 可分离 box blur，三趟 ≈ 高斯。
 *
 * 只在**降采样的小纹理**上跑（长边 ≤320px，约 4 万像素），所以 API 29 到 API 36
 * 共用一条代码路径、开销恒定 —— 这是放弃 `RenderEffect` 换来的收益：
 * 不必为 Android 10/11 单独设计一套"降级残影"（PRD §9.1 的实现修正）。
 */
internal object BoxBlur {

    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w <= 0 || h <= 0) return
        // 三趟一维滑窗 ≈ 高斯核；两数组来回倒换，src 最终必须落回 px
        var src = px
        var dst = IntArray(px.size)
        repeat(3) {
            horizontal(src, dst, w, h, radius)
            var t: IntArray = src; src = dst; dst = t
            vertical(src, dst, w, h, radius)
            t = src; src = dst; dst = t
        }
        if (src !== px) System.arraycopy(src, 0, px, 0, px.size)
    }

    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                aSum += Color.alpha(p); rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p)
            }
            for (x in 0 until w) {
                dst[row + x] = (aSum / win shl 24) or (rSum / win shl 16) or (gSum / win shl 8) or (bSum / win)
                val add = src[row + (x + r + 1).coerceAtMost(w - 1)]
                val sub = src[row + (x - r).coerceAtLeast(0)]
                aSum += Color.alpha(add) - Color.alpha(sub)
                rSum += Color.red(add) - Color.red(sub)
                gSum += Color.green(add) - Color.green(sub)
                bSum += Color.blue(add) - Color.blue(sub)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val win = r * 2 + 1
        for (x in 0 until w) {
            var aSum = 0; var rSum = 0; var gSum = 0; var bSum = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                aSum += Color.alpha(p); rSum += Color.red(p); gSum += Color.green(p); bSum += Color.blue(p)
            }
            for (y in 0 until h) {
                dst[y * w + x] = (aSum / win shl 24) or (rSum / win shl 16) or (gSum / win shl 8) or (bSum / win)
                val add = src[(y + r + 1).coerceAtMost(h - 1) * w + x]
                val sub = src[(y - r).coerceAtLeast(0) * w + x]
                aSum += Color.alpha(add) - Color.alpha(sub)
                rSum += Color.red(add) - Color.red(sub)
                gSum += Color.green(add) - Color.green(sub)
                bSum += Color.blue(add) - Color.blue(sub)
            }
        }
    }
}

/**
 * 背景纹理协调器（PRD §8.2 的性能命门）。
 *
 * 规则：
 *  - **永远不在原始分辨率上模糊**：整屏只留一张 1/8 缩放、长边 ≤320px 的复用位图；
 *  - 采集有节流，且排在下一帧执行，绝不在 `draw()` 里同步采集；
 *  - 一个内容源一个实例（挂在 source 的 tag 上），**不是每面玻璃一个**；
 *  - 玻璃面不能是 source 的子节点，否则 `draw()` 递归。
 *
 * 顺手产出纹理平均亮度，供对比度自适应用（PRD §9.3 —— 模糊与可读性共用一次采集）。
 */
class GlassCoordinator private constructor(val source: View) {

    companion object {
        /**
         * 非滚动期两次采集的最小间隔。
         *
         * 玻璃面每次绘制都可能请求重采，而重采完成又会触发一次重绘 —— 所以这个值就是
         * 纹理的刷新上限。150ms（≈6fps）对"模糊背景"已经看不出生硬，同时把 CPU
         * 压在约 1% 占空比内。
         */
        private const val MIN_REFRESH_MS = 150L

        /**
         * 滚动期的采集间隔。比静止期密，是因为这时候背景真的在动：
         * 14fps 足以让"内容从玻璃底下划过"连续，而一次采集 ≈1~2ms、占空比 ≈3%。
         */
        private const val SCROLL_REFRESH_MS = 70L

        fun attach(source: View): GlassCoordinator =
            (source.getTag(R.id.glass_coordinator) as? GlassCoordinator)
                ?: GlassCoordinator(source).also { source.setTag(R.id.glass_coordinator, it) }

        /** 从任意 view 往上找已注册的内容源；找不到返回 null（该面退化成实体材质） */
        fun find(view: View): GlassCoordinator? {
            var p = view.parent
            var depth = 0
            while (p is View && depth < 30) {
                (p.getTag(R.id.glass_coordinator) as? GlassCoordinator)?.let { return it }
                p = p.parent
                depth++
            }
            return null
        }
    }

    /** 纹理就绪后需要重绘的玻璃面（弱引用，避免 View 泄漏） */
    internal val hosts = mutableListOf<WeakReference<GlassSurfaceDrawable>>()

    /** 由 [applyGlass] 登记：一面玻璃认了一个内容源 */
    internal fun addHost(surface: GlassSurfaceDrawable) {
        hosts.removeAll { it.get() == null }
        if (hosts.none { it.get() === surface }) hosts.add(WeakReference(surface))
        if (hosts.size > 64) hosts.removeAt(0)
        assertBlurQuota()
    }

    /**
     * AC-4：真模糊面的运行时配额检查（PRD §8.2）。
     *
     * 之前这条只写在文档里、靠"一个内容源一张纹理"的结构兜着，debug 构建没有任何硬检查。
     * 现在超了就在 logcat 里叫一声（**不 crash** —— 测试包崩在用户手里比超预算更糟）。
     * 计数口径是"真的在采背景"的面，L2 控件与只淡底片的不算。
     */
    private fun assertBlurQuota() {
        if (!BuildConfig.DEBUG || quotaWarned) return
        val cap = source.resources.getInteger(R.integer.glass_max_blur_surfaces)
        val n = hosts.count { it.get()?.sampling == true }
        if (n > cap) {
            quotaWarned = true
            Timber.e(
                "AC-4 玻璃配额超限：%s 上 %d 面真模糊（上限 %d）；每多一面多一次透镜重算，滚动帧预算按 §8.2 分配",
                source.javaClass.simpleName, n, cap,
            )
        }
    }

    private var quotaWarned = false

    private val downscale = GlassTokens.num(source.context, R.dimen.glass_backdrop_downscale)
        .coerceIn(0.05f, 0.25f)
    private val maxEdge = source.resources.getInteger(R.integer.glass_backdrop_max_long_edge)

    private var tex: Bitmap? = null
    private var pix: IntArray? = null
    private var texW = 0
    private var texH = 0
    private var lastRefreshAt = 0L
    private var scheduled = false

    private val srcLoc = IntArray(2)
    private val hostLoc = IntArray(2)

    /** 纹理代数：每采集一次 +1，玻璃面据此决定自己的透镜缓存要不要重算 */
    var generation: Long = 0L
        private set

    /** 当前同屏需要的最大模糊半径（dp）。一份纹理服务多面玻璃，取最大值 */
    var currentBlurDp: Float = 24f
        private set

    /**
     * 纹理平均相对亮度（0..1）；-1 表示还没采过
     */
    var avgLuminance: Float = -1f
        private set

    /**
     * 本内容源的最小采集间隔。监看页会调大它（视频解码已经在吃 CPU/GPU），
     * 相册用默认值即可。
     */
    var minRefreshMs: Long = MIN_REFRESH_MS

    /** 滚动中：采集降频（不冻采）的开关，见 [setScrollSuppressing] */
    private var degraded = false

    /**
     * 滚动期间**降频而不冻采**（PRD §8.2 第 4 条的实现修正）。
     *
     * 原本按 AC-6 的做法是 fling 期间完全停采，省下整条列表的帧预算。但 dock 悬浮玻璃
     * 要的就是「内容从玻璃底下划过」这一眼：停采会让背景在整段滚动里定格、
     * 手指停住时突然跳一帧，反而更像贴纸而不像玻璃。
     * 一张 1/8 纹理（长边 ≤320px ≈ 3.7 万像素）连采带糊约 1~2ms，
     * 于是滚动期间把间隔放宽到 [SCROLL_REFRESH_MS]（≈14fps）—— 观感连续，预算仍可控。
     */
    fun setScrollSuppressing(suppress: Boolean) {
        if (degraded == suppress) return
        degraded = suppress
        // 停住的那一刻补采一次，把背景对齐到最终位置
        if (!suppress) invalidate()
    }

    /** 按当前是否滚动中的节流间隔，请求一次重采（滚动回调与玻璃面绘制都走这里）。
     *
     *  [dy] = 这一帧内容又滚过了多少像素。累计进 [shiftY] 后即使这次被节流挡掉，
     *  采样窗口也不会丢位移 —— 位移是**每帧免费**的，重采只负责纠正漂移。
     */
    fun requestRefresh(dy: Int = 0) {
        if (dy != 0) shiftY += dy
        if (scheduled) return
        if (SystemClock.uptimeMillis() - lastRefreshAt < refreshInterval) return
        schedule()
    }

    /**
     * 上次采集之后内容又滚过的距离（源视图坐标系，px）。
     *
     * 这是"采样区域不跟随滚动"的正解。纹理每 150ms 才重采一次，而底片的 alpha 和内容
     * 都在 60fps 动 —— 背景于是像一格一格的幻灯片，读起来就是错位 + 闪烁（设备页顶栏
     * 走查反馈第 1 条）。列表滚动是**刚体平移**：内容滚过 dy 像素，某块玻璃底下该有的
     * 背景就是纹理里往下 dy 的位置，直接平移采样窗口即可，零额外采集。
     * 每次重采归零，残余漂移（换行、插入、下拉刷新位移）由下一次采集纠正 —— 自愈。
     */
    private var shiftY = 0

    private val refreshInterval: Long
        get() = if (degraded) minOf(SCROLL_REFRESH_MS, minRefreshMs) else minRefreshMs

    val textureReady: Boolean get() = tex != null && texW > 0 && texH > 0

    /** 纹理相对内容源的缩放比（1/8）。玻璃面据此算自己的透镜缓冲区尺寸 */
    val textureScale: Float get() = downscale

    init {
        source.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (source.width > 0 && source.height > 0) invalidate()
        }
    }

    /** 强制作废并在下一帧重采（切 Tab、材质开关变更、尺寸变化） */
    fun invalidate() {
        lastRefreshAt = 0L
        // 旧纹理要作废，累计的滚动位移也就没有意义了 —— 不清零会把采样窗口错位的
        // 上一帧内容当成本帧背景
        shiftY = 0
        schedule()
    }

    /** 上报本面需要的模糊半径；半径变大时立刻重采，避免"高半径配低纹理"的糊度不一致 */
    fun reportBlurNeed(blurDp: Float) {
        if (blurDp > currentBlurDp + 0.5f) {
            currentBlurDp = blurDp
            invalidate()
        } else if (blurDp > currentBlurDp) {
            currentBlurDp = blurDp
        }
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        source.post {
            scheduled = false
            refreshNow()
        }
    }

    /** 采集：把 source 子树按 1/8 画进小位图 → CPU 模糊 → 回写 */
    fun refreshNow() {
        lastRefreshAt = SystemClock.uptimeMillis()
        val sw = source.width
        val sh = source.height
        if (sw <= 0 || sh <= 0 || !source.isShown) return

        var tw = (sw * downscale).toInt().coerceAtLeast(1)
        var th = (sh * downscale).toInt().coerceAtLeast(1)
        val edge = maxOf(tw, th)
        if (edge > maxEdge) {
            val k = maxEdge.toFloat() / edge
            tw = (tw * k).toInt().coerceAtLeast(1)
            th = (th * k).toInt().coerceAtLeast(1)
        }

        var bmp = tex
        var pixels = pix
        if (bmp == null || bmp.isRecycled || bmp.width != tw || bmp.height != th) {
            bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            tex = bmp
            pixels = IntArray(tw * th)
            pix = pixels
            texW = tw
            texH = th
        }
        if (pixels == null) return

        // 采集时让子树里的玻璃面自我屏蔽：内容列里也挂着玻璃（相册页的分段胶囊、
        // 选择操作坞），不屏蔽就会把"上一帧的自己"糊进纹理，每采一次浓一层，
        // 看着像玻璃起雾。屏蔽后那几块采到的是它们身下的内容，才是干净的背景。
        val snapshot = hosts.toList()
        snapshot.forEach { it.get()?.suppressedDraw = true }
        try {
            Canvas(bmp).apply {
                drawColor(Color.TRANSPARENT)
                scale(tw.toFloat() / sw, th.toFloat() / sh)
                source.draw(this)
            }
        } finally {
            snapshot.forEach { it.get()?.suppressedDraw = false }
        }
        bmp.getPixels(pixels, 0, tw, 0, 0, tw, th)

        // 24dp 模糊换算到 1/8 纹理上只剩几个像素，开销近乎恒定（PRD §8.2 第 2 条）
        val radius = (GlassTokens.dp(source.context, currentBlurDp) * downscale).toInt()
            .coerceIn(1, 12)
        BoxBlur.blur(pixels, tw, th, radius)
        bmp.setPixels(pixels, 0, tw, 0, 0, tw, th)
        generation++
        // 这张纹理对应的就是"此刻的内容位置"，累计位移到此作废
        shiftY = 0

        measureLuminance(pixels)
        // 只重绘登记过的面，不整树 invalidate
        val iter = hosts.iterator()
        while (iter.hasNext()) {
            val ref = iter.next().get()
            if (ref == null) iter.remove() else ref.hostInvalidate()
        }
    }

    /** 内存吃紧时释放纹理，下次采集重建（PRD §8.3） */
    fun release() {
        tex?.recycle()
        tex = null
        pix = null
        texW = 0
        texH = 0
        avgLuminance = -1f
        lastRefreshAt = 0L
    }

    /** 纹理中对应 [host] 屏幕区域的子矩形（纹理坐标系）；失败返回 false */
    fun mapRect(host: View, out: Rect): Boolean {
        if (!textureReady) return false
        val sw = source.width
        val sh = source.height
        if (sw <= 0 || sh <= 0) return false
        source.getLocationInWindow(srcLoc)
        host.getLocationInWindow(hostLoc)
        val sx = texW.toFloat() / sw
        val sy = texH.toFloat() / sh
        val left = (hostLoc[0] - srcLoc[0]) * sx
        val h = host.height * sy
        // 见 [shiftY]：内容滚过 dy，背景就要从纹理里更靠下 dy 的位置取。
        // 保持窗口高度做钳制，所以**贴底的 dock 自动不动**（它的窗口本来就已经抵到
        // texH —— 新内容是从视口底下进来的，那张纹理里根本还没有它），
        // 而贴顶的顶栏会跟着滚 —— 它要的内容就在视口里，早就采到了。
        val top = ((hostLoc[1] - srcLoc[1] + shiftY) * sy)
            .coerceIn(0f, (texH - h).coerceAtLeast(0f))
        out.set(
            left.toInt().coerceIn(0, texW - 1),
            top.toInt().coerceIn(0, texH - 1),
            (left + host.width * sx).toInt().coerceIn(1, texW),
            (top + h).toInt().coerceIn(1, texH),
        )
        return out.width() > 0 && out.height() > 0
    }

    fun bitmap(): Bitmap? = tex?.takeUnless { it.isRecycled }

    /**
     * 读出纹理中 [src] 区域的像素，交给调用方做透镜位移。
     * 走像素而不是再建一张位图：区域只有几个 k px，读一次比 allocate + draw 便宜。
     */
    fun readRegion(src: Rect, dst: IntArray): Boolean {
        val bmp = bitmap() ?: return false
        if (!textureReady) return false
        if (dst.size < src.width() * src.height()) return false
        return try {
            bmp.getPixels(dst, 0, src.width(), src.left, src.top, src.width(), src.height())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun measureLuminance(pixels: IntArray) {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            if (Color.alpha(p) > 8) {
                sum += relativeLuminance(p)
                n++
            }
            i += 8
        }
        avgLuminance = if (n == 0) -1f else (sum / n).toFloat().let { raw ->
            // 指数平滑：整屏平均亮度会随内容滚动大幅摆动，直接喂给 tint 自适应就是
            // 每 150ms 换一次档 —— 玻璃"呼吸/闪"的观感来自这里。平滑后同一块玻璃的
            // 底色在一段滚动里保持稳定（配合 guardedTint 的档位粘滞）。
            if (avgLuminance < 0f) raw else 0.75f * avgLuminance + 0.25f * raw
        }
    }
}

/**
 * 薄透镜折射（PRD §3.6-B 的等价实现）。
 *
 * iOS 的"液态"感主要来自这里，而不是模糊：玻璃边缘把背景**弯出去**、中心略微**放大**。
 * 成熟做法（AGSL `RuntimeShader` + displacement map）要 API 33，本仓 minSdk 29 用不了；
 * 但我们本来就为模糊持有一张 1/8 降采样纹理，所以直接在小纹理上做同样的数学：
 * 一块玻璃面的 footprint 只有 150×10 上下 ≈ 1500 像素，一次重算几十微秒，
 * **反而比 GPU 路径更省**（不必为每个面开 render node 链）。
 *
 * 色散（RGB 三通道按不同半径取样）默认关闭：`colors.xml:39` 的灰阶规范，见 PRD §3.7 / Q1。
 */
internal object GlassLens {

    /**
     * @param src 纹理中该玻璃面对应的区域像素，尺寸 [sw] x [sh]
     * @param dst 输出，同尺寸
     * @param cornerPx 玻璃圆角半径（纹理像素）
     * @param bandPx 透镜"厚度"：离边缘多远之内开始弯折（纹理像素）
     * @param strength 边缘向外位移的最大幅度（纹理像素）
     * @param magnify 中心放大倍率，1.0 = 不放大
     * @param dispersion 色散强度（通道位移比例），0 = 关闭
     */
    fun warp(
        src: IntArray,
        dst: IntArray,
        sw: Int,
        sh: Int,
        cornerPx: Float,
        bandPx: Float,
        strength: Float,
        magnify: Float,
        dispersion: Float,
    ) {
        val cx = (sw - 1) / 2f
        val cy = (sh - 1) / 2f
        // 圆角矩形的符号距离场：负值在内部，0 在边界上
        val rx = (cornerPx.coerceAtMost(minOf(cx, cy))).toInt()
        val band = bandPx.coerceAtMost(minOf(cx, cy)).coerceAtLeast(1f)
        var y = 0
        while (y < sh) {
            var x = 0
            while (x < sw) {
                // 1) 中心放大：把取样坐标往中心收
                var sx = cx + (x - cx) / magnify
                var sy = cy + (y - cy) / magnify
                // 2) 边缘折射：越靠近边界，越把采样点往**外**推
                val ax = abs(sx - cx) - (cx - rx)
                val ay = abs(sy - cy) - (cy - rx)
                val outside = hypot(ax.coerceAtLeast(0f), ay.coerceAtLeast(0f))
                val inside = min(max(ax, ay), 0f)
                val sd = outside + inside - rx          // <0 在内部
                val t = (-sd / band).coerceIn(0f, 1f)   // 0=贴边 1=深处
                if (t < 1f) {
                    val k = (1f - t) * (1f - t) * strength  // 二次衰减，边缘最弯
                    val dx = sx - cx
                    val dy = sy - cy
                    val len = hypot(dx, dy)
                    if (len > 0.5f) {
                        val f = k / len
                        sx += dx * f
                        sy += dy * f
                    }
                }
                val si = sx.roundToInt().coerceIn(0, sw - 1)
                val ti = sy.roundToInt().coerceIn(0, sh - 1)
                val p = src[ti * sw + si]
                dst[y * sw + x] = if (dispersion > 0.001f && t < 1f) {
                    // 三通道各自按不同半径取样：蓝的弯得最少、红的弯得最多
                    val k = (1f - t) * (1f - t) * strength * dispersion
                    val dx = sx - cx
                    val dy = sy - cy
                    val len = hypot(dx, dy).coerceAtLeast(0.5f)
                    val ux = dx / len
                    val uy = dy / len
                    val r = sample(src, sw, sh, sx + ux * k, sy + uy * k)
                    val b = sample(src, sw, sh, sx - ux * k, sy - uy * k)
                    Color.rgb(Color.red(r), Color.green(p), Color.blue(b))
                } else {
                    p
                }
                x++
            }
            y++
        }
    }

    private fun sample(src: IntArray, sw: Int, sh: Int, x: Float, y: Float): Int =
        src[y.roundToInt().coerceIn(0, sh - 1) * sw + x.roundToInt().coerceIn(0, sw - 1)]

    private fun hypot(a: Float, b: Float): Float = Math.hypot(a.toDouble(), b.toDouble()).toFloat()

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}

/** WCAG 相对亮度 */
internal fun relativeLuminance(color: Int): Double {
    fun ch(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * ch(Color.red(color)) + 0.7152 * ch(Color.green(color)) +
        0.0722 * ch(Color.blue(color))
}

/** WCAG 对比度 */
internal fun contrastRatio(fg: Int, bg: Int): Double {
    val a = relativeLuminance(fg)
    val b = relativeLuminance(bg)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** 半透明 tint 覆盖在亮度为 [luminance] 的内容上之后的合成结果色 */
internal fun compositeOnLuminance(tint: Int, luminance: Float): Int {
    if (luminance < 0f) return tint
    val a = Color.alpha(tint) / 255f
    fun mix(v: Int): Int = (v * a + luminance * 255f * (1f - a)).toInt().coerceIn(0, 255)
    return Color.rgb(mix(Color.red(tint)), mix(Color.green(tint)), mix(Color.blue(tint)))
}

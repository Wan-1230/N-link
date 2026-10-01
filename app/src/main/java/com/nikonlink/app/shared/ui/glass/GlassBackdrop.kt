package com.nikonlink.app.shared.ui.glass

import android.content.Context
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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 可分离 box blur，三趟 ≈ 高斯。
 *
 * 只在**降采样的小纹理**上跑（长边 ≤320px，约 4 万像素），所以 API 29 到 API 36
 * 共用一条代码路径、开销恒定 —— 这是放弃 `RenderEffect` 换来的收益：
 * 不必为 Android 10/11 单独设计一套"降级残影"（PRD §9.1 的实现修正）。
 *
 * **必须先预乘再模糊**（v2.6.2 修复）。滑窗求的是算术平均，而 `Bitmap.getPixels()`
 * 给出的是**非预乘** ARGB：一个 alpha=0 的透明像素其 RGB 仍是 0（黑），直接平均就会把
 * 黑算进邻域 —— 纹理里被自我屏蔽的玻璃区域、以及内容没铺满的空白全是这种像素，
 * 于是每条玻璃边缘外翻出一圈灰黑晕（走查「毛玻璃发灰、脏」的主因）。
 * 预乘后透明像素对 RGB 的贡献自然为 0，模糊完再除回 alpha 即可。
 */
internal object BoxBlur {

    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w <= 0 || h <= 0) return
        toPremultiplied(px)
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
        fromPremultiplied(px)
    }

    /** ARGB → 预乘（RGB 乘以 alpha/255）。透明像素的 RGB 归零，才不会被平均进邻域 */
    private fun toPremultiplied(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            val a = Color.alpha(p)
            if (a == 255) continue
            if (a == 0) {
                px[i] = 0
                continue
            }
            px[i] = (a shl 24) or
                (Color.red(p) * a / 255 shl 16) or
                (Color.green(p) * a / 255 shl 8) or
                (Color.blue(p) * a / 255)
        }
    }

    /** 预乘 → 非预乘（除回 alpha）。alpha=0 的像素保持全 0，不再凭空造色 */
    private fun fromPremultiplied(px: IntArray) {
        for (i in px.indices) {
            val p = px[i]
            val a = Color.alpha(p)
            if (a == 0 || a == 255) continue
            val inv = 255f / a
            px[i] = (a shl 24) or
                (min(255, (Color.red(p) * inv).toInt()) shl 16) or
                (min(255, (Color.green(p) * inv).toInt()) shl 8) or
                min(255, (Color.blue(p) * inv).toInt())
        }
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
         * sRGB → 线性查找表。饱和度提升必须在线性空间做才物理正确（QmDeve 的
         * `saturateColor` 同款流程），逐像素 `pow()` 太贵，查表后整张纹理亚毫秒。
         */
        private val SRGB_TO_LIN = FloatArray(256).also { t ->
            for (i in 0..255) {
                val s = i / 255f
                t[i] = if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
            }
        }

        /** 线性 → sRGB，量化到 257 级；在 1/8 模糊纹理上看不出误差 */
        private val LIN_TO_SRGB = IntArray(257).also { t ->
            for (i in 0..256) {
                val l = i / 256f
                val s = if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
                t[i] = (s.coerceIn(0f, 1f) * 255f).roundToInt()
            }
        }

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

        fun attach(source: View): GlassCoordinator {
            val existing = source.getTag(R.id.glass_coordinator) as? GlassCoordinator
            if (existing != null) return existing
            val c = GlassCoordinator(source)
            source.setTag(R.id.glass_coordinator, c)
            live.removeAll { it.get() == null }
            if (live.size > 32) live.removeAt(0)
            live.add(WeakReference(c))
            // PRD §8.3 写了"内存吃紧时释放纹理"，但 [GlassCoordinator.release] 全仓
            // 没有任何调用点 —— 回收通道没接线，纹理只在进程死时才归还。这里补上：
            // 首次 attach 时挂一次进程级回调，后台 / onLowMemory 时统一释放。
            installMemoryWatcher(source.context)
            return c
        }

        /** 进程内活着的全部协调器（弱引用），供内存回调统一释放 */
        private val live = mutableListOf<WeakReference<GlassCoordinator>>()

        @Volatile private var watcherInstalled = false

        /**
         * 挂 `ComponentCallbacks2`：应用转入后台或系统内存吃紧时释放所有纹理。
         *
         * 只装一次（[watcherInstalled]），用 applicationContext 所以不随 Activity 泄漏。
         * 释放后下一次绘制会走 [GlassCoordinator.refreshNow] 自动重建，不需要调用方配合。
         */
        private fun installMemoryWatcher(context: Context) {
            if (watcherInstalled) return
            val app = context.applicationContext ?: return
            runCatching {
                app.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        // UI_HIDDEN(20) = 应用进后台；再往上(40/60/80)是系统真的在收内存
                        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                            releaseAll()
                        }
                    }

                    override fun onLowMemory() = releaseAll()

                    override fun onConfigurationChanged(p0: android.content.res.Configuration) = Unit
                })
            }.onSuccess { watcherInstalled = true }
        }

        private fun releaseAll() {
            val snapshot = live.toList()
            live.clear()
            snapshot.forEach { runCatching { it.get()?.release() } }
        }

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
        get() {
            // 传输期整屏让路：采集间隔翻倍（PRD §8.4 第 2 条 / AC-7）。
            // 与 base() 里"CPU 侧模糊退成静态 tint"是同一个开关的两半。
            val base = if (degraded) minOf(SCROLL_REFRESH_MS, minRefreshMs) else minRefreshMs
            return if (GlassBudget.transferring) base * 2 else base
        }

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

    /**
     * 按当前登记的玻璃面重算所需半径（v2.6.2 修复 [reportBlurNeed] 只增不减）。
     *
     * 一份纹理服务同屏多面玻璃，取的是最大值。原实现只有"变大"这一条路：监看页
     * HUD(60dp) 一旦出现过，`currentBlurDp` 就永久停在 60，退回主界面后 dock(24dp)
     * 仍按 60dp 糊 —— 悬浮控件被糊得过厚、内容划过时失去细节，"玻璃"变成"毛玻璃贴纸"。
     * 每次采集前重新扫一遍活着的宿主，半径才会跟着页面回落。
     */
    private fun syncBlurNeed() {
        var maxDp = 0f
        var i = 0
        while (i < hosts.size) {
            val s = hosts[i].get()
            if (s == null) {
                hosts.removeAt(i)
                continue
            }
            val b = s.material.blurDp
            if (b > maxDp) maxDp = b
            i++
        }
        if (maxDp > 0f && maxDp != currentBlurDp) currentBlurDp = maxDp
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

        // 先按**当前还活着**的玻璃面重算所需半径，再定模糊核（见 [syncBlurNeed]）
        syncBlurNeed()
        // 24dp 模糊换算到 1/8 纹理上只剩几个像素，开销近乎恒定（PRD §8.2 第 2 条）。
        //
        // 上限不再写死 12（v2.6.2 修复）：12 纹理像素在 3x 屏上只折合 96/3 = 32dp，
        // 于是 `glass_blur_xl`(60dp) 的 HUD 从来没拿到过它要的糊度，而且这个截断值
        // 随 density 漂移（2x 屏折合 48dp、3x 屏只有 32dp）—— 同一份 token 在不同
        // 手机上表现为两种材质。改成按纹理短边的 1/4 封顶：物理含义是"核不超过纹理
        // 的 1/4，再糊就整片摊平"，与密度无关，滑窗是 O(1)/像素所以也不增加开销。
        val radius = (GlassTokens.dp(source.context, currentBlurDp) * downscale).toInt()
            .coerceIn(1, (minOf(tw, th) / 4).coerceAtLeast(4))
        BoxBlur.blur(pixels, tw, th, radius)
        saturate(pixels)
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
        // 换代：否则玻璃面会拿"上一代"的透镜缓存继续画，而那批像素已经随位图回收了
        generation++
        shiftY = 0
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

    /**
     * 纹理中 [src] 区域的平均相对亮度（0..1）；失败 / 区域全透明返回 -1。
     *
     * v2.6.2：对比度自适应原本吃的是 [avgLuminance] —— **整张屏幕**的平均亮度。
     * 但玻璃是局部材质：dock 压在暗色照片上时整屏可能仍然很亮（上方是白色列表），
     * 于是 tint 不加浓 → 白字压在暗照片上不可读；反过来整屏偏暗时 dock 明明压在
     * 白底上却被加浓到不透明，"玻璃"被自己的可读性保护吃掉。
     * 苹果的 material 是按**材质所在区域**自适应的，这里补上这一层。
     * 缓冲复用、采样步长 4（区域只有几千像素，比整屏那次密一点代价仍可忽略）。
     */
    fun regionLuminance(src: Rect): Float {
        val bmp = bitmap() ?: return -1f
        if (!textureReady) return -1f
        val w = src.width()
        val h = src.height()
        if (w <= 0 || h <= 0) return -1f
        val need = w * h
        if (lumBuf.size < need) lumBuf = IntArray(need)
        return try {
            bmp.getPixels(lumBuf, 0, w, src.left, src.top, w, h)
            var sum = 0.0
            var n = 0
            var i = 0
            while (i < need) {
                val p = lumBuf[i]
                if (Color.alpha(p) > 8) {
                    sum += relativeLuminance(p)
                    n++
                }
                i += 4
            }
            if (n == 0) -1f else (sum / n).toFloat()
        } catch (e: Exception) {
            -1f
        }
    }

    private var lumBuf = IntArray(0)

    /**
     * 苹果式 vibrancy：模糊后把饱和度轻微抬一档（线性空间）。
     *
     * 对照 QmDeve 的 AGSL（`saturateColor`，sRGB→线性→抬饱和→回 sRGB）与
     * QWEA0 的 `saturation = 140%`：真实玻璃会把透过的颜色稍微"提纯"，纯 alpha 叠底色
     * 做不出来这个效果 —— 这就是"玻璃比毛玻璃贵"的那一点。灰阶 UI 上几乎不可见，
     * 压在照片 / 彩色内容上才显出来，所以零风险。
     *
     * 用 256 项 LUT 做线性转换：整张 4 万像素 × 15 次查表 ≈ 亚毫秒，不动帧预算。
     */
    private fun saturate(px: IntArray) {
        val amount = GlassTokens.num(source.context, R.dimen.glass_saturation)
        if (amount <= 1.001f) return
        for (i in px.indices) {
            val p = px[i]
            if (Color.alpha(p) == 0) continue
            val lr = SRGB_TO_LIN[Color.red(p)]
            val lg = SRGB_TO_LIN[Color.green(p)]
            val lb = SRGB_TO_LIN[Color.blue(p)]
            val y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            px[i] = Color.argb(
                Color.alpha(p),
                LIN_TO_SRGB[((lr + (lr - y) * (amount - 1f)) * 256f).roundToInt().coerceIn(0, 256)],
                LIN_TO_SRGB[((lg + (lg - y) * (amount - 1f)) * 256f).roundToInt().coerceIn(0, 256)],
                LIN_TO_SRGB[((lb + (lb - y) * (amount - 1f)) * 256f).roundToInt().coerceIn(0, 256)],
            )
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
 * **v2.6.3 对照 QmDeve/AndroidLiquidGlassView 的 AGSL 实现重写**（MIT，主参考）：
 *
 *  1. **位移剖面换成 `circleMap`**：原实现用 `(1-t)²`，位移沿整条带摊开，边缘只是一条
 *     略弯的带，读不出"透镜"。QmDeve 用 `1 - sqrt(1 - u²)`（u = 1 - 深度/带高），
 *     半带处位移只剩 **13%**（二次剖面还有 25%）—— 位移被压进贴边一薄层，
 *     形成苹果那条细而亮的压缩镜面环，内侧只留轻微放大的尾巴。
 *  2. **方向换成 SDF 梯度**：原来用"从中心指向该点"的径向。对长条面（dock 340×62、
 *     通栏预览条）这是错的 —— 长边中点的径向是斜的，折射会斜着拐向角落；
 *     表面外法线才是物理正确的方向。圆角矩形 SDF 的解析梯度是现成的，直接用。
 *  3. **采样区外扩（pad）**：向外折射意味着贴边像素要采到 footprint **外面**的内容。
 *     原实现只读 footprint 本身，采样点一出界就被 clamp 到自身边界 —— 表现为边缘
 *     一圈拖影。现在由调用方把读取区域外扩 pad 像素（[drawBackdrop] 负责），
 *     本函数收 [ox]/[oy] 表示 footprint 在缓冲区里的偏移，出界仍 clamp 但那是真边界。
 *
 * 色散（RGB 三通道按不同半径取样）默认开启 0.10（参考两边项目的默认值）。
 * 注意这偏离了 PRD §3.7 的"默认关"决定 —— 理由见 UiFlags.DISPERSION 的注释，可一键回退。
 */
internal object GlassLens {

    /**
     * @param src 外扩后的背景区域像素，尺寸 [sw] x [sh]
     * @param dst 输出，尺寸 [fw] x [fh]（= footprint 本身）
     * @param ox/oy footprint 原点在 [src] 缓冲区里的坐标
     * @param cornerPx 玻璃圆角半径（纹理像素）
     * @param bandPx 透镜"厚度"：离边缘多远之内开始弯折（纹理像素）
     * @param strength 边缘向外位移的最大幅度（纹理像素）
     * @param magnify 中心放大倍率，1.0 = 不放大
     * @param dispersion 色散强度（相对位移比例），0 = 关闭
     */
    fun warp(
        src: IntArray,
        dst: IntArray,
        sw: Int,
        sh: Int,
        ox: Int,
        oy: Int,
        fw: Int,
        fh: Int,
        cornerPx: Float,
        bandPx: Float,
        strength: Float,
        magnify: Float,
        dispersion: Float,
    ) {
        // footprint 几何（在 padded 缓冲区坐标系里）
        val cx = ox + fw / 2f
        val cy = oy + fh / 2f
        val hw = fw / 2f
        val hh = fh / 2f
        val r = cornerPx.coerceAtMost(minOf(hw, hh)).coerceAtLeast(0f)
        val band = bandPx.coerceAtMost(minOf(hw, hh)).coerceAtLeast(1f)
        val kDisp = strength * dispersion

        var y = 0
        while (y < fh) {
            var x = 0
            while (x < fw) {
                val px = ox + x
                val py = oy + y
                // 1) 中心放大：把取样坐标往中心收
                var sx = cx + (px - cx) / magnify
                var sy = cy + (py - cy) / magnify

                // 2) 圆角矩形 SDF（footprint 局部）：负值在内部，0 在边界上
                val lx = sx - cx
                val ly = sy - cy
                val qx = abs(lx) - (hw - r)
                val qy = abs(ly) - (hh - r)
                val outX = qx.coerceAtLeast(0f)
                val outY = qy.coerceAtLeast(0f)
                val outside = hypot(outX, outY)
                val inside = min(max(qx, qy), 0f)
                val sd = outside + inside - r
                val depth = -sd          // 0=贴边，越大越深

                var k = 0f
                var gx = 0f
                var gy = 0f
                if (depth < band) {
                    // 位移剖面：circleMap（见类注释 1）
                    val u = (1f - depth / band).coerceIn(0f, 1f)
                    k = (1f - sqrt((1f - u * u).coerceAtLeast(0f))) * strength
                    // 方向：SDF 解析梯度 = 表面外法线（见类注释 2）
                    if (outX > 0f || outY > 0f) {
                        if (outside > 0.0001f) {
                            gx = (if (lx < 0f) -outX else outX) / outside
                            gy = (if (ly < 0f) -outY else outY) / outside
                        }
                    } else if (qx >= qy) {
                        gx = if (lx < 0f) -1f else 1f
                    } else {
                        gy = if (ly < 0f) -1f else 1f
                    }
                    sx += gx * k
                    sy += gy * k
                }

                val p = sample(src, sw, sh, sx, sy)
                dst[y * fw + x] = if (kDisp > 0.0001f && k > 0f) {
                    // 三通道沿法线取不同折射量：红弯最多、蓝最少（正常色散）。
                    // 采样点已经位移过 k，这里在此基础上再 ±kDisp。
                    val rr = sample(src, sw, sh, sx + gx * kDisp, sy + gy * kDisp)
                    val bb = sample(src, sw, sh, sx - gx * kDisp, sy - gy * kDisp)
                    // `Color.argb` 保留原 alpha：纹理里有透明区域（被自我屏蔽的玻璃面、
                    // 内容没铺满的空白），用 `Color.rgb` 会把它们钉成不透明色块。
                    Color.argb(Color.alpha(p), Color.red(rr), Color.green(p), Color.blue(bb))
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

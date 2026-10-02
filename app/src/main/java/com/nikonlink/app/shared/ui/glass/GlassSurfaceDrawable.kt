package com.nikonlink.app.shared.ui.glass

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import com.nikonlink.app.R
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * 一面玻璃的绘制器（PRD §3.1–§3.4、§3.6-A）。
 *
 * 四条边信息定义玻璃，缺一条就退化成"半透明灰块"：
 *   ①外描边 ②内顶高光 ③内底反光 ④四角透镜亮线；底下再垫 tint 与背景模糊。
 *
 * 两个实现约束：
 *  - **绘制路径零分配**（shader 在 bounds 变化时重建，Paint/Path 全部复用），
 *    否则每帧 4 个对象会让相册页的 fling 直接掉帧（AC-6）；
 *  - 按压反馈走 `Drawable.onStateChange`，**不占用 OnTouchListener** ——
 *    既拿到"玻璃被按下去"的手感，又不会和相册页长按拖选互踩（PRD §2.3 D1）。
 */
class GlassSurfaceDrawable(
    private val hostRef: WeakReference<View>,
    @Volatile private var _material: GlassMaterial,
    private val coordinator: GlassCoordinator?,
) : Drawable() {

    /** 换材质时（开关重涂）作废透镜缓存，否则会拿旧半径/强度继续画 */
    var material: GlassMaterial
        get() = _material
        set(value) {
            _material = value
            lensGen = -1L
            guardKey = Long.MIN_VALUE
            guardAlpha = -1
            // 材质换了（开关重涂 / 深浅色切换）旧的亮度惯性没有意义，重新收敛
            lumEma = -1f
        }

    /**
     * 释放本面私有的像素缓存（透镜输入/输出缓冲与输出位图）。
     *
     * v2.6.2：这些缓冲原来是"只增不减"的 —— [applyGlass] 每次调用都会 new 一个
     * drawable，而开关切换、切页、旋转都会反复调用它，旧 drawable 虽然可被 GC，
     * 但它抱着的那张 `lensOut` 位图要等 GC 才归还。显式回收让内存立刻可用。
     */
    fun release() {
        lensOut?.takeUnless { it.isRecycled }?.recycle()
        lensOut = null
        lensSrc = IntArray(0)
        lensDst = IntArray(0)
        lensGen = -1L
        lensRect.setEmpty()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val cornerPath = Path()
    private val boundsRect = RectF()
    private val srcRect = Rect()

    /** 折射用的**外扩**读取区域（footprint ± pad），见 [drawBackdrop] */
    private val texRect = Rect()

    private var rimShader: LinearGradient? = null
    private var rimH = 0f
    private var onePx = 1f

    /** 屏幕密度；[onBoundsChange] 里也要用（算高光带上限），所以单独存一份 */
    private var density = 1f

    /** v2.6.2：上沿镜面亮线的厚度（px）与底边内阴影带的高度（px） */
    private var specH = 0f
    private var innerH = 0f
    private var innerShader: LinearGradient? = null

    /**
     * 本面区域亮度的指数平滑值（-1 = 还没取到）。
     *
     * 区域亮度每次重采都会变（内容从底下划过），直接喂给 [guardedTint] 会让 tint 档位
     * 随每一张纹理抖动 —— 这就是"玻璃在呼吸"的观感来源。先做 EMA 再自适应，
     * 配合 [guardedTint] 的档位粘滞，同一段滚动里底色才稳得住。
     */
    private var lumEma = -1f

    /** 0=静止 1=按下：驱动 rim 削弱 + tint 提浓 */
    private var press = 0f
    private var animator: ValueAnimator? = null
    private var pressed = false

    private var guardKey = Long.MIN_VALUE
    private var guardTint = 0

    /** 上一次选中的 tint alpha；粘滞判定用它，避免档位随亮度微摆来回跳 */
    private var guardAlpha = -1

    // 透镜结果缓存：只在纹理换代或区域尺寸/位置变化时重算，滚动中每帧只花一次位图 blit
    private var lensSrc = IntArray(0)
    private var lensDst = IntArray(0)
    private var lensOut: Bitmap? = null
    private var lensGen = -1L
    private val lensRect = Rect()

    override fun isStateful(): Boolean = true

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val res = hostRef.get()?.resources
        density = res?.displayMetrics?.density?.coerceAtLeast(1f) ?: 1f
        val h = bounds.height().toFloat()

        // v2.6.2：高光带原来恒取面高的一半 —— 62dp 的 dock 就是 31dp 的白色渐变铺在上
        // 半张面上，读作磨砂塑料而不是玻璃。苹果那条高光是**贴着上沿、快速衰减**的：
        // 这里改成"面高的 35% 与 16dp 取小"，小胶囊不吃亏、大面板不再白掉半张。
        val rimMax = num(R.dimen.glass_rim_band_max_dp, 16f) * density
        rimH = (h * 0.35f).coerceAtMost(rimMax).coerceAtLeast(1f)
        rimShader = LinearGradient(
            0f, 0f, 0f, rimH,
            intArrayOf(Color.WHITE, Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.28f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 上沿镜面亮线：1.2dp，是整块玻璃最亮的一条
        specH = (num(R.dimen.glass_specular_h_dp, 1.2f) * density).coerceAtMost(h * 0.25f)

        // 底边内阴影：从底边向上衰减的短暗带，亮线之下的"厚度"
        val innerMax = num(R.dimen.glass_inner_band_max_dp, 8f) * density
        innerH = (h * 0.35f).coerceAtMost(innerMax).coerceAtLeast(1f)
        innerShader = LinearGradient(
            0f, h, 0f, h - innerH,
            // 停点：底边最暗 → 1/4 处已淡掉大半 → 归零。峰值压在边上才有"内侧"的感觉，
            // 均匀铺开会变成半张面的灰。
            intArrayOf(Color.BLACK, Color.argb(90, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.25f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 尺寸变了（旋转 / 分屏 / 首次布局）纹理坐标要重算
        coordinator?.invalidate()
    }

    /** 读无量纲 token（写成 `format=float` 的 item）；宿主已回收时退回 [fallback] */
    private fun num(id: Int, fallback: Float): Float =
        hostRef.get()?.context?.let { GlassTokens.num(it, id) } ?: fallback

    override fun onStateChange(state: IntArray): Boolean {
        val now = material.glass && state.any { it == android.R.attr.state_pressed }
        if (now == pressed) return false
        pressed = now
        animatePress(if (now) 1f else 0f)
        return true
    }

    private fun animatePress(to: Float) {
        val ctx = hostRef.get()?.context ?: run {
            press = to
            return
        }
        animator?.cancel()
        if (!UiFlags.motionEnabled(ctx)) {
            press = to
            invalidateSelf()
            return
        }
        animator = ValueAnimator.ofFloat(press, to).apply {
            duration = 100L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                press = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    /** 纹理就绪后由协调器回调 */
    fun hostInvalidate() = invalidateSelf()

    /** 这一面是否**真的**在采背景 —— AC-4 配额的计数口径：有源 + 材质生效 + 开了模糊 */
    internal val sampling: Boolean
        get() = coordinator != null && material.glass && material.blurDp > 0f

    /**
     * 采集期间自我屏蔽（由 [GlassCoordinator] 置位）。
     *
     * 内容列里也挂着玻璃面（相册页的分段胶囊、选择操作坞），而采集就是把这个子树整体
     * 画进纹理 —— 不屏蔽的话纹理里会有"上一帧的自己"，每采一次再糊进去一层，
     * 看久了像玻璃起雾（自反馈）。屏蔽后这块区域在纹理里画成父层底色，
     * 也就是它真正遮住的那部分内容，取到的才是干净的背景。
     */
    @Volatile internal var suppressedDraw = false

    override fun draw(canvas: Canvas) {
        if (suppressedDraw) return
        val host = hostRef.get() ?: return
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val m = material
        if (onePx != host.resources.displayMetrics.density) {
            onePx = host.resources.displayMetrics.density.coerceAtLeast(1f)
        }

        boundsRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(boundsRect, m.radiusPx, m.radiusPx, Path.Direction.CW)

        // ---- 经典外观 / 关闭玻璃：画成实体表面，不留半吊子中间态 ----
        // 用材质自己的不透明底而不是全局 surface，暗面 HUD 才不会翻成白卡。
        if (!m.glass) {
            fill.shader = null
            fill.style = Paint.Style.FILL
            // v2.6.2：用 [GlassMaterial.solid] 而不是 [GlassMaterial.opaqueTint]。
            // opaqueTint 是"把半透明 tint 的 alpha 钉成 255"，暗面 HUD 的 tint 是
            // #99000000，钉完就是纯黑 —— 比 v2.2 的 60% 黑更黑一截。
            fill.color = m.solid
            canvas.drawPath(clipPath, fill)
            stroke.strokeWidth = m.strokeWidthPx.coerceAtLeast(MIN_STROKE_PX)
            stroke.color = ContextCompat.getColor(host.context, R.color.outline)
            canvas.drawPath(clipPath, stroke)
            return
        }

        val sc = canvas.save()
        canvas.clipPath(clipPath)

        // ---- ① 背景模糊 + 边缘折射 ----
        var backdropUsed = false
        val coord = coordinator
        if (m.blurDp > 0f && coord != null) {
            coord.reportBlurNeed(m.blurDp)
            if (coord.mapRect(host, srcRect)) {
                backdropUsed = drawBackdrop(canvas, coord, m)
            }
            if (!backdropUsed) coord.requestRefresh()
        }

        // ---- ② 染色 tint（含对比度自适应） ----
        // v2.6.2：亮度取自**本面在纹理里的那块区域**，不是整屏平均（见 [GlassCoordinator.regionLuminance]）。
        // 只有真的压了文字的面（onColor != 0）才需要算，纯装饰面跳过这次采样。
        val lum = if (backdropUsed && coord != null && m.onColor != 0) {
            val raw = coord.regionLuminance(srcRect)
            if (raw < 0f) {
                lumEma
            } else {
                lumEma = if (lumEma < 0f) raw else lumEma * LUM_SMOOTH + raw * (1f - LUM_SMOOTH)
                lumEma
            }
        } else {
            -1f
        }
        fill.shader = null
        fill.style = Paint.Style.FILL
        fill.color = guardedTint(host, m, lum)
        canvas.drawRect(boundsRect, fill)

        // 按下：提浓，"玻璃变厚了"
        if (press > 0f && m.pressAlphaDelta > 0) {
            fill.color = Color.argb(
                (m.pressAlphaDelta * press).toInt().coerceIn(0, 255),
                Color.red(m.tintColor), Color.green(m.tintColor), Color.blue(m.tintColor),
            )
            canvas.drawRect(boundsRect, fill)
        }

        // ---- ③ 内顶高光：顶端 1dp 量级的渐变，按下时被削弱（"光被打散"） ----
        val rimKeep = (1f - (1f - m.rimPressKeep) * press).coerceIn(0f, 1f)
        rimShader?.let { shader ->
            if (rimKeep > 0.02f) {
                fill.shader = shader
                fill.alpha = (Color.alpha(m.rimTopColor) * rimKeep).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, w, rimH, fill)
                fill.shader = null
                fill.alpha = 255
            }
        }

        // ---- ③b 上沿镜面亮线（v2.6.2 新增）----
        // 苹果玻璃最像玻璃的一笔：上沿那条比周围亮得多的细线。原来的四层里只有一条
        // 铺满半张面的白色渐变，缺了这条"峰"，所以整体读作磨砂亚克力而不是玻璃。
        if (m.specularColor != 0 && rimKeep > 0.02f && specH > 0f) {
            fill.color = scaleAlpha(m.specularColor, rimKeep)
            canvas.drawRect(0f, 0f, w, specH, fill)
        }

        // ---- ③c 底边内阴影（v2.6.2 新增）----
        // 只在玻璃**内侧**、紧贴底边的一小段压暗。没有它，玻璃就是一张贴在内容上的
        // 半透明片；有了它才有"这块东西有厚度、光是从上面来的"这个读数。
        innerShader?.let { shader ->
            if (m.innerShadowColor != 0 && rimKeep > 0.02f) {
                fill.shader = shader
                fill.alpha = (Color.alpha(m.innerShadowColor) * rimKeep).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, h - innerH, w, h, fill)
                fill.shader = null
                fill.alpha = 255
            }
        }

        // ---- ④ 内底反光：均匀的细亮线 ----
        fill.color = scaleAlpha(m.rimBottomColor, rimKeep)
        canvas.drawRect(0f, h - onePx, w, h, fill)

        canvas.restoreToCount(sc)

        // ---- ⑤ 外描边 + 四角透镜亮线（折射假象的主要来源）----
        // v2.6.2：下限原来是 onePx（= density）。3x 屏上会把 0.5dp 的设计值顶成 3px，
        // 也就是 1dp —— 边框比设计粗一倍，正是"塑料边框"的观感来源。Android 支持亚像素
        // 描边（会抗锯齿成发丝线），这里只保一个 0.5px 的物理下限防止彻底消失。
        stroke.strokeWidth = m.strokeWidthPx.coerceAtLeast(MIN_STROKE_PX)
        stroke.color = scaleAlpha(m.strokeColor, rimKeep)
        canvas.drawPath(clipPath, stroke)

        if (m.edgeLensColor != 0 && m.radiusPx > 6f) {
            stroke.strokeWidth = onePx * 1.5f
            stroke.color = scaleAlpha(m.edgeLensColor, rimKeep)
            val reach = m.radiusPx * 1.6f
            cornerPath.reset()
            // 只在四角描、中段不描 —— 整圈加亮会变成"白塑料边"
            corner(clipPath, canvas, 0f, 0f, reach)
            corner(clipPath, canvas, w, 0f, reach)
            corner(clipPath, canvas, 0f, h, reach)
            corner(clipPath, canvas, w, h, reach)
        }
    }

    private fun corner(outline: Path, canvas: Canvas, cx: Float, cy: Float, reach: Float) {
        val sc = canvas.save()
        cornerPath.reset()
        cornerPath.addCircle(cx, cy, reach, Path.Direction.CW)
        canvas.clipPath(cornerPath)
        canvas.drawPath(outline, stroke)
        canvas.restoreToCount(sc)
    }

    private fun modulate(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt(),
        Color.red(color), Color.green(color), Color.blue(color),
    )

    /**
     * 把纹理中属于本面的区域画进来，可选走透镜折射。
     * 返回 false = 这帧没有可用背景（首帧或采集失败），调用方会去请求重采。
     */
    private fun drawBackdrop(canvas: Canvas, coord: GlassCoordinator, m: GlassMaterial): Boolean {
        val tex = coord.bitmap() ?: return false
        val sw = srcRect.width()
        val sh = srcRect.height()
        if (sw < 2 || sh < 2) return false

        if (m.lensStrengthDp <= 0f) {
            canvas.drawBitmap(tex, srcRect, boundsRect, bitmapPaint)
            return true
        }

        val ctx = hostRef.get()?.context ?: return false
        val scale = coord.textureScale
        val strength = GlassTokens.dp(ctx, m.lensStrengthDp) * scale
        // 折射是**向外**采样的：贴边像素要拿到 footprint 外面的内容。
        // 只读 footprint 本身的话，出界一律 clamp 到自身边界 → 边缘一圈拖影。
        // 所以把读取区域往外扩 pad，warp 用 (ox,oy) 知道 footprint 在缓冲区里的位置；
        // 出界仍 clamp，但那已经是纹理的真边界（比如贴底的 dock 下面本来就没有内容）。
        val pad = strength.roundToInt().coerceAtLeast(2)
        texRect.set(
            (srcRect.left - pad).coerceAtLeast(0),
            (srcRect.top - pad).coerceAtLeast(0),
            (srcRect.right + pad).coerceAtMost(tex.width),
            (srcRect.bottom + pad).coerceAtMost(tex.height),
        )
        val bw = texRect.width()
        val bh = texRect.height()
        if (bw < 2 || bh < 2) return false

        val regionMoved = lensRect != texRect
        if (coord.generation != lensGen || sw != lensOut?.width || sh != lensOut?.height || regionMoved) {
            val need = bw * bh
            if (lensSrc.size < need) {
                lensSrc = IntArray(need)
                lensDst = IntArray(need)
            }
            var out = lensOut
            if (out == null || out.isRecycled || out.width != sw || out.height != sh) {
                out?.recycle()
                out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
                lensOut = out
            }
            if (coord.readRegion(texRect, lensSrc)) {
                GlassLens.warp(
                    src = lensSrc, dst = lensDst, sw = bw, sh = bh,
                    ox = srcRect.left - texRect.left,
                    oy = srcRect.top - texRect.top,
                    fw = sw, fh = sh,
                    cornerPx = m.radiusPx * scale,
                    // 带高取 3 倍最大位移：circleMap 剖面下，深度到 1 个 strength 处位移
                    // 已经掉到 25%，再深贡献趋近于零，带再宽只是白算
                    bandPx = (strength * 3f).coerceAtLeast(1f),
                    strength = strength,
                    magnify = m.magnify,
                    dispersion = m.dispersion,
                )
                out.setPixels(lensDst, 0, sw, 0, 0, sw, sh)
                lensGen = coord.generation
                lensRect.set(texRect)
            } else {
                // 读不到区域（纹理刚被重建）：这帧先用未折射的模糊底，不阻塞
                canvas.drawBitmap(tex, srcRect, boundsRect, bitmapPaint)
                return true
            }
        }
        val done = lensOut?.takeUnless { it.isRecycled } ?: return false
        canvas.drawBitmap(done, null, boundsRect, bitmapPaint)
        return true
    }

    /**
     * 对比度自适应（PRD §9.3）：文字压在自己身上够不够黑由纹理亮度决定，不靠手调 alpha。
     * 不达标先加浓 tint（最多 6 档），仍不达标就把这一面退回不透明白底 —— 即现设计规范。
     *
     * **档位是粘滞的**：已选的档只要还达标（留 0.3 容差）就不重算。逐次重算时整屏平均亮度
     * 会随内容滚动上下摆动，tint 就在 56%↔70%↔56% 之间来回跳 —— 设备页顶栏那个"玻璃在呼吸/
     * 闪"的观感主要来自这里，而不是采样本身（走查反馈第 1 条）。
     */
    private fun guardedTint(host: View, m: GlassMaterial, luminance: Float): Int {
        if (m.onColor == 0 || luminance < 0f) return m.tintColor
        val base = m.tintColor
        val key = (luminance * 1000).toLong() * 131 + m.tintColor
        if (key == guardKey) return guardTint

        // 粘滞检查：沿用上一档，除非它已经不达标
        val held = guardAlpha
        if (held > Color.alpha(base)) {
            val step = Color.argb(held, Color.red(base), Color.green(base), Color.blue(base))
            if (contrastRatio(m.onColor, compositeOnLuminance(step, luminance)) >= AA_BODY - HYST) {
                guardTint = step
                guardKey = key
                return step
            }
        }
        var alpha = Color.alpha(base)
        var step = base
        // 6 档 × 7%：tint 现在起手只有 56%（要透得过去），留给自适应的行程更长，
        // 不至于一遇暗底就整面退回不透明白板
        for (i in 0..5) {
            val composite = compositeOnLuminance(step, luminance)
            if (contrastRatio(m.onColor, composite) >= AA_BODY) break
            alpha = (alpha + (255 * 0.07f).toInt()).coerceAtMost(255)
            step = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        }
        val ok = contrastRatio(m.onColor, compositeOnLuminance(step, luminance)) >= AA_BODY
        guardTint = if (ok) step else m.opaqueTint
        guardAlpha = Color.alpha(guardTint)
        guardKey = key
        return guardTint
    }

    private fun scaleAlpha(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt(),
        Color.red(color), Color.green(color), Color.blue(color),
    )

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    /** 圆角轮廓交给 outline，L3 的真阴影由 elevation 走 GPU 路径 */
    override fun getOutline(outline: Outline) {
        val m = material
        if (m.glass && m.elevationPx > 0f) {
            outline.setRoundRect(bounds, m.radiusPx)
        } else {
            outline.setEmpty()
        }
    }

    companion object {
        /** WCAG AA 正文门槛 */
        private const val AA_BODY = 4.5

        /** 粘滞容差：已选档位只要还差得远才换档（见 [guardedTint]） */
        private const val HYST = 0.3

        /** 描边宽度的物理下限（px）。见 draw() ⑤ 的说明 */
        private const val MIN_STROKE_PX = 0.5f

        /** 本面亮度 EMA 的惯性（越大越稳、跟随越慢） */
        private const val LUM_SMOOTH = 0.8f
    }
}

/** 材质提供者：登记后开关变更时整体重涂，无需重启 Activity */
fun interface GlassMaterialProvider {
    fun get(view: View): GlassMaterial
}

/**
 * chip / 筛选标签的背景渲染：未选中 = 玻璃胶囊，选中 = **实体填充**。
 *
 * 选中态刻意不做玻璃 —— 状态可辨性优先于材质统一（PRD §3.3 `glass_tint_solid_sel`）。
 * 关掉玻璃时两态都回到原 `bg_chip*`，逐值等于 v2.2（AC-12）。
 */
fun View.renderChipBackground(selected: Boolean) {
    val ctx = context
    if (!selected && UiFlags.glassEnabled(ctx)) {
        applyGlass { GlassTokens.chip(it.context) }
    } else {
        GlassRegistry.unregister(this)
        elevation = 0f
        setBackgroundResource(
            if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip,
        )
    }
}

/**
 * 把 dock 遮住的高度叠加到某个视图的底部内衬上，并且能**精确还原**。
 *
 * 首次构造时捕获视图自己的 paddingBottom / bottomMargin，所以「经典外观」
 * （dockSpace=0）会把值原样写回去，不会留下玻璃时代加的内衬（AC-12 逐像素相等）。
 * 可滚动的列表用 [applyPadding]（配 clipToPadding=false，内容才滚得出 dock 底下）；
 * 贴底的固定 chrome 用 [applyMargin]（它不该被遮住，只能抬起来）。
 */
class DockInset(private val view: View) {

    private val basePadding = view.paddingBottom
    private val baseMargin =
        (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    fun applyPadding(dockSpace: Int) {
        val target = basePadding + dockSpace
        if (view.paddingBottom != target) view.updatePadding(bottom = target)
    }

    fun applyMargin(dockSpace: Int) {
        val lp = view.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val target = baseMargin + dockSpace
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target
            view.layoutParams = lp
        }
    }
}

/**
 * 页面按 dock 是否悬浮来调整自己滚动容器的底部内衬（PRD §7.2）。
 *
 * dock 是覆盖层、内容列铺满整屏，所以滚动容器必须自己加
 * `paddingBottom` + `clipToPadding=false`：既是"最后一屏不被 dock 盖住"的可读性要求，
 * 也正是看得见内容从玻璃底下滚过的前提。经典外观下 dockSpace=0，内衬归零，
 * 与 v2.2 逐像素相等。
 */
interface GlassInsetAware {
    fun onDockSpaceChanged(dockSpace: Int)
}

/**
 * 大字档（PRD §7.3）下给胶囊**容器**按比例加高。
 *
 * `fontScale ≥ 1.3` 时 13sp 的标签能长到 17sp 以上，而分段控件这类胶囊容器是固定高度的
 * （34 / 40 / 44dp），文字必被上下裁。这里只加高容器：里面的滑块与标签都是
 * `match_parent` / `wrap_content`，会跟着长，不需要同步改别的尺寸。
 * 低于 1.3 档时**一个像素都不动** —— 绝大多数用户走原路径。
 *
 * 叶子胶囊（单个 chip / 按钮）不用这个：它们已经改成 `wrap_content + minHeight`，
 * 自己会长。
 */
fun View.growForLargeFont(factor: Float = 1.35f) {
    if (!GlassTokens.largeFont(context)) return
    val lp = layoutParams ?: return
    val want = (lp.height * factor).toInt()
    if (lp.height > 0 && lp.height != want) {
        lp.height = want
        layoutParams = lp
    }
}

/**
 * 一个入口把一个面变成玻璃：不重排布局、不换 class，
 * 只换 background + outline/elevation —— 回退闸门因此能做到"逐像素等于 v2.2"。
 *
 * [coordinator] 放在第一个参数位，所以尾部 lambda 能直接落到 [provider] 上：
 * `view.applyGlass { GlassTokens.dock(it) }` / `view.applyGlass(coord) { ... }`。
 *
 * @param coordinator 背景纹理源；null 表示该面不做实时模糊（L2 控件、或找不到内容源时）
 */
fun View.applyGlass(
    coordinator: GlassCoordinator? = null,
    register: Boolean = true,
    provider: GlassMaterialProvider,
): View {
    val material = provider.get(this)
    // 换掉旧玻璃面之前先归还它的像素缓存（见 [GlassSurfaceDrawable.release]）。
    // 开关切换 / 切页 / 旋转都会重复走这里，不回收就是每张纹理攒一批等 GC。
    (background as? GlassSurfaceDrawable)?.release()
    val surface = GlassSurfaceDrawable(WeakReference(this), material, coordinator)
    background = surface
    elevation = material.elevationPx
    if (material.elevationPx > 0f) {
        val radius = material.radiusPx
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        applySoftShadow()
    }
    if (register) GlassRegistry.register(this, provider)
    coordinator?.let {
        // 登记为宿主：纹理每次重采只回调这些面重绘。少了这一步，采到的新背景
        // 没有任何人会被叫回来画 —— 玻璃就永远停在第一帧（或干脆不上糊）。
        it.addHost(surface)
        it.reportBlurNeed(material.blurDp)
        it.invalidate()
    }
    return this
}

/**
 * 把系统默认那套「纯黑 × 按高度衰减」的投影换成按 token 着色的柔和双层阴影。
 *
 * 默认阴影在高 elevation 的圆角面上会压出一圈硬黑边（走查第 1 条「阴影生硬」），
 * 尤其 dock 现在真的浮在内容上，黑边叠在照片上更脏。spot / ambient 分开给 alpha，
 * 才是"浮起来"而不是"压上去"。API 28+，本仓 minSdk 29 直接调。
 */
fun View.applySoftShadow() {
    outlineSpotShadowColor = ContextCompat.getColor(context, R.color.glass_shadow_key)
    outlineAmbientShadowColor = ContextCompat.getColor(context, R.color.glass_shadow_ambient)
}

/**
 * 全局注册表：弱引用，不阻止 View 回收。
 * 「经典外观 / 降低透明度」切换时用它即时重涂（PRD §9.4）。
 */
object GlassRegistry {

    init {
        // 开关变更时全量重涂。object 只在第一个玻璃面登记时初始化，那时才需要监听
        UiFlags.observe(this) { reapplyAll() }
    }

    private val entries = mutableListOf<WeakReference<Pair<View, GlassMaterialProvider>>>()

    internal fun register(view: View, provider: GlassMaterialProvider) {
        entries.removeAll { it.get()?.first === view }
        if (entries.size > 128) entries.removeAll { it.get() == null }
        entries.add(WeakReference(view to provider))
        view.setTag(R.id.glass_provider, provider)
    }

    /**
     * 摘掉登记。选中态要换回实体填充的面必须走这里，
     * 否则开关变更重涂会把实心背景又盖成玻璃（chip 选中态失效）。
     */
    fun unregister(view: View) {
        entries.removeAll { it.get()?.first === view }
        view.setTag(R.id.glass_provider, null)
    }

    /** 开关变更后调用：所有已登记面按新 token 重涂 */
    fun reapplyAll() {
        val iter = entries.iterator()
        while (iter.hasNext()) {
            val pair = iter.next().get()
            if (pair == null) {
                iter.remove()
                continue
            }
            val (view, provider) = pair
            val drawable = view.background as? GlassSurfaceDrawable ?: continue
            drawable.material = provider.get(view)
            view.elevation = drawable.material.elevationPx
            view.invalidateOutline()
            drawable.invalidateSelf()
        }
    }
}

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
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val cornerPath = Path()
    private val boundsRect = RectF()
    private val srcRect = Rect()

    private var rimShader: LinearGradient? = null
    private var rimH = 0f
    private var onePx = 1f

    /** 0=静止 1=按下：驱动 rim 削弱 + tint 提浓 */
    private var press = 0f
    private var animator: ValueAnimator? = null
    private var pressed = false

    private var guardKey = Long.MIN_VALUE
    private var guardTint = 0

    // 透镜结果缓存：只在纹理换代或区域尺寸/位置变化时重算，滚动中每帧只花一次位图 blit
    private var lensSrc = IntArray(0)
    private var lensDst = IntArray(0)
    private var lensOut: Bitmap? = null
    private var lensGen = -1L
    private val lensRect = Rect()

    override fun isStateful(): Boolean = true

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val h = bounds.height().toFloat()
        rimH = (h * 0.5f).coerceAtLeast(1f)
        rimShader = LinearGradient(
            0f, 0f, 0f, rimH,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        // 尺寸变了（旋转 / 分屏 / 首次布局）纹理坐标要重算
        coordinator?.invalidate()
    }

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

    override fun draw(canvas: Canvas) {
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
            fill.color = m.opaqueTint
            canvas.drawPath(clipPath, fill)
            stroke.strokeWidth = m.strokeWidthPx.coerceAtLeast(onePx)
            stroke.color = ContextCompat.getColor(host.context, R.color.outline)
            canvas.drawPath(clipPath, stroke)
            return
        }

        val sc = canvas.save()
        canvas.clipPath(clipPath)

        // 底片完全淡出时连 clip 与模糊 blit 一起省掉（顶栏静止态就是这条路）
        val pa = plateAlpha.coerceIn(0f, 1f)
        if (pa < 0.004f) {
            canvas.restoreToCount(sc)
            return
        }

        // ---- ① 背景模糊 + 边缘折射 ----
        var backdropUsed = false
        val coord = coordinator
        if (m.blurDp > 0f && coord != null) {
            coord.reportBlurNeed(m.blurDp)
            if (coord.mapRect(host, srcRect)) {
                backdropUsed = drawBackdrop(canvas, coord, m)
            }
            if (!backdropUsed) coord.requestRefreshFromDraw()
        }

        // ---- ② 染色 tint（含对比度自适应） ----
        fill.shader = null
        fill.style = Paint.Style.FILL
        val tint = guardedTint(host, m, if (backdropUsed) coord?.avgLuminance ?: -1f else -1f)
        fill.color = if (pa == 1f) tint else Color.argb(
            (Color.alpha(tint) * pa).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint),
        )
        canvas.drawRect(boundsRect, fill)

        // 按下：提浓，"玻璃变厚了"
        if (press > 0f && m.pressAlphaDelta > 0) {
            fill.color = Color.argb(
                ((m.pressAlphaDelta * press) * pa).toInt().coerceIn(0, 255),
                Color.red(m.tintColor), Color.green(m.tintColor), Color.blue(m.tintColor),
            )
            canvas.drawRect(boundsRect, fill)
        }

        // ---- ③ 内顶高光：顶端 1dp 量级的渐变，按下时被削弱（"光被打散"） ----
        val rimKeep = (1f - (1f - m.rimPressKeep) * press).coerceIn(0f, 1f) * pa
        rimShader?.let { shader ->
            if (rimKeep > 0.02f) {
                fill.shader = shader
                fill.alpha = (Color.alpha(m.rimTopColor) * rimKeep).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, w, rimH, fill)
                fill.shader = null
                fill.alpha = 255
            }
        }

        // ---- ④ 内底反光：均匀的细亮线 ----
        fill.color = scaleAlpha(m.rimBottomColor, rimKeep)
        canvas.drawRect(0f, h - onePx, w, h, fill)

        canvas.restoreToCount(sc)

        // ---- ⑤ 外描边 + 四角透镜亮线（折射假象的主要来源） ----
        stroke.strokeWidth = m.strokeWidthPx.coerceAtLeast(onePx)
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

    /**
     * 玻璃底片的整体不透明度（0..1），只影响**材质本身**，不影响子 View。
     *
     * 顶栏要"内容滚进来才浮现"，但不能用 `View.alpha` —— 那会连标题和图标一起淡掉。
     * 用 `Drawable.setAlpha` 也不行：绘制时每次都重设 `paint.color`，会把它覆盖掉。
     */
    @Volatile var plateAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            invalidateSelf()
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

        val regionMoved = lensRect != srcRect
        if (coord.generation != lensGen || sw != lensOut?.width || sh != lensOut?.height || regionMoved) {
            val need = sw * sh
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
            if (coord.readRegion(srcRect, lensSrc)) {
                val scale = coord.textureScale
                val strength = GlassTokens.dp(
                    hostRef.get()?.context ?: return false, m.lensStrengthDp,
                ) * scale
                GlassLens.warp(
                    src = lensSrc, dst = lensDst, sw = sw, sh = sh,
                    cornerPx = m.radiusPx * scale,
                    bandPx = (m.radiusPx * scale + strength * 1.5f).coerceAtMost(minOf(sw, sh) / 2f).coerceAtLeast(1f),
                    strength = strength,
                    magnify = m.magnify,
                    dispersion = m.dispersion,
                )
                out.setPixels(lensDst, 0, sw, 0, 0, sw, sh)
                lensGen = coord.generation
                lensRect.set(srcRect)
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
     * 不达标先加浓 tint（最多 3 档），仍不达标就把这一面退回不透明白底 —— 即现设计规范。
     */
    private fun guardedTint(host: View, m: GlassMaterial, luminance: Float): Int {
        if (m.onColor == 0 || luminance < 0f) return m.tintColor
        val key = (luminance * 1000).toLong() * 131 + m.tintColor
        if (key == guardKey) return guardTint

        val base = m.tintColor
        var alpha = Color.alpha(base)
        var step = base
        for (i in 0..3) {
            val composite = compositeOnLuminance(step, luminance)
            if (contrastRatio(m.onColor, composite) >= AA_BODY) break
            alpha = (alpha + (255 * 0.05f).toInt()).coerceAtMost(255)
            step = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        }
        val ok = contrastRatio(m.onColor, compositeOnLuminance(step, luminance)) >= AA_BODY
        guardTint = if (ok) step else m.opaqueTint
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
 * dock 悬浮时容器用负 margin 多占了一条 dock 的高度，内容必须自己加
 * `paddingBottom` + `clipToPadding=false` 才滚得出 dock 底下 —— 这既是可读性要求，
 * 也正是"内容从玻璃底下滚过"能看到透景的原因。经典外观下 dockSpace=0，内衬归零，
 * 与 v2.2 逐像素相等。
 */
interface GlassInsetAware {
    fun onDockSpaceChanged(dockSpace: Int)
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
    background = GlassSurfaceDrawable(WeakReference(this), material, coordinator)
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
    }
    if (register) GlassRegistry.register(this, provider)
    coordinator?.let {
        it.reportBlurNeed(material.blurDp)
        it.invalidate()
    }
    return this
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

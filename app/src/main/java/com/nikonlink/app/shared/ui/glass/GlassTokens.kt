package com.nikonlink.app.shared.ui.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import com.nikonlink.app.R

/**
 * 玻璃材质档位（PRD §3.1）。只有四层，归不进去的面就不该做玻璃。
 */
enum class GlassLevel {
    /** 贴合玻璃：与内容同滚动的信息面 */
    L1_FITTED,

    /** 控件玻璃：chip / 按钮 / 分段控件，不吃实时模糊 */
    L2_CONTROL,

    /** 悬浮玻璃：dock / 顶栏 / 弹窗 / 监看 HUD */
    L3_FLOATING,
}

/**
 * 一面玻璃的完整规格。全部字段已按当前 UiFlags 解析过，
 * 所以**关闭玻璃或降低透明度后拿到的就是实体材质**，绘制路径不需要再判断开关。
 */
data class GlassMaterial(
    val level: GlassLevel,
    val radiusPx: Float,
    /** 模糊半径（dp）。0 表示不采背景 */
    val blurDp: Float,
    val tintColor: Int,
    val strokeColor: Int,
    val strokeWidthPx: Float,
    val rimTopColor: Int,
    val rimBottomColor: Int,
    val edgeLensColor: Int,
    val elevationPx: Float,
    /** 按压时的 tint 提浓量（alpha 加数） */
    val pressAlphaDelta: Int,
    /** 按下时 rim 保留比例，玻璃"沉下去"主要靠这条而非缩放 */
    val rimPressKeep: Float,
    /** 材质是否生效；false = 画成实体表面（v2.2 观感） */
    val glass: Boolean,
    /** 会压在这面上的文字/图标色，供对比度自适应判定（PRD §9.3）。0 = 该面无文字，不做判定 */
    val onColor: Int,
    /** 边缘向外位移的最大幅度（dp）。0 = 不做折射 */
    val lensStrengthDp: Float = 0f,
    /** 中心放大倍率，1f = 不放大 */
    val magnify: Float = 1f,
    /** 色散强度（相对位移比例）。默认 0：见 PRD §3.7 与 Q1 的灰阶冲突 */
    val dispersion: Float = 0f,
) {
    /** 不透明实底（降低透明度 / 经典外观） */
    val opaqueTint: Int
        get() = Color.argb(
            255,
            Color.red(tintColor),
            Color.green(tintColor),
            Color.blue(tintColor),
        )
}

/**
 * 卡片抬升（PRD §3.4；用户反馈"玻璃不明显处至少给阴影，要有立体感"）。
 *
 * 这些面背后是页面底色，做真玻璃等于什么都透不出来（PRD §1.2 三判据第一条），
 * 所以它们不该磨砂 —— 但"平"是 v2.2 刻意压平的结果（`cardElevation 0dp` +
 * 所有按钮 `stateListAnimator @null`），不是设计意图里的"永远没有层次"。
 * 这里补的是**真实 GPU 阴影**：`bg_card` 是带圆角的 GradientDrawable，
 * View 默认 outline 就取自 background，所以只设 elevation 就能得到正确的圆角软阴影，
 * 不需要自绘、也不进模糊帧预算。
 *
 * 认卡片的方式刻意保守：**圆角恰好等于 `card_radius`(12dp) 的矩形 GradientDrawable**。
 * 于是 `bg_card` / `bg_card_dark` 命中，chip(20dp)、info_grid_item(8dp)、
 * 以及已经上了玻璃材质的面（不是 GradientDrawable）都不命中。
 *
 * 关键前提（已核对）：全仓 `res/layout` 里 `android:elevation` 命中 **0** 次，
 * 所以"经典外观 → 归零"是逐值还原，不是近似（AC-12）。
 */
object DepthLift {

    fun apply(root: View, context: Context) {
        val lift = if (UiFlags.glassEnabled(context)) {
            context.resources.getDimension(R.dimen.glass_elevation_l1)
        } else {
            0f
        }
        walk(root, lift)
    }

    private fun walk(view: View, lift: Float) {
        val d = view.background as? android.graphics.drawable.GradientDrawable
        if (d != null && d.shape == android.graphics.drawable.GradientDrawable.RECTANGLE) {
            val want = CARD_RADIUS_DP * view.resources.displayMetrics.density
            // 只认 12dp 圆角矩形 = bg_card / bg_card_dark；chip(20)、格子(8) 不命中
            if (kotlin.math.abs(d.cornerRadius - want) < 0.6f) {
                view.elevation = lift
                // 卡片只有 1.5dp，默认阴影在浅底上是一条贴边的硬灰边；
                // 压过 alpha 之后才是"纸片离开桌面"的那点软影子
                if (lift > 0f) view.applySoftShadow()
            }
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), lift)
        }
    }

    private const val CARD_RADIUS_DP = 12f
}

/**
 * 顶栏标题随滚动让位。
 *
 * v2.3.1 起**顶栏不再有玻璃底片**：它背后是"自己页面的滚动区"，模糊采样跟着滚动总差
 * 一帧（走查反馈：错位、闪烁、切页残留）。现在顶栏是一块干净的统一底色，
 * 这里只剩标题自身的轻微上移 + 缩到 0.955 —— 保留它是因为"标题给内容让一点位"
 * 这个手感已经验过，与材质无关。
 *
 * 动作用追帧平滑而不是一帧一赋值：fling 时进度会在几帧内被拽完整条，读作抖动。
 */
class GlassTopBar(
    private val host: View,
    private val title: View?,
) {

    /** 已应用的进度；-1 = 还没应用过（首帧不该播动画） */
    private var progress = -1f
    private var target = -1f
    private val risePx = RISE_DP * host.resources.displayMetrics.density

    /** 追帧循环里只允许排一个 runnable，否则滚动中每帧各排一个，队列会自己长起来 */
    private var chasing = false
    private val chaser = object : Runnable {
        override fun run() {
            if (!host.isAttachedToWindow) {
                chasing = false
                settle(target.coerceAtLeast(0f))
                return
            }
            val d = target - progress
            if (kotlin.math.abs(d) < EPS) {
                chasing = false
                settle(target)
                return
            }
            settle(progress + d * SMOOTH)
            host.postOnAnimation(this)
        }
    }

    fun onScroll(offsetPx: Int) {
        val span = (COLLAPSE_DP * host.resources.displayMetrics.density).coerceAtLeast(1f)
        val t = (offsetPx.toFloat() / span).coerceIn(0f, 1f)
        // smoothstep：两端慢、中间快。线性映射下刚开始滚的几像素就会跳一大截
        glide(t * t * (3f - 2f * t))
    }

    private fun glide(to: Float) {
        if (to == target) return
        target = to
        if (progress < 0f || !UiFlags.motionEnabled(host.context)) {
            settle(to)   // 首帧与「减少动效」下直接落位，不追帧
            return
        }
        if (chasing) return
        chasing = true
        host.postOnAnimation(chaser)
    }

    private fun settle(t: Float) {
        if (t == progress) return
        progress = t
        title?.apply {
            alpha = 1f - 0.08f * t
            translationY = -risePx * t
            // 标题是定位锚，缩得太狠读作"被压下去"；只留一点让位的意思
            scaleX = 1f - 0.045f * t
            scaleY = 1f - 0.045f * t
        }
    }

    /** 退回静态：切页/换主题时避免带着上一次的缩放态 */
    fun reset() {
        progress = -1f
        target = -1f
        chasing = false
        host.removeCallbacks(chaser)
        settle(0f)
        progress = -1f
    }

    companion object {
        /** 滚过多远标题完全让位 */
        private const val COLLAPSE_DP = 72f

        /** 标题上移距离 */
        private const val RISE_DP = 4f

        /** 每帧向目标推进的比例 —— 追帧平滑，不是逐帧硬赋值 */
        private const val SMOOTH = 0.22f

        /** 收敛判定：再小到这个差就一步到位并停下 */
        private const val EPS = 0.004f
    }
}

/**
 * 玻璃语言的运动参数（PRD §6）。
 *
 * 这里只放已经在用的那一条：分段控件指示胶囊的"液态"位移。
 * 固定时长 + Decelerate 之所以看着生硬，是因为它只有位置在动 ——
 * iOS 那个手感来自**位置轻微过冲 + 途中横向拉伸、落位时压回**，
 * 让胶囊看起来像一滴有表面张力的液体而不是一个贴纸。
 */
object GlassMotion {

    private const val SLIDE_MS = 380L

    /** 途中最大横向拉伸比例 */
    private const val STRETCH = 0.18f

    fun slidePill(target: View, toX: Float, animated: Boolean) {
        val ctx = target.context
        target.animate().cancel()
        val from = target.translationX
        val dist = toX - from
        if (!animated || !UiFlags.motionEnabled(ctx) || kotlin.math.abs(dist) < 1f) {
            target.translationX = toX
            target.scaleX = 1f
            target.scaleY = 1f
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SLIDE_MS
            // 末端过冲的曲线（0.2,0.9,0.2,1.05 近似临界阻尼偏弹）
            interpolator = PathInterpolator(0.2f, 0.9f, 0.2f, 1.05f)
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                target.translationX = from + dist * v
                val s = v.coerceIn(0f, 1f)
                val bulge = kotlin.math.sin(Math.PI * s).toFloat()
                target.scaleX = 1f + STRETCH * bulge
                target.scaleY = 1f - STRETCH * 0.35f * bulge
            }
            start()
        }
    }
}
/**
 * 覆盖式顶栏的"让位"计算（PRD §15.6-1）。
 *
 * 页面根是 `FrameLayout`：滚动区满高，`topChrome`（标题行 + 分割线 + 设备页的模式行）
 * 作为覆盖层压在上面，这里负责把滚动区顶部让出 chrome 的实际高度。
 *
 * 顶栏**不再有玻璃底片**（v2.3.1 走查后取消 —— 它背后就是本页的滚动区，采样永远慢一帧，
 * 表现为错位、闪烁、切页残留），所以两种外观下滚动区都是 `clipToPadding=false`：
 * 内容滚到不透明顶栏底下被遮住，与 Android 普通 Toolbar 的行为一致。
 * chrome 高度要等首次布局才量得准（此时 `height=0`），量不到就 post 一次再试。
 */
object GlassChrome {

    fun apply(chrome: View, scroll: View) {
        val h = chrome.height
        if (h <= 0) {
            chrome.post { if (chrome.height > 0 && scroll.isAttachedToWindow) apply(chrome, scroll) }
            return
        }
        if (scroll.paddingTop != h) {
            scroll.updatePadding(top = h)
            // 转圈位置是相对 SwipeRefreshLayout 顶边的（默认 20/64dp），滚动区铺到屏幕顶之后
            // 不抬高就会被顶栏压住
            val d = scroll.resources.displayMetrics.density
            (scroll.parent as? androidx.swiperefreshlayout.widget.SwipeRefreshLayout)
                ?.setProgressViewOffset(
                    false,
                    h + (SPINNER_START_DP * d).toInt(),
                    h + (SPINNER_END_DP * d).toInt(),
                )
        }
    }

    /** 默认转圈起点/终点（dp），相对滚动容器顶边 */
    private const val SPINNER_START_DP = 20f
    private const val SPINNER_END_DP = 64f
}

/**
 * Token 解析（值见 `res/values/glass.xml` 与 `values-night/glass.xml`）。
 *
 * 命名即用途：dock / hud / floatingBar / chip / statusStrip，
 * 新增界面用这里的工厂而不是自己拼参数，配额和一致性才不会漂。
 */
object GlassTokens {

    fun dp(c: Context, v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics,
    )

    /** 从 dimens 里读的"无量纲数值"token（写成 format=float 的 item） */
    internal fun num(c: Context, id: Int): Float {
        val tv = TypedValue()
        c.resources.getValue(id, tv, true)
        return tv.float
    }

    private fun color(c: Context, id: Int): Int = ContextCompat.getColor(c, id)

    private fun base(
        c: Context,
        level: GlassLevel,
        radiusRes: Int,
        blurRes: Int,
        tintRes: Int,
        onColorRes: Int,
    ): GlassMaterial {
        val r = c.resources.getDimension(radiusRes)
        val glass = UiFlags.glassEnabled(c)
        val blurOn = UiFlags.blurEnabled(c)
        val reduce = UiFlags.reduceTransparency(c)
        val blur = if (glass && blurOn && level != GlassLevel.L2_CONTROL) num(c, blurRes) else 0f
        return GlassMaterial(
            level = level,
            radiusPx = r,
            blurDp = blur,
            tintColor = color(c, tintRes),
            strokeColor = color(c, R.color.glass_stroke_outer),
            strokeWidthPx = if (reduce || !glass) r.coerceAtMost(dp(c, 1f)) else dp(c, 0.5f),
            rimTopColor = color(c, R.color.glass_rim_top),
            rimBottomColor = color(c, R.color.glass_rim_bottom),
            edgeLensColor = color(c, R.color.glass_edge_lens),
            elevationPx = when {
                !glass || reduce -> 0f
                level == GlassLevel.L3_FLOATING -> c.resources.getDimension(R.dimen.glass_elevation_l3)
                level == GlassLevel.L1_FITTED -> c.resources.getDimension(R.dimen.glass_elevation_l1)
                else -> 0f
            },
            pressAlphaDelta = Color.alpha(color(c, R.color.glass_tint_press)),
            rimPressKeep = if (UiFlags.motionEnabled(c)) 0.45f else 1f,
            glass = glass && !reduce,
            onColor = color(c, onColorRes),
        )
    }

    /**
     * 给"背后真的有内容"的面加折射参数（PRD §3.6）。
     * 强度以 dp 规定、实现在 1/8 纹理上换算，所以观感与密度无关。
     */
    private fun lens(c: Context, m: GlassMaterial, strengthDp: Float, magnify: Float): GlassMaterial =
        if (m.blurDp <= 0f || !UiFlags.lensEnabled(c)) m
        else m.copy(
            lensStrengthDp = strengthDp,
            magnify = magnify,
            dispersion = if (UiFlags.dispersionEnabled(c)) 0.35f else 0f,
        )

    /**
     * 底部悬浮导航 dock（L3，r28，24dp 模糊）。
     * tint 用 `floating` 而不是 `content`：悬浮控件要**透**得过去，
     * 85% 白压在白页上等于不透明，模糊与折射就全白做了（PRD §3.3 走查修正）。
     */
    fun dock(c: Context): GlassMaterial = lens(
        c,
        base(c, GlassLevel.L3_FLOATING, R.dimen.glass_radius_xl, R.dimen.glass_blur_m,
            R.color.glass_tint_floating, R.color.text_primary),
        6f, 1.06f,
    )

    /** 选择操作坞（L3，与 dock 同材质同半径，天然一致） */
    fun floatingBar(c: Context): GlassMaterial = lens(
        c,
        base(c, GlassLevel.L3_FLOATING, R.dimen.glass_radius_xl, R.dimen.glass_blur_m,
            R.color.glass_tint_floating, R.color.text_primary),
        6f, 1.06f,
    )

    /**
     * 相册页底部的「相机照片 / 已标记 / 本地照片」分段胶囊：与 dock 同一套悬浮语言，
     * 只差半径跟着 40dp 的高度收成整圆端（r20）。
     */
    fun tabPill(c: Context): GlassMaterial = lens(
        c,
        base(c, GlassLevel.L3_FLOATING, R.dimen.glass_radius_xl, R.dimen.glass_blur_m,
            R.color.glass_tint_floating, R.color.text_primary)
            .copy(radiusPx = dp(c, 20f)),
        5f, 1.05f,
    )

    /** 监看 / 预览 HUD（L3，60dp 模糊，背后是视频流所以 tint 用暗面参数） */
    fun hud(c: Context): GlassMaterial = lens(
        c,
        base(c, GlassLevel.L3_FLOATING, R.dimen.glass_radius_m, R.dimen.glass_blur_xl,
            R.color.glass_tint_scrim, R.color.liveview_text,
        ).let {
            // 暗面 HUD：半径沿用现值 14dp，描边用半透明白
            it.copy(
                radiusPx = dp(c, 14f),
                strokeColor = color(c, R.color.glass_rim_top),
                strokeWidthPx = dp(c, 1f),
            )
        },
        5f, 1.04f,
    )

    /** 行内小控件玻璃（chip / 分段槽）。L2 不吃实时模糊，只吃 tint + rim */
    fun chip(c: Context): GlassMaterial = base(
        c, GlassLevel.L2_CONTROL, R.dimen.glass_radius_xl, R.dimen.glass_blur_s,
        R.color.glass_tint_content, R.color.text_secondary,
    ).copy(radiusPx = dp(c, 20f))
}

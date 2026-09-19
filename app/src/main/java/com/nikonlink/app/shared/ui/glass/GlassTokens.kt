package com.nikonlink.app.shared.ui.glass

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import androidx.core.content.ContextCompat
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
 * Token 解析（值见 `res/values/glass.xml` 与 `values-night/glass.xml`）。
 *
 * 命名即用途：dock / hud / floatingBar / chip / heroDark / cardGlass，
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

    /** 底部悬浮导航 dock（L3，r28，24dp 模糊） */
    fun dock(c: Context): GlassMaterial = lens(
        c,
        base(c, GlassLevel.L3_FLOATING, R.dimen.glass_radius_xl, R.dimen.glass_blur_m,
            R.color.glass_tint_content, R.color.text_primary),
        6f, 1.06f,
    )

    /** 选择操作坞（L3，与 dock 同材质同半径，天然一致） */
    fun floatingBar(c: Context): GlassMaterial = lens(
        c,
        base(c, GlassLevel.L3_FLOATING, R.dimen.glass_radius_xl, R.dimen.glass_blur_m,
            R.color.glass_tint_content, R.color.text_primary),
        6f, 1.06f,
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

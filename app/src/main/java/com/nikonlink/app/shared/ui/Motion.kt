package com.nikonlink.app.shared.ui

/**
 * 视S1 动效时长 token —— 全 App 的动画时长只允许这 4 档。
 *
 * 背景：2026-10-04 审计发现 20 个互不相同的裸时长（50~380ms）散落在 8 个文件里，
 * 只有 GlassMotion 有曲线定义。本对象与 GlassMotion 同源（曲线一致），是
 * 「时长」这一维的单一出口；玻璃动效本身的编排仍在 GlassTokens。
 *
 * 归档口径（替换前 → 档位）：
 * - ≤90ms  → [INSTANT]（闪光/焦点框等物理感瞬时反馈）
 * - ≤160ms → [FAST]（按压缩放、勾选、小控件淡入）
 * - ≤260ms → [BASE]（常规淡入淡出、缩放复位，180/200/220 归此）
 * - 其余   → [SLOW]（胶囊滑动、页面级移动，对齐 GlassMotion 380ms）
 *
 * 自绘仪表（刻度尺/表盘 Paint）与系统转场 anim 资源不在本 token 管辖内。
 */
object Motion {
    /** 瞬时反馈：闪光、按下即回的物理感效果 */
    const val INSTANT = 80L

    /** 快：按压反馈、小控件淡入淡出 */
    const val FAST = 150L

    /** 常规：淡入淡出、缩放复位 */
    const val BASE = 220L

    /** 慢：胶囊滑动、页面级位移（与 GlassTokens.slidePill 同档） */
    const val SLOW = 380L
}

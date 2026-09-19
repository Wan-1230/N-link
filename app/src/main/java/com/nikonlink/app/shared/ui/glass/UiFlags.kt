package com.nikonlink.app.shared.ui.glass

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager

/**
 * v2.3 液态玻璃的运行时开关（回退闸门）+ 能力分层。
 *
 * 语义与 `ConnFlags` 一致：关掉后**逐像素回到 v2.2**，而不是"大致没变"。
 * 玻璃化全部走覆盖层（新 drawable / 新 flag 分支），旧 `bg_*.xml` 一个都不删，
 * 所以回退是换开关，不是 revert 代码。
 */
object UiFlags {

    private const val PREFS_NAME = "nl_settings"

    /** 经典外观（总闸）：开启 = 整个玻璃子系统失效，回到 v2.2 观感 */
    const val CLASSIC = "ui_classic"

    /** 降低透明度：保留层级（rim / 圆角 / 阴影），但 tint 不透明、模糊全关 */
    const val REDUCE_TRANSPARENCY = "ui_reduce_transparency"

    /** 背景模糊：单独关掉用于低端机验证，tint + rim + 阴影仍保留 */
    const val BLUR = "ui_blur"

    /** 动态效果：按压时 rim 削弱 / tint 提浓 / 模态半径联动 */
    const val MOTION = "ui_motion"

    /** 边缘折射（透镜位移 + 中心微放大）。PRD §3.6，本包用 CPU 在降采样纹理上实现 */
    const val LENS = "ui_lens"

    /**
     * 边缘色散（RGB 分离）。iOS 玻璃棱边有细微色散，但那是彩色，
     * 与 `colors.xml:39` 的灰阶规范冲突 → **默认关**，等 PRD §14 Q1 定了再开。
     */
    const val DISPERSION = "ui_dispersion"

    private val DEFAULTS = mapOf(
        CLASSIC to false,
        REDUCE_TRANSPARENCY to false,
        BLUR to true,
        MOTION to true,
        LENS to true,
        DISPERSION to false,
    )

    private val listeners = mutableListOf<() -> Unit>()

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun get(c: Context, key: String): Boolean =
        prefs(c).getBoolean(key, DEFAULTS[key] ?: true)

    /** 玻璃总开关：经典外观关闭时才生效 */
    fun glassEnabled(c: Context): Boolean = !get(c, CLASSIC)

    /**
     * 降低透明度。
     *
     * PRD §9.4 原本要求「系统高对比度文字开启时自动等效开启」，但
     * `AccessibilityManager.isHighTextContrastEnabled()` **不在 compileSdk 34/35 的公开
     * stub 里**（已用 javap 核对 android-35.jar，只有 isEnabled / isTouchExplorationEnabled）。
     * 与 v2.2 §G4 的立场一致：不做隐藏 API 反射 hack。改为用户开关 + 设置页里给一句指引。
     */
    fun reduceTransparency(c: Context): Boolean = get(c, REDUCE_TRANSPARENCY)

    /**
     * 背景模糊总开关。
     *
     * 故意**不看 SDK 档位**：模糊走 CPU 在 1/8 降采样纹理上施算，Android 10 与 Android 16
     * 是同一条路径（这正是放弃 `RenderEffect` 的目的）。只有窗口级模糊才受 API 31 限制，
     * 见 [windowBlurAvailable]。省电模式仍然收摊。
     */
    fun blurEnabled(c: Context): Boolean =
        glassEnabled(c) && get(c, BLUR) && !reduceTransparency(c) && !isPowerSave(c)

    fun motionEnabled(c: Context): Boolean = glassEnabled(c) && get(c, MOTION)

    /** 折射只在"真的采了背景"的面才有意义 */
    fun lensEnabled(c: Context): Boolean = blurEnabled(c) && get(c, LENS)

    fun dispersionEnabled(c: Context): Boolean = lensEnabled(c) && get(c, DISPERSION)

    fun set(c: Context, key: String, value: Boolean) {
        prefs(c).edit().putBoolean(key, value).apply()
        notifyChanged()
    }

    /**
     * 窗口级 blur-behind（弹窗 / sheet）是否可用。
     *
     * 这是唯一真正受 SDK 限制的玻璃能力：`WindowManager.LayoutParams.FLAG_BLUR_BEHIND`
     * 与 `Window.setBackgroundBlurRadius` 都要 API 31。不可用时静默退化为不透明面板。
     *
     * PRD §9.1 设想的 T1/T2/T3 三档在当前实现下塌缩成一个布尔 —— 因为背景模糊已改为
     * CPU 在小尺寸降采样纹理上施算，Android 29 到 36 是同一条路径，不必再分层。
     * 等 AGSL 折射（真·lensing，要 API 33）落地时再恢复分层。
     */
    fun windowBlurAvailable(c: Context): Boolean =
        blurEnabled(c) && Build.VERSION.SDK_INT >= 31

    fun isPowerSave(c: Context): Boolean = try {
        c.applicationContext
            .getSystemService(PowerManager::class.java)
            ?.isPowerSaveMode == true
    } catch (e: Exception) {
        false
    }

    fun addOnChangeListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeOnChangeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** 开关变更后通知所有已注册的界面重涂材质（即时生效，不重启 Activity） */
    fun notifyChanged() {
        listeners.toList().forEach { it() }
    }
}

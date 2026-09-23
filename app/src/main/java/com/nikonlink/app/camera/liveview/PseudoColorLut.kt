package com.nikonlink.app.camera.liveview

/**
 * 伪彩色映射（v2.5 FR-13 的第一件工具）。
 *
 * 干什么用：监看画面按**亮度**着色，让人一眼看出哪片区域在哪个曝光区间——
 * 直方图回答"分布是什么样"，伪彩回答"具体是哪一块"。补曝/裁切判断是同一件事的两半。
 *
 * 两点刻意的设计：
 * 1. **分段而不连续**：连续渐变在人眼上和"没分段"没区别，边界一硬才能数得清
 *    "这块皮肤掉进黄档了"。分档点也不均分：两端（死黑 / 溢出）挤得密，
 *    因为曝光判断真正要看的正是这两头能不能救回来。
 * 2. **亮度口径与直方图同一个函数**：两个工具若各算各的，就会出现
 *    "直方图说不曝、伪彩说曝"，那种矛盾没法解释也没法修。
 *
 * 纯计算，不收 Context，可 JVM 单测。配色本身是本 App 黑白体系里的一处**有意例外**：
 * 伪彩的功能就是靠颜色传递信息，改成灰阶等于把工具删了。
 */
object PseudoColorLut {

    /** 分档数：12 档在 38dp 宽的图标与全屏画面上都还分得清，再多就是噪声 */
    const val BAND_COUNT = 12

    /** 每档的**上界**（不含）。最后一条必须是 256，否则最高档没有归属。 */
    private val BAND_UPPER = intArrayOf(8, 24, 48, 80, 112, 144, 176, 200, 220, 236, 250, 256)

    /** 与 [BAND_UPPER] 一一对应的 ARGB（不透明；叠加透明度由绘制方决定） */
    private val BAND_COLORS = intArrayOf(
        0xFF000B4D.toInt(), // 近死黑：靛蓝，暗到没信息也仍然看得见边界
        0xFF0021A8.toInt(),
        0xFF0055DD.toInt(),
        0xFF00A6E6.toInt(), // 青
        0xFF12B98A.toInt(),
        0xFF66CC2E.toInt(), // 绿
        0xFFD9D318.toInt(), // 黄：中灰以上，多数肤色/高光过渡落在这里
        0xFFF2A11B.toInt(),
        0xFFF2621B.toInt(), // 橙
        0xFFE02B2B.toInt(), // 红
        0xFFFF3D9E.toInt(), // 品红：接近溢出，最后一道提醒
        0xFFFFFFFF.toInt()  // 白：已经糊成一团，救不回来
    )

    /** 256 项反查表：逐像素调用时省掉分支，渲染侧一次循环就能出图。 */
    val palette: IntArray by lazy {
        IntArray(256) { colorOf(it) }
    }

    /**
     * Rec.601 亮度（与 JPEG 的 Y 分量一致）。
     *
     * 整数权重而不是浮点：每帧要算六千多次，且这套权重与 [HistogramView] 里
     * 原本内联的那份是同一个公式，抽出来后两边不可能再算出两个结果。
     */
    fun lumaOf(color: Int): Int =
        ((color shr 16 and 0xFF) * 299 + (color shr 8 and 0xFF) * 587 + (color and 0xFF) * 114) / 1000

    /** 亮度 → 档号；入参越界按"就近截断"，不给调用方留抛异常的空间。 */
    fun bandOf(luma: Int): Int {
        val v = if (luma < 0) 0 else if (luma > 255) 255 else luma
        for (i in BAND_UPPER.indices) {
            if (v < BAND_UPPER[i]) return i
        }
        return BAND_COUNT - 1
    }

    /** 亮度 → 该档的 ARGB 颜色。 */
    fun colorOf(luma: Int): Int = BAND_COLORS[bandOf(luma)]

    /** 供 UI / 图例用：第 n 档的颜色。 */
    fun colorOfBand(band: Int): Int = BAND_COLORS[band.coerceIn(0, BAND_COUNT - 1)]

    /** 供 UI / 图例用：第 n 档覆盖的亮度上界（不含）。 */
    fun bandUpper(band: Int): Int = BAND_UPPER[band.coerceIn(0, BAND_COUNT - 1)]
}

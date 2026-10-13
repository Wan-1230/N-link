package com.nikonlink.app.camera.liveview

/**
 * 斑马纹（Zebra）标记（C1）。
 *
 * **这个工具回答的问题**：直方图告诉你"整体有多少像素过曝"，但说不出
 * **是哪一块**过曝。天空白成一片与模特额头糊掉一块，在直方图上长得一模一样。
 * 斑马纹把"过曝"画回画面原位——这就是它和直方图无法互相替代的原因。
 *
 * **实现取向（刻意不用彩色）**：标记画成**黑白交替的斜纹**，而不是业界常见的
 * 红/黄单色块。理由有两层：① 斜纹靠"亮度翻转"传信息，压在任何底上都看得见，
 * 单色块压在同色底上会整片消失；② 本项目 `colors.xml` 的灰阶规范管的是界面元件，
 * 斑马纹不需要色相就能表达，就不必动用伪彩那个"颜色例外"。
 * 峰值对焦（[PeakingMask]）不一样，它确实需要色相，那一处才走例外。
 *
 * 阈值按 **IRE** 给（0–100），与摄影机的斑马纹档位同一口径。
 * 纯计算，不碰 Android 类型，单测见 `ZebraMaskTest`。
 */
object ZebraMask {

    /** 默认阈值：90 IRE。再低会把"正常高光"也圈进来，看不出真正要救的地方 */
    const val DEFAULT_IRE = 90

    /** 斜纹周期（采样像素）：太小糊成灰，太大看不出"条纹" */
    const val STRIPE_PERIOD = 4

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** 8bit 亮度 → IRE。与摄影机档位对齐用 `luma * 100 / 255`（全幅 0–100 IRE）。 */
    fun ireOf(luma: Int): Int {
        val v = if (luma < 0) 0 else if (luma > 255) 255 else luma
        return v * 100 / 255
    }

    /** IRE → 8bit 亮度，[ireOf] 的反向，给设置项换算用 */
    fun lumaOfIre(ire: Int): Int {
        val v = if (ire < 0) 0 else if (ire > 100) 100 else ire
        return v * 255 / 100
    }

    /**
     * 就地标记超过阈值的像素为黑白斜纹。
     *
     * @param pixels ARGB 像素数组，长度必须 ≥ [width] × [height]，**会被就地改写**
     * @param thresholdIre 触发阈值（IRE）
     * @param period 斜纹周期
     * @return 被标记的像素数（渲染侧可据此判断"这一帧有没有过曝"）
     */
    fun mark(
        pixels: IntArray,
        width: Int,
        height: Int,
        thresholdIre: Int = DEFAULT_IRE,
        period: Int = STRIPE_PERIOD,
    ): Int {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return 0
        val threshold = lumaOfIre(thresholdIre)
        val p = if (period < 1) 1 else period
        var marked = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                if (i >= pixels.size) return marked
                if (PseudoColorLut.lumaOf(pixels[i]) < threshold) continue
                pixels[i] = if (((x + y) / p) and 1 == 0) WHITE else BLACK
                marked++
            }
        }
        return marked
    }
}

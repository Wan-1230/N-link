package com.nikonlink.app.camera.liveview

/**
 * 峰值对焦（Focus Peaking）标记（C1）。
 *
 * **这个工具回答的问题**：手机屏幕只有几百像素宽，相机对上焦没有，
 * 在缩放前的画面上肉眼看不出来。峰值把**亮度梯度最大**的那些像素描出来
 * ——梯度大就是边缘锐，所以合焦区域的轮廓会"亮起来"，跑焦时整片消失。
 * 手动对焦没有它基本靠猜。
 *
 * **算法取向**：一阶差分（Sobel 的简化）逐像素求横纵梯度，取 `max(|gx|, |gy|)`。
 * 不开根号是有意的：峰值只需要"够不够锐"的判定，开根号在每帧上万像素上是白花的钱，
 * 且阈值含义（0–255 的梯度）反而更好解释给用的人听。
 *
 * **为什么用彩色**：斑马纹（[ZebraMask]）靠亮度翻转就能表达，不需要色相；
 * 峰值必须与画面本身的颜色拉开，否则描在红花上的红描边等于没画。
 * 这是本项目 `colors.xml` 灰阶规范的一处有意例外，与伪彩（[PseudoColorLut]）
 * 同一性质：规范管的是界面元件，不是画面测量叠加层。默认红是业界最常见的一档。
 *
 * 缓冲区复用：与 [FrameSampler] 同一套做法，每帧新建数组会被 GC 拖成掉帧。
 * 纯计算（不碰 Android 类型），单测见 `PeakingMaskTest`。
 */
class PeakingMask(val width: Int, val height: Int) {

    private val luma: IntArray = try {
        IntArray(width * height)
    } catch (e: Throwable) {
        IntArray(0)
    }

    /** 本帧最大梯度值（0–255）。渲染侧可拿它做"合焦程度"读数，测试里可断言 */
    var peakGradient = 0
        private set

    /**
     * 就地标记高梯度像素。
     *
     * @param pixels ARGB 像素数组，长度必须 ≥ [width] × [height]，**会被就地改写**
     * @param threshold 梯度阈值（0–255），越大越只描最锐的那几条边
     * @param color 描边颜色（ARGB）
     * @return 被标记的像素数
     */
    fun mark(pixels: IntArray, threshold: Int = DEFAULT_THRESHOLD, color: Int = DEFAULT_COLOR): Int {
        val w = width
        val h = height
        if (w <= 2 || h <= 2 || pixels.size < w * h || luma.isEmpty()) return 0

        val n = w * h
        for (i in 0 until n) luma[i] = PseudoColorLut.lumaOf(pixels[i])

        var maxGrad = 0
        var marked = 0
        // 边框像素没有完整的左右/上下邻居，梯度无从定义，直接跳过
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val gx = kotlin.math.abs(luma[i + 1] - luma[i - 1])
                val gy = kotlin.math.abs(luma[i + w] - luma[i - w])
                val mag = if (gx > gy) gx else gy
                if (mag > maxGrad) maxGrad = mag
                if (mag >= threshold) {
                    pixels[i] = color
                    marked++
                }
            }
        }
        peakGradient = maxGrad
        return marked
    }

    companion object {
        /** 默认阈值：40/255。再低会把噪点也描出来，画面糊一层彩点 */
        const val DEFAULT_THRESHOLD = 40

        /** 默认描边色：红。业界最常见的一档，与画面常见色相拉开得最远 */
        const val DEFAULT_COLOR = 0xFFFF3B30.toInt()
    }
}

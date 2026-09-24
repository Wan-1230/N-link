package com.nikonlink.app.camera.liveview

/**
 * RGB 分量波形的累加器（v2.5 FR-13 的第二件工具）。
 *
 * 与直方图的区别就是要紧的那部分：**直方图把所有像素堆成一个分布，丢掉了位置**；
 * 波形保留"这一路信号来自画面哪一列"。于是同一件事能看出来而直方图看不出来：
 * 左边暗右边亮（正常分区）与上下各半（反光/滤镜），在直方图上是同一条曲线。
 * 三通道分开累加，是为了看**彩色偏在哪一档**——纯亮度波形看不出蓝色溢出。
 *
 * 数据形状：`3 × columns × bins`，每个采样像素在其所属列上贡献 R/G/B 三个格子各一次。
 * 纯计算，不碰 Android 类型，所以分列、分档、守恒律这些都能在 JVM 上断言。
 */
class RgbWaveform(val columns: Int, val levelBins: Int) {

    companion object {
        const val CHANNEL_RED = 0
        const val CHANNEL_GREEN = 1
        const val CHANNEL_BLUE = 2
        const val CHANNEL_COUNT = 3
    }

    private val counts = IntArray(CHANNEL_COUNT * columns * levelBins)

    /** 一帧内所有通道的最大格计数；渲染侧拿它归一化透明度 */
    var peak = 0
        private set

    fun clear() {
        counts.fill(0)
        peak = 0
    }

    /** 亮度级（0..255）→ 档号。上界闭区间归最后一档，与直方图的 64 柱分法同一口径。 */
    fun binOf(level: Int): Int {
        val v = if (level < 0) 0 else if (level > 255) 255 else level
        val bin = v * levelBins / 256
        return if (bin >= levelBins) levelBins - 1 else bin
    }

    /** 源像素的横坐标 → 波形列号（等分，不做重采样） */
    fun columnOf(x: Int, width: Int): Int {
        if (width <= 0) return 0
        val col = x * columns / width
        return if (col >= columns) columns - 1 else col
    }

    /**
     * 累加一帧降采样像素。[pixels] 是 [FrameSampler] 的复用数组，只有前
     * `width * height` 项有效；alpha 为 0 的像素跳过（那是清底留下的，不是画面）。
     */
    fun accumulate(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val limit = minOf(pixels.size, width * height)
        var maxSeen = peak
        var i = 0
        var x = 0
        while (i < limit) {
            val color = pixels[i]
            if (color and 0xFF000000.toInt() != 0) {
                val column = columnOf(x, width)
                val red = (color shr 16 and 0xFF) * levelBins / 256
                val green = (color shr 8 and 0xFF) * levelBins / 256
                val blue = (color and 0xFF) * levelBins / 256
                // 三个通道各自落到自己的段里，段内按 列×档 连续排布
                val base = column * levelBins
                val rIdx = base + (if (red >= levelBins) levelBins - 1 else red)
                val gIdx = columns * levelBins + base +
                    (if (green >= levelBins) levelBins - 1 else green)
                val bIdx = 2 * columns * levelBins + base +
                    (if (blue >= levelBins) levelBins - 1 else blue)
                var v = ++counts[rIdx]; if (v > maxSeen) maxSeen = v
                v = ++counts[gIdx]; if (v > maxSeen) maxSeen = v
                v = ++counts[bIdx]; if (v > maxSeen) maxSeen = v
            }
            x++
            if (x == width) x = 0
            i++
        }
        peak = maxSeen
    }

    fun countOf(channel: Int, column: Int, bin: Int): Int =
        counts[channel * columns * levelBins + column * levelBins + bin]
}

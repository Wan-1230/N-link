package com.nikonlink.app.camera.liveview

/**
 * 安全区（Safe Area）尺寸计算（C1）。
 *
 * **为什么需要**：相机的取景器画面会被电视/播放器再"过采样"一次——
 * 广播电视里外圈约 7% 在多数终端上会被裁掉，字幕再往里还要留余量。
 * 所以专业监看要画两条框：**动作安全区**（主体别出这条）与**字幕安全区**（文字别出这条）。
 * 拍的时候看着没出画，播出时人脑袋被削一半，就是没看这条框。
 *
 * 比例沿用广播电视通行口径：动作安全 93%、字幕安全 90%。
 *
 * 纯算术，不碰 Android 类型，单测见 `SafeAreaTest`。
 */
object SafeArea {

    /** 动作安全区：主体（人脸、手、关键道具）不要越过这条框 */
    const val ACTION_SAFE = 0.93f

    /** 字幕安全区：文字不要越过这条框（比动作安全再往里一层） */
    const val TITLE_SAFE = 0.90f

    /**
     * 单边内缩量。安全框是**居中**的，所以左右/上下各缩一半。
     *
     * @param size 画面这一边的长度（宽或高，单位随意，返回值同单位）
     * @param ratio 安全区比例，如 [ACTION_SAFE]
     */
    fun inset(size: Float, ratio: Float): Float {
        if (size <= 0f) return 0f
        // 比例截断到 [0,1]：越界值（如 1.5 或 -0.5）不截断会算出负内缩，
        // 也就是把安全框画到画面外面去。**必须用截断后的 r**——
        // 初版这里漏用了 r、直接拿原 ratio 去算，单测当场抓到 -250 的负几何。
        val r = if (ratio < 0f) 0f else if (ratio > 1f) 1f else ratio
        return size * (1f - r) / 2f
    }

    /**
     * 内缩后这一边的长度。给渲染侧直接用，省得自己再减一次。
     */
    fun innerSize(size: Float, ratio: Float): Float {
        val s = if (size <= 0f) 0f else size
        return s - inset(s, ratio) * 2f
    }
}

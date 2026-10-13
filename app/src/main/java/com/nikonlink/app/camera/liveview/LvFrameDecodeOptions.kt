package com.nikonlink.app.camera.liveview

/**
 * 监看帧解码参数计算（B1/R1 的纯逻辑部分）。
 *
 * 抽成独立对象是为了**能单测**：`BitmapFactory` 要 Android 运行时，而「该用多大的
 * inSampleSize」是个纯算术问题，把它和解码器绑在一起就只能靠真机试出来。
 */
object LvFrameDecodeOptions {

    /**
     * inSampleSize 上限。
     *
     * 再往上画质掉得明显，而 640×424 的监看帧缩到 1/8（80×53）已经远低于
     * 直方图/波形所需的采样精度（FrameSampler 取 96/128 宽），继续降就是纯损失。
     */
    const val MAX_SAMPLE_SIZE = 8

    /**
     * 按目标显示尺寸算降采样倍率（仅取 2 的幂，符合 `inSampleSize` 的契约）。
     *
     * **为什么需要**：当前每帧都按全尺寸解码（640×424×4B ≈ 1.08MB），而全屏页
     * `ImageView` 的 fitCenter 显示区通常不到全尺寸的 1/2 —— 多出来的像素既占内存
     * 又要多花解码时间，中低端机上直接体现为掉帧与触控迟滞（R1）。
     *
     * @param srcW/srcH 源帧像素尺寸；未知（<=0）时返回 1，不做任何降采样
     * @param dstW/dstH 实际显示尺寸（应已含当前缩放倍率）；未知（<=0）时返回 1
     */
    fun sampleSizeFor(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return 1
        var size = 1
        // 只要再降一档仍不小于显示区，就继续降
        while (srcW / (size * 2) >= dstW && srcH / (size * 2) >= dstH && size < MAX_SAMPLE_SIZE) {
            size *= 2
        }
        return size
    }
}

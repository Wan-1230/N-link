package com.nikonlink.app.camera.liveview

/**
 * 监看帧的字节级工具（A4/X2 合并产物）。
 *
 * **为什么抽出来**：`findJpegStart` 此前有三份逐字节相同的实现——
 * `LiveViewManager`（解析帧尺寸）、`LiveViewFragment`（全屏页上屏）、
 * `RemoteFragment`（遥控页上屏）。三份各自腐化是早晚的事：
 * 相机帧头格式一旦变化，就要在三个文件里各改一遍，漏一处就是「某一页黑屏」。
 *
 * 放在 liveview 包而不是塞进 Manager：遥控页（`capture` 包）也要用，
 * 但它不该为了一个纯字节函数去依赖整个监看 Manager。
 *
 * 纯 JVM 可测（不碰 Android 类），单测见 `LvFrameTest`。
 */
object LvFrame {

    /**
     * 在原始帧字节流中定位 JPEG 的 SOI 标记 `0xFF 0xD8`。
     *
     * **为什么需要**：Nikon 监看帧带 1024 字节的厂商帧头，`BitmapFactory` 不认，
     * 直接从头喂会整帧解码失败（表现为某一页长期黑屏）。
     *
     * @return SOI 的下标；找不到时返回 0（退回从 0 解码，保住原有行为：
     *         宁可让 BitmapFactory 自己报失败，也不在这里静默丢帧）。
     */
    fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8) return i
        }
        return 0
    }
}

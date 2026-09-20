package com.nikonlink.app.shared.ui.glass

/**
 * 全进程的玻璃负载预算（PRD §8.4 第 2 条 / AC-7）。
 *
 * 传输期是这套 UI 最挤的时刻：PTP 在拉全尺寸 JPEG，解码与写盘已经在吃 CPU 和内存带宽，
 * 这时候还每 150ms 重采一次整屏纹理，就是在跟传输抢帧。所以 `transferring` 为真时：
 *
 *  - **CPU 侧模糊退成静态 tint**（[GlassTokens.base] 把 `blurDp` 归零；折射依赖模糊，
 *    于是自动一起关）；
 *  - **采集间隔翻倍**（[GlassCoordinator.refreshInterval]）。
 *
 * 只降 **CPU 侧**：窗口级 blur-behind（弹窗）走系统 GPU 路径，不在这份预算里抢资源，
 * 所以传输中弹窗照样是糊的 —— 那是纯收益，没必要跟着收。
 * 状态翻转时走 [UiFlags.notifyChanged] 复用既有的原地重涂机制，不重建 Activity
 * （重建会闪一帧白底 windowBackground，见 §15.5 的 F6）。
 *
 * 为什么不读 `PtpSessionManager.bulkDepth`：那是连接层的文件，而"正在传输"这件事
 * UI 层本来就有权威信号（`TransferState`）。用它既不必跨层拿计数，也不用碰别人
 * 正在改的代码 —— 与 `PhotoGridAdapter` 传输期让路缩略图请求是同一个套路。
 */
object GlassBudget {

    @Volatile
    var transferring: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            UiFlags.notifyChanged()
        }
}

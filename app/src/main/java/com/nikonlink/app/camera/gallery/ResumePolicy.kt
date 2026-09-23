package com.nikonlink.app.camera.gallery

/**
 * 断点续传的判定口径（PRD v2.5 FR-05）。
 *
 * **为什么 >2GB 不能续传**：标准 PTP 的 `GetPartialObject`(0x101B) 把 offset 定义为
 * **32 位参数**，文件写到 2GB 之后就没有合法的 offset 可发；这不是我们装箱溢出问题，是协议上限。
 * 64 位变体 `GetPartialObject64` 在 Android 的 `MtpConstants` 里是 **0x95C1**（厂商扩展区，
 * 由 MS-OTP 系定义），**尼康机身是否实现未经真机验证** → 不能当默认路径上生产，
 * 已列入 FR-15 的真机探测清单。
 *
 * 这里只把既有判定抽成纯函数：行为与 v2.3.2 逐位一致，区别是**能给出原因**
 * （"机身续传范围之外" 与 "链路断了" 是完全不同的两件事，以前都是同一句"传输中断"）。
 */
object ResumePolicy {

    /** 32 位有符号上限：`GetPartialObject` 的 offset/count 参数都是这么编码的 */
    const val MAX_OFFSET_PARAM = Int.MAX_VALUE.toLong()

    /** 单次分块的 offset 参数还发得出去吗（与旧代码 `totalReceived > Int.MAX - size` 同一判据）。 */
    fun offsetFits(offset: Long, chunkSize: Int): Boolean =
        offset <= MAX_OFFSET_PARAM - chunkSize

    /**
     * 循环中途撞上限时怎么办。旧行为是 `break` 出循环再走整文件下载，
     * 只有「起点本来就是 0 或本来就在范围外」才允许清档重来；
     * 中途断的（有起点且在范围内）算链路问题，保留半成品等下次续。
     */
    fun restartWholeFileAfterAbort(startOffset: Long): Boolean =
        startOffset == 0L || startOffset > MAX_OFFSET_PARAM - 1L
}

/** 一次分块下载收口时的三种结局——以前只有两种（成功/失败），"机身范围外"被混进了"链路断了"。 */
enum class DownloadOutcome {
    Complete,

    /** 32 位续传范围之外，只能整份重来：这是协议上限，不是网络问题 */
    BeyondResumeRange,

    /** 链路或机身中途断了，半成品留着下次续 */
    LinkBroken;

    val isComplete: Boolean get() = this == Complete
}

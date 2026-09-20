package com.nikonlink.app.shared.ui

import android.content.Context
import android.os.SystemClock
import android.widget.Toast

/**
 * 全 App 的悬浮反馈单入口（PRD §2.2 D6）。
 *
 * 之前 42 处各自 `Toast.makeText(...).show()`：时长、文案详略、重复抑制全凭各页手感，
 * 而且"反馈长什么样"这件事没有一处能改 —— 想把它换成玻璃内联条得满仓搜。
 * 收成一个入口后顺手补两件本来就该有的事：
 *
 *  1. **同文案短时窗去重**：连点开关、批量失败回调成串回来时，不会叠出一列一模一样的提示
 *     （Android 的 Toast 队列是排队的，视觉上就是"消息雨"）。去重时**刷新时间戳**，
 *     持续失败的循环不会被误放行。
 *  2. 时长只留一个布尔参数，避免 `LENGTH_SHORT/LONG` 各处拍脑袋。
 *
 * 底座仍是系统 Toast，不自定义视图：自定义悬浮条要动窗口层级、焦点和无障碍语义，
 * 那是另一个 PRD 条目（§4 反馈层），不该夹在这次"先收口"里。
 */
object NlFeedback {

    /** 同文案去重窗口：比一次 Toast 的短显示（≈2s）略短，取 1.6s */
    private const val DEDUPE_WINDOW_MS = 1_600L

    private var lastText: CharSequence? = null
    private var lastAt = 0L

    /**
     * 用 applicationContext：Toast 会持有传入的 context，
     * 从 Fragment 里传 `requireContext()`（Activity 实例）进去等于给系统队列留个长引用。
     */
    @JvmOverloads
    fun show(context: Context, text: CharSequence, long: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastText && now - lastAt < DEDUPE_WINDOW_MS) {
            lastAt = now
            return
        }
        lastText = text
        lastAt = now
        Toast.makeText(
            context.applicationContext,
            text,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }
}

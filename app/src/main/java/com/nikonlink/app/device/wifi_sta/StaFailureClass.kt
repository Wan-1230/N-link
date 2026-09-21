package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.ptp.PtpConstants

/**
 * STA 连接失败的**机器可读**分类（PRD v2.5 FR-07）。
 *
 * 单独抽出来只为一件事：这套判定要能脱离 socket 与协程被钉死在测试里。
 * 之前只有 `ptp_handshake_failed` 一个笼统桶，于是「机身只有一个 PTP/IP 客户端槽位，
 * 被 SnapBridge 或另一台手机占着」这种**用户自己就能解决**的失败，
 * 和「相机在忙/在休眠」混成同一句提示 —— 前者关一下 App 就好，后者关不了。
 */
object StaFailureClass {

    /** 争抢型：相机明确回了「连接被占用 / 该主机已连接」 */
    const val BUSY_OTHER_CLIENT = "ptp_busy_other_client"

    /** 相机主动拒绝，但不是占用（含主机未认可，需做 STA 主机注册） */
    const val DENIED = "ptp_handshake_denied"

    /** 相机接了 TCP 却对事件通道一言不发（FR-02 的 event_ack_timeout） */
    const val ACK_SILENT = "event_ack_timeout"

    /** 地址上没人应答：不在同网段 / 相机没醒 / 被 AP 隔离 */
    const val UNREACHABLE = "camera_unreachable"

    const val HANDSHAKE_FAILED = "ptp_handshake_failed"

    /**
     * 判定顺序不能换：
     * ① 相机自己说过的话（InitFail 码）优先于任何推断；
     * ② 「静默」是我们在无证据下的推断，排在机身证据之后；
     * ③ 可达性探测只说明"有没有人"，不说明"为什么不让我连"。
     */
    fun classify(initFailReason: Int?, ackSilent: Boolean, reachable: Boolean): String = when {
        initFailReason == PtpConstants.INIT_FAIL_CONNECTION_IN_USE ||
            initFailReason == PtpConstants.INIT_FAIL_HOST_ALREADY_CONNECTED -> BUSY_OTHER_CLIENT
        initFailReason != null -> DENIED
        ackSilent -> ACK_SILENT
        !reachable -> UNREACHABLE
        else -> HANDSHAKE_FAILED
    }
}

package com.nikonlink.app.device.wifi_sta

import com.nikonlink.app.device.ptp.PtpConstants

/**
 * STA 连接失败的**机器可读**分类（PRD v2.5 FR-07）。
 *
 * 单独抽出来只为一件事：这套判定要能脱离 socket 与协程被钉死在测试里。
 * 之前只有 `ptp_handshake_failed` 一个笼统桶，于是「机身只有一个 PTP/IP 客户端槽位，
 * 被其它设备占着」这种**用户自己就能解决**的失败，
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

    /**
     * FR-39①：InitFail(reason=1) **持续被拒** → 判定为「需主机注册」而非「槽位被占」。
     *
     * 为什么不能只看 reason=1：本仓真机日志里 reason=1 有两种结局 ——
     * ① 相机在收上一代的半开会话（FR-21 实测 ~35s 内自己收干净，随后 pair_ok）；
     * ② 相机不认本机 GUID（同一路由器拓扑：相机为路由器新建了配置文件，
     *    本机没对**这个**配置文件做过 0x952B/0x935A 注册）—— 等待永远不会自愈。
     * 对同类控制应用的协议分析中，reason=1 的用户提示正是
     * "请确认相机当前处于配对模式；普通传输需使用已授权 GUID"。
     *
     * 分诊口径：连续 [streak] 次且距首次 [elapsedMs] —— 跨过槽位回收窗口
     * （35s）仍在拒，才是授权问题。两条件同时满足才升级；任何一个不满足
     * 都维持"忙/等待重试"的旧口径（含 streak=0 的首次，闸门关时根本不进来）。
     */
    fun initFail1NeedsHostReg(streak: Int, elapsedMs: Long): Boolean =
        streak >= INITFAIL1_STREAK_THRESHOLD && elapsedMs >= INITFAIL1_ELAPSED_FLOOR_MS

    /** 连续 reason=1 次数门槛：退避表下第 4 次失败约在首次后 ~30-46s，跨过 35s 回收窗 */
    const val INITFAIL1_STREAK_THRESHOLD = 4

    /** 距首次 reason=1 的时间下限（对齐 FR-21 的 ~35s 槽位回收 + 余量） */
    const val INITFAIL1_ELAPSED_FLOOR_MS = 45_000L
}

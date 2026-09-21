package com.nikonlink.app.device.connect

/**
 * PRD v2.5 FR-02 的三条 STA 超时判据，单独拎出来只为可测：
 * 真机上「拔掉相机 WiFi 看它会不会永久转圈」不是一周内的验收手段，
 * 而这三个判定本身是纯函数，没理由留在只能靠人肉验证的地方。
 */
object StaTimeoutPolicy {

    /**
     * event 通道的 `soTimeout`。
     *
     * 配对模式下机身会等用户按 OK，本来就有超时；**非配对模式在 v2.3.1 之前拿的是 0（永久阻塞）**，
     * 相机接了 TCP 却不回 InitEventAck 时整条连接挂死，且阻塞 read 不是挂起点，上层 generation 校验轮不到执行。
     * 闸门关 → 回到旧的 0。
     */
    fun eventSoTimeout(pairingMode: Boolean, gateOn: Boolean, readTimeoutMs: Int): Int =
        if (pairingMode || gateOn) readTimeoutMs else 0

    /**
     * `requestNetwork` 的系统侧截止点，与 AP 侧同款三参重载。
     * 返回 null = 用两参重载（把超时完全交给我们自己的轮询），即闸门关时的旧行为。
     */
    fun systemRequestDeadline(gateOn: Boolean, timeoutMs: Long): Int? =
        if (gateOn) timeoutMs.coerceAtLeast(1L).toInt() else null

    /**
     * ARP 扫描剩余预算（毫秒）。≤ 0 表示预算已尽，不再发起新的探测。
     * 已发起的那一笔仍由探测自己的超时收尾，所以总时长的上界是
     * 「预算 + 一笔在途探测」，与网段盲扫同一口径。
     */
    fun arpRemainingMs(deadlineMs: Long, nowMs: Long): Long = deadlineMs - nowMs
}

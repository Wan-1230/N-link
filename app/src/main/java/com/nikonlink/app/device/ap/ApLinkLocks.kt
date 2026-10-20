package com.nikonlink.app.device.ap

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.shared.common.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AP 链路的 WiFi 持锁器（与 STA 的 `WifiNetworkMonitor` 同档）。
 *
 * ## 为什么 AP 需要它
 *
 * STA 从 v1.1.0 起就在整个会话期持有 `WIFI_MODE_FULL_HIGH_PERF`
 * （`WifiNetworkMonitor.acquireLocks/retainLocks`，注释写明「三方 APK 全部持有」）。
 * AP 路径（`NetworkBinder` → `ApPtpClient`）此前**一把锁都没有**，这是两条链路
 * 在监看流畅度上最大的不对称：
 *
 * - 相机热点没有外网，系统把它当「闲置网络」，Wi-Fi 很快进入省电（PS-Poll）。
 *   省电模式下 AP 只在信标周期醒来收发，单帧数百 KB 会被切成几百毫秒一段，
 *   体感就是画面一顿一顿；
 * - STA 侧的路由器通常有外网、且会话期一直持着高性能锁，同样的帧长不会被切。
 *
 * ## 两级持锁
 *
 * - **会话级**（连接成功 → 断开）：`WIFI_MODE_FULL_HIGH_PERF`，与 STA 同档。
 *   覆盖相册浏览、原图下载等整段 AP 会话；
 * - **监看级**（LiveView RUNNING → 停止）：再叠一把 `WIFI_MODE_FULL_LOW_LATENCY`
 *   （API 29+ 专为实时场景提供，进一步压低空口排队时延）。
 *   只在取景期间持有，避免非监看时白白耗电。
 *
 * 两把锁都是 `setReferenceCounted(false)` + 幂等持有/释放：重复 retain 不会叠加，
 * 重复 release 不会抛异常 —— 调用方（状态机回调、页面销毁、异常收口）可以放心多调。
 *
 * 闸门 [ConnFlags.AP_LV_WIFI_LOCK]：关掉即回到「不持锁」的旧行为。
 */
@Singleton
class ApLinkLocks @Inject constructor(
    @ApplicationContext context: Context,
    private val connFlags: ConnFlags,
    private val eventLogger: AppEventLogger,
) {
    private companion object {
        const val TAG = "ApLinkLocks"
        const val SESSION_TAG = "N-Link:ap-session"
        const val STREAM_TAG = "N-Link:ap-liveview"
    }

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var sessionLock: WifiManager.WifiLock? = null
    private var streamLock: WifiManager.WifiLock? = null

    /** 会话级持锁（幂等）。 */
    @Synchronized
    fun retainSession() {
        if (sessionLock != null) return
        sessionLock = acquire(WIFI_MODE_HIGH_PERF, SESSION_TAG) ?: return
        Timber.tag(TAG).i("AP session WifiLock acquired")
        eventLogger.event("ap_lock", "scope" to "session", "held" to true)
    }

    /** 会话级释放（幂等）。 */
    @Synchronized
    fun releaseSession() {
        val lock = sessionLock ?: return
        sessionLock = null
        release(lock, "session")
    }

    /** 监看级持锁（幂等）。 */
    @Synchronized
    fun retainStreaming() {
        if (streamLock != null) return
        streamLock = acquire(WIFI_MODE_LOW_LATENCY, STREAM_TAG) ?: return
        Timber.tag(TAG).i("AP live-view WifiLock acquired (low latency)")
        eventLogger.event("ap_lock", "scope" to "liveview", "held" to true)
        logLinkSnapshot()
    }

    /**
     * 把「这次监看是在什么空口条件下跑的」写进全链路日志（2026-10-19）。
     *
     * 只读一次、只在开播时写一行。四个数各自回答一个具体问题：
     * - `freq`：2.4GHz（24xx）还是 5GHz（5xxx）—— 决定带宽上限；
     * - `mbps`：协商速率。若只有 1~11Mbps，帧再小也快不起来；
     * - `rssi`：信号强度，判断是不是离机身太远；
     * - `lock_*`：两把锁**到底有没有拿到**（部分 ROM 会拒绝/忽略 WifiLock，
     *   拿到 ≠ 生效，但没拿到就一定在省电）。
     */
    @Suppress("DEPRECATION")
    fun logLinkSnapshot() {
        val info = runCatching { wifiManager?.connectionInfo }.getOrNull() ?: return
        eventLogger.event(
            "ap_wifi",
            "freq" to info.frequency,
            "mbps" to info.linkSpeed,
            "rssi" to info.rssi,
            "lock_session" to (sessionLock != null),
            "lock_stream" to (streamLock != null)
        )
    }

    /** 监看级释放（幂等）。 */
    @Synchronized
    fun releaseStreaming() {
        val lock = streamLock ?: return
        streamLock = null
        release(lock, "liveview")
    }

    private fun acquire(mode: Int, tag: String): WifiManager.WifiLock? {
        if (!connFlags.isEnabled(ConnFlags.AP_LV_WIFI_LOCK)) return null
        val wm = wifiManager ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(mode, tag)
            lock.setReferenceCounted(false)
            lock.acquire()
            lock
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to acquire WifiLock($tag)")
        }.getOrNull()
    }

    private fun release(lock: WifiManager.WifiLock, scope: String) {
        runCatching { if (lock.isHeld) lock.release() }
        Timber.tag(TAG).i("AP WifiLock released ($scope)")
        eventLogger.event("ap_lock", "scope" to scope, "held" to false)
    }

    /**
     * `WIFI_MODE_FULL_LOW_LATENCY`（API 29+）在部分 ROM 上不可用，
     * 取不到时退回 `WIFI_MODE_FULL_HIGH_PERF` —— 两者都能关掉省电，
     * 后者只是少了低时延那一档。
     */
    private val WIFI_MODE_LOW_LATENCY: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }

    @Suppress("DEPRECATION")
    private val WIFI_MODE_HIGH_PERF: Int
        get() = WifiManager.WIFI_MODE_FULL_HIGH_PERF
}

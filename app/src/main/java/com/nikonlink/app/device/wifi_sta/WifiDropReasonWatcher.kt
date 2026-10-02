package com.nikonlink.app.device.wifi_sta

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import com.nikonlink.app.device.connect.ConnFlags
import com.nikonlink.app.shared.common.AppEventLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-34⑦（P1 诊断）：WLAN 掉线瞬间，把系统广播**实际携带**的原因字段落进导出日志。
 *
 * 悬案「谁砍的 wlan0」（我们的请求回收 / vivo 策略 / 相机 AP 复位）此前只能推断：
 * 日志里最早的一行是 `net_cb lost` 或 `sta_ifaces` 少了一块，而那已经是**结果**。
 *
 * ## 为什么是"枚举键名"而不是读固定常量
 *
 * AOSP 在 API 30 给 `NETWORK_STATE_CHANGED_ACTION` 加过掉线原因类 extras，但它们全是
 * @SystemApi/@hide（android-35 的 android.jar 与 stubs 源码已逐一核对：公开常量只有
 * EXTRA_NEW_STATE / EXTRA_BSSID 等 12 个，没有任何 reason/disconnect-type 常量），
 * 而"普通 App 的接收器实际能收到哪些字段"各 ROM 行为不一 —— 这条**无法离线验证**。
 * 所以本类不硬编码任何凭记忆的键名（避免把推断当事实），改为在 DISCONNECTED 时
 * 枚举 Intent extras 里键名含 reason / disconnect / error 的项，只落**键名与整数值**。
 * 第一次真机掉线就能定谳：这台 ROM 到底下发了哪些原因字段、值是多少。
 *
 * 隐私边界：只落键名与整数 —— 不读 EXTRA_NEW_STATE 的 SSID、不读 EXTRA_BSSID，
 * 与「导出内容声明」一致。纯日志、零行为变化；生命周期与连接会话对齐
 * （[WifiDirectConnector] 起停）。
 */
@Singleton
class WifiDropReasonWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventLogger: AppEventLogger,
    private val connFlags: ConnFlags
) {
    companion object {
        private const val TAG = "WlanDrop"

        /** 只采集这些关键词的 extra 键，其余一律不看（防把 SSID/BSSID 顺出去）。 */
        private val INTERESTING = listOf("reason", "disconnect", "error")
    }

    @Volatile
    private var receiver: BroadcastReceiver? = null

    /** 开始监听（幂等；闸门关时什么都不做）。 */
    @Synchronized
    fun start() {
        if (receiver != null) return
        if (!connFlags.isEnabled(ConnFlags.WLAN_DROP_REASON)) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val state = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NEW_STATE)
                    if (state?.detailedState != NetworkInfo.DetailedState.DISCONNECTED) return
                    val fields = collectReasonFields(intent)
                    eventLogger.event("wlan_drop", "fields" to (fields.ifEmpty { "none" }))
                    Timber.tag(TAG).i("wlan drop fields=[%s]", fields)
                }
            }
        }
        runCatching {
            val filter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            receiver = r
        }.onFailure {
            Timber.tag(TAG).w(it, "registerReceiver(NETWORK_STATE_CHANGED) failed")
        }
    }

    /** 停止监听（幂等）。 */
    @Synchronized
    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * 枚举 extras 里键名含 reason/disconnect/error 的整数型字段（`key=value;` 串）。
     *
     * 抽成 internal 纯逻辑便于单测：喂一个 Bundle 就能钉死"只收整数键、
     * 不碰 SSID/BSSID"这条隐私线。
     */
    internal fun collectReasonFields(intent: Intent): String {
        val extras = intent.extras ?: return ""
        val sb = StringBuilder()
        for (key in extras.keySet()) {
            val lk = key.lowercase()
            if (INTERESTING.none { frag -> lk.contains(frag) }) continue
            val v = runCatching { extras.get(key) }.getOrNull()
            when (v) {
                is Int -> sb.append("$key=$v;")
                is Long -> sb.append("$key=$v;")
                // 非数值型的原因键（少见）只落键名不落值 —— 值可能是 Parcelable 带隐私字段
                is String -> sb.append("$key=str(${v.length});")
                else -> if (v != null) sb.append("$key=${v.javaClass.simpleName};")
            }
        }
        return sb.toString()
    }
}

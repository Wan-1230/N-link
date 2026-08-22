package com.nikonlink.app.device.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

/**
 * 开机自启广播接收器
 *
 * PRD 3.2: BOOT_COMPLETED 广播接收，开机后自动恢复连接
 * PRD 3.5: App 被杀 → BOOT_COMPLETED 兜底
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Timber.tag(TAG).i("Boot completed, starting ConnectionService")
            val serviceIntent = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

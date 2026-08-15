package com.nikonlink.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nikonlink.app.MainActivity
import com.nikonlink.app.NLinkApp
import com.nikonlink.app.R
import com.nikonlink.app.core.common.ConnectionState
import com.nikonlink.app.core.connection.ConnectionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 连接前台服务 - N-Link 保活核心
 *
 * PRD 3.2 Android 保活策略:
 * - Foreground Service + 持久通知（显示连接状态）
 * - PARTIAL_WAKE_LOCK 保持 CPU 运行（BLE 心跳期间）
 * - 前台服务自动重启
 *
 * PRD 1.5: 后台存活率 > 99%
 */
@AndroidEntryPoint
class ConnectionService : LifecycleService() {

    companion object {
        private const val TAG = "ConnectionService"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "N-Link::ConnectionWakeLock"
    }

    @Inject
    lateinit var connectionManager: ConnectionManager

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("Service created")
        acquireWakeLock()
        startForegroundWithNotification()
        startConnectionManager()
        observeConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // START_STICKY: 服务被杀后自动重启
        // PRD 3.5: App 被杀 → 前台服务自动重启
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        Timber.tag(TAG).i("Service destroyed")
        connectionManager.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.tag(TAG).w("Task removed, service will restart (START_STICKY)")
    }

    /**
     * PRD 3.2: PARTIAL_WAKE_LOCK 保持 CPU 运行
     */
    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Timber.tag(TAG).d("WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.tag(TAG).d("WakeLock released")
            }
        }
        wakeLock = null
    }

    /**
     * 启动前台通知
     */
    private fun startForegroundWithNotification() {
        val notification = buildNotification(
            title = getString(R.string.notification_connection_title),
            text = getString(R.string.notification_connection_text_disconnected)
        )
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    /**
     * 启动连接管理器
     */
    private fun startConnectionManager() {
        connectionManager.start(lifecycleScope)
        Timber.tag(TAG).i("ConnectionManager started in service")
    }

    /**
     * 监听连接状态变化，更新通知栏
     * PRD 3.4: 通知栏常驻 - 显示连接状态图标
     */
    private fun observeConnectionState() {
        lifecycleScope.launch {
            connectionManager.connectionState.collectLatest { state ->
                val (title, text) = when (state) {
                    ConnectionState.DISCONNECTED ->
                        getString(R.string.notification_connection_title) to
                                getString(R.string.notification_connection_text_disconnected)
                    ConnectionState.CONNECTING ->
                        getString(R.string.notification_connection_title) to "正在连接相机..."
                    ConnectionState.BLE_CONNECTED ->
                        getString(R.string.notification_connection_title) to
                                getString(R.string.notification_connection_text_ble)
                    ConnectionState.WIFI_UPGRADING ->
                        getString(R.string.notification_connection_title) to "正在建立高速通道..."
                    ConnectionState.FULLY_CONNECTED ->
                        getString(R.string.notification_connection_title) to
                                getString(R.string.notification_connection_text_full)
                    ConnectionState.ERROR_WAITING_RETRY ->
                        getString(R.string.notification_connection_title) to "连接中断，正在自动恢复..."
                }
                updateNotification(title, text)
            }
        }
    }

    private fun updateNotification(title: String, text: String) {
        val notification = buildNotification(title, text)
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NLinkApp.CHANNEL_CONNECTION)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}

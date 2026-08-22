package com.nikonlink.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.nikonlink.app.device.service.ConnectionHealthWorker
import com.nikonlink.app.shared.common.AppEventLogger
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * N-Link Application 入口
 * PRD 4.2: Hilt DI, Timber logging
 */
@HiltAndroidApp
class NLinkApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var eventLogger: AppEventLogger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    companion object {
        const val CHANNEL_CONNECTION = "connection_status"
        const val CHANNEL_TRANSFER = "file_transfer"
    }

    override fun onCreate() {
        super.onCreate()
        eventLogger.installCrashHandler()
        initLogging()
        createNotificationChannels()
        scheduleHealthCheck()
    }

    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val connectionChannel = NotificationChannel(
            CHANNEL_CONNECTION,
            getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示相机连接状态"
            setShowBadge(false)
        }

        val transferChannel = NotificationChannel(
            CHANNEL_TRANSFER,
            getString(R.string.notification_channel_transfer),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "显示照片传输进度"
        }

        notificationManager.createNotificationChannels(
            listOf(connectionChannel, transferChannel)
        )
    }

    /**
     * PRD 3.2: WorkManager 周期性连接健康检查（15min 间隔）
     */
    private fun scheduleHealthCheck() {
        ConnectionHealthWorker.schedule(WorkManager.getInstance(this))
    }
}

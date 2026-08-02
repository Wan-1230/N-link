package com.nikonlink.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.nikonlink.app.core.connection.ConnectionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 连接健康检查 Worker
 *
 * PRD 3.2: WorkManager 周期性连接健康检查（15min 间隔）
 * PRD 3.5: 检测连接异常并自动恢复
 */
@HiltWorker
class ConnectionHealthWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val connectionManager: ConnectionManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HealthWorker"
        private const val WORK_NAME = "connection_health_check"

        /**
         * 调度周期性健康检查
         * PRD 3.2: 15min 间隔
         */
        fun schedule(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<ConnectionHealthWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.tag(TAG).i("Health check scheduled (15min interval)")
        }
    }

    override suspend fun doWork(): Result {
        Timber.tag(TAG).d("Running connection health check")

        return try {
            val state = connectionManager.connectionState.value

            when {
                // 完全断开 → 尝试重连
                state == com.nikonlink.app.core.common.ConnectionState.DISCONNECTED -> {
                    Timber.tag(TAG).w("Connection lost, attempting auto-reconnect")
                    connectionManager.reconnectLastDevice()
                    Result.success()
                }
                // BLE 连接但无 WiFi → 正常（低功耗模式）
                state == com.nikonlink.app.core.common.ConnectionState.BLE_CONNECTED -> {
                    Timber.tag(TAG).d("BLE only - normal low-power state")
                    Result.success()
                }
                // 完全连接 → 健康
                state == com.nikonlink.app.core.common.ConnectionState.FULLY_CONNECTED -> {
                    Timber.tag(TAG).d("Fully connected - healthy")
                    Result.success()
                }
                // 错误等待重试 → 触发重连
                state == com.nikonlink.app.core.common.ConnectionState.ERROR_WAITING_RETRY -> {
                    Timber.tag(TAG).w("In error state, forcing reconnect")
                    connectionManager.reconnectLastDevice()
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Health check failed")
            Result.retry()
        }
    }
}

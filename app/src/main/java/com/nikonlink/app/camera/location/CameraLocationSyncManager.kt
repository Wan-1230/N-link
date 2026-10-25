package com.nikonlink.app.camera.location

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.nikonlink.app.shared.common.AppEventLogger
import com.nikonlink.app.shared.common.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 位置状态（设置页与相机详情页共用；文案由 UI 侧本地化）。
 */
sealed interface CameraLocationStatus {
    data object Off : CameraLocationStatus
    data object PermissionMissing : CameraLocationStatus
    data object LocationServiceDisabled : CameraLocationStatus
    data object PausedInBackground : CameraLocationStatus
    data object WaitingForCamera : CameraLocationStatus
    data object Locating : CameraLocationStatus
    data class Synced(val atMillis: Long, val accuracyMeters: Float) : CameraLocationStatus
    data class Failed(val code: Int) : CameraLocationStatus
    data object Unsupported : CameraLocationStatus
}

/**
 * 把手机位置写入相机（PTP 0x9523），让相机此后拍的照片带地点。
 *
 * 与「分享时保留 GPS」完全无关：本类只碰相机，不读写手机照片的 EXIF。
 *
 * 触发条件（三者同时满足）：
 * 1. 设置里开启「向相机发送位置信息」；
 * 2. App 在前台（避免申请后台定位权限，也避免用户不知情时持续定位）；
 * 3. 相机已通过 USB / AP / STA 任一通道连上。
 *
 * 发送节奏：连接后首个定位立即发；之后位移 ≥20m 或距上次 ≥5 分钟再发。
 * 关闭开关时若相机在线，补发一次全零包清除相机内的旧位置。
 */
@Singleton
class CameraLocationSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
    private val channel: CameraLocationChannel,
    private val eventLogger: AppEventLogger
) {

    companion object {
        private const val TAG = "CameraLocation"
        private const val UPDATE_INTERVAL_MS = 30_000L
        private const val UPDATE_MIN_DISTANCE_M = 15f
        private const val RESEND_INTERVAL_MS = 5 * 60_000L
        private const val RESEND_DISTANCE_M = 20f
        private const val WATCHDOG_INTERVAL_MS = 10_000L
        private const val FRESH_FIX_TIMEOUT_MS = 8_000L
        private const val RECENT_LAST_KNOWN_MS = 2 * 60_000L
    }

    private val _status = kotlinx.coroutines.flow.MutableStateFlow<CameraLocationStatus>(
        CameraLocationStatus.Off
    )
    val status: kotlinx.coroutines.flow.StateFlow<CameraLocationStatus> = _status

    private val app = context.applicationContext as Application
    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false
    private var foreground = false
    private var startedActivities = 0
    private var watchdogJob: Job? = null
    private var updatesRegistered = false
    private val sending = AtomicBoolean(false)
    private var lastSendElapsed = 0L
    private var lastAttemptElapsed = 0L
    private var lastSentLocation: Location? = null
    private var unsupportedThisProcess = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            maybeSend(location)
        }

        @Deprecated("Android 29 仍要求实现；状态变化不影响本类")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    fun start() {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) {
                    foreground = true
                    refresh()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    foreground = false
                    stopUpdates()
                    stopWatchdog()
                    if (settings.cameraLocationEnabled) {
                        _status.value = CameraLocationStatus.PausedInBackground
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        refresh()
    }

    /** 设置开关变化后调用：开启则重新评估，关闭则停更并清掉相机里的旧位置。 */
    fun onSettingChanged() {
        if (settings.cameraLocationEnabled) {
            unsupportedThisProcess = false
            refresh()
        } else {
            stopUpdates()
            stopWatchdog()
            lastSendElapsed = 0L
            lastSentLocation = null
            _status.value = CameraLocationStatus.Off
            sendDisableIfPossible()
        }
    }

    /**
     * 详情页「立即同步」：即使连续定位尚未启动，也取一次新鲜位置并发送。
     * 返回发送后的状态，调用方直接展示。
     */
    suspend fun syncNow(): CameraLocationStatus {
        if (!settings.cameraLocationEnabled) return CameraLocationStatus.Off
        if (!hasPermission()) return CameraLocationStatus.PermissionMissing
        if (!isLocationServiceEnabled()) return CameraLocationStatus.LocationServiceDisabled
        if (!channel.isAvailable()) return CameraLocationStatus.WaitingForCamera
        if (unsupportedThisProcess || channel.knownUnsupported()) {
            unsupportedThisProcess = true
            _status.value = CameraLocationStatus.Unsupported
            return CameraLocationStatus.Unsupported
        }
        _status.value = CameraLocationStatus.Locating
        val location = requestFreshLocation() ?: return CameraLocationStatus.Failed(-1)
        return sendLocation(location)
    }

    private fun refresh() {
        if (!settings.cameraLocationEnabled) {
            stopUpdates()
            stopWatchdog()
            _status.value = CameraLocationStatus.Off
            return
        }
        if (!hasPermission()) {
            stopUpdates()
            stopWatchdog()
            _status.value = CameraLocationStatus.PermissionMissing
            return
        }
        if (!isLocationServiceEnabled()) {
            stopUpdates()
            stopWatchdog()
            _status.value = CameraLocationStatus.LocationServiceDisabled
            return
        }
        if (!foreground) {
            stopUpdates()
            stopWatchdog()
            _status.value = CameraLocationStatus.PausedInBackground
            return
        }
        ensureWatchdog()
        tick()
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                tick()
                delay(WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private fun tick() {
        if (!settings.cameraLocationEnabled) {
            stopUpdates()
            stopWatchdog()
            _status.value = CameraLocationStatus.Off
            return
        }
        if (!foreground || !hasPermission() || !isLocationServiceEnabled()) {
            stopUpdates()
            stopWatchdog()
            refresh()
            return
        }
        if (!channel.isAvailable()) {
            stopUpdates()
            // 换相机/重连后要重新试一次；同一台不支持 0x9523 的机身则保持"不支持"不再打扰
            unsupportedThisProcess = false
            // 相机断开后旧位置不能当作"刚发过"：重连的首个定位必须立即写一次
            lastSendElapsed = 0L
            lastSentLocation = null
            _status.value = CameraLocationStatus.WaitingForCamera
            return
        }
        if (unsupportedThisProcess || channel.knownUnsupported()) {
            unsupportedThisProcess = true
            stopUpdates()
            // 保留 watchdog：换一台相机/重连后能力表可能变化，10s 后再评估
            _status.value = CameraLocationStatus.Unsupported
            return
        }
        startUpdates()
        if (_status.value !is CameraLocationStatus.Synced) {
            _status.value = CameraLocationStatus.Locating
        }
    }

    private fun startUpdates() {
        if (updatesRegistered) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            _status.value = CameraLocationStatus.LocationServiceDisabled
            return
        }
        try {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    UPDATE_INTERVAL_MS,
                    UPDATE_MIN_DISTANCE_M,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            updatesRegistered = true
            // 注册后先拿一次上次已知位置：室内 GPS 首次定位可能要几十秒，
            // 等待期间相机仍会拍到"无地点"的照片。
            bestLastKnownLocation()?.let { maybeSend(it, force = lastSendElapsed == 0L) }
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Location update rejected")
            _status.value = CameraLocationStatus.PermissionMissing
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Location update failed")
            _status.value = CameraLocationStatus.Failed(-1)
        }
    }

    private fun stopUpdates() {
        if (!updatesRegistered) return
        runCatching { locationManager.removeUpdates(locationListener) }
        updatesRegistered = false
    }

    private fun maybeSend(location: Location, force: Boolean = false) {
        if (!settings.cameraLocationEnabled || !foreground) return
        if (unsupportedThisProcess) return
        if (!channel.isAvailable()) return
        if (sending.get()) return
        val now = SystemClock.elapsedRealtime()
        val moved = lastSentLocation?.distanceTo(location) ?: Float.MAX_VALUE
        val due = force || lastSendElapsed == 0L ||
            now - lastSendElapsed >= RESEND_INTERVAL_MS || moved >= RESEND_DISTANCE_M
        if (!due) return
        // 失败后 30s 内不重复撞同一个错误；成功时间只在 sendLocation 成功后推进
        if (now - lastAttemptElapsed < UPDATE_INTERVAL_MS) return
        lastAttemptElapsed = now
        scope.launch { sendLocation(location) }
    }

    private suspend fun sendLocation(location: Location): CameraLocationStatus {
        if (!sending.compareAndSet(false, true)) {
            return _status.value
        }
        return try {
            val payload = CameraLocationPayload.build(location)
                ?: return CameraLocationStatus.Failed(-1).also {
                    _status.value = it
                }
            val result = channel.send(payload)
            val next = when {
                result.ok -> {
                    lastSendElapsed = SystemClock.elapsedRealtime()
                    lastSentLocation = location
                    CameraLocationStatus.Synced(
                        atMillis = System.currentTimeMillis(),
                        accuracyMeters = if (location.hasAccuracy()) location.accuracy else -1f
                    )
                }
                result.unsupported -> {
                    unsupportedThisProcess = true
                    CameraLocationStatus.Unsupported
                }
                else -> CameraLocationStatus.Failed(result.code)
            }
            _status.value = next
            next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Send location failed")
            _status.value = CameraLocationStatus.Failed(-1)
            CameraLocationStatus.Failed(-1)
        } finally {
            sending.set(false)
        }
    }

    private fun sendDisableIfPossible() {
        if (!channel.isAvailable()) return
        scope.launch {
            runCatching { channel.send(CameraLocationPayload.disabled()) }
            eventLogger.event("gps_camera_disable")
        }
    }

    private suspend fun requestFreshLocation(): Location? {
        bestLastKnownLocation()?.takeIf {
            System.currentTimeMillis() - it.time <= RECENT_LAST_KNOWN_MS
        }?.let { return it }

        return withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val delivered = AtomicBoolean(false)
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (delivered.compareAndSet(false, true) && continuation.isActive) {
                            runCatching { locationManager.removeUpdates(this) }
                            continuation.resume(location)
                        }
                    }

                    @Deprecated("Android 29 仍要求实现")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                }
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER
                ).filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
                if (providers.isEmpty()) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                try {
                    providers.forEach {
                        locationManager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper())
                    }
                } catch (e: SecurityException) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { locationManager.removeUpdates(listener) }
                }
            }
        }
    }

    private fun bestLastKnownLocation(): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun isLocationServiceEnabled(): Boolean =
        runCatching { locationManager.isLocationEnabled }.getOrDefault(false)
}

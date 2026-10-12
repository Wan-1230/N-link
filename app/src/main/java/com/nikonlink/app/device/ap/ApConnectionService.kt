package com.nikonlink.app.device.ap

import android.content.Context
import com.camerakit.api.CameraConnection
import com.camerakit.api.CameraEvent
import com.camerakit.api.CameraSession
import com.camerakit.api.CameraState
import com.camerakit.api.ConnectionMethod
import com.camerakit.client.CameraConnectionFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AP 连接的全局持有者（工作树连接方案的唯一入口）。
 *
 * 连接实例必须全 App 单例：设备页发起连接，相册 / 监看共享同一个会话 ——
 * 多份实例会各自占用相机唯一的 PTP/IP 客户端槽，把活会话踢下线
 * （表现为「连接成功瞬间断开」）。
 */
@Singleton
class ApConnectionService @Inject constructor(
    @ApplicationContext context: Context,
) {
    val client: CameraConnection = CameraConnectionFactory.create(context) { msg ->
        Timber.tag("ApKit").i(msg)
    }

    val state: StateFlow<CameraState> get() = client.state
    val events: SharedFlow<CameraEvent> get() = client.events

    /**
     * 发起过 AP 连接（且未显式断开）。
     * 用于区分「AP 场景」与 STA/USB 场景：AP 断线后相册等模块不得回退旧链路自动重连，
     * 只提示用户回连接页重连。
     */
    @Volatile
    var engaged: Boolean = false
        private set

    fun markEngaged() {
        engaged = true
    }

    /** 当前会话（未连接为 null）。 */
    fun session(): CameraSession? = client.session()

    /** 底层 PTP 会话（监看等需要扩展命令时使用）。 */
    fun ptpSession(): com.camerakit.ptp.PtpSession? =
        client.session() as? com.camerakit.ptp.PtpSession

    fun isConnected(): Boolean = client.state.value is CameraState.Connected

    /** 断开连接（优雅关闭，不触发自动恢复）。 */
    fun disconnect() {
        engaged = false
        client.disconnect()
    }
}

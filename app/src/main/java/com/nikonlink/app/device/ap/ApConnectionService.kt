package com.nikonlink.app.device.ap

import android.content.Context
import com.camerakit.api.CameraConnection
import com.camerakit.api.CameraEvent
import com.camerakit.api.CameraSession
import com.camerakit.api.CameraState
import com.camerakit.api.ConnectionMethod
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
    private val eventLogger: com.nikonlink.app.shared.common.AppEventLogger,
) {
    /** AP 引擎连接（整体替换版：开源 PTP/IP 引擎 + 网络绑定；失败直接自动重试）。 */
    val ap: com.camerakit.ap.ApCameraConnection = com.camerakit.ap.ApCameraConnection(context) { msg ->
        Timber.tag("ApKit").i(msg)
        // AP 连接全程打点写入全链路日志（设置页可导出）——此前 AP 只进 logcat，
        // 真机出问题没现场可查
        eventLogger.event("ap_kit", "msg" to msg)
    }

    val client: CameraConnection get() = ap

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
    fun session(): CameraSession? = ap.session()

    /** AP 引擎桥（监看 / 遥控 / 参数 / 相册的命令面；未连接为 null）。 */
    fun bridge(): com.camerakit.ap.ApEngineBridge? = ap.bridge()

    fun isConnected(): Boolean = client.state.value is CameraState.Connected

    /** 断开连接（优雅关闭，不触发自动恢复）。 */
    fun disconnect() {
        engaged = false
        client.disconnect()
    }

    /** AP 连接失败打点：把完整异常链（含握手阶段）写入全链路日志，便于真机定位。 */
    fun logFailure(action: String, t: Throwable) {
        Timber.tag("ApKit").w(t, "$action failed")
        val chain = generateSequence(t) { it.cause }
            .take(5)
            .joinToString(" <- ") { it.javaClass.simpleName + ":" + (it.message ?: "-") }
        eventLogger.event("link_error", "channel" to "ap", "action" to action, "chain" to chain)
    }
}

package com.camerakit.pairing

import com.camerakit.api.CameraError
import com.camerakit.ptp.PtpConsts
import com.camerakit.ptp.PtpSession
import kotlinx.coroutines.delay

/**
 * 主机配对（基准实现「PC 主机注册」流程，逆向实证）：
 *
 * 1. `0x952B` 预备（data-in 返回相机侧展示字符串）；
 * 2. 等待用户在相机「主机确认」界面确认本机（基准实现 等待 8500ms，可配置）；
 * 3. `0x935A` 参数 `0x2001` 确认；失败自动重试。
 */
class HostRegistrar(
    private val log: (String) -> Unit = {},
) {

    suspend fun register(
        session: PtpSession,
        onAwaitConfirm: suspend (String) -> Unit = {},
        confirmWaitMs: Long = PtpConsts.HOST_CONFIRM_WAIT_MS,
        retries: Int = 3,
    ): String {
        log("[注册] 正在建立 PC 主机注册会话…")
        val prepare = session.hostRegisterPrepare()
        log("[注册] phase=prepare 响应=「$prepare」（${prepare.length} 字符）")
        onAwaitConfirm(prepare)

        log("[注册] phase=confirm-wait ms=$confirmWaitMs（等相机端按确认）")
        delay(confirmWaitMs)

        var lastError: Exception? = null
        repeat(retries) { attempt ->
            try {
                session.hostRegisterConfirm()
                log("[注册] phase=confirm ✓ 注册完成（预备响应「$prepare」）")
                return prepare
            } catch (e: Exception) {
                lastError = e
                log("[注册] phase=confirm 失败（第 ${attempt + 1}/$retries 次）：${e.message}")
                if (attempt < retries - 1) delay(1_500)
            }
        }
        throw CameraError.PairingRejected("相机未确认主机注册：${lastError?.message ?: "无响应"}")
    }
}

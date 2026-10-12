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
        log("正在建立 PC 主机注册会话…")
        val prepare = session.hostRegisterPrepare()
        log("host-register phase=prepare 响应=$prepare")
        onAwaitConfirm(prepare)

        log("host-register phase=confirm-wait ms=$confirmWaitMs")
        delay(confirmWaitMs)

        var lastError: Exception? = null
        repeat(retries) { attempt ->
            try {
                session.hostRegisterConfirm()
                log("PC 主机注册完成：预备响应 $prepare；确认命令成功")
                return prepare
            } catch (e: Exception) {
                lastError = e
                log("host-register confirm 失败（第 ${attempt + 1}/$retries 次）：${e.message}")
                if (attempt < retries - 1) delay(1_500)
            }
        }
        throw CameraError.PairingRejected("相机未确认主机注册：${lastError?.message ?: "无响应"}")
    }
}

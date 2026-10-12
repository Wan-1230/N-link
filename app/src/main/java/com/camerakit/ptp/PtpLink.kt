package com.camerakit.ptp

import kotlinx.coroutines.flow.SharedFlow

/** 单次 PTP 事务结果。 */
data class PtpResult(
    val code: Int,
    val params: List<Long> = emptyList(),
    val dataIn: ByteArray? = null,
) {
    val isOk: Boolean get() = code == PtpConsts.RC_OK

    override fun equals(other: Any?): Boolean =
        other is PtpResult && other.code == code && other.params == params &&
            ((dataIn == null && other.dataIn == null) || dataIn?.contentEquals(other.dataIn) == true)

    override fun hashCode(): Int = code * 31 + params.hashCode()
}

/** 相机异步事件（PTP/IP type8 / USB interrupt 容器）。 */
data class PtpCameraEvent(val code: Int, val params: List<Long>)

/**
 * 链路抽象：屏蔽 PTP/IP（双 socket）与 USB（bulk 容器）差异。
 *
 * 约定：
 * - [transact] 串行执行（内部互斥），自动处理数据相位、事务号匹配与超时；
 * - data-in 聚合上限 32MB，超限或大对象请传 [sink] 流式消费；
 * - 传输层断开抛 [com.camerakit.api.CameraError.TransportLost]。
 */
interface PtpLink {
    val events: SharedFlow<PtpCameraEvent>
    val isOpen: Boolean

    /** 最近一次收到任何包的时刻（SystemClock/elapsedRealtime 语义，毫秒）。 */
    fun lastReceiveAt(): Long

    suspend fun transact(
        opcode: Int,
        params: List<Long> = emptyList(),
        dataOut: ByteArray? = null,
        sink: ((ByteArray) -> Unit)? = null,
        timeoutMs: Long = 20_000L,
    ): PtpResult

    /** 发送保活 Ping（PTP/IP type13 / USB 侧为轻量只读事务），不等待响应。 */
    suspend fun sendKeepalive()

    /** 关闭链路；[graceful] 为 true 时不触发传输丢失回调。 */
    fun close(graceful: Boolean = true)
}

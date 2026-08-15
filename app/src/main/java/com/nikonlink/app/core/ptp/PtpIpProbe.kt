package com.nikonlink.app.core.ptp

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 轻量 PTP/IP Init 探测。
 *
 * STA 发现流程：仅 TCP 端口可连不代表相机已准备好，必须发送
 * InitCommand 并收到 InitResponse，才能把该 IP 判定为可用的尼康相机。
 */
object PtpIpProbe {
    private const val TAG = "PtpIpProbe"

    suspend fun probe(
        host: String,
        port: Int = PtpConstants.DEFAULT_PORT,
        timeoutMs: Long = 1200L,
        network: Network? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = if (network != null) network.socketFactory.createSocket() else Socket()
            socket.use {
                it.connect(
                    InetSocketAddress(host, port),
                    timeoutMs.toInt().coerceIn(300, 1500)
                )
                it.soTimeout = timeoutMs.toInt().coerceIn(300, 1500)
                it.tcpNoDelay = true

                val output = it.getOutputStream()
                output.write(InitCommandPacket(clientName = "N-LinkProbe").toBytes())
                output.flush()

                val response = PtpPacket.fromStream(it.getInputStream())
                response is InitResponsePacket
            }
        } catch (e: Exception) {
            Timber.tag(TAG).v("PTP init probe failed for $host:$port: ${e.message}")
            false
        }
    }
}

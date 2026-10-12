package com.camerakit.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.camerakit.api.CameraEndpoint
import com.camerakit.api.CameraError
import com.camerakit.ptp.PtpConsts
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * mDNS/NSD 发现（服务类型 `_ptp._tcp`，CIPA DC-X005；基准实现 实证同款）。
 *
 * 解析结果做过滤（基准实现 实证：跳过 link-local / 不可达 / 与手机地址冲突的端点），
 * host:port 去重后持续发射；调用方取消收集即停止发现。
 */
class MdnsCameraDiscovery(
    context: Context,
    private val log: (String) -> Unit = {},
) {
    /** 无 NSD 服务的设备上为 null，不能强转 */
    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    fun discover(): Flow<CameraEndpoint> = callbackFlow {
        val manager = nsdManager
            ?: throw CameraError.Protocol("系统不支持 NSD/mDNS 服务发现")
        val seen = HashSet<String>()
        val resolveLock = Any()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                log("mDNS 发现已启动 $serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(resolveLock) {
                    try {
                        manager.resolveService(
                            service,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo?,
                                    errorCode: Int,
                                ) {
                                    log("mDNS 服务解析失败（$errorCode），继续搜索…")
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    val host = serviceInfo.host?.hostAddress ?: return
                                    val port =
                                        if (serviceInfo.port > 0) serviceInfo.port
                                        else PtpConsts.DEFAULT_PTP_IP_PORT
                                    val name =
                                        serviceInfo.serviceName?.takeIf { it.isNotBlank() }
                                            ?: "Nikon camera"
                                    if (!isUsable(host)) {
                                        log("mDNS 跳过不可用端点 $host:$port")
                                        return
                                    }
                                    val key = "$host:$port"
                                    if (seen.add(key)) {
                                        trySend(CameraEndpoint(name, host, port, "mdns"))
                                    }
                                }
                            },
                        )
                    } catch (e: Exception) {
                        log("mDNS resolve 异常：${e.message}")
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                log("mDNS 服务丢失 ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                log("mDNS 发现已停止")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS 发现启动失败（$errorCode）")
                close(CameraError.Protocol("mDNS 发现启动失败：$errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log("mDNS 发现停止失败（$errorCode）")
            }
        }

        try {
            manager.discoverServices(
                PtpConsts.MDNS_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            try {
                manager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
            }
        }
    }

    private fun isUsable(host: String): Boolean {
        if (host.startsWith("169.254.")) return false // IPv4 link-local
        if (host.startsWith("fe80", ignoreCase = true)) return false // IPv6 link-local
        return true
    }
}

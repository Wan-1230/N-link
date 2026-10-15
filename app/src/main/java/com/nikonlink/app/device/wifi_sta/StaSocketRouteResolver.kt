package com.nikonlink.app.device.wifi_sta

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** STA 专用逐 socket 路由，不申请、释放或改动进程级网络。 */
@Singleton
class StaSocketRouteResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localInterfaces: LocalNetworkInterfaceResolver
) {
    data class Route(val network: Network?, val localAddress: String?) {
        fun createSocket(): Socket {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            try {
                localAddress?.let { socket.bind(InetSocketAddress(InetAddress.getByName(it), 0)) }
                return socket
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }
    }

    fun resolve(host: String, preferred: Network? = null): Route {
        val local = selectLocalAddress(host, localInterfaces.wifiLikeAddresses())
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val networks = listOfNotNull(preferred) + (cm?.allNetworks?.toList() ?: emptyList())
        val network = networks.distinct().firstOrNull { candidate ->
            val caps = cm?.getNetworkCapabilities(candidate) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                cm.getLinkProperties(candidate)?.linkAddresses?.any { address ->
                    val ip = address.address.hostAddress ?: return@any false
                    sameSubnet(host, ip, address.prefixLength) &&
                        (local == null || local.address == ip)
                } == true
        }
        return Route(network, local?.address)
    }

    companion object {
        internal fun selectLocalAddress(
            host: String,
            addresses: List<LocalNetworkInterfaceResolver.LocalAddress>
        ): LocalNetworkInterfaceResolver.LocalAddress? =
            addresses.filter {
                it.address != host && it.prefixLength in 1..30 &&
                    sameSubnet(host, it.address, it.prefixLength)
            }.maxByOrNull { it.prefixLength }

        internal fun sameSubnet(host: String, local: String, prefix: Int): Boolean {
            if (prefix !in 1..32) return false
            fun number(ip: String): Long? {
                val parts = ip.split('.')
                if (parts.size != 4) return null
                var value = 0L
                for (part in parts) {
                    val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                    value = (value shl 8) or octet.toLong()
                }
                return value
            }
            val remote = number(host) ?: return false
            val address = number(local) ?: return false
            val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            val network = address and mask
            val broadcast = network or (mask xor 0xffffffffL)
            return remote and mask == network && remote != network && remote != broadcast
        }
    }
}

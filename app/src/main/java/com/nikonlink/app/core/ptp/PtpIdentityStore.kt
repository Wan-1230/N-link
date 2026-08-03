package com.nikonlink.app.core.ptp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PTP/IP 客户端身份持久化。
 *
 * 影犀等成熟相机应用会复用同一个 client GUID；随机变化会导致部分尼康相机
 * 认为这是新设备并要求重新确认。这里把 GUID 固化，重连时保持同一身份。
 */
@Singleton
class PtpIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context
) : PtpClientIdentity {
    private val prefs = context.getSharedPreferences("ptp_identity", Context.MODE_PRIVATE)

    override val clientGuid: ByteArray
        get() {
            val cached = prefs.getString(KEY_GUID, null)
            if (cached != null && cached.length == 32) {
                return hexToBytes(cached)
            }
            val guid = generateGuid()
            prefs.edit().putString(KEY_GUID, guid.toHex()).apply()
            return guid
        }

    override val clientName: String
        get() = "NikonLink-${clientGuid.copyOfRange(0, 4).toHex().uppercase()}"

    private fun generateGuid(): ByteArray {
        val uuid = UUID.randomUUID()
        return ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val KEY_GUID = "ptp_ip_client_guid"
    }
}

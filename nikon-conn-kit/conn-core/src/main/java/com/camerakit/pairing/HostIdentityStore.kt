package com.camerakit.pairing

import android.content.Context
import com.camerakit.api.HostIdentity

/**
 * 主机身份持久化：GUID(16B) + 主机名 = PTP/IP 会话令牌。
 * 首次生成后长期复用，相机侧记住该主机即可免二次确认。
 */
object HostIdentityStore {

    private const val PREFS = "camerakit_host_identity"
    private const val KEY_GUID = "guid_hex"
    private const val KEY_NAME = "host_name"

    const val DEFAULT_HOST_NAME = "CameraKit-Android"

    fun loadOrCreate(context: Context, hostName: String = DEFAULT_HOST_NAME): HostIdentity {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guidHex = prefs.getString(KEY_GUID, null)
        val savedName = prefs.getString(KEY_NAME, null)
        if (guidHex != null && savedName != null) {
            return HostIdentity(hexToBytes(guidHex), savedName)
        }
        val guid = randomGuid()
        prefs.edit()
            .putString(KEY_GUID, bytesToHex(guid))
            .putString(KEY_NAME, hostName)
            .apply()
        return HostIdentity(guid, hostName)
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun randomGuid(): ByteArray {
        val uuid = java.util.UUID.randomUUID()
        val buf = java.nio.ByteBuffer.allocate(16)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        return buf.array()
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == 32) { "bad guid hex" }
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

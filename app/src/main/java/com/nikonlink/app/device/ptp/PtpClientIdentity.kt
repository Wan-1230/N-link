package com.nikonlink.app.device.ptp

/**
 * PTP/IP client identity.
 *
 * Kept as an interface so JVM tests can drive [PtpSessionManager] with a fixed
 * GUID/name against a mock camera without an Android Context.
 */
interface PtpClientIdentity {
    val clientGuid: ByteArray
    val clientName: String
}

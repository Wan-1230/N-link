package com.nikonlink.app.camera.location

import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * PTP 0x9523 的数据阶段（尼康 UpdateLocation / DisableLocation 共用）。
 *
 * 41 字节固定布局，来自尼康官方应用的序列化实现：
 *
 * | 偏移 | 长度 | 含义 |
 * |---|---|---|
 * | 0 | 2 | 有效期位图（小端）：纬度/经度/卫星/海拔/时间/GPS |
 * | 2 | 5 | 纬度：半球 N/S、度、分、秒、百分秒 |
 * | 7 | 5 | 经度：半球 E/W、度、分、秒、百分秒 |
 * | 12 | 1 | 卫星数（0 = 未提供） |
 * | 13 | 3 | 海拔：半球 P/M + int16 绝对值（小端） |
 * | 16 | 7 | UTC 时间：年(int16) 月 日 时 分 秒 百分秒 |
 * | 24 | 1 | 位置有效标志（1 = 有效，0 = 关闭） |
 * | 25 | 16 | 大地基准，ASCII "WGS-84" + 补零 |
 *
 * 关闭位置时整包为 0（位图与有效标志都为 0）。相机收到后停止给新照片写入地点。
 */
object CameraLocationPayload {

    const val SIZE = 41

    private const val FLAG_LATITUDE = 1 shl 0
    private const val FLAG_LONGITUDE = 1 shl 1
    private const val FLAG_SATELLITES = 1 shl 2
    private const val FLAG_ALTITUDE = 1 shl 3
    private const val FLAG_TIME = 1 shl 4
    private const val FLAG_GPS = 1 shl 5

    private val DATUM = "WGS-84".toByteArray(Charsets.US_ASCII)

    /** 用系统位置构造更新包；无有效经纬度时返回 null（调用方不得盲发空坐标）。 */
    fun build(location: Location): ByteArray? {
        val lat = location.latitude
        val lon = location.longitude
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val satellites = location.extras?.getInt("satellites", 0) ?: 0
        return build(
            latitude = lat,
            longitude = lon,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            timeMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            satellites = satellites
        )
    }

    /** 关闭相机位置数据（0x9523 全零包）。 */
    fun disabled(): ByteArray = ByteArray(SIZE)

    internal fun build(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        timeMillis: Long,
        satellites: Int
    ): ByteArray {
        val out = ByteArray(SIZE)
        val hasSatellites = satellites > 0
        val flags = FLAG_LATITUDE or FLAG_LONGITUDE or FLAG_ALTITUDE or FLAG_TIME or FLAG_GPS or
            (if (hasSatellites) FLAG_SATELLITES else 0)

        out[0] = (flags and 0xFF).toByte()
        out[1] = ((flags shr 8) and 0xFF).toByte()

        writeCoordinate(out, offset = 2, value = latitude, positive = 'N', negative = 'S')
        writeCoordinate(out, offset = 7, value = longitude, positive = 'E', negative = 'W')

        out[12] = satellites.coerceIn(0, 255).toByte()

        val alt = altitude.takeIf { it.isFinite() } ?: 0.0
        out[13] = if (alt > 0) 'P'.code.toByte() else 'M'.code.toByte()
        val altAbs = abs(alt).toInt().coerceIn(0, Short.MAX_VALUE.toInt())
        ByteBuffer.wrap(out, 14, 2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(altAbs.toShort())

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
        }
        ByteBuffer.wrap(out, 16, 2).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(cal.get(Calendar.YEAR).toShort())
        out[18] = (cal.get(Calendar.MONTH) + 1).toByte()
        out[19] = cal.get(Calendar.DAY_OF_MONTH).toByte()
        out[20] = cal.get(Calendar.HOUR_OF_DAY).toByte()
        out[21] = cal.get(Calendar.MINUTE).toByte()
        out[22] = cal.get(Calendar.SECOND).toByte()
        out[23] = (cal.get(Calendar.MILLISECOND) / 10).toByte()

        out[24] = 1
        DATUM.copyInto(out, destinationOffset = 25)
        return out
    }

    private fun writeCoordinate(out: ByteArray, offset: Int, value: Double, positive: Char, negative: Char) {
        out[offset] = (if (value >= 0) positive else negative).code.toByte()
        val totalSeconds = abs(value) * 3600.0
        val degrees = floor(totalSeconds / 3600.0).toInt()
        val afterDegrees = totalSeconds - degrees * 3600.0
        val minutes = floor(afterDegrees / 60.0).toInt()
        val afterMinutes = afterDegrees - minutes * 60.0
        val seconds = floor(afterMinutes).toInt()
        val hundredths = floor((afterMinutes - seconds) * 100.0).toInt().coerceIn(0, 99)
        out[offset + 1] = degrees.toByte()
        out[offset + 2] = minutes.toByte()
        out[offset + 3] = seconds.toByte()
        out[offset + 4] = hundredths.toByte()
    }
}

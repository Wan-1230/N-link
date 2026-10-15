package com.camerakit.ap

import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class CameraProperty(val code: Int, val type: Int, val writable: Boolean, val current: Long,
    val values: List<Long>, val minimum: Long? = null, val maximum: Long? = null, val step: Long? = null) {
    val title: String get() = when (code) { 0x5007 -> "光圈"; 0x500D -> "快门"; 0x500F -> "ISO"; 0x5010 -> "曝光补偿"; 0x500E -> "曝光模式"; 0xD1A6 -> "拍摄模式"; else -> "白平衡" }
    fun label(v: Long = current): String = when (code) {
        0xD1A6 -> when (v) { 0L -> "照片"; 1L -> "视频"; else -> "模式 $v" }
        0x500E -> mapOf(1L to "M 手动", 2L to "P 程序自动", 3L to "A 光圈优先", 4L to "S 快门优先", 0x8010L to "自动")[v] ?: "模式 $v"
        0x5007 -> "f/${String.format(Locale.US, "%.1f", v / 100.0).removeSuffix(".0")}"
        0x500D -> when {
            v == 0xffffffffL -> "Bulb"
            v == 0xfffffffeL -> "Time"
            v <= 0 -> "自动"
            v < 10000 -> "1/${(10000.0 / v).roundToInt()} s"
            else -> "${String.format(Locale.US, "%.1f", v / 10000.0).removeSuffix(".0")} s"
        }
        0x500F -> if (v == 0xffffL || v == 0L) "自动 ISO" else v.toString()
        0x5010 -> String.format(Locale.US, "%+.1f EV", v / 1000.0)
        0x5005 -> mapOf(1L to "手动", 2L to "自动", 3L to "白炽灯", 4L to "荧光灯", 5L to "日光", 6L to "闪光灯", 0x8010L to "阴天", 0x8011L to "背阴", 0x8012L to "色温")[v] ?: "模式 $v"
        else -> v.toString()
    }
    fun choices(): List<Long> {
        if (values.isNotEmpty()) return values
        val min = minimum ?: return emptyList()
        val max = maximum ?: return emptyList()
        val increment = step ?: return emptyList()
        if (increment <= 0 || max < min) return emptyList()
        val count = (max - min) / increment + 1
        return if (count <= 512) List(count.toInt()) { min + it * increment } else emptyList()
    }
    fun encode(value: Long): ByteArray {
        val bits = width(type) * 8
        val signed = type % 2 == 1
        val low = if (signed) -(1L shl (bits - 1)) else 0
        val high = if (signed) (1L shl (bits - 1)) - 1 else (1L shl bits) - 1
        if (value !in low..high) throw IOException("数值超出相机参数格式范围")
        if (!writable) throw IOException("当前机身模式不允许调整$title")
        if (values.isNotEmpty() && value !in values) throw IOException("相机不支持这个$title 值")
        if (minimum != null && maximum != null && (value !in minimum..maximum || (step != null && step > 0 && (value - minimum) % step != 0L))) throw IOException("数值超出相机允许范围")
        return ByteArray(width(type)).also { bytes -> repeat(bytes.size) { bytes[it] = (value ushr (it * 8)).toByte() } }
    }
    fun parseInput(input: String): Long {
        val text = input.trim()
        val number = if (code == 0x500D && '/' in text) {
            val parts = text.split('/')
            require(parts.size == 2) { "请输入秒数或分数，例如 1/125" }
            parts[0].toDouble() / parts[1].toDouble()
        } else text.toDouble()
        require(number.isFinite()) { "数值无效" }
        val factor = when (code) { 0x500D -> 10000; 0x5007 -> 100; 0x5010 -> 1000; else -> 1 }
        return (number * factor).roundToLong().also { encode(it) }
    }
    companion object {
        internal fun scalar(data: ByteArray, type: Int): Long {
            val size = width(type)
            if (data.size < size) throw IOException("相机参数值不完整")
            var value = 0L
            repeat(size) { value = value or ((data[it].toLong() and 255) shl (it * 8)) }
            if (type % 2 == 1 && value and (1L shl (size * 8 - 1)) != 0L) value -= 1L shl (size * 8)
            return value
        }
        internal fun width(type: Int): Int = when (type) { 1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4; else -> throw IOException("不支持的参数类型 0x${type.toString(16)}") }
        internal fun decode(data: ByteArray): CameraProperty {
            val code = ApPtpWire.u16(data, 0)
            val type = ApPtpWire.u16(data, 2)
            val size = width(type)
            var offset = 5
            fun value(): Long {
                if (offset + size > data.size) throw IOException("相机参数描述不完整")
                var result = 0L
                repeat(size) { result = result or ((data[offset + it].toLong() and 255) shl (it * 8)) }
                offset += size
                if (type % 2 == 1 && result and (1L shl (size * 8 - 1)) != 0L) result -= 1L shl (size * 8)
                return result
            }
            if (data.size < 5) throw IOException("相机参数描述过短")
            value() // factory default
            val current = value()
            if (offset >= data.size) throw IOException("相机参数缺少取值范围")
            val form = data[offset++].toInt() and 255
            var min: Long? = null; var max: Long? = null; var step: Long? = null
            val values = when (form) {
                0 -> emptyList()
                1 -> { min = value(); max = value(); step = value(); emptyList() }
                2 -> { val count = ApPtpWire.u16(data, offset); offset += 2; List(count) { value() } }
                else -> throw IOException("未知参数范围类型 $form")
            }
            return CameraProperty(code, type, data[4] == 1.toByte(), current, values, min, max, step)
        }
    }
}

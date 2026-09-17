package com.nikonlink.app.camera.liveview

import timber.log.Timber

/**
 * 相机 AF 状态只读回读探针（重新设计，非 v2.0.2 实现）。
 *
 * 背景：监看触摸对焦后，相机可能把点击吸附到最近的 AF 点格、或处于「自选对焦点」模式，
 * 而手机端本地画的对焦框（viewFocusIndicator）只反映「用户点了哪儿」，并不能反映
 * 相机实际生效的对焦点 —— 这正是手机/相机对焦点不一致的根因之一。
 *
 * 本类负责「从相机只读回读对焦相关属性」并打到日志，供真机标定：
 * - 尼康厂商属性码（0xD0xx/0xD1xx 段）此前未经真机确认，故此处逐个试读候选码，
 *   记录每个码的成败与原始字节，便于后续在真机上确定哪个码对应「当前对焦点」。
 * - 在属性码标定完成前，本类**不声称已解析出相机对焦点**；调用方据此降级为
 *   「只显示点击位置」，绝不画一个与相机不符的框。
 *
 * 命名说明：本实现为重新设计，不含历史 v2.0.2 的 af_calib/af_probe 打点名、
 * TouchFocusDraw 密封类、readCameraAfPoint() 方法名。
 */
object AfReadback {

    private const val TAG = "AfReadback"

    /**
     * 候选 AF 相关属性码（尼康厂商段）。
     * 注意：以下码值取自社区/逆向资料，**未经本型号真机确认**，仅用于「逐个试读 + 记录成败」。
     * `verified=false` 表示语义待实机标定，请勿据此直接解析坐标。
     */
    data class Candidate(val code: Int, val label: String, val verified: Boolean = false)

    val CANDIDATES: List<Candidate> = listOf(
        Candidate(0xD005, "Nikon AF Area Mode (unverified)"),
        Candidate(0xD006, "Nikon AF Point Select (unverified)"),
        Candidate(0xD007, "Nikon AF Point (unverified)"),
        Candidate(0xD008, "Nikon AF Mode (unverified)"),
        Candidate(0xD061, "Nikon AF Area Mode vendor (unverified)"),
        Candidate(0xD062, "Nikon AF Point vendor (unverified)"),
        Candidate(0xD081, "Nikon AF Type (unverified)"),
        Candidate(0xD089, "Nikon AF Assist (unverified)"),
        Candidate(0xD1A2, "Nikon LV AF state (unverified)"),
        Candidate(0xD1A3, "Nikon AF drive result (unverified)"),
        Candidate(0xD161, "Nikon AF Fine-tune (unverified)"),
    )

    /** 单个候选码的回读结果 */
    data class ProbeOutcome(
        val code: Int,
        val label: String,
        val available: Boolean,
        val rawHex: String?,
        val error: String?
    )

    /**
     * 逐个试读所有候选码，返回每个码的成败与原始字节。
     * [read] 由调用方注入（走当前活跃通道，只读），实现通道无关。
     */
    suspend fun probeAll(read: suspend (Int) -> ByteArray?): List<ProbeOutcome> {
        return CANDIDATES.map { cand ->
            try {
                val data = read(cand.code)
                if (data == null) {
                    ProbeOutcome(cand.code, cand.label, false, null, "no_data")
                } else {
                    ProbeOutcome(cand.code, cand.label, true, hex(data), null)
                }
            } catch (e: Exception) {
                ProbeOutcome(cand.code, cand.label, false, null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}

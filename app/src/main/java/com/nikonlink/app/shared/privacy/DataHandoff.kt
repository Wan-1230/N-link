package com.nikonlink.app.shared.privacy

import android.content.SharedPreferences

/**
 * 一次「把用户数据交给第三方」的具名声明（PRD v2.5 FR-08c）。
 *
 * 项目里这类出口本来就会越来越多（云端解析计数、以后的崩溃上报、机型规则热更…），
 * 而"问一次、记住、说清上传什么给谁"这件事一旦各写各的，就会出现某一处默认上传、
 * 某一处只写在小字里。所以只留一个结构：**事实归这里，文案归 strings.xml**。
 *
 * 组件不碰界面、不碰网络，只负责：这条交接叫什么、给谁、传什么、用户同过没有。
 */
data class DataHandoff(
    /** 稳定标识；同时是授权状态在 prefs 里的键的一部分，改名等于让老用户重答一遍 */
    val id: String,
    /** 接收方（域名或公司名，别写"云端"这种模糊词） */
    val recipient: String,
    /** 交出去的是什么 */
    val payload: String,
    /** 换回来的是什么 */
    val purpose: String,
    /** 一次还是每次 */
    val frequency: String,
    /** 沿用既有的 prefs 键时显式给出；不填则用 `handoff_consent_<id>` */
    val prefsKey: String? = null
) {
    fun consentKey(): String = prefsKey ?: "handoff_consent_$id"

    /** 导出诊断时给外部看的那一行（英文字段名，方便日志被第三方读） */
    fun disclosureLine(granted: Boolean): String =
        "- $id → $recipient｜payload: $payload｜purpose: $purpose｜frequency: $frequency｜consent: ${if (granted) "granted" else "not-granted"}"
}

/**
 * 授权状态读写。**默认一律未授权**（缺省 false），且只有用户明确同意过才算授权。
 */
object HandoffConsent {

    fun isGranted(prefs: SharedPreferences, handoff: DataHandoff): Boolean =
        prefs.getBoolean(handoff.consentKey(), false)

    fun grant(prefs: SharedPreferences, handoff: DataHandoff) {
        prefs.edit().putBoolean(handoff.consentKey(), true).apply()
    }

    fun revoke(prefs: SharedPreferences, handoff: DataHandoff) {
        prefs.edit().putBoolean(handoff.consentKey(), false).apply()
    }

    /** 已知的所有交接及其状态 —— 诊断导出与设置页都读这个，别各自拼装。 */
    fun snapshot(prefs: SharedPreferences, all: List<DataHandoff>): List<Pair<DataHandoff, Boolean>> =
        all.map { it to isGranted(prefs, it) }
}

/**
 * 本机已有的数据交接清单。新增出口必须往这里登记一条，否则它不该有上传能力。
 */
object KnownHandoffs {

    /**
     * 快门次数的云端解析（v2.4 引入）。
     * `prefsKey` 显式沿用 v2.4 的 `shutter_cloud_consent` —— 换键等于让已经同意过的用户
     * 再被问一遍，也可能让"已授权"的机器一夜之间退回未授权。
     */
    val ShutterCloudAnalysis = DataHandoff(
        id = "shutter_cloud_analysis",
        recipient = "digeeker.com（第三方在线 EXIF 解析接口）",
        payload = "相机里一张最新样张的原始文件（NEF/JPEG），含机身序列号、镜头信息，若相机开启 GPS 还含拍摄位置",
        purpose = "读出快门使用次数",
        frequency = "本机解析失败时的退路；每次查询最多一份",
        prefsKey = "shutter_cloud_consent"
    )

    val all: List<DataHandoff> = listOf(ShutterCloudAnalysis)
}

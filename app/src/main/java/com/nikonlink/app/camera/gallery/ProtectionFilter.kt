package com.nikonlink.app.camera.gallery

/**
 * FR-16：把机内「保护键」当传输清单用——只读筛选。
 *
 * 纯判定逻辑，全部不收 Context，方便把「机身没报」这一种最容易被忽略的情况
 * 写成断言。协议侧只提供原值（PTP `ProtectionStatus`，u16），语义在这里解释：
 * - `0x0000` → 未保护
 * - 非零（`0x0001` / `0x8001` 等）→ 已保护。规范里非零值不止一个，逐值列举会漏掉
 *   机型私有取值，所以只按「非零即已保护」判。
 * - `null` → **机身没报这一列**，未知。未知不能当未保护：批量属性路径
 *   （`0x9805`）返回哪些列随机型而定，一旦某机型不带保护列，把它当成"全部未保护"
 *   会让「只传已保护的」筛出空列表，用户看到的是"我明明按了保护键"。
 */

/** 保护状态原值；null 表示未报告。 */
val CameraFile.protectionKnown: Boolean
    get() = protectionStatus != null

/** 机内是否被按过保护键（非零即已保护；未报告按未保护处理，但见 [protectionKnown]）。 */
val CameraFile.isProtected: Boolean
    get() = (protectionStatus ?: 0) != 0

/** 一批文件的保护列报告情况——决定「已保护」入口该不该出现。 */
enum class ProtectionReport {
    /** 列表还是空的，什么都还没拉到 */
    NO_DATA,

    /** 一条保护状态都没报（机身批量属性不带这一列，或全是本地文件） */
    UNREPORTED,

    /** 至少有一条报了保护状态，筛选可用 */
    REPORTED
}

/**
 * 判定优先于内容：只要有一条报了就判 [ProtectionReport.REPORTED]，
 * 哪怕报的全是 `0x0000`（一张都没保护）——那是"筛出来是空的"，不是"这功能不支持"，
 * 两者给用户的话完全不同。
 */
fun protectionReportOf(files: List<CameraFile>): ProtectionReport = when {
    files.isEmpty() -> ProtectionReport.NO_DATA
    files.any { it.protectionKnown } -> ProtectionReport.REPORTED
    else -> ProtectionReport.UNREPORTED
}

/** 已保护的张数；未报告的条目不计入。 */
fun protectedCountOf(files: List<CameraFile>): Int = files.count { it.isProtected }

/** 相册「已保护」标签的呈现：出不出、叫什么。 */
data class ProtectChip(val shown: Boolean, val label: String)

/**
 * 机身报了保护状态才出这个标签；一张都没保护时不带数字（`已保护`），
 * 有则带计数（`已保护 3`）——与「已标记 N」同一套读感。
 */
fun protectChipOf(files: List<CameraFile>): ProtectChip {
    if (protectionReportOf(files) != ProtectionReport.REPORTED) {
        return ProtectChip(shown = false, label = PhotoFilter.PROTECTED.label)
    }
    val count = protectedCountOf(files)
    return ProtectChip(
        shown = true,
        label = if (count == 0) PhotoFilter.PROTECTED.label else "${PhotoFilter.PROTECTED.label} $count"
    )
}

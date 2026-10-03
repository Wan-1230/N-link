package com.nikonlink.app.device.model

import java.util.Locale

/**
 * 相机型号的**官方名称**归一化（FR-36）。
 *
 * 三条通道各自的型号串来源与写法都不一样，直接把原文塞进设备页就会出现
 * 「显示的不是官方相机名称」：
 *
 * | 来源 | 实测/规范写法 | 官方写法 |
 * |---|---|---|
 * | PTP `GetDeviceInfo`.Model（USB 与 PTP/IP 同一字段） | `NIKON Z 50II`（机身自报，见 `UsbPtpProtocol.parseDeviceInfoModel` 注释） | `Nikon Z 50II` |
 * | 尼康 EXIF/PTP 的罗马数字变体 | `NIKON Z 6_III` | `Nikon Z 6III` |
 * | BLE 广播名 / mDNS 服务名 | `Z6III_12345678`（见 `WifiScanner` 注释） | `Nikon Z 6III` |
 * | USB 产品 ID 映射表 | `Z 6III`；未知 PID 是 `Nikon Camera (0x0FFF)` | 识别不出就不显示 |
 *
 * 规则刻意保守：**认不出机型就返回 null**，由调用方回落到下一个来源。
 * 宁可显示上一个可信来源，也不把 IP、MAC、十六进制 PID 或猜测的型号装成相机名称——
 * 「猜错的名字」比「暂时没有名字」更难被用户发现，也更难被我们排查。
 */
object CameraModelCatalog {

    /** 官方全称的品牌前缀（设备页那一行是「相机全称」，品牌写在先）。 */
    private const val BRAND = "Nikon"
    private val BRAND_UPPER = BRAND.uppercase(Locale.ROOT)

    /** 无权限/未就绪时系统给的空值占位，绝不当成型号显示。 */
    private val NON_NAMES = setOf("<unknown ssid>", "<unknown>", "0000000000000000")

    /** `displayName` 去分隔符后的查找表；`UNKNOWN` 不参与。 */
    private val BY_KEY: Map<String, CameraModel> = CameraModel.entries
        .filter { it != CameraModel.UNKNOWN }
        .associateBy({ it.displayName.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit) }, { it })

    /**
     * 表里没有、但形态明确是「Z + 数字 + 后缀」的机型（如 `Z 5II`、`Z 50III`）。
     * 全串消费才允许泛化，避免把 `Nikon Z Camera` 这类非型号串拆成型号。
     */
    private val GENERIC_MODEL = Regex("^Z(\\d+)([A-Z0-9]*)$")

    /**
     * 归一化成查找键：先剥掉广播名尾部的序列号，再去品牌、去分隔符。
     *
     * 剥序列号**必须按下划线分隔剥**，不能直接砍尾部的数字串：`Z6III_12345678` 与
     * `Z8_00123456` 是同一个形态，但 `Z 8` 的型号本身就是一位数字——按「尾部 4 位以上数字」
     * 砍会把型号自己的数字一起吞掉（`Z8_00123456` → `Z`）。而 `NIKON Z 6_III` 里的下划线
     * 是罗马数字代次的分隔，不能当序列号剥（尾巴 `III` 不是纯数字，正好留下来）。
     */
    private fun normalizeKey(raw: String): String {
        val trimmed = raw.trim()
        val cut = trimmed.lastIndexOf('_')
        val tail = if (cut >= 0) trimmed.substring(cut + 1) else ""
        // 尾巴是 4 位以上的纯数字才是序列号；`Z 6_III` 的 `III` 不是，必须留在键里
        val head = if (tail.length >= 4 && tail.all(Char::isDigit)) trimmed.substring(0, cut) else trimmed
        var key = head.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (key.startsWith(BRAND_UPPER)) key = key.removePrefix(BRAND_UPPER)
        return key
    }

    /**
     * 从任意来源的型号串里认出机型。
     *
     * @return 认不出返回 null（**不做模糊猜测**：既不返回 `UNKNOWN`，也不返回相近的老机型）。
     */
    fun model(raw: String?): CameraModel? {
        val key = raw?.trim()?.takeIf { it.isNotEmpty() && it !in NON_NAMES } ?: return null
        val normalized = normalizeKey(key)
        if (!normalized.startsWith("Z")) return null
        return BY_KEY[normalized]
    }

    /** 已认出的机型 → 官方全称。`UNKNOWN` 不算认出来（它的 displayName 是 "Unknown"）。 */
    fun official(model: CameraModel?): String? =
        model?.takeIf { it != CameraModel.UNKNOWN }?.displayName?.let { "$BRAND $it" }

    /**
     * 任意来源的型号串 → 官方全称。
     *
     * 先查表（覆盖全部已知机型），查不到但形态是「Z + 数字」时按规则泛化
     * （`NIKON Z 5_II` → `Nikon Z 5II`，表里没有的新机型不至于退回品牌名）；
     * 两者都不成立返回 null。
     */
    fun official(raw: String?): String? {
        val key = raw?.trim()?.takeIf { it.isNotEmpty() && it !in NON_NAMES } ?: return null
        official(model(key))?.let { return it }
        val normalized = normalizeKey(key)
        if (!normalized.startsWith("Z")) return null
        val match = GENERIC_MODEL.matchEntire(normalized) ?: return null
        val number = match.groupValues[1]
        val suffix = match.groupValues[2]
        // `Z 30`/`Z 5` 这类没有后缀的：数字就是全部；有后缀的紧贴数字写（官方写法 Z 50II 不留空格）
        return "$BRAND Z $number$suffix"
    }
}

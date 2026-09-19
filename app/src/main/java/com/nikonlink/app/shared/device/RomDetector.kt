package com.nikonlink.app.shared.device

import android.os.Build

/**
 * 极简 ROM 探测：识别小米 / 红米设备（MIUI / HyperOS）。
 *
 * 仅用于连接失败时的提示文案，**绝不**改变任何连接 / 重试 / 绑定的行为逻辑。
 * 所有读取都用 [runCatching] 包住，读不到（无特权 / 被裁剪 / 反射失败）就视为非小米，返回 false。
 */
object RomDetector {
    /** ROM 家族。仅用于提示文案与预检建议，不参与任何连接/重试/绑定决策。 */
    enum class Family { XIAOMI, VIVO, OPPO, HUAWEI, SAMSUNG, OTHER }

    val family: Family by lazy { detectFamily() }

    /** 是否为小米 / 红米系 ROM（MIUI / HyperOS）。懒加载，只算一次。 */
    val isXiaomi: Boolean by lazy { family == Family.XIAOMI }

    /** 连接失败提示里追加的针对性引导文案（仅当 [isXiaomi] 为 true 时使用）。 */
    const val XIAOMI_HINT =
        "检测到小米/红米设备：请确认已授予「附近设备/定位」权限，并在系统设置中关闭对该网络的「智能网络切换/流量节省」限制"

    /**
     * OTG 总开关的所在路径。小米/红米、vivo/OriginOS、OPPO/一加/realme 都有**独立
     * 用户开关**且**约 10 分钟无操作自动关闭** —— 关掉后 USB 总线上什么都不剩，
     * App 侧只能看到「无设备」，是这些机型 USB 连不上的头号原因（且与数据线、相机
     * 设置都无关，用户自己想不到）。其余厂商返回 null。
     */
    fun otgHint(): String? = when (family) {
        Family.XIAOMI -> "小米/红米：设置 → 更多连接 → OTG 连接（约 10 分钟无操作会自动关闭，重连前请确认它是开的）"
        Family.VIVO -> "vivo/iQOO：设置 → 其他网络与连接 → OTG 连接（约 10 分钟无操作会自动关闭，重连前请确认它是开的）"
        Family.OPPO -> "OPPO/一加/realme：设置 → 系统设置（或「其他设置」）→ OTG 连接 → 开启（长时间不用会自动关闭）"
        else -> null
    }

    private fun detectFamily(): Family = runCatching {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val props = listOf(
            readSystemProperty("ro.miui.ui.version.name"),
            readSystemProperty("ro.mi.os.version.name")
        ).any { it.isNotBlank() }
        when {
            manufacturer == "xiaomi" || brand == "xiaomi" || brand == "redmi" || props -> Family.XIAOMI
            manufacturer == "vivo" || brand == "vivo" || brand == "iqoo" ||
                readSystemProperty("ro.vivo.os.version").isNotBlank() -> Family.VIVO
            brand == "oppo" || brand == "oneplus" || brand == "realme" ||
                manufacturer == "oppo" || readSystemProperty("ro.build.version.opporom").isNotBlank() -> Family.OPPO
            manufacturer == "huawei" || manufacturer == "honor" || brand == "honor" ||
                readSystemProperty("ro.build.hw_emui_api_level").isNotBlank() -> Family.HUAWEI
            manufacturer == "samsung" -> Family.SAMSUNG
            else -> Family.OTHER
        }
    }.getOrDefault(Family.OTHER)

    /** 读系统属性；读不到（隐藏 API / 无特权）就返回空串。 */
    private fun readSystemProperty(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, name, "") as? String).orEmpty()
    }.getOrDefault("")
}

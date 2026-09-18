package com.nikonlink.app.shared.device

import android.os.Build

/**
 * 极简 ROM 探测：识别小米 / 红米设备（MIUI / HyperOS）。
 *
 * 仅用于连接失败时的提示文案，**绝不**改变任何连接 / 重试 / 绑定的行为逻辑。
 * 所有读取都用 [runCatching] 包住，读不到（无特权 / 被裁剪 / 反射失败）就视为非小米，返回 false。
 */
object RomDetector {
    /** 是否为小米 / 红米系 ROM（MIUI / HyperOS）。懒加载，只算一次。 */
    val isXiaomi: Boolean by lazy { detectXiaomi() }

    /** 连接失败提示里追加的针对性引导文案（仅当 [isXiaomi] 为 true 时使用）。 */
    const val XIAOMI_HINT =
        "检测到小米/红米设备：请确认已授予「附近设备/定位」权限，并在系统设置中关闭对该网络的「智能网络切换/流量节省」限制"

    private fun detectXiaomi(): Boolean = runCatching {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val miui = readSystemProperty("ro.miui.ui.version.name").isNotBlank()
        val hyperOs = readSystemProperty("ro.mi.os.version.name").isNotBlank()
        manufacturer == "xiaomi" || brand == "xiaomi" || brand == "redmi" || miui || hyperOs
    }.getOrDefault(false)

    /** 读系统属性；读不到（隐藏 API / 无特权）就返回空串。 */
    private fun readSystemProperty(name: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val method = cls.getMethod("get", String::class.java, String::class.java)
        (method.invoke(null, name, "") as? String).orEmpty()
    }.getOrDefault("")
}

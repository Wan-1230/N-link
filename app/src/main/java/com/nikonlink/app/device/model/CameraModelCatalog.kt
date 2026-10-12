package com.nikonlink.app.device.model

import java.util.Locale

/**
 * 相机型号的官方名称解析（FR-36，v3.1 修订：显示与查表彻底分家）。
 *
 * 三条通道各自的型号串来源与写法都不一样，直接把原文塞进设备页就会出现
 * 「显示的不是官方相机名称」：
 *
 * | 来源 | 实测/规范写法 | 显示写法 |
 * |---|---|---|
 * | PTP `GetDeviceInfo`.Model | `NIKON Z 50II` / `Z 50Ⅱ`（机身自报，罗马数字形态见 2026-10-11 真机） | 一律 ASCII：`Nikon Z 50II` |
 * | 尼康 EXIF/PTP 的下划线代次 | `NIKON Z 6_III` | `Nikon Z 6III` |
 * | BLE 广播名 / mDNS 服务名 | `Z6III_12345678`（见 `WifiScanner` 注释） | `Nikon Z 6III` |
 * | USB 产品 ID 映射表 | `Z 6III`；未知 PID 是 `Nikon Camera (0x0FFF)` | 识别不出就不显示 |
 *
 * ## 两条不可违反的规则（v3.1；显示字形 2026-10-12 定谳）
 *
 * 1. **显示层只清洗 + 罗马字形折 ASCII，但绝不吞字符**：清洗只动品牌前缀、序列号后缀、
 *    代次下划线、`Z` 与数字间的空格；代次的**信息量**一个字节都不许丢。
 *    `Z 50Ⅱ` 与 `Z 50II` 都显示成 `Nikon Z 50II` —— 用户 2026-10-12 拍板显示面用 ASCII，
 *    两种自报写法收敛成同一个名字，设备页与配对记录不会分出两样。
 *    原文没有丢：`model_raw` 事件按码点记录机身自报的每一个字符，排查时照样能对上。
 * 2. **查表键转写成 ASCII 后查枚举**：`model()` 要的是机型身份（能力表/兼容表按它分派）；
 *    而 `Z 50` 与 `Z 50Ⅱ` 必须命中**不同**的枚举条目——它们是两台相机。
 *
 * 代次曾被整字符吞掉的原因（2026-10-11 JVM 实测）：`Character.isLetterOrDigit('\u2161')`
 * 返回 **false**（Nl 既不算字母也不算数字），旧实现的 `filter(Char::isLetterOrDigit)`
 * 会把 Ⅱ 静默删掉，于是 `Z 50Ⅱ` → 键 `Z50` → 撞上真实存在的 Z50 枚举 →
 * **显示成另一台相机，且能力表也跟着错**。
 * 所以红线是「不许吞字符」，字形只是显示选择；把 `Ⅱ` 转成 `II` 是**先转写再判等**，
 * 与「删掉不可识别字符」是两回事，任何时候都不许退回后者。
 *
 * 与旧版一致的保守底线：**认不出机型就返回 null**，由调用方回落到下一个来源。
 * 宁可显示上一个可信来源，也不把 IP、MAC、十六进制 PID、路由器名或猜测的型号
 * 装成相机名称——「猜错的名字」比「暂时没有名字」更难被用户发现，也更难被我们排查。
 */
object CameraModelCatalog {

    /** 官方全称的品牌前缀（设备页那一行是「相机全称」，品牌写在先）。 */
    private const val BRAND = "Nikon"
    private val BRAND_UPPER = BRAND.uppercase(Locale.ROOT)

    /** 无权限/未就绪时系统给的空值占位，绝不当成型号显示。 */
    private val NON_NAMES = setOf("<unknown ssid>", "<unknown>", "0000000000000000")

    /** I..X 的 ASCII 形态，与下面两个罗马数字区间一一对应。 */
    private val ROMAN_ASCII = arrayOf(
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    )

    /**
     * 罗马数字 → ASCII 字母形态，**只用于查表键**。
     * U+2160..U+2169（大写）与 U+2170..U+2179（小写）各十个，一一对到 I..X。
     */
    private val ROMAN_TO_ASCII: Map<Char, String> =
        (('Ⅰ'..'Ⅹ') + ('ⅰ'..'ⅹ')).mapIndexed { i, c -> c to ROMAN_ASCII[i % 10] }.toMap()

    /** 型号本体形态：`Z` + 可选空格 + 数字 + 代次后缀（ASCII 字母或罗马数字）。 */
    private val MODEL_SHAPE = Regex("""^Z\s?\d{1,3}[IⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩA-Z]{0,4}$""", RegexOption.IGNORE_CASE)

    /** 无数字的那两个机身名（`Zf`、`Z fc`）——设备页与枚举里都带空格形态，两种都要收。 */
    private val BRANDLESS_SHAPE = Regex("""^Z\s?(f|fc)?$""", RegexOption.IGNORE_CASE)

    /** `displayName` 去分隔符后的查找表；`UNKNOWN` 不参与。 */
    private val BY_KEY: Map<String, CameraModel> = CameraModel.entries
        .filter { it != CameraModel.UNKNOWN }
        .associateBy({ it.displayName.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit) }, { it })

    /**
     * 表里没有、但形态明确是「Z + 数字 + 后缀」的机型（如 `Z 5II`、`Z 50III`）。
     * 全串消费才允许泛化，避免把 `Nikon Z Camera` 这类非型号串拆成型号。
     * 后缀这一族**必须同时容纳 ASCII 字母与罗马数字字符**，否则 `Z 50Ⅱ` 会掉进"认不出"。
     */
    private val GENERIC_MODEL = Regex("^Z(\\d+)([A-Z0-9\\u2160-\\u218F]*)$")

    /**
     * **查表键**：剥序列号 → 去下划线 → 罗马数字转写成 ASCII → 去品牌 → 只留字母数字。
     *
     * 剥序列号**必须按下划线分隔剥**，不能直接砍尾部的数字串：`Z6III_12345678` 与
     * `Z8_00123456` 是同一个形态，但 `Z 8` 的型号本身就是一位数字——按「尾部 4 位以上数字」
     * 砍会把型号自己的数字一起吞掉（`Z8_00123456` → `Z`）。而 `NIKON Z 6_III` 里的下划线
     * 是罗马数字代次的分隔，不能当序列号剥（尾巴 `III` 不是纯数字，正好留下来）。
     *
     * 转写成 ASCII 是**故意**的：它让 `Z 50Ⅱ` 与 `Z 50II` 命中同一个 `CameraModel.Z50II`
     * （能力/兼容表按机型分派，不该因为机身固件用哪种 Ⅱ 写法就走两条路）；
     * 而 `Z 50` 转写后仍是 `Z50`，与 `Z50II` 天然不同。
     */
    private fun lookupKey(raw: String): String {
        val head = expandGenerationSuffix(stripSerial(raw.trim()))
        val ascii = buildString(head.length) {
            for (c in head) append(ROMAN_TO_ASCII[c] ?: c)
        }
        var key = ascii.uppercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        if (key.startsWith(BRAND_UPPER)) key = key.removePrefix(BRAND_UPPER)
        return key
    }

    /**
     * 尼康的**代次记法**：`_<1..4>` 折成对应长度的罗马数字字母。
     *
     * 2026-10-12 真机一手取证（`model_raw source=ptpip len=5 chars=Z50_2`）：
     * Z 50Ⅱ 机身自报的 Model 原文是 `Z50_2` —— **既不是 `Ⅱ` 也不是 `II`**。
     * 旧实现把下划线一删了事，`Z50_2` 就成了 `Z502`，再被泛化分支拼成 `Nikon Z 502`，
     * 这就是用户报的那个数。而同一份日志里 USB 的 PID 表给的是正确的 `Z 50II`
     * （`usb_model parsed=true ... pid_fallback=Z 50II`），两条来源打架的根因就在这。
     *
     * 这条规则不是新发明的：FR-36 早就承认 `NIKON Z 6_III → Nikon Z 6III`，
     * 只是漏了机身也在用的阿拉伯数字写法 `_2`/`_3`。折成字母后走枚举，
     * 显示形态由枚举说了算（`Z 50II`），与 `Z50II`、`Z 50Ⅱ` 两种自报形态归一。
     *
     * 只在**尾部 1~4 位纯数字**时触发，且必须在剥序列号**之后** ——
     * `Z6III_12345678` 那种 8 位尾巴已经先被当序列号剥掉了，不会被误折。
     */
    private fun expandGenerationSuffix(s: String): String {
        val m = Regex("""^(.*?)_([1-4])$""").find(s) ?: return s
        return m.groupValues[1] + "I".repeat(m.groupValues[2].toInt())
    }

    /** 尾巴是 4 位以上的纯数字才是序列号；`Z 6_III` 的 `III` 不是，必须留在串里。 */
    private fun stripSerial(trimmed: String): String {
        val cut = trimmed.lastIndexOf('_')
        if (cut < 0) return trimmed
        val tail = trimmed.substring(cut + 1)
        return if (tail.length >= 4 && tail.all(Char::isDigit)) trimmed.substring(0, cut) else trimmed
    }

    /**
     * **显示形态**：只做 §「连接与元数据可靠性专项」v1 §2.2 允许的结构性清理，
     * 一个字符都不许丢。
     *
     * 步骤：剥序列号 → 去代次下划线 → **罗马字形转 ASCII** → 去品牌前缀 → 压空格 →
     * `Z` 紧跟数字时补一个空格。
     * 例：`Z6III_12345678` → `Z 6III`；`NIKON Z 6_III` → `Z 6III`；`Z 50Ⅱ` → `Z 50II`。
     *
     * 罗马字形为什么要转（2026-10-12 用户定谳）：立项时的要求是「特殊字符原样展示」，
     * 针对的是 `Z 50Ⅱ` 被读成 `Z 502` 那个 bug —— 病因是 `Character.isLetterOrDigit('Ⅱ')`
     * 为 **false**（Nl 类别既不是字母也不是数字），清洗时整个字符被吞掉，
     * 跟用哪个字形无关。转 ASCII 之后 `Ⅱ` 变成两个实打实的 `I`，同样回不到 `502`，
     * 而显示面拿到的是 `Z 50II`。原文并没有丢：`model_raw` 事件记的是码点，
     * 机身自报 `Z 50Ⅱ` 在日志里仍是 `chars=Z 50Ⅱ`。
     */
    private fun displayToken(raw: String): String {
        var token = toAsciiRoman(expandGenerationSuffix(stripSerial(raw.trim()))).replace("_", "")
        if (token.uppercase(Locale.ROOT).startsWith(BRAND_UPPER)) {
            token = token.substring(BRAND.length)
        }
        token = token.trim().replace(Regex("\\s+"), " ")
        return token.replace(Regex("^Z(?=\\d)"), "Z ")
    }

    /** 把串里的罗马数字字形逐个换成 ASCII 字母（`Ⅱ`→`II`），其余字符原样。 */
    private fun toAsciiRoman(s: String): String =
        buildString(s.length + 4) { for (c in s) append(ROMAN_TO_ASCII[c] ?: c) }

    /** 机身自报里有没有罗马数字——有就不查表，按清洗后的原文结构展示。 */
    private fun hasRomanNumeral(raw: String): Boolean = raw.any { it in ROMAN_TO_ASCII }

    /** 形态像不像型号（不是 IP/MAC/路由器名/十六进制 PID 兜底串）。 */
    private fun looksLikeModel(token: String): Boolean =
        MODEL_SHAPE.matches(token) || BRANDLESS_SHAPE.matches(token)

    /**
     * 把机身自报的型号原文转成**可安全外发**的诊断串：可打印 ASCII 原样保留，
     * 其余字符一律换成 `U+XXXX` 码点。
     *
     * 为什么需要它（专项 v1 §4.2）：`device_model` 事件历来只记**归一化之后**的结果，
     * 于是 2026-10-11 那份真机日志留下 `Nikon Z 502` 却查不出原文长什么样——
     * 穷举过 `Z 50Ⅱ`、`Z 50II` 都推不出 `502`，说明原文里另有其字，而这条线索
     * 已经在归一化那一步被丢掉了。没有原文，型号问题就只能靠猜，而猜是禁止的。
     *
     * 码点写法同时守住了导出声明的隐私口径：型号在允许范围内，序列号不在——
     * 万一机身的 Model 字段混进了序列号形态的串，这里也不会把不可见字符原样发出去。
     */
    fun describeRaw(raw: String?): String {
        if (raw == null) return "null"
        return buildString(raw.length + 8) {
            for (c in raw) {
                if (c.code in 0x20..0x7E) append(c) else append("U+").append(c.code.toString(16).uppercase().padStart(4, '0'))
            }
        }
    }

    /**
     * 从任意来源的型号串里认出机型。
     *
     * @return 认不出返回 null（**不做模糊猜测**：既不返回 `UNKNOWN`，也不返回相近的老机型）。
     */
    fun model(raw: String?): CameraModel? {
        val key = raw?.trim()?.takeIf { it.isNotEmpty() && it !in NON_NAMES } ?: return null
        val normalized = lookupKey(key)
        if (!normalized.startsWith("Z")) return null
        return BY_KEY[normalized]
    }

    /** 已认出的机型 → 官方全称。`UNKNOWN` 不算认出来（它的 displayName 是 "Unknown"）。 */
    fun official(model: CameraModel?): String? =
        model?.takeIf { it != CameraModel.UNKNOWN }?.displayName?.let { "$BRAND $it" }

    /**
     * 任意来源的型号串 → 相机全称。三条分支，各有明确理由：
     *
     * 1. **机身自报带罗马数字** → 用清洗后的原文结构（不查表）。
     *    表里只有我们已经登记过的机型，机身自报了新形态时查表会把它抹平成已知型号，
     *    而代次后缀正是 Z50 与 Z 50Ⅱ 的分界，抹不得。显示一律 ASCII（`Z 50II`）。
     * 2. **认得出且不带罗马数字** → 用枚举的 `displayName`。它修的是大小写与分隔形态的
     *    脏数据（`nikon z_50ii`、`Z6III_12345678`），是 FR-36 立项的本意。
     * 3. **认不出但形态是型号**（表里没有的新机型）→ 用清洗后的原文泛化，
     *    至少不至于退回品牌名；形态不像型号就返回 null，绝不猜。
     */
    fun official(raw: String?): String? {
        val key = raw?.trim()?.takeIf { it.isNotEmpty() && it !in NON_NAMES } ?: return null
        val token = displayToken(key)
        if (!looksLikeModel(token)) return null
        if (hasRomanNumeral(key)) return "$BRAND $token"
        val known = model(key)
        if (known != null) return "$BRAND ${known.displayName}"
        // 走到这里说明形态已经过 looksLikeModel 把关：表里没有的新机型按清洗后的原文泛化，
        // 至少不至于退回品牌名。GENERIC_MODEL 在这里只用于确认「Z+数字+后缀」这个结构，
        // 结果本身不用于拼显示串——显示串一律来自清洗后的原文。
        if (GENERIC_MODEL.matchEntire(lookupKey(key)) == null && !BRANDLESS_SHAPE.matches(token)) return null
        return "$BRAND $token"
    }
}

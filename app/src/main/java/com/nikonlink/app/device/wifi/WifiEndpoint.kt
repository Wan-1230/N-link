package com.nikonlink.app.device.wifi

import com.nikonlink.app.device.ptp.PtpConstants

/**
 * STA 模式下相机的网络端点（IPv4 主机 + TCP 端口）。
 *
 * ## 为什么需要它（RC-3）
 *
 * 手动输入连接框此前唯一的校验是「非空」，任何字符串都会原样塞进
 * `InetSocketAddress(host, port)`。测试日志里 `192.168.031.065` 连续 55 次
 * `phase=socket`、一次 `phase=init` 都没有，用户端只看到「一直在转圈」，
 * 拿不到任何错误提示。
 *
 * ### 关于前导零：一条被证伪的假设
 *
 * 最初怀疑 `InetAddress` 会把 `031` 按**八进制**读成 25（即连到
 * `192.168.25.53`）。实测推翻了这个假设：
 *
 * ```
 * InetAddress.getByName("192.168.031.065") -> 192.168.31.65   // OpenJDK 21
 * ```
 *
 * 前导零按**十进制**处理，所以它不是「STA 完全连不上」的主因
 * —— 主因是 RC-1 / RC-2 的并发重连风暴（日志里 3 秒内出现 8 次 `pair_start`）。
 *
 * 但仍然必须做归一化 + 校验，三个理由：
 * 1. 前导零是非规范写法，各平台 / 系统版本的解析器行为并不统一，属于埋雷
 * 2. 归一化后日志、历史记录、候选去重才有唯一表示，否则
 *    `192.168.031.065` 与 `192.168.31.65` 会被当成两台不同的相机
 * 3. **校验本身才是最大价值**：把 `192.168.1.256`、`192.168.1`、`abc`
 *    这类输入当场挡掉并明确报错，而不是静默重试 5 分钟
 *
 * ## 归一化规则
 *
 * 1. 去两端空白 → 去 `wifi:` 前缀 → 去 IPv6 方括号
 * 2. 按 `.` 拆成**恰好 4 段**，每段必须是 1~3 位纯数字
 * 3. 逐段去前导零（`031`→`31`、`065`→`65`、`000`→`0`），再做 `0..255` 校验
 * 4. 用**归一化后的段重新拼接** host，绝不复用原始字符串
 * 5. 端口按 `1..65535` 校验，缺省 [PtpConstants.DEFAULT_PORT]
 *
 * ## 行为示例
 *
 * | 输入 | 结果 |
 * |---|---|
 * | `192.168.031.065` | `192.168.31.65:15740`（静默纠正） |
 * | `wifi:192.168.031.065:15740` | `192.168.31.65:15740` |
 * | `  192.168.1.1  ` | `192.168.1.1:15740` |
 * | `192.168.1.256` / `abc` / `192.168.1` | `null` |
 */
data class WifiEndpoint(
    val host: String,
    val port: Int = PtpConstants.DEFAULT_PORT
) {
    /** 归一化后的可读地址，默认端口时省略 `:port`。 */
    val display: String
        get() = if (port == PtpConstants.DEFAULT_PORT) host else "$host:$port"

    /** 与既有持久化格式兼容的地址串：`wifi:<host>:<port>`。 */
    val address: String
        get() = "wifi:$host:$port"

    override fun toString(): String = display

    companion object {

        /**
         * 解析任意来源的相机地址串，失败返回 `null`。
         *
         * 接受：`1.2.3.4` / `1.2.3.4:15740` / `wifi:1.2.3.4:15740` / `[1.2.3.4]:15740`。
         */
        fun parse(raw: String?, defaultPort: Int = PtpConstants.DEFAULT_PORT): WifiEndpoint? {
            var text = raw?.trim() ?: return null
            if (text.isEmpty()) return null

            // 既有持久化格式 `wifi:host:port`，以及用户直接粘贴时的容错
            if (text.startsWith("wifi:", ignoreCase = true)) {
                text = text.substringAfter(':').trim()
            }
            if (text.isEmpty()) return null

            val hostPart: String
            val portPart: String?
            if (text.startsWith('[')) {
                // 兼容 IPv6 方括号写法；本项目只支持 IPv4，
                // 去括号后交给下面的点分十进制校验，非法即返回 null。
                val bracketEnd = text.lastIndexOf(']')
                if (bracketEnd <= 0) return null
                hostPart = text.substring(1, bracketEnd)
                val rest = text.substring(bracketEnd + 1)
                portPart = rest.removePrefix(":").takeIf { rest.startsWith(":") }
            } else {
                val colon = text.lastIndexOf(':')
                if (colon >= 0 && !text.substring(colon + 1).contains(':')) {
                    hostPart = text.substring(0, colon)
                    portPart = text.substring(colon + 1)
                } else {
                    hostPart = text
                    portPart = null
                }
            }

            val host = normalizeIpv4(hostPart) ?: return null
            val port = when {
                portPart.isNullOrBlank() -> defaultPort
                else -> portPart.trim().toIntOrNull()
                    ?.takeIf { it in 1..65535 }
                    ?: return null
            }
            return WifiEndpoint(host, port)
        }

        /** 仅判断 host 段是否是合法（且可归一化）的 IPv4 字面量。 */
        fun isValidHost(host: String?): Boolean {
            return host != null && normalizeIpv4(host) != null
        }

        /**
         * 把点分十进制归一化成无前导零的规范形式，非法返回 `null`。
         *
         * 这是整个 RC-3 修复的核心：**返回的永远是重新拼接出来的字符串**，
         * 调用方拿到的 host 可以直接安全传给 `InetSocketAddress`。
         */
        private fun normalizeIpv4(host: String): String? {
            val parts = host.trim().split('.')
            if (parts.size != 4) return null
            val octets = IntArray(4)
            for (i in 0..3) {
                val segment = parts[i]
                // 长度 1~3 且全为数字：挡掉空段、超长段、十六进制/负数/空白
                if (segment.isEmpty() || segment.length > 3) return null
                if (segment.any { it !in '0'..'9' }) return null
                // 去前导零后再解析，杜绝八进制误读
                val trimmed = segment.trimStart('0')
                val value = (if (trimmed.isEmpty()) "0" else trimmed).toIntOrNull() ?: return null
                if (value > 255) return null
                octets[i] = value
            }
            return octets.joinToString(".")
        }
    }
}

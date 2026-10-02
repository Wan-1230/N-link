package com.nikonlink.app.device.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.6 FR-29：`ConnFunnel.Reason` 的码值契约。
 *
 * `ConnFunnel.kt:94` 自己立过规矩——**码值只增不改**：一旦发布，它就是导出日志里的
 * 稳定契约，改名会让历史日志对不上。这份测试把这条规矩钉住，顺便钉住 FR-29 新增的
 * 三个码（`camera_unreachable` / `loop_error` / `round_budget_exhausted`）确实到位。
 *
 * 为什么要专门测"码值不重复"：这三个码是照抄 connector 侧 `onFail` 的字符串加的，
 * 手写枚举最容易出的错就是复制上一行忘了改 code —— 那不会编译失败，
 * 只会让两类失败在日志里合并成一类，而且没人会发现。
 */
class ConnFunnelReasonContractTest {

    @Test
    fun `所有原因码必须唯一`() {
        val codes = ConnFunnel.Reason.values().map { it.code }
        val duplicated = codes.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue("码值重复：$duplicated", duplicated.isEmpty())
        assertEquals("枚举数量与码值数量必须一致", codes.size, codes.distinct().size)
    }

    @Test
    fun `FR-29 新增的三个码到位且文案可执行`() {
        // 这三个码此前全部落到 UNKNOWN，等于把已知信息扔掉
        val unreachable = ConnFunnel.Reason.values().first { it.code == "camera_unreachable" }
        assertNotNull(unreachable.hint)
        assertTrue("hint 必须是能照着做的动作，不能是空话",
            (unreachable.hint ?: "").length >= 6)

        assertNotNull(ConnFunnel.Reason.values().first { it.code == "loop_error" })
        assertNotNull(ConnFunnel.Reason.values().first { it.code == "round_budget_exhausted" })
    }

    @Test
    fun `既有码一个都不许消失或改名（只增不改）`() {
        // 抽样钉住历史上已发布、且被真机日志引用过的码。
        // 少了任何一个都意味着有人改了契约而不是新增。
        val mustExist = setOf(
            "ok", "unknown", "superseded",
            "no_wifi_network", "bind_failed", "tcp_timeout",
            "event_ack_timeout", "ptp_busy", "ptp_init_rejected", "ptp_handshake_failed",
            "discover_timeout", "camera_sleeping", "router_isolation", "tcp_refused",
            "not_on_camera_ap", "ap_not_found", "gateway_unstable", "session_drop",
            "no_usb_interface", "usb_ptp_no_response", "otg_disabled", "usb_permission_denied"
        )
        val present = ConnFunnel.Reason.values().map { it.code }.toSet()
        val missing = mustExist - present
        assertTrue("以下已发布码值消失或被改名：$missing", missing.isEmpty())
    }

    /**
     * `tcp_timeout` 现在没有生产者了（FR-29① 把 `camera_unreachable` 改挂新码），
     * 但**不许删**：删了历史日志里那一串 `reason=tcp_timeout` 就再也对不上枚举。
     * 这条测试是防止后人"顺手清理死码"。
     */
    @Test
    fun `tcp_timeout 即使无生产者也必须保留`() {
        assertNotNull(ConnFunnel.Reason.values().firstOrNull { it.code == "tcp_timeout" })
    }
}

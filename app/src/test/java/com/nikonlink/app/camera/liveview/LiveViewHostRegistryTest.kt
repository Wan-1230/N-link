package com.nikonlink.app.camera.liveview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiveViewHostRegistry] 状态机测试（A5 / S1 / S3）。
 *
 * 每条用例都对应一个真实场景，不是为覆盖率凑的：
 * - 「全屏退出不该带走遥控页」是最容易复发的一处，单独立一条；
 * - 「旋屏重复 attach 不算接管」决定了旋屏会不会被误判成换宿主而打日志/重启。
 */
class LiveViewHostRegistryTest {

    private fun scope() = CoroutineScope(SupervisorJob())

    @Test
    fun `first attach is marked as first and takes over nobody`() {
        val r = LiveViewHostRegistry()
        val res = r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())
        assertTrue(res.isFirst)
        assertNull(res.tookOverFrom)
        assertEquals(1, r.size())
        assertEquals(LiveViewHostRegistry.HOST_FULLSCREEN, r.currentOwner())
    }

    @Test
    fun `re-attaching the same host is idempotent and not a takeover`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())
        val again = r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())

        assertFalse("同一 hostId 重复 attach 不算「第一个」", again.isFirst)
        assertNull("旋屏重建 VM 不能被判成接管，否则会被误当成换宿主", again.tookOverFrom)
        assertEquals("登记数不能涨", 1, r.size())
    }

    @Test
    fun `second host takes over and is reported`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_REMOTE, scope())
        val res = r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())

        assertFalse(res.isFirst)
        assertEquals(LiveViewHostRegistry.HOST_REMOTE, res.tookOverFrom)
        assertEquals(2, r.size())
        assertEquals(LiveViewHostRegistry.HOST_FULLSCREEN, r.currentOwner())
    }

    @Test
    fun `leaving fullscreen must not stop the session while remote is still watching`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_REMOTE, scope())
        r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())

        // 这正是旧实现的 bug 现场：全屏页销毁时无条件停流，把还在前台的遥控页一起带走
        val res = r.detach(LiveViewHostRegistry.HOST_FULLSCREEN)

        assertTrue(res.removed)
        assertFalse("遥控页还在看，绝不能停流", res.shouldStop)
        assertEquals(1, res.remaining)
        assertEquals(LiveViewHostRegistry.HOST_REMOTE, res.newOwner)
    }

    @Test
    fun `last host leaving triggers stop`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())
        val res = r.detach(LiveViewHostRegistry.HOST_FULLSCREEN)

        assertTrue(res.removed)
        assertTrue(res.shouldStop)
        assertEquals(0, res.remaining)
        assertNull(res.newOwner)
        assertEquals(0, r.size())
    }

    @Test
    fun `detaching an unregistered host is ignored`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_REMOTE, scope())

        val res = r.detach(LiveViewHostRegistry.HOST_FULLSCREEN)

        assertFalse("过期/乱序的 detach 必须忽略，不能影响在册宿主", res.removed)
        assertFalse(res.shouldStop)
        assertEquals(1, r.size())
    }

    @Test
    fun `repeated detach of the same host is ignored the second time`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())
        assertTrue(r.detach(LiveViewHostRegistry.HOST_FULLSCREEN).removed)
        assertFalse(r.detach(LiveViewHostRegistry.HOST_FULLSCREEN).removed)
    }

    @Test
    fun `snapshot keeps attach order`() {
        val r = LiveViewHostRegistry()
        r.attach(LiveViewHostRegistry.HOST_REMOTE, scope())
        r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())
        assertEquals(
            listOf(LiveViewHostRegistry.HOST_REMOTE, LiveViewHostRegistry.HOST_FULLSCREEN),
            r.snapshot()
        )
    }

    @Test
    fun `full cross-page round trip keeps the camera session alive`() {
        val r = LiveViewHostRegistry()
        // 遥控页起流
        r.attach(LiveViewHostRegistry.HOST_REMOTE, scope())
        // 进全屏
        r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope())
        // 全屏旋屏若干次（VM 保留，重复 attach）
        repeat(3) { r.attach(LiveViewHostRegistry.HOST_FULLSCREEN, scope()) }
        // 退出全屏
        val out = r.detach(LiveViewHostRegistry.HOST_FULLSCREEN)

        assertFalse("整个来回期间不允许出现停流", out.shouldStop)
        assertEquals(1, r.size())
        assertEquals(LiveViewHostRegistry.HOST_REMOTE, r.currentOwner())
    }
}

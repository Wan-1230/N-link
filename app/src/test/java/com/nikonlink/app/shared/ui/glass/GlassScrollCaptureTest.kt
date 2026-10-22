package com.nikonlink.app.shared.ui.glass

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 玻璃背景的滚动采集契约：滚动态零采集，停稳后补采一次。
 *
 * 这是相册快速滑动的关键修复点。一次采集要把整棵内容树软件绘制进小位图再跑模糊，
 * 全部在主线程；旧实现每 70ms 采一次，滚动帧被反复挤占。这里用"内容源被绘制"
 * 的次数做客观判据，而不是靠观感：
 *  - 滚动中（setScrolling(true) + onScroll）请求重采 → 绘制次数不得增加；
 *  - 回到 IDLE 后 → 必须补采一次把背景对齐。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w360dp-h640dp-xxhdpi")
class GlassScrollCaptureTest {

    /** 记录软件绘制次数的内容源；玻璃采集就是通过 source.draw(canvas) 读它的 */
    private class CountingView(context: Context) : View(context) {
        var draws = 0
            private set

        override fun onDraw(canvas: Canvas) {
            draws++
        }
    }

    @Test
    fun `滚动中不采集_停稳后补采一次`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = CountingView(activity)
        activity.setContentView(source)
        source.layout(0, 0, 360, 640)

        val coordinator = GlassCoordinator.attach(source)
        shadowOf(Looper.getMainLooper()).idle()
        coordinator.invalidate()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertTrue("前提：首帧应有可用纹理", coordinator.textureReady)
        val baseline = source.draws
        assertTrue("前提：首帧采集应发生", baseline > 0)

        // 滚动中：采样窗口位移 + 请求重采，都不应触发整树绘制
        coordinator.setScrolling(true)
        coordinator.onScroll(120)
        coordinator.requestRefresh()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals("滚动期间不得采集", baseline, source.draws)

        // 停稳：80ms 后补采一次，把背景对齐到最终位置
        coordinator.setScrolling(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue("停稳后必须补采", source.draws > baseline)
    }
}

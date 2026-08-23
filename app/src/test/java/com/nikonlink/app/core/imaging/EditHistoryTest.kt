package com.nikonlink.app.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditHistory 单元测试（PRD 4.8: 栈式历史 ≥20 步，参数快照而非位图）
 */
class EditHistoryTest {

    @Test
    fun `初始状态无撤销无重做`() {
        val history = EditHistory()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(EditParams(), history.currentParams)
    }

    @Test
    fun `push 后可撤销回上一状态`() {
        val history = EditHistory()
        val p1 = EditParams(brightness = 20)
        history.push(p1)
        assertTrue(history.canUndo)

        val undone = history.undo()
        assertEquals(EditParams(), undone)
        assertEquals(EditParams(), history.currentParams)
    }

    @Test
    fun `撤销后可重做`() {
        val history = EditHistory()
        val p1 = EditParams(contrast = 30)
        history.push(p1)
        history.undo()

        assertTrue(history.canRedo)
        val redone = history.redo()
        assertEquals(p1, redone)
        assertFalse(history.canRedo)
    }

    @Test
    fun `新 push 清空 redo 分支`() {
        val history = EditHistory()
        history.push(EditParams(brightness = 10))
        history.push(EditParams(brightness = 20))
        history.undo()
        assertTrue(history.canRedo)

        history.push(EditParams(brightness = 50))
        assertFalse(history.canRedo)
        assertEquals(EditParams(brightness = 50), history.currentParams)
    }

    @Test
    fun `push 相同参数不产生历史`() {
        val history = EditHistory()
        history.push(EditParams(brightness = 10))
        history.push(EditParams(brightness = 10))
        history.undo()
        assertFalse(history.canUndo)
    }

    @Test
    fun `历史超过 20 步丢弃最早记录`() {
        val history = EditHistory()
        repeat(25) { i -> history.push(EditParams(brightness = i + 1)) }

        var steps = 0
        while (history.canUndo) {
            history.undo()
            steps++
        }
        assertEquals(EditHistory.MAX_STEPS, steps)
        // 最早的 5 步已被丢弃，undo 到底后 brightness 应为 25-20=5
        assertEquals(EditParams(brightness = 5), history.currentParams)
    }

    @Test
    fun `空历史撤销返回 null`() {
        assertNull(EditHistory().undo())
        assertNull(EditHistory().redo())
    }

    @Test
    fun `reset 回到默认参数且可撤销`() {
        val history = EditHistory()
        history.push(EditParams(brightness = 80, contrast = 40))
        val reset = history.reset()
        assertEquals(EditParams(), reset)
        assertTrue(history.canUndo)
    }
}

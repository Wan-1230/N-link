package com.nikonlink.app.core.imaging

/**
 * 编辑历史栈（PRD 4.8 撤销/重做）
 *
 * 栈式历史，每步记录参数快照而非位图快照（内存友好）。
 * 上限 20 步；超出时丢弃最早记录。
 * 日志来源: EditVM 在每次 push/undo/redo 时打点。
 */
class EditHistory(private val capacity: Int = MAX_STEPS) {

    companion object {
        const val MAX_STEPS = 20
    }

    private val undoStack = ArrayDeque<EditParams>()
    private val redoStack = ArrayDeque<EditParams>()
    private var current: EditParams = EditParams()

    val currentParams: EditParams get() = current

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 推入新参数状态；与当前相同则忽略；清空 redo 分支 */
    fun push(params: EditParams) {
        if (params == current) return
        undoStack.addLast(current)
        if (undoStack.size > capacity) undoStack.removeFirst()
        redoStack.clear()
        current = params
    }

    fun undo(): EditParams? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = prev
        return current
    }

    fun redo(): EditParams? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        return current
    }

    /** 一键重置（保留历史，可撤销回编辑状态） */
    fun reset(): EditParams {
        push(EditParams())
        return current
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        current = EditParams()
    }
}

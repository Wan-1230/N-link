package com.nikonlink.app.camera.liveview

import kotlinx.coroutines.CoroutineScope

/**
 * 监看会话宿主登记表（A5 / S1 / S3）。
 *
 * **要解决的竞态**：`LiveViewManager` 是全仓单例，遥控页与全屏页各自持有自己的
 * `LiveViewViewModel`，两边都会在 `init` 里调 `manager.start(viewModelScope)`。
 * 旧实现是「后者直接覆盖前者的 scope」，于是：
 *   遥控页起流 → 进全屏，scope 被全屏页覆盖 → 全屏退出时停流
 *   → 遥控页虽然还在前台，却没有宿主再驱动帧循环，表现为黑屏。
 *
 * 这个登记表把「谁在看着画面」这件事显式化：
 * - 宿主按 attach 顺序登记，可多宿主共存；
 * - 只有**最后一个宿主离开**才判定「没人看了」，才由调用方停流；
 * - 中途被别的宿主接管时，返回 [AttachResult.tookOverFrom] 让调用方记一笔，
 *   而不是默默把前一个宿主的会话抢走。
 *
 * **纯状态机**：不碰 Android、不碰调度器，单测见 `LiveViewHostRegistryTest`。
 */
class LiveViewHostRegistry {

    companion object {
        /** 全屏监看页（LiveViewActivity / LiveViewFragment） */
        const val HOST_FULLSCREEN = "fullscreen"

        /** 遥控拍摄页（RemoteFragment，内嵌取景窗） */
        const val HOST_REMOTE = "remote"
    }

    /** hostId → 该宿主传进来的 scope（仅用于诊断，帧循环不再跑在宿主的 scope 上） */
    private val hosts = LinkedHashMap<String, CoroutineScope>()

    /** attach 的结果：调用方据此决定要不要记日志/打点 */
    data class AttachResult(
        val hostId: String,
        /** 被顶掉的前一个宿主；null 表示没有顶掉任何人 */
        val tookOverFrom: String?,
        /** 是否是第一个宿主（即「从无人看」变成「有人看」） */
        val isFirst: Boolean,
    )

    /** detach 的结果：[shouldStop] 为 true 时调用方才停流 */
    data class DetachResult(
        /** 该宿主是否确实登记着（false = 重复/过期调用，直接忽略） */
        val removed: Boolean,
        /** 剩余宿主数量 */
        val remaining: Int,
        /** 接管者（通常是「上一个还在的宿主」）；没有则为 null */
        val newOwner: String?,
    ) {
        /** 没人看了 → 该停流 */
        val shouldStop: Boolean get() = removed && remaining == 0
    }

    /**
     * 登记一个宿主。同一 hostId 重复 attach（旋屏重建、VM 重建）只刷新 scope，
     * 不算接管——这是旋屏不黑屏的前提。
     */
    fun attach(hostId: String, scope: CoroutineScope): AttachResult {
        val previousOwner = hosts.keys.lastOrNull()
        val isFirst = hosts.isEmpty()
        val tookOverFrom = if (isFirst || previousOwner == hostId) null else previousOwner
        hosts[hostId] = scope
        return AttachResult(hostId, tookOverFrom, isFirst)
    }

    /**
     * 注销一个宿主。**非当前宿主的过期调用一律忽略**——这正是旧实现踩的坑：
     * 全屏页销毁时无条件停流，把还在前台的遥控页一起带走了。
     */
    fun detach(hostId: String): DetachResult {
        if (!hosts.containsKey(hostId)) {
            return DetachResult(removed = false, remaining = hosts.size, newOwner = hosts.keys.lastOrNull())
        }
        hosts.remove(hostId)
        return DetachResult(removed = true, remaining = hosts.size, newOwner = hosts.keys.lastOrNull())
    }

    /** 当前登记的宿主数 */
    fun size(): Int = hosts.size

    /** 最后一个登记／被刷新的宿主（后进先出，符合「谁最后进来谁在看」的直觉） */
    fun currentOwner(): String? = hosts.keys.lastOrNull()

    /** 诊断用：当前登记顺序快照 */
    fun snapshot(): List<String> = hosts.keys.toList()

    /** 清空登记（仅测试与进程级收尾使用） */
    fun clear() = hosts.clear()
}

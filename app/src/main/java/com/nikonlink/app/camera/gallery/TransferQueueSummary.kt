package com.nikonlink.app.camera.gallery

/**
 * 下载队列的计数摘要（纯函数，可单测）：相册页底部「下载中 n/m」与队列面板标题共用。
 *
 * 口径：m = 本批任务总数；n = 已结束（完成 + 失败）的张数，正在下载时再 +1 表示「第几张」。
 */
data class TransferQueueSummary(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val pending: Int,
    val downloading: Int
) {
    val active: Int get() = pending + downloading
    val finished: Int get() = completed + failed

    /** 当前进行到第几张（1 起）。 */
    val position: Int get() = (finished + if (downloading > 0) 1 else 0).coerceIn(0, total)

    /** 本批全部成功结束（入口只短暂显示「已完成」后收起）。 */
    val allDone: Boolean get() = total > 0 && active == 0 && failed == 0

    /** 底部入口文案；null = 不显示入口（队列空）。 */
    fun pillText(paused: Boolean): String? = when {
        active > 0 && paused -> "已暂停 $position/$total"
        active > 0 -> "下载中 $position/$total"
        failed > 0 -> "$failed 项下载失败"
        allDone -> "已完成 $completed/$total"
        else -> null
    }

    companion object {
        fun of(tasks: List<TransferTask>): TransferQueueSummary = TransferQueueSummary(
            total = tasks.count { it.status != TransferTaskStatus.CANCELLED },
            completed = tasks.count { it.status == TransferTaskStatus.COMPLETED },
            failed = tasks.count { it.status == TransferTaskStatus.FAILED },
            pending = tasks.count { it.status == TransferTaskStatus.PENDING },
            downloading = tasks.count { it.status == TransferTaskStatus.DOWNLOADING }
        )
    }
}

package com.nikonlink.app.camera.share

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 相册/预览页 → ShareActivity 的内存交接。
 *
 * 不用 Intent 传 CameraFile：它没有实现 Parcelable，塞进 Bundle 需要改模型；
 * 也不把大列表序列化进 Intent —— 分享草稿只活在一次页面跳转内，进程被杀时
 * 重新选一次比恢复半截状态更诚实。
 */
@Singleton
class ShareDraftStore @Inject constructor() {

    private data class Entry(val draft: ShareDraft, val createdAt: Long)

    private val drafts = ConcurrentHashMap<String, Entry>()
    private val nextId = AtomicLong(1)

    fun put(draft: ShareDraft): String {
        val now = System.currentTimeMillis()
        drafts.entries.removeIf { now - it.value.createdAt > 10 * 60_000L }
        val token = "share-${nextId.getAndIncrement()}"
        drafts[token] = Entry(draft, now)
        return token
    }

    /** 取出即消费；页面重建时不会拿到上一轮的过期草稿。 */
    fun take(token: String): ShareDraft? = drafts.remove(token)?.draft
}

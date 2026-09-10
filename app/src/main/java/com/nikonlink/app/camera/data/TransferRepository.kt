package com.nikonlink.app.camera.data

import com.nikonlink.app.shared.data.TransferHistoryDao
import com.nikonlink.app.shared.data.TransferRecord
import com.nikonlink.app.shared.data.deleteByHandlesBatch
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传输数据仓库
 * PRD 2.1: 传输历史 - 记录已传输文件，避免重复下载
 * PRD 4.3: data/repository 数据仓库层
 */
@Singleton
class TransferRepository @Inject constructor(
    private val transferHistoryDao: TransferHistoryDao
) {
    companion object {
        private const val TAG = "TransferRepo"
    }

    /** 获取全部传输历史 */
    fun getHistory(): Flow<List<TransferRecord>> = transferHistoryDao.getAll()

    /** 检查文件是否已传输（避免重复下载） */
    suspend fun isAlreadyTransferred(fileHandle: Int): Boolean {
        return transferHistoryDao.isTransferred(fileHandle)
    }

    /** 记录传输完成 */
    suspend fun recordTransfer(
        fileHandle: Int,
        fileName: String,
        fileSize: Long,
        localPath: String,
        status: String = "completed"
    ) {
        transferHistoryDao.insert(
            TransferRecord(
                fileHandle = fileHandle,
                fileName = fileName,
                fileSize = fileSize,
                localPath = localPath,
                status = status
            )
        )
        Timber.tag(TAG).d("Recorded transfer: $fileName ($status)")
    }

    /**
     * 全部已完成的传输记录（含 localPath / fileName / fileSize）。
     * 供「本地文件已删除 → 状态恢复为未下载」的自愈校验使用。
     */
    suspend fun getCompletedRecords(): List<TransferRecord> = transferHistoryDao.getCompletedRecords()

    /**
     * 批量回收传输记录（用户删除本地文件后调用）。
     * 删除记录 = 相机照片页该张不再显示「已下载」角标、在「未下载」筛选下重新出现。
     *
     * v1.3.0 说明：本类原有 `cleanOldRecords()`（按时间清理最近 30 天外的记录）已废弃——
     * 时间口径会让下载满 30 天的照片**无端丢失「已下载」状态**（文件还在手机里，
     * App 里角标却消失了）。新口径只看事实：本地文件确实不存在了才回收记录，
     * 由 `TransferViewModel.reconcileWithLocalMedia()` 比对媒体库后调用本方法。
     */
    suspend fun removeTransfers(handles: List<Int>) {
        if (handles.isEmpty()) return
        transferHistoryDao.deleteByHandlesBatch(handles)
        Timber.tag(TAG).d("Removed ${handles.size} transfer records")
    }
}

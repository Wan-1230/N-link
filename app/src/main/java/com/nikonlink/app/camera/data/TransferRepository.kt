package com.nikonlink.app.camera.data

import com.nikonlink.app.shared.data.TransferHistoryDao
import com.nikonlink.app.shared.data.TransferRecord
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

    /** 清理过期记录（保留最近30天） */
    suspend fun cleanOldRecords() {
        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        transferHistoryDao.deleteOlderThan(thirtyDaysAgo)
        Timber.tag(TAG).d("Cleaned old transfer records")
    }
}

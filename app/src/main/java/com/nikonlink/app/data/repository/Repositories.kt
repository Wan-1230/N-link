package com.nikonlink.app.data.repository

import com.nikonlink.app.data.local.PairedDevice
import com.nikonlink.app.data.local.PairedDeviceDao
import com.nikonlink.app.data.local.TransferHistoryDao
import com.nikonlink.app.data.local.TransferRecord
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

/**
 * 设备数据仓库
 * PRD 1.5: 一次配对，永久连接，零操作自动恢复
 * PRD 4.3: data/repository 数据仓库层
 */
@Singleton
class DeviceRepository @Inject constructor(
    private val pairedDeviceDao: PairedDeviceDao
) {
    companion object {
        private const val TAG = "DeviceRepo"
    }

    /** 获取所有配对设备 */
    fun getPairedDevices(): Flow<List<PairedDevice>> = pairedDeviceDao.getAll()

    /** 获取上次自动连接的设备（用于开机自动恢复） */
    suspend fun getLastAutoConnectDevice(): PairedDevice? {
        return pairedDeviceDao.getLastAutoConnect()
    }

    /** 保存/更新配对设备 */
    suspend fun savePairedDevice(
        address: String,
        name: String,
        model: String,
        autoConnect: Boolean = true
    ) {
        pairedDeviceDao.upsert(
            PairedDevice(
                address = address,
                deviceName = name,
                cameraModel = model,
                autoConnect = autoConnect
            )
        )
        Timber.tag(TAG).i("Saved paired device: $name [$address]")
    }

    /** 更新最后连接时间 */
    suspend fun updateLastConnected(address: String) {
        pairedDeviceDao.updateLastConnected(address, System.currentTimeMillis())
    }

    /** 删除配对设备 */
    suspend fun removeDevice(device: PairedDevice) {
        pairedDeviceDao.delete(device)
        Timber.tag(TAG).i("Removed device: ${device.deviceName}")
    }
}

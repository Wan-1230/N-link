package com.nikonlink.app.device.data

import com.nikonlink.app.shared.data.PairedDevice
import com.nikonlink.app.shared.data.PairedDeviceDao
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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

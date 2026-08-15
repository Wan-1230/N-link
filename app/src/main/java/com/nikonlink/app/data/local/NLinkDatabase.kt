package com.nikonlink.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 传输记录实体
 * PRD 2.1: 传输历史 - 记录已传输文件，避免重复下载
 */
@Entity(tableName = "transfer_history")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "file_handle") val fileHandle: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "transfer_time") val transferTime: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "status") val status: String = "completed"  // completed / failed / cancelled
)

/**
 * 配对设备实体
 * PRD 1.5: 一次配对，永久连接
 */
@Entity(tableName = "paired_devices")
data class PairedDevice(
    @PrimaryKey val address: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "camera_model") val cameraModel: String,
    @ColumnInfo(name = "last_connected") val lastConnected: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "auto_connect") val autoConnect: Boolean = true
)

@Dao
interface TransferHistoryDao {
    @Query("SELECT * FROM transfer_history ORDER BY transfer_time DESC")
    fun getAll(): Flow<List<TransferRecord>>

    @Query("SELECT * FROM transfer_history WHERE file_handle = :handle LIMIT 1")
    suspend fun getByHandle(handle: Int): TransferRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM transfer_history WHERE file_handle = :handle AND status = 'completed')")
    suspend fun isTransferred(handle: Int): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TransferRecord)

    @Query("DELETE FROM transfer_history WHERE transfer_time < :beforeTime")
    suspend fun deleteOlderThan(beforeTime: Long)
}

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices ORDER BY last_connected DESC")
    fun getAll(): Flow<List<PairedDevice>>

    @Query("SELECT * FROM paired_devices WHERE auto_connect = 1 ORDER BY last_connected DESC LIMIT 1")
    suspend fun getLastAutoConnect(): PairedDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDevice)

    @Query("UPDATE paired_devices SET last_connected = :time WHERE address = :address")
    suspend fun updateLastConnected(address: String, time: Long)

    @Delete
    suspend fun delete(device: PairedDevice)
}

/**
 * N-Link 数据库
 * PRD 4.2: Room + MediaStore（传输记录 + 照片归档）
 */
@Database(
    entities = [TransferRecord::class, PairedDevice::class],
    version = 1,
    exportSchema = false
)
abstract class NLinkDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun pairedDeviceDao(): PairedDeviceDao
}

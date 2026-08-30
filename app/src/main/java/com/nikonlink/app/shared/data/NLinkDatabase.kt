package com.nikonlink.app.shared.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * 传输记录实体
 * PRD 2.1: 传输历史 - 记录已传输文件，避免重复下载
 *
 * S5（数据库唯一约束）：file_handle 上建唯一索引，杜绝同一 handle 写入多条记录。
 * v0.1.2 只有自增主键，REPLACE 永不触发冲突，历史库里可能已存在重复行。
 */
@Entity(
    tableName = "transfer_history",
    indices = [Index(value = ["file_handle"], unique = true)]
)
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

    /** 批量查询：这批 handle 里哪些已成功传输过（供入队去重一次查完，避免 N 次 IO） */
    @Query(
        "SELECT file_handle FROM transfer_history " +
            "WHERE status = 'completed' AND file_handle IN (:handles)"
    )
    suspend fun findTransferredHandles(handles: List<Int>): List<Int>

    // S5: 唯一索引生效后，同一 handle 的重复写入直接忽略，不再产生第二条记录
    @Insert(onConflict = OnConflictStrategy.IGNORE)
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
 * Hilt EntryPoint：给「由 AppModule 手动构造、无法追加构造器参数」的类按类型取用 DAO。
 * TransferManager 由 AppModule.provideTransferManager 手动 new，
 * 入队阶段要批量查已传输 handle，只能通过这里拿 TransferHistoryDao。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NLinkDatabaseEntryPoint {
    fun transferHistoryDao(): TransferHistoryDao
}

/** SQLite 绑定变量上限 999，按 400 一批切分（一次入队仍只做 1~2 次查询） */
private const val HANDLE_QUERY_CHUNK = 400

/**
 * 批量去重查询：返回这批 handle 中已成功传输过的子集。
 * 单次入队只做切分后的极少量查询，取代逐条 isTransferred() 的 N 次 IO。
 */
suspend fun TransferHistoryDao.findTransferredHandlesBatch(handles: List<Int>): Set<Int> {
    if (handles.isEmpty()) return emptySet()
    val result = HashSet<Int>(handles.size)
    handles.chunked(HANDLE_QUERY_CHUNK).forEach { chunk ->
        result.addAll(findTransferredHandles(chunk))
    }
    return result
}

/**
 * N-Link 数据库
 * PRD 4.2: Room + MediaStore（传输记录 + 照片归档）
 */
@Database(
    entities = [TransferRecord::class, PairedDevice::class],
    version = 2,
    exportSchema = false
)
abstract class NLinkDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun pairedDeviceDao(): PairedDeviceDao

    companion object {
        private const val TAG = "NLinkDb"

        /**
         * v1 → v2：transfer_history 增加 file_handle 唯一索引。
         *
         * 步骤：备份全表 → 按 file_handle 去重（只保留 transfer_time 最大的一条）→ 建唯一索引。
         * 任一步失败则兑底：清空表后重建索引，保证最终 schema 与 version 2 一致
         * （索引建不出来会让 Room 的 schema 校验失败并崩 App）。
         *
         * 集成接线（AppModule.provideDatabase，本分支不修改该文件）：
         *   Room.databaseBuilder(context, NLinkDatabase::class.java, "n-link.db")
         *       .addMigrations(NLinkDatabase.MIGRATION_1_2)      // ← 必须加这一行
         *       .build()
         */
        @JvmField
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                runCatching {
                    // 1) 全表备份，异常时可从 transfer_history_bak_1 回捞
                    db.execSQL("DROP TABLE IF EXISTS transfer_history_bak_1")
                    db.execSQL("CREATE TABLE transfer_history_bak_1 AS SELECT * FROM transfer_history")

                    // 2) 去重：同一 file_handle 只保留 transfer_time 最大的一条（同时间取 id 最大的）。
                    //    不用窗口函数，保证在低版本 SQLite 上也能跑。
                    db.execSQL(
                        "DELETE FROM transfer_history WHERE EXISTS (" +
                            "SELECT 1 FROM transfer_history t2 " +
                            "WHERE t2.file_handle = transfer_history.file_handle " +
                            "AND (t2.transfer_time > transfer_history.transfer_time " +
                            "OR (t2.transfer_time = transfer_history.transfer_time " +
                            "AND t2.id > transfer_history.id)))"
                    )

                    // 3) 建唯一索引（名称需与 Room 约定一致：index_表名_列名）
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_transfer_history_file_handle " +
                            "ON transfer_history (file_handle)"
                    )
                    Timber.tag(TAG).i("Migration 1->2 done: unique index on file_handle")
                }.onFailure { e ->
                    Timber.tag(TAG).w(e, "Migration 1->2 failed, fallback: clear transfer_history")
                    // 兑底：传输历史可重建，不影响已落盘照片；索引必须建成功，否则 Room 校验不过
                    runCatching {
                        db.execSQL("DELETE FROM transfer_history")
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_transfer_history_file_handle " +
                                "ON transfer_history (file_handle)"
                        )
                    }.onFailure { ex ->
                        Timber.tag(TAG).e(ex, "Migration 1->2 fallback failed")
                    }
                }
            }
        }
    }
}

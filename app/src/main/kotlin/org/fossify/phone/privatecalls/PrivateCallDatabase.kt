package org.fossify.phone.privatecalls

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 私密通话记录的存放处。
 *
 * ── 为什么要换掉原来的实现 ────────────────────────────────────
 *
 * 之前的 PrivateCallHistoryStore 把整个通话历史序列化成一个 JSON 字符串，
 * 塞进 SharedPreferences 的 `privateCallHistoryEntries`。那是
 * `/data/data/<pkg>/shared_prefs/*.xml`，一个明文 XML 文件。
 *
 * 配合当时 manifest 里的 `allowBackup="true"`，`adb backup` 一条命令就能整个拿走 ——
 * 比存数据库还糟，因为 XML 里连号码带时间戳都是可读的。
 * 而且它有 500 条上限，超了静默丢弃，用户不会知道。
 *
 * 换成 Room + SQLCipher 之后：文件加密、没有条数上限、能按号码/时间建索引、
 * 也能干净地接上端到端加密同步。
 */

@Entity(tableName = "private_calls")
data class PrivateCallEntity(
    /**
     * 跨设备稳定的 id。由 (归一化号码, 开始时间, 类型) 确定性推导，
     * 见 PrivateCallStore.callUuid。
     *
     * 用确定性 id 而不是随机的，是因为同一通电话可能被 ContentObserver
     * 和通话结束后的兜底扫描各抓一次 —— 确定性 id 天然去重。
     */
    @PrimaryKey val uuid: String,

    val phoneNumber: String,
    /** 归一化后的号码，只留数字和开头的加号。查询和去重都用它。 */
    val normalizedNumber: String,
    val name: String,
    val photoUri: String,
    /** 通话开始时间，毫秒。 */
    val startTs: Long,
    /** 通话时长，秒。 */
    val duration: Int,
    /** CallLog.Calls 里的 TYPE_*。 */
    val type: Int,
    val simId: Int,
    val simColor: Int,
    val specificNumber: String,
    val specificType: String,
    val isUnknownNumber: Boolean,

    // ---- 同步用的字段 ----
    /** 服务端版本号，0 表示还没推上去过。 */
    val rev: Int = 0,
    /** 有待推送的本地改动。 */
    val dirty: Boolean = true,
    /** 本机已删除，等着把墓碑推上去。 */
    val deletedLocally: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 单行表，记同步游标。 */
@Entity(tableName = "call_sync_state")
data class CallSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastSeq: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String = "",
)

@Dao
interface PrivateCallDao {

    @Query("SELECT * FROM private_calls WHERE deletedLocally = 0 ORDER BY startTs DESC")
    fun getAll(): List<PrivateCallEntity>

    @Query("SELECT * FROM private_calls WHERE deletedLocally = 0 ORDER BY startTs DESC LIMIT :limit")
    fun getRecent(limit: Int): List<PrivateCallEntity>

    @Query("SELECT * FROM private_calls WHERE uuid = :uuid")
    fun getByUuid(uuid: String): PrivateCallEntity?

    @Query("SELECT * FROM private_calls WHERE normalizedNumber = :normalized AND deletedLocally = 0 ORDER BY startTs DESC")
    fun getByNumber(normalized: String): List<PrivateCallEntity>

    @Query("SELECT COUNT(*) FROM private_calls WHERE deletedLocally = 0")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(calls: List<PrivateCallEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(call: PrivateCallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(calls: List<PrivateCallEntity>)

    /** 软删除：先留墓碑，等推给服务器之后才真正删掉。 */
    @Query("UPDATE private_calls SET deletedLocally = 1, dirty = 1, updatedAt = :now WHERE uuid IN (:uuids)")
    fun markDeleted(uuids: Collection<String>, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM private_calls WHERE uuid = :uuid")
    fun hardDelete(uuid: String)

    @Query("SELECT * FROM private_calls WHERE dirty = 1 ORDER BY updatedAt LIMIT :limit")
    fun getPending(limit: Int): List<PrivateCallEntity>

    @Query("SELECT COUNT(*) FROM private_calls WHERE dirty = 1")
    fun countPending(): Int

    @Query("DELETE FROM private_calls")
    fun wipe()

    @Query("SELECT * FROM call_sync_state WHERE id = 1")
    fun getSyncState(): CallSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSyncState(state: CallSyncStateEntity)
}

@Database(
    entities = [PrivateCallEntity::class, CallSyncStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PrivateCallDatabase : RoomDatabase() {

    abstract fun privateCallDao(): PrivateCallDao

    companion object {
        private const val NAME = "fc_private_calls.db"

        @Volatile
        private var instance: PrivateCallDatabase? = null

        fun get(context: Context): PrivateCallDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PrivateCallDatabase::class.java,
                NAME,
            ).apply {
                // 加密失败会返回 null，那就退回明文 —— 让 App 打不开通话记录比不加密更糟。
                // 但这个状态会记在 PhoneDatabaseKey 里并显示在设置页，不静默降级。
                CallDatabaseEncryption.openHelperFactory(context)?.let { openHelperFactory(it) }
            }.build().also { instance = it }
        }

        fun destroy(context: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
                context.applicationContext.deleteDatabase(NAME)
            }
        }

        const val DB_NAME = NAME
    }
}

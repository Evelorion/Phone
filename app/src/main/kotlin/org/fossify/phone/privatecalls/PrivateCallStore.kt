package org.fossify.phone.privatecalls

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import org.fossify.phone.extensions.config
import org.fossify.phone.models.RecentCall
import org.json.JSONObject

/**
 * 私密通话记录的读写入口。
 *
 * 对上层（RecentsHelper、RecentsFragment）暴露的接口刻意做成和原来的
 * PrivateCallHistoryStore 一样，这样接入时改动最小。
 * 底下的存储换成了加密的 Room 库，见 PrivateCallDatabase 的注释。
 */
class PrivateCallStore(private val context: Context) {

    companion object {
        private const val TAG = "PrivateCallStore"

        /**
         * 私有记录的 id 从这里往下走负数，避免和系统 CallLog 的 id 撞上。
         * 上层 UI 靠 id 区分一条记录是系统的还是私有的。
         */
        const val INITIAL_PRIVATE_CALL_ID = -1_000_000

        /**
         * 号码归一化：只留数字和开头的加号。
         * 必须和通讯录 App 的 ContactPayload.normalizeNumber 完全一致，
         * 否则两边算出的盲索引和条目 id 对不上。
         */
        fun normalizeNumber(raw: String): String {
            val sb = StringBuilder(raw.length)
            for ((i, c) in raw.withIndex()) {
                when {
                    c.isDigit() -> sb.append(c)
                    c == '+' && i == 0 -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }

    private val dao by lazy { PrivateCallDatabase.get(context).privateCallDao() }

    // ------------------------------------------------------------ 读

    fun getCalls(): MutableList<RecentCall> = dao.getAll().map { it.toRecentCall() }.toMutableList()

    fun getRecent(limit: Int): List<RecentCall> = dao.getRecent(limit).map { it.toRecentCall() }

    fun getByNumber(number: String): List<RecentCall> =
        dao.getByNumber(normalizeNumber(number)).map { it.toRecentCall() }

    fun count(): Int = dao.count()

    // ------------------------------------------------------------ 写

    /**
     * CallLogGuard 抓到记录后调这个。
     * 用 INSERT OR IGNORE，同一通电话被抓两次不会产生重复行。
     *
     * @return 实际新增的条数
     */
    fun captureAll(calls: List<PrivateCallEntity>): Int {
        if (calls.isEmpty()) return 0
        val results = dao.insertIfAbsent(calls)
        return results.count { it >= 0 }
    }

    /** 兼容原来的接口：从 RecentCall 列表导入（设置页的「迁移旧通话记录」用）。 */
    fun addCalls(calls: List<RecentCall>): Int {
        if (calls.isEmpty()) return 0
        return captureAll(calls.map { it.toEntity() })
    }

    /**
     * 删除。走软删除留墓碑，等推给服务器之后再真正删掉 ——
     * 直接硬删的话另一台设备下次同步会把它当成「本机没有的新记录」重新拉回来。
     */
    fun removeCallsByIds(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val uuids = dao.getAll().filter { it.toLocalId() in ids }.map { it.uuid }
        if (uuids.isNotEmpty()) dao.markDeleted(uuids)
    }

    fun removeByUuids(uuids: Collection<String>) {
        if (uuids.isNotEmpty()) dao.markDeleted(uuids)
    }

    fun clear() {
        dao.wipe()
    }

    // ------------------------------------------------------------ 构造

    fun buildEntity(
        phoneNumber: String,
        name: String,
        photoUri: String,
        startTs: Long,
        duration: Int,
        type: Int,
        simId: Int = 0,
        simColor: Int = 0,
    ): PrivateCallEntity {
        val normalized = normalizeNumber(phoneNumber)
        return PrivateCallEntity(
            uuid = callUuid(normalized, startTs, type),
            phoneNumber = phoneNumber,
            normalizedNumber = normalized,
            name = name,
            photoUri = photoUri,
            startTs = startTs,
            duration = duration,
            type = type,
            simId = simId,
            simColor = simColor,
            specificNumber = "",
            specificType = "",
            isUnknownNumber = name.isBlank(),
        )
    }

    /**
     * 通话记录的 uuid 由内容确定性推导。
     *
     * 时间戳取整到秒：系统 CallLog 的 DATE 是毫秒，但同一通电话在
     * ContentObserver 回调和兜底扫描里读到的值理论上应该一致 ——
     * 取整到秒是为了防御个别 ROM 在写入后又微调时间戳的情况。
     */
    private fun callUuid(normalizedNumber: String, startTs: Long, type: Int): String =
        CallCrypto.deterministicUuid("call|$normalizedNumber|${startTs / 1000}|$type")

    // ------------------------------------------------------------ 同步用

    fun pendingForSync(limit: Int): List<PrivateCallEntity> = dao.getPending(limit)

    fun countPending(): Int = dao.countPending()

    fun markSynced(entity: PrivateCallEntity, rev: Int) {
        if (entity.deletedLocally) {
            // 墓碑推上去之后就可以真正删了
            dao.hardDelete(entity.uuid)
        } else {
            dao.upsert(entity.copy(rev = rev, dirty = false, updatedAt = System.currentTimeMillis()))
        }
    }

    fun applyRemote(entity: PrivateCallEntity) {
        dao.upsert(entity)
    }

    fun getByUuid(uuid: String): PrivateCallEntity? = dao.getByUuid(uuid)

    fun deleteLocally(uuid: String) = dao.hardDelete(uuid)

    fun syncState(): CallSyncStateEntity = dao.getSyncState() ?: CallSyncStateEntity()

    fun putSyncState(state: CallSyncStateEntity) = dao.putSyncState(state)

    // ------------------------------------------------------------ 序列化

    /**
     * 上传到服务器的明文结构（加密之前那一层）。
     * 键按字典序、无空白，和通讯录那边的 canonical JSON 规则一致。
     */
    fun PrivateCallEntity.toPayloadJson(): String = buildString {
        append('{')
        append("\"dur\":").append(duration).append(',')
        append("\"name\":").append(JSONObject.quote(name)).append(',')
        append("\"norm\":").append(JSONObject.quote(normalizedNumber)).append(',')
        append("\"number\":").append(JSONObject.quote(phoneNumber)).append(',')
        append("\"sim\":").append(simId).append(',')
        append("\"ts\":").append(startTs).append(',')
        append("\"type\":").append(type).append(',')
        append("\"v\":").append(CallCrypto.SCHEMA_VERSION)
        append('}')
    }

    fun payloadToEntity(uuid: String, json: String, rev: Int): PrivateCallEntity {
        val o = JSONObject(json)
        val number = o.optString("number")
        return PrivateCallEntity(
            uuid = uuid,
            phoneNumber = number,
            normalizedNumber = o.optString("norm").ifEmpty { normalizeNumber(number) },
            name = o.optString("name"),
            photoUri = "",
            startTs = o.optLong("ts"),
            duration = o.optInt("dur"),
            type = o.optInt("type"),
            simId = o.optInt("sim"),
            simColor = 0,
            specificNumber = "",
            specificType = "",
            isUnknownNumber = o.optString("name").isBlank(),
            rev = rev,
            dirty = false,
        )
    }

    // ------------------------------------------------------------ 旧数据迁移

    /**
     * 把老版本存在 SharedPreferences 里的那串 JSON 搬进加密库，然后擦掉原文。
     *
     * 这一步很要紧：老的 `privateCallHistoryEntries` 是
     * `/data/data/<pkg>/shared_prefs/*.xml` 里的明文，里面号码和时间戳都可读。
     * 不擦掉的话，就算新库加密了，旧的明文副本还躺在那儿。
     *
     * 只跑一次，跑完打标记。
     */
    fun migrateFromLegacyPrefs() {
        val prefs = context.applicationContext.getSharedPreferences("fc_call_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_migrated", false)) return

        val raw = runCatching { context.config.privateCallHistoryEntries }.getOrDefault("")
        if (raw.isBlank()) {
            prefs.edit().putBoolean("legacy_migrated", true).apply()
            return
        }

        try {
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            val legacy = json.decodeFromString<List<RecentCall>>(raw)
            val migrated = captureAll(legacy.map { it.toEntity() })
            Log.i(TAG, "从旧的 SharedPreferences 迁移了 $migrated 条通话记录")
        } catch (e: Exception) {
            Log.e(TAG, "旧通话记录解析失败，保留原文不删，避免丢数据", e)
            return
        }

        // 只有解析并写入成功才擦除原文，否则宁可留着明文也不能丢数据
        runCatching { context.config.privateCallHistoryEntries = "" }
        prefs.edit().putBoolean("legacy_migrated", true).apply()
    }

    // ------------------------------------------------------------ 转换

    private fun PrivateCallEntity.toLocalId(): Int =
        INITIAL_PRIVATE_CALL_ID - (uuid.hashCode().toLong() and 0x7fffffL).toInt()

    private fun PrivateCallEntity.toRecentCall() = RecentCall(
        id = toLocalId(),
        phoneNumber = phoneNumber,
        name = name,
        photoUri = photoUri,
        startTS = startTs,
        duration = duration,
        type = type,
        simID = simId,
        simColor = simColor,
        specificNumber = specificNumber,
        specificType = specificType,
        isUnknownNumber = isUnknownNumber,
        isPrivateRecord = true,
        groupedCalls = null,
    )

    private fun RecentCall.toEntity(): PrivateCallEntity {
        val normalized = normalizeNumber(phoneNumber)
        return PrivateCallEntity(
            uuid = callUuid(normalized, startTS, type),
            phoneNumber = phoneNumber,
            normalizedNumber = normalized,
            name = name,
            photoUri = photoUri,
            startTs = startTS,
            duration = duration,
            type = type,
            simId = simID,
            simColor = simColor,
            specificNumber = specificNumber,
            specificType = specificType,
            isUnknownNumber = isUnknownNumber,
        )
    }
}

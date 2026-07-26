package org.fossify.phone.privatecalls

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 向通讯录 App 索取同步凭据。
 *
 * ── 为什么不自己搞一套账号 ────────────────────────────────────
 *
 * 密钥体系在通讯录那边：主口令、DEK、恢复码都归它管。
 * 电话 App 再来一套的话，用户要记两个口令、抄两份恢复码 —— 没人会这么用。
 *
 * ── 拿到的是什么 ──────────────────────────────────────────────
 *
 * **不是 DEK 本身**，而是 `HKDF(DEK, "fc.collection.calls.v1")` 派生的子密钥。
 * 有它能加解密通话记录，但推不回 DEK，所以本 App 解不开任何一条联系人。
 *
 * 这个区分是刻意的：万一电话 App 出漏洞被攻破，攻击者拿到的是通话记录，
 * 不是整个通讯录。同理，访问令牌拿的是 15 分钟就过期的那个，
 * 刷新令牌通讯录不给 —— 本 App 也就没法长期独占账号访问权。
 *
 * ── 拿不到的三种情况 ──────────────────────────────────────────
 *
 *   NotInstalled   通讯录 App 没装，或者签名不一致（系统不授予 signature 权限）
 *   NotConfigured  装了但用户还没配置同步账号
 *   Locked         配置了但保险库锁着，要用户先去通讯录里解锁一次
 *
 * 三种都不是错误，UI 上要分别给出可操作的提示，不能一律显示「同步失败」。
 */
object VaultBridge {

    private const val TAG = "VaultBridge"

    private const val AUTHORITY = "org.fossify.contacts.vaultbridge"
    private val SESSION_URI: Uri = Uri.parse("content://$AUTHORITY/session")

    private const val COL_STATUS = "status"
    private const val COL_BASE_URL = "base_url"
    private const val COL_ACCESS_TOKEN = "access_token"
    private const val COL_ACCESS_EXPIRES_AT = "access_expires_at"
    private const val COL_COLLECTION_KEY = "collection_key"
    private const val COL_ACCOUNT_ID = "account_id"

    sealed class Result {
        data class Ready(
            val baseUrl: String,
            val accessToken: String,
            val accessExpiresAt: Long,
            /** 十六进制形式的 collection 子密钥。用完记得 wipe。 */
            val collectionKeyHex: String,
            val accountId: String,
        ) : Result()

        object NotInstalled : Result()
        object NotConfigured : Result()
        object Locked : Result()
    }

    /**
     * @param collection 目前只允许 "calls"，通讯录那边也只放行这一个
     */
    fun requestSession(context: Context, collection: String = "calls"): Result {
        return try {
            context.contentResolver.query(
                SESSION_URI,
                null,
                null,
                arrayOf(collection),
                null,
            ).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return Result.NotInstalled

                fun str(name: String): String =
                    cursor.getColumnIndex(name).takeIf { it >= 0 }?.let { cursor.getString(it) }.orEmpty()

                when (str(COL_STATUS)) {
                    "ok" -> Result.Ready(
                        baseUrl = str(COL_BASE_URL),
                        accessToken = str(COL_ACCESS_TOKEN),
                        accessExpiresAt = cursor.getColumnIndex(COL_ACCESS_EXPIRES_AT)
                            .takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        collectionKeyHex = str(COL_COLLECTION_KEY),
                        accountId = str(COL_ACCOUNT_ID),
                    )

                    "locked" -> Result.Locked
                    else -> Result.NotConfigured
                }
            }
        } catch (e: SecurityException) {
            // 签名不一致时系统不会授予 signature 权限，必然走到这里
            Log.w(TAG, "没有权限访问通讯录的同步凭据，请确认两个 App 用的是同一把签名证书", e)
            Result.NotInstalled
        } catch (e: Exception) {
            Log.w(TAG, "索取同步凭据失败", e)
            Result.NotInstalled
        }
    }

    /** 给设置页显示用的人话提示。 */
    fun describe(result: Result): String = when (result) {
        is Result.Ready -> "已连接到通讯录的同步账号"
        Result.NotInstalled ->
            "没有找到通讯录 App，或者两个 App 的签名证书不一致。通话记录只会存在本机。"
        Result.NotConfigured ->
            "通讯录 App 还没有配置同步服务器。先去那边设置好，通话记录才能一起同步。"
        Result.Locked ->
            "通讯录的保险库锁着。打开通讯录解锁一次之后，通话记录同步会自动恢复。"
    }
}

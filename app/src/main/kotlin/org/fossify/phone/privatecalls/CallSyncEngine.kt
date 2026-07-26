package org.fossify.phone.privatecalls

import android.content.Context
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通话记录的端到端加密同步。
 *
 * 和通讯录用同一台服务器、同一个账号，但走不同的 collection（"calls"），
 * 用不同的密钥。服务端因此不会把两类数据混在一起返回 ——
 * 否则各自都会拉到一堆自己解不开的密文。
 *
 * ── 和联系人同步的两处不同 ────────────────────────────────────
 *
 * 1. **不做三方合并。** 通话记录是只追加的事实记录，不存在「两台设备同时编辑
 *    同一通电话」这回事。撞冲突只可能是同一条被推了两次，直接采用服务端版本即可。
 *
 * 2. **凭据是借来的，而且会过期。** 访问令牌只有 15 分钟，过期就再向通讯录要一次。
 *    这里不持有刷新令牌，所以不需要处理令牌轮换。
 */
class CallSyncEngine(private val context: Context) {

    companion object {
        private const val TAG = "CallSyncEngine"
        private const val COLLECTION = "calls"
        private const val PULL_PAGE = 500
        private const val PUSH_BATCH = 100
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    class Report(
        val pulled: Int = 0,
        val pushed: Int = 0,
        val error: String = "",
    ) {
        val ok: Boolean get() = error.isEmpty()
    }

    private val store by lazy { PrivateCallStore(context) }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun sync(): Report {
        val session = when (val result = VaultBridge.requestSession(context, COLLECTION)) {
            is VaultBridge.Result.Ready -> result
            else -> return Report(error = VaultBridge.describe(result))
        }

        if (session.baseUrl.isEmpty() || session.collectionKeyHex.length != 64) {
            return Report(error = "通讯录返回的同步凭据不完整")
        }

        val key = CallCrypto.fromHex(session.collectionKeyHex)
        return try {
            val pulled = pull(session, key)
            val pushed = push(session, key)
            store.putSyncState(
                store.syncState().copy(lastSyncAt = System.currentTimeMillis(), lastError = "")
            )
            Report(pulled = pulled, pushed = pushed)
        } catch (e: IOException) {
            fail("网络不可用：${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "通话记录同步失败", e)
            fail("同步失败：${e.message}")
        } finally {
            CallCrypto.wipe(key)
        }
    }

    private fun fail(message: String): Report {
        store.putSyncState(store.syncState().copy(lastError = message))
        return Report(error = message)
    }

    // ------------------------------------------------------------ 拉取

    private fun pull(session: VaultBridge.Result.Ready, key: ByteArray): Int {
        var since = store.syncState().lastSeq
        var applied = 0

        while (true) {
            val response = get(session, "/v1/sync/changes?collection=$COLLECTION&since=$since&limit=$PULL_PAGE")
            val changes = response.optJSONArray("changes") ?: JSONArray()
            if (changes.length() == 0) break

            for (i in 0 until changes.length()) {
                val change = changes.optJSONObject(i) ?: continue
                if (applyRemote(change, key)) applied++
            }

            since = response.optLong("nextSince", since)
            store.putSyncState(store.syncState().copy(lastSeq = since))
            if (!response.optBoolean("hasMore", false)) break
        }
        return applied
    }

    private fun applyRemote(change: JSONObject, key: ByteArray): Boolean {
        val uuid = change.getString("uuid")
        val rev = change.getInt("rev")

        if (change.optBoolean("deleted", false)) {
            store.deleteLocally(uuid)
            return true
        }

        val known = store.getByUuid(uuid)
        // 自己刚推上去的又拉回来了，跳过
        if (known != null && known.rev == rev && !known.dirty) return false

        return try {
            val sealed = Base64.decode(change.getString("nonce"), Base64.NO_WRAP) +
                Base64.decode(change.getString("ciphertext"), Base64.NO_WRAP)
            val json = CallCrypto.decryptRecord(key, uuid, rev, sealed)
            store.applyRemote(store.payloadToEntity(uuid, json, rev))
            true
        } catch (e: Exception) {
            // 解不开只可能是密钥不对或数据被改过。跳过这条别让整次同步挂掉，
            // 但要记进状态里，否则用户永远不知道有条记录拉不下来。
            Log.e(TAG, "通话记录 $uuid 解密失败", e)
            store.putSyncState(store.syncState().copy(lastError = "有通话记录解密失败，可能来自另一个账号"))
            false
        }
    }

    // ------------------------------------------------------------ 推送

    private fun push(session: VaultBridge.Result.Ready, key: ByteArray): Int {
        var pushed = 0
        var round = 0

        while (round < 3) {
            val pending = store.pendingForSync(PUSH_BATCH)
            if (pending.isEmpty()) break

            val changes = JSONArray()
            val byUuid = HashMap<String, PrivateCallEntity>()

            for (entity in pending) {
                byUuid[entity.uuid] = entity
                if (entity.deletedLocally) {
                    changes.put(
                        JSONObject()
                            .put("uuid", entity.uuid)
                            .put("baseRev", entity.rev)
                            .put("deleted", true)
                            .put("schemaVer", CallCrypto.SCHEMA_VERSION)
                    )
                    continue
                }
                val json = with(store) { entity.toPayloadJson() }
                val sealed = CallCrypto.encryptRecord(key, entity.uuid, entity.rev + 1, json)
                changes.put(
                    JSONObject()
                        .put("uuid", entity.uuid)
                        .put("baseRev", entity.rev)
                        .put("deleted", false)
                        .put("schemaVer", CallCrypto.SCHEMA_VERSION)
                        .put(
                            "nonce",
                            Base64.encodeToString(sealed.copyOfRange(0, CallCrypto.NONCE_BYTES), Base64.NO_WRAP)
                        )
                        .put(
                            "ciphertext",
                            Base64.encodeToString(
                                sealed.copyOfRange(CallCrypto.NONCE_BYTES, sealed.size), Base64.NO_WRAP
                            )
                        )
                )
            }

            if (changes.length() == 0) break

            val body = JSONObject().put("collection", COLLECTION).put("changes", changes)
            val response = post(session, "/v1/sync/push", body)
            val results = response.optJSONArray("results") ?: JSONArray()
            var hadConflict = false

            for (i in 0 until results.length()) {
                val result = results.optJSONObject(i) ?: continue
                val entity = byUuid[result.getString("uuid")] ?: continue
                when (result.optString("status")) {
                    "applied" -> {
                        store.markSynced(entity, result.getInt("rev"))
                        pushed++
                    }

                    "conflict" -> {
                        hadConflict = true
                        // 通话记录不做合并：同一通电话不存在「两边改得不一样」。
                        // 撞冲突只可能是这条已经被另一台设备推上去了，采用服务端版本即可。
                        val server = result.optJSONObject("server")
                        if (server == null) {
                            store.deleteLocally(entity.uuid)
                        } else {
                            applyRemote(server, key)
                        }
                    }
                }
            }

            if (!hadConflict && store.countPending() == 0) break
            round++
        }
        return pushed
    }

    // ------------------------------------------------------------ HTTP

    private fun get(session: VaultBridge.Result.Ready, path: String): JSONObject =
        execute(Request.Builder().url(session.baseUrl.trimEnd('/') + path).get(), session)

    private fun post(session: VaultBridge.Result.Ready, path: String, body: JSONObject): JSONObject =
        execute(
            Request.Builder()
                .url(session.baseUrl.trimEnd('/') + path)
                .post(body.toString().toRequestBody(JSON)),
            session,
        )

    private fun execute(builder: Request.Builder, session: VaultBridge.Result.Ready): JSONObject {
        builder.header("Authorization", "Bearer ${session.accessToken}")
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (response.isSuccessful) return json

            if (response.code == 401) {
                // 借来的访问令牌过期了。不在这里续 —— 我们没有刷新令牌。
                // 下次同步重新向通讯录要一个新的即可。
                throw IOException("同步凭据已过期，下次同步会自动重新获取")
            }
            throw IOException(json.optString("message", "HTTP ${response.code}"))
        }
    }
}

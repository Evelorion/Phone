package org.fossify.phone.privatecalls

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.util.Log
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.commons.extensions.hasPermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 盯着系统 CallLog，把该保护的记录搬进私有库并从系统里删掉。
 *
 * ── 一个必须说清楚的前提 ──────────────────────────────────────
 *
 * **App 没法阻止系统写 CallLog。** 通话结束时是 telecom 框架自己往
 * `CallLog.Calls` 里插一条，这个动作发生在系统进程里，第三方 App
 * （哪怕是默认拨号器）没有任何钩子能拦住它。
 *
 * 能做的只有「写进去之后尽快删掉」。所以这里追求的是把窗口压到最短，
 * 而不是消灭窗口 —— 那做不到，谁说能做到都是在骗人。
 *
 * ── 为什么用 ContentObserver 而不是通话结束回调 ──────────────
 *
 * 原来的实现是在 `CallService.onCallRemoved()` 里去查 CallLog 再删。
 * 问题是 onCallRemoved 和框架写入 CallLog 之间没有顺序保证，
 * 经常要等几百毫秒甚至一秒多，还得靠「回溯最近 N 秒」这种模糊匹配去找。
 *
 * ContentObserver 是 CallLog 一被写入就回调，窗口从「秒级」压到「几十毫秒」。
 * 通话结束回调保留下来当兜底 —— observer 可能因为进程被杀而漏掉。
 *
 * ── 残留风险 ─────────────────────────────────────────────────
 *
 * 1. 那几十毫秒里，另一个持有 READ_CALL_LOG 的 App 如果正好在轮询，能读到。
 * 2. App 进程被系统杀掉时 observer 就没了，得靠下次启动的兜底扫描补。
 * 3. 部分厂商 ROM 有自己的通话记录数据库（不走标准 CallLog），
 *    删了标准表不代表厂商那份也没了。这个没有通用解法。
 */
class CallLogGuard private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CallLogGuard"

        @Volatile
        private var instance: CallLogGuard? = null

        fun get(context: Context): CallLogGuard = instance ?: synchronized(this) {
            instance ?: CallLogGuard(context.applicationContext).also { instance = it }
        }

        private val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_PHOTO_URI,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
    }

    private val store by lazy { PrivateCallStore(context) }
    private val running = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var observer: ContentObserver? = null

    /**
     * 开始监听。幂等，重复调用没有副作用。
     *
     * 在 Application.onCreate 里调用。注意不能放 attachBaseContext ——
     * 那时候还没法安全地注册 ContentObserver。
     */
    @Synchronized
    fun start() {
        if (!CallLogScope.isEnabled(context)) return
        if (!hasCallLogPermissions()) {
            Log.i(TAG, "缺少通话记录读写权限，暂不启动保护")
            return
        }
        if (running.getAndSet(true)) return

        val handlerThread = HandlerThread("call-log-guard").apply { start() }
        thread = handlerThread

        val contentObserver = object : ContentObserver(Handler(handlerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // 我们自己的删除操作也会触发回调，扫一遍是幂等的，不会出问题
                sweep()
            }
        }
        observer = contentObserver

        context.contentResolver.registerContentObserver(
            CallLog.Calls.CONTENT_URI,
            true,
            contentObserver,
        )
        Log.i(TAG, "已开始监听系统通话记录")

        // 启动时先补一次：进程上次被杀之后可能漏了几条
        Handler(handlerThread.looper).post { sweep() }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
        thread?.quitSafely()
        thread = null
    }

    /**
     * 扫一遍系统 CallLog，把该保护的搬走。
     *
     * 通话结束时也直接调它一次当兜底（CallService.onCallRemoved）。
     * 全程同步执行，调用方要保证在后台线程。
     */
    @Synchronized
    fun sweep() {
        if (!CallLogScope.isEnabled(context)) return
        if (!hasCallLogPermissions()) return

        val scope = CallLogScope.current(context)
        val captured = mutableListOf<PrivateCallEntity>()
        val systemIds = mutableListOf<Long>()

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                PROJECTION,
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 200",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val photoIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_PHOTO_URI)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)

                while (cursor.moveToNext()) {
                    val number = cursor.getStringOrEmpty(numberIdx)
                    if (number.isBlank()) continue

                    if (!scope.shouldProtect(context, number)) continue

                    val entity = store.buildEntity(
                        phoneNumber = number,
                        name = cursor.getStringOrEmpty(nameIdx),
                        photoUri = cursor.getStringOrEmpty(photoIdx),
                        startTs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                        duration = if (durationIdx >= 0) cursor.getInt(durationIdx) else 0,
                        type = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0,
                    )
                    captured.add(entity)
                    if (idIdx >= 0) systemIds.add(cursor.getLong(idIdx))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "读取系统通话记录被拒", e)
            return
        } catch (e: Exception) {
            Log.e(TAG, "扫描系统通话记录失败", e)
            return
        }

        if (captured.isEmpty()) return

        // 顺序很重要：**先存进私有库，再删系统记录**。
        // 反过来的话，如果写库失败，这条通话记录就彻底没了。
        val stored = try {
            store.captureAll(captured)
        } catch (e: Exception) {
            Log.e(TAG, "写入私有通话库失败，保留系统记录不删", e)
            return
        }

        deleteFromSystem(systemIds)
        if (stored > 0) {
            Log.i(TAG, "已保护 $stored 条通话记录")
            // 有新记录才触发同步。每次 sweep 都触发的话，我们自己的删除操作
            // 会再次唤醒 observer，形成「删除 → sweep → 同步」的空转循环。
            CallSyncScheduler.syncNow(context, "captured")
        }
    }

    private fun deleteFromSystem(ids: List<Long>) {
        if (ids.isEmpty()) return
        // 分批删，避免 SQL 语句里的参数太多
        ids.chunked(50).forEach { chunk ->
            try {
                val placeholders = chunk.joinToString(",") { "?" }
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} IN ($placeholders)",
                    chunk.map { it.toString() }.toTypedArray(),
                )
            } catch (e: Exception) {
                // 逐条重试一次，个别记录删不掉不该拖垮整批
                Log.w(TAG, "批量删除失败，改为逐条删除", e)
                chunk.forEach { id ->
                    runCatching {
                        context.contentResolver.delete(
                            ContentUris.withAppendedId(CallLog.Calls.CONTENT_URI, id), null, null
                        )
                    }
                }
            }
        }
    }

    private fun hasCallLogPermissions(): Boolean =
        context.hasPermission(PERMISSION_READ_CALL_LOG) && context.hasPermission(PERMISSION_WRITE_CALL_LOG)

    private fun android.database.Cursor.getStringOrEmpty(index: Int): String =
        if (index >= 0) getString(index).orEmpty() else ""
}

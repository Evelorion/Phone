package org.fossify.phone.privatecalls

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 通话记录同步的后台调度。
 *
 * 和通讯录那边的 SyncWorker 是同一套思路，但有一处关键区别：
 * **凭据是向通讯录借来的，而且随时可能借不到**（没装、没配置、保险库锁着）。
 * 这三种情况都不是错误，重试也没用 —— 返回 success 而不是 retry，
 * 否则 WorkManager 会一直指数退避重排，白白耗电。
 */
class CallSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "CallSyncWorker"
        const val KEY_TRIGGER = "trigger"
    }

    override fun doWork(): Result {
        if (!CallLogScope.isEnabled(applicationContext)) return Result.success()

        val report = CallSyncEngine(applicationContext).sync()
        return when {
            report.ok -> {
                Log.i(TAG, "通话记录同步完成：拉取 ${report.pulled}，上传 ${report.pushed}")
                Result.success(
                    Data.Builder()
                        .putInt("pulled", report.pulled)
                        .putInt("pushed", report.pushed)
                        .build()
                )
            }

            // 借不到凭据不是错误，重试没有意义
            report.error.contains("没有找到通讯录") ||
                report.error.contains("还没有配置") ||
                report.error.contains("保险库锁着") -> {
                Log.i(TAG, "暂时无法同步：${report.error}")
                Result.success()
            }

            else -> Result.retry()
        }
    }
}

object CallSyncScheduler {

    private const val PERIODIC_WORK = "fc_call_sync_periodic"
    private const val ONE_SHOT_WORK = "fc_call_sync_now"

    /**
     * 周期性同步。
     *
     * 默认 6 小时一次，比联系人那边（1 小时）稀疏得多。理由：
     * 通话记录是只追加的，晚几小时同步过去没有任何影响；而且它跟联系人不一样，
     * 不存在「另一台设备改了我得赶紧看到」的需求。省电优先。
     *
     * 真正让记录及时上去的是 syncNow —— 每次抓到新通话就触发一次。
     */
    fun schedulePeriodic(context: Context, intervalHours: Long = 6) {
        val request = PeriodicWorkRequestBuilder<CallSyncWorker>(
            intervalHours.coerceAtLeast(1), TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * 立刻同步一次。抓到新通话记录、用户下拉刷新、设置页点「立即同步」时调用。
     *
     * KEEP 而不是 REPLACE：连续几通电话结束时会连着触发好几次，
     * REPLACE 会把已经在跑的那次取消掉重来，反而更慢。
     * 已经排着队的那次会把新记录一起带上去 —— 它读的是当时的数据库。
     */
    fun syncNow(context: Context, trigger: String = "manual") {
        val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(Data.Builder().putString(CallSyncWorker.KEY_TRIGGER, trigger).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(ONE_SHOT_WORK)
        }
    }
}

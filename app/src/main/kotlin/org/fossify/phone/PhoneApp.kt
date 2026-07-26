package org.fossify.phone

import org.fossify.commons.FossifyApp
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.privatecalls.CallLogGuard
import org.fossify.phone.privatecalls.CallLogScope
import org.fossify.phone.privatecalls.CallSyncScheduler
import org.fossify.phone.privatecalls.PrivateCallStore

/**
 * 自定义 Application。做两件事：
 *
 *   1. 把老版本存在 SharedPreferences 里的明文通话历史搬进加密库并擦掉原文
 *   2. 启动 CallLog 监听
 *
 * 都放在 onCreate 而不是 attachBaseContext —— 这两件事都要读数据库和注册
 * ContentObserver，在 attachBaseContext 阶段做不安全。
 * （通讯录那边把加密安装放 attachBaseContext 是因为它必须抢在
 *   ContentProvider.onCreate 之前，这里没有那个约束。）
 *
 * 记得在 AndroidManifest 里把 android:name 从
 *   org.fossify.commons.FossifyApp
 * 改成
 *   .PhoneApp
 */
class PhoneApp : FossifyApp() {

    override fun onCreate() {
        super.onCreate()

        ensureBackgroundThread {
            // 迁移要在监听启动之前跑完，否则两边可能同时写同一条记录。
            // 好在 uuid 是确定性的，真撞上了也只是 INSERT OR IGNORE，不会重复。
            runCatching { PrivateCallStore(this).migrateFromLegacyPrefs() }
            runCatching { CallLogGuard.get(this).start() }

            if (CallLogScope.isEnabled(this)) {
                runCatching { CallSyncScheduler.schedulePeriodic(this) }
            }
        }
    }
}

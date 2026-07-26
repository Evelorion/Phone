package org.fossify.phone.privatecalls

import android.os.Bundle
import android.text.format.DateUtils
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_WRITE_CALL_LOG
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ActivityCallGuardSettingsBinding
import java.util.concurrent.Executors

/**
 * 通话记录保护的设置页。
 *
 * 这个页面有意把「做不到的事」写在最上面（call_guard_intro）。
 * 用户如果以为通话记录能做到零残留，会基于错误的预期做决定 ——
 * 比如在真正敏感的场合依赖它。说清楚窗口有几十毫秒，比留白更负责任。
 */
class CallGuardSettingsActivity : SimpleActivity() {

    private val binding by lazy { ActivityCallGuardSettingsBinding.inflate(layoutInflater) }
    private val executor = Executors.newSingleThreadExecutor()
    private val store by lazy { PrivateCallStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val enabled = CallLogScope.isEnabled(this)
        binding.callGuardEnable.isChecked = enabled
        binding.callGuardScopeGroup.beVisibleIf(enabled)
        binding.callGuardScopeDesc.beVisibleIf(enabled)
        binding.callGuardStorageGroup.beVisibleIf(enabled)
        binding.callGuardSyncGroup.beVisibleIf(enabled)
        binding.callGuardMigrateHolder.beVisibleIf(enabled)

        val scope = CallLogScope.current(this)
        binding.callGuardScopePrivate.isChecked = scope == CallLogScope.PRIVATE_ONLY
        binding.callGuardScopeUnknown.isChecked = scope == CallLogScope.PRIVATE_AND_UNKNOWN
        binding.callGuardScopeAll.isChecked = scope == CallLogScope.ALL
        binding.callGuardScopeDesc.text = CallLogScope.describe(scope)

        binding.callGuardEncryptionStatus.text = when {
            CallDatabaseEncryption.encrypted -> getString(R.string.call_guard_encrypted)
            CallDatabaseEncryption.lastError.isNotEmpty() ->
                getString(R.string.call_guard_not_encrypted) + "：" + CallDatabaseEncryption.lastError
            else -> getString(R.string.call_guard_not_encrypted)
        }
        binding.callGuardRequireScreenLock.isChecked = CallDatabaseEncryption.requiresScreenLock(this)

        binding.callGuardPermissionWarning.beGoneIf(hasCallLogPermissions())

        if (!enabled) return

        executor.execute {
            val count = runCatching { store.count() }.getOrDefault(0)
            val pending = runCatching { store.countPending() }.getOrDefault(0)
            val state = runCatching { store.syncState() }.getOrNull()
            val bridge = VaultBridge.requestSession(this)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.callGuardStoredCount.text = "本机已保护 $count 条通话记录"
                binding.callGuardBridgeStatus.text = VaultBridge.describe(bridge)
                binding.callGuardSyncStatus.text = when {
                    state == null || state.lastSyncAt == 0L -> getString(R.string.call_guard_sync_never)
                    else -> "上次同步 " + DateUtils.getRelativeTimeSpanString(state.lastSyncAt) +
                        "，待上传 $pending 条"
                }
                binding.callGuardSyncError.text = state?.lastError.orEmpty()
                binding.callGuardSyncError.beGoneIf(state?.lastError.isNullOrEmpty())
            }
        }
    }

    // ---------------------------------------------------------------- 交互

    private fun setupListeners() {
        binding.callGuardEnableHolder.setOnClickListener { binding.callGuardEnable.performClick() }

        binding.callGuardEnable.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                CallLogScope.setEnabled(this, false)
                CallLogGuard.get(this).stop()
                CallSyncScheduler.cancelAll(this)
                render()
                return@setOnCheckedChangeListener
            }

            // 开启需要读写通话记录的权限。没有写权限就只能读不能删，
            // 那等于什么都没保护 —— 所以两个都必须拿到才算启用成功。
            handlePermission(PERMISSION_READ_CALL_LOG) { readGranted ->
                if (!readGranted) {
                    binding.callGuardEnable.isChecked = false
                    toast(R.string.call_guard_need_permissions)
                    return@handlePermission
                }
                handlePermission(PERMISSION_WRITE_CALL_LOG) { writeGranted ->
                    if (!writeGranted) {
                        binding.callGuardEnable.isChecked = false
                        toast(R.string.call_guard_need_permissions)
                        return@handlePermission
                    }
                    CallLogScope.setEnabled(this, true)
                    executor.execute {
                        CallLogGuard.get(this).start()
                        CallSyncScheduler.schedulePeriodic(this)
                        runOnUiThread { render() }
                    }
                }
            }
        }

        binding.callGuardScopePrivate.setOnClickListener { selectScope(CallLogScope.PRIVATE_ONLY) }
        binding.callGuardScopeUnknown.setOnClickListener { selectScope(CallLogScope.PRIVATE_AND_UNKNOWN) }
        binding.callGuardScopeAll.setOnClickListener { selectScope(CallLogScope.ALL) }

        binding.callGuardRequireScreenLockHolder.setOnClickListener {
            binding.callGuardRequireScreenLock.performClick()
        }
        binding.callGuardRequireScreenLock.setOnCheckedChangeListener { _, isChecked ->
            executor.execute {
                runCatching { CallDatabaseEncryption.setRequireScreenLock(this, isChecked) }
                    .onFailure { e ->
                        runOnUiThread { toast(e.message ?: "切换失败") }
                    }
                runOnUiThread { render() }
            }
        }

        binding.callGuardMigrateHolder.setOnClickListener {
            ConfirmationDialog(this, getString(R.string.call_guard_migrate_confirm)) {
                busy(true)
                executor.execute {
                    val before = runCatching { store.count() }.getOrDefault(0)
                    runCatching { CallLogGuard.get(this).sweep() }
                    val after = runCatching { store.count() }.getOrDefault(before)
                    runOnUiThread {
                        busy(false)
                        toast(getString(R.string.call_guard_migrate_result, after - before))
                        render()
                    }
                }
            }
        }

        binding.callGuardSyncNowButton.setOnClickListener {
            busy(true)
            executor.execute {
                val report = CallSyncEngine(this).sync()
                runOnUiThread {
                    busy(false)
                    toast(
                        if (report.ok) {
                            getString(R.string.call_guard_sync_result, report.pulled, report.pushed)
                        } else {
                            report.error
                        }
                    )
                    render()
                }
            }
        }
    }

    /**
     * 切到 ALL 档时额外确认一次。
     * 车机蓝牙同步失效这类后果，用户往往是几周后才发现，那时已经想不起来是这个开关导致的。
     */
    private fun selectScope(scope: CallLogScope) {
        if (scope == CallLogScope.ALL && CallLogScope.current(this) != CallLogScope.ALL) {
            ConfirmationDialog(this, getString(R.string.call_guard_scope_all_desc)) {
                applyScope(scope)
            }
            // 对话框取消时把选中状态还原回去
            render()
            return
        }
        applyScope(scope)
    }

    private fun applyScope(scope: CallLogScope) {
        CallLogScope.set(this, scope)
        binding.callGuardScopeDesc.text = CallLogScope.describe(scope)
        // 换范围之后立刻按新规则扫一遍，否则用户会觉得开关没生效
        executor.execute { runCatching { CallLogGuard.get(this).sweep() } }
        render()
    }

    private fun busy(isBusy: Boolean) {
        binding.callGuardProgress.beVisibleIf(isBusy)
        binding.callGuardSyncNowButton.isEnabled = !isBusy
        binding.callGuardMigrateHolder.isEnabled = !isBusy
    }

    private fun hasCallLogPermissions(): Boolean =
        hasPermission(PERMISSION_READ_CALL_LOG) && hasPermission(PERMISSION_WRITE_CALL_LOG)
}

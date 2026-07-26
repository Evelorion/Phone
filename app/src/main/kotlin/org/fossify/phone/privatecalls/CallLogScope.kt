package org.fossify.phone.privatecalls

import android.content.Context
import android.provider.ContactsContract
import org.fossify.phone.privatecontacts.PrivateContactsClient

/**
 * 哪些通话记录要从系统 CallLog 里拿走。
 *
 * 三档各有取舍，没有一个「都好」的选项，所以做成开关让用户自己定：
 *
 *   PRIVATE_ONLY   只保护私密联系人的通话。
 *                  陌生号码、外卖快递的来电依然明文留在系统里 ——
 *                  别的 App 照样能看到你跟谁通过话（只是看不出是私密联系人）。
 *
 *   PRIVATE_AND_UNKNOWN（默认）
 *                  私密联系人 + 通讯录里查不到的号码都拿走。
 *                  系统里只剩下普通联系人的通话。
 *                  这一档的判断依据是「这个号码在系统通讯录里有没有」，
 *                  所以外卖快递这类你存过号的仍会留下。
 *
 *   ALL            全部拿走，系统 CallLog 基本为空。
 *                  最彻底，但代价实在：
 *                    · 部分 ROM 的通话记录小组件会空白
 *                    · 车机通过蓝牙 PBAP 同步通话记录会同步不到东西
 *                    · 第三方拨号盘、来电识别类 App 会失去历史
 *                  想清楚再开。
 */
enum class CallLogScope {
    PRIVATE_ONLY,
    PRIVATE_AND_UNKNOWN,
    ALL;

    /**
     * @param number 系统 CallLog 里的原始号码
     */
    fun shouldProtect(context: Context, number: String): Boolean = when (this) {
        ALL -> true
        PRIVATE_ONLY -> isPrivateContact(context, number)
        PRIVATE_AND_UNKNOWN -> isPrivateContact(context, number) || !isKnownSystemContact(context, number)
    }

    /**
     * 号码属不属于通讯录 App 里的私密联系人。
     * 走的是通讯录那边 signature 权限保护的按号码查询接口，
     * 通讯录保险库锁着时会返回空 —— 那时保守起见当作「不是私密联系人」，
     * 由 PRIVATE_AND_UNKNOWN 的第二个条件兜底。
     */
    private fun isPrivateContact(context: Context, number: String): Boolean =
        PrivateContactsClient.lookupByNumber(context, number).isNotEmpty()

    /** 号码在系统通讯录里存不存在。 */
    private fun isKnownSystemContact(context: Context, number: String): Boolean = try {
        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(number),
        )
        context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    } catch (e: SecurityException) {
        // 没有 READ_CONTACTS 权限时无法判断。返回 false 会导致所有通话都被当成
        // 「未知号码」而全部拿走 —— 那等于偷偷把用户切到了 ALL 档。
        // 返回 true 更保守：宁可少保护，也不做用户没同意的事。
        true
    } catch (e: Exception) {
        true
    }

    companion object {
        private const val PREFS = "fc_call_guard"
        private const val KEY_SCOPE = "scope"
        private const val KEY_ENABLED = "enabled"

        val DEFAULT = PRIVATE_AND_UNKNOWN

        fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun current(context: Context): CallLogScope {
            val name = prefs(context).getString(KEY_SCOPE, DEFAULT.name)
            return entries.firstOrNull { it.name == name } ?: DEFAULT
        }

        fun set(context: Context, scope: CallLogScope) {
            prefs(context).edit().putString(KEY_SCOPE, scope.name).apply()
        }

        /** 给设置页显示用的说明文字。 */
        fun describe(scope: CallLogScope): String = when (scope) {
            PRIVATE_ONLY -> "只保护私密联系人的通话。其余通话依然留在系统通话记录里，其它 App 可以读到。"
            PRIVATE_AND_UNKNOWN -> "私密联系人和通讯录里没有的号码都会被拿走。系统里只剩普通联系人的通话。"
            ALL -> "所有通话都不留在系统里。最彻底，但车机蓝牙同步、部分 ROM 的通话记录小组件会失效。"
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

package org.fossify.phone.privatecontacts

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact

/**
 * 电话 App 这边读取私密联系人的入口。
 *
 * 权限是 signature 级的：只有用同一把证书签名的 App 才能拿到
 * org.fossify.permission.READ_PRIVATE_CONTACTS，系统在安装时就会判定。
 * 别的 App 声明了这个权限也拿不到，系统直接不授予。
 *
 * 通讯录那边还会再校验一次调用方的签名和包名（PrivacyGuard），双保险。
 *
 * 两种查法：
 *   lookupByNumber  来电时用。只把一个号码换成一个名字，不搬运整个通讯录。
 *   loadAll         需要完整列表时用（比如通话记录页要给一批号码配名字）。
 */
object PrivateContactsClient {

    private const val TAG = "PrivateContacts"
    private const val AUTHORITY = "org.fossify.commons.contactsprovider"

    /** commons 的 MyContactsContentProvider.CONTACTS_CONTENT_URI 就是这个。 */
    private val contactsUri: Uri = Uri.parse("content://$AUTHORITY/contacts")

    private val baseUri: Uri = Uri.parse("content://$AUTHORITY")

    /**
     * 来电号码查联系人。
     *
     * 返回空列表的三种可能，调用方不需要区分，都按「查不到」处理：
     *   · 通讯录 App 没装，或者版本太老不支持这条 URI
     *   · 本 App 没拿到 signature 权限（签名不一致）
     *   · 通讯录的保险库还锁着，算不出盲索引
     */
    fun lookupByNumber(context: Context, number: String): List<SimpleContact> {
        if (number.isBlank()) return emptyList()
        val uri = baseUri.buildUpon().appendPath("number").appendPath(Uri.encode(number)).build()
        return query(context, uri, null)
    }

    fun loadAll(
        context: Context,
        favoritesOnly: Boolean = false,
        withPhoneNumbersOnly: Boolean = true,
    ): List<SimpleContact> = query(
        context,
        contactsUri,
        arrayOf(if (favoritesOnly) "1" else "0", if (withPhoneNumbersOnly) "1" else "0"),
    )

    /** 通讯录装没装、能不能读。设置页用它来提示用户「私密联系人不可用」的原因。 */
    fun isAvailable(context: Context): Boolean = try {
        context.contentResolver.query(contactsUri, null, null, null, null)?.use { true } ?: false
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun query(context: Context, uri: Uri, selectionArgs: Array<String>?): List<SimpleContact> {
        val out = ArrayList<SimpleContact>()
        val gson = Gson()
        val phoneListType = object : TypeToken<ArrayList<PhoneNumber>>() {}.type
        val stringListType = object : TypeToken<ArrayList<String>>() {}.type

        try {
            context.contentResolver.query(uri, null, null, selectionArgs, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    fun str(column: String): String =
                        cursor.getColumnIndex(column).takeIf { it >= 0 }
                            ?.let { cursor.getString(it) }.orEmpty()

                    fun int(column: String): Int =
                        cursor.getColumnIndex(column).takeIf { it >= 0 }
                            ?.let { cursor.getInt(it) } ?: 0

                    out.add(
                        SimpleContact(
                            rawId = int(MyContactsContentProvider.COL_RAW_ID),
                            contactId = int(MyContactsContentProvider.COL_CONTACT_ID),
                            name = str(MyContactsContentProvider.COL_NAME),
                            photoUri = str(MyContactsContentProvider.COL_PHOTO_URI),
                            phoneNumbers = gson.fromJson(
                                str(MyContactsContentProvider.COL_PHONE_NUMBERS).ifEmpty { "[]" },
                                phoneListType,
                            ),
                            birthdays = gson.fromJson(
                                str(MyContactsContentProvider.COL_BIRTHDAYS).ifEmpty { "[]" },
                                stringListType,
                            ),
                            anniversaries = gson.fromJson(
                                str(MyContactsContentProvider.COL_ANNIVERSARIES).ifEmpty { "[]" },
                                stringListType,
                            ),
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // 签名不一致时系统不会授予 signature 权限，这里必然走到
            Log.w(TAG, "没有读取私密联系人的权限，请确认两个 App 用的是同一把签名证书", e)
        } catch (e: Exception) {
            Log.w(TAG, "读取私密联系人失败", e)
        }
        return out
    }
}

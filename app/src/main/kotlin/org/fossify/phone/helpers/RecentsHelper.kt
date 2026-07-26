package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE
import android.provider.CallLog.Calls.PRESENTATION_UNKNOWN
import android.telecom.Call
import android.telephony.PhoneNumberUtils
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.models.RecentCall
import org.fossify.phone.models.SIMAccount
import org.fossify.phone.privatecalls.PrivateCallStore

class RecentsHelper(private val context: Context) {
    companion object {
        private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9
        private const val PRIVATE_CALL_LOOKBACK_MS = 10 * 60 * 1000L
        private const val PRIVATE_CALL_QUERY_LIMIT = 20
        const val QUERY_LIMIT = 100
    }

    private val contentUri = Calls.CONTENT_URI
    // 存储换成了加密的 Room 库（PrivateCallStore），对外接口刻意保持一致。
    // 旧的 PrivateCallHistoryStore 把整个历史塞进 SharedPreferences 的一个 JSON 字符串里，
    // 那是明文 XML，adb backup 一条命令就能拿走。
    private val privateCallHistoryStore = PrivateCallStore(context)

    @Suppress("UNUSED_PARAMETER")
    fun getRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val privateCalls = privateCallHistoryStore.getCalls()
            .sortedByDescending { it.startTS }

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(privateCalls.take(queryLimit))
            return
        }

        getContactsWithPrivate(showOnlyContactsWithNumbers = true) { contacts, _ ->
            ensureBackgroundThread {
                val recentCalls = (getRecents(contacts = contacts, maxResults = queryLimit) + privateCalls)
                    .sortedByDescending { it.startTS }
                    .distinctBy { it.id }
                    .take(queryLimit)

                callback(recentCalls)
            }
        }
    }

    fun getGroupedRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        getRecentCalls(previousRecents, queryLimit) { recentCalls ->
            callback(
                groupSubsequentCalls(calls = recentCalls)
            )
        }
    }

    fun protectPrivateCallHistory(call: Call) {
        val number = call.details.handle?.schemeSpecificPart
        if (!context.config.privateCallHistoryProtectionEnabled || number.isNullOrBlank()) {
            return
        }

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || !context.hasPermission(PERMISSION_WRITE_CALL_LOG)) {
            return
        }

        protectPrivateCallHistory(number, System.currentTimeMillis() - PRIVATE_CALL_LOOKBACK_MS)
    }

    fun protectPrivateCallHistory(number: String) {
        if (!context.config.privateCallHistoryProtectionEnabled || number.isBlank()) {
            return
        }

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || !context.hasPermission(PERMISSION_WRITE_CALL_LOG)) {
            return
        }

        protectPrivateCallHistory(number, System.currentTimeMillis() - PRIVATE_CALL_LOOKBACK_MS)
    }

    fun migratePrivateCallsToProtectedStorage(activity: SimpleActivity, callback: (protectedCalls: Int) -> Unit) {
        activity.handlePermission(PERMISSION_READ_CALL_LOG) { readGranted ->
            if (!readGranted) {
                return@handlePermission
            }

            activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { writeGranted ->
                if (!writeGranted) {
                    return@handlePermission
                }

                getContactsWithPrivate(showOnlyContactsWithNumbers = true) { contacts, privateContacts ->
                    ensureBackgroundThread {
                        if (privateContacts.isEmpty()) {
                            callback(0)
                            return@ensureBackgroundThread
                        }

                        val matchingCalls = getRecents(
                            contacts = contacts,
                            maxResults = Int.MAX_VALUE,
                            filterBlockedNumbers = false
                        ).filter { recentCall ->
                            privateContacts.any { it.doesContainPhoneNumber(recentCall.phoneNumber) }
                        }

                        if (matchingCalls.isEmpty()) {
                            callback(0)
                            return@ensureBackgroundThread
                        }

                        privateCallHistoryStore.addCalls(matchingCalls)
                        removeSystemRecentCallsByIds(matchingCalls.map { it.id })
                        activity.config.privateCallHistoryProtectionEnabled = true
                        callback(matchingCalls.size)
                    }
                }
            }
        }
    }

    private fun protectPrivateCallHistory(number: String, dateThreshold: Long) {
        getContactsWithPrivate(showOnlyContactsWithNumbers = true) { contacts, privateContacts ->
            ensureBackgroundThread {
                if (privateContacts.none { it.doesContainPhoneNumber(number) }) {
                    return@ensureBackgroundThread
                }

                val matchingCall = getRecents(
                    contacts = contacts,
                    selection = "${Calls.DATE} >= ?",
                    selectionParams = arrayOf(dateThreshold.toString()),
                    maxResults = PRIVATE_CALL_QUERY_LIMIT,
                    filterBlockedNumbers = false
                ).firstOrNull { samePhoneNumber(it.phoneNumber, number) }

                if (matchingCall != null) {
                    privateCallHistoryStore.addCalls(listOf(matchingCall))
                    removeSystemRecentCallsByIds(listOf(matchingCall.id))
                }
            }
        }
    }

    private fun getContactsWithPrivate(
        showOnlyContactsWithNumbers: Boolean,
        callback: (contacts: ArrayList<Contact>, privateContacts: ArrayList<Contact>) -> Unit
    ) {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = showOnlyContactsWithNumbers)
        ContactsHelper(context).getContacts(getAll = true, showOnlyContactsWithNumbers = showOnlyContactsWithNumbers) { contacts ->
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }
                callback(contacts, privateContacts)
            }
        }
    }

    private fun shouldGroupCalls(callA: RecentCall, callB: RecentCall): Boolean {
        val differentSim = callA.simID != callB.simID
        val differentDay = callA.dayCode != callB.dayCode
        val namesAreBothRealAndDifferent =
            callA.name != callB.name &&
                    callA.name != callA.phoneNumber &&
                    callB.name != callB.phoneNumber

        if (differentSim || differentDay || namesAreBothRealAndDifferent) return false

        return samePhoneNumber(callA.phoneNumber, callB.phoneNumber)
    }

    private fun groupSubsequentCalls(calls: List<RecentCall>): List<RecentCall> {
        val result = mutableListOf<RecentCall>()
        if (calls.isEmpty()) return result

        var currentCall = calls[0]
        for (i in 1 until calls.size) {
            val nextCall = calls[i]
            if (shouldGroupCalls(currentCall, nextCall)) {
                if (currentCall.groupedCalls.isNullOrEmpty()) {
                    currentCall = currentCall.copy(groupedCalls = mutableListOf(currentCall))
                }

                currentCall.groupedCalls?.add(nextCall)
            } else {
                result += currentCall
                currentCall = nextCall
            }
        }

        result.add(currentCall)
        return result
    }

    @SuppressLint("NewApi")
    private fun getRecents(
        contacts: List<Contact>,
        selection: String? = null,
        selectionParams: Array<String>? = null,
        maxResults: Int = QUERY_LIMIT,
        filterBlockedNumbers: Boolean = true,
    ): List<RecentCall> {
        val recentCalls = mutableListOf<RecentCall>()
        var previousStartTS = 0L
        val contactsNumbersMap = HashMap<String, String>()
        val contactPhotosMap = HashMap<String, String>()

        val projection = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.CACHED_PHOTO_URI,
            Calls.DATE,
            Calls.DURATION,
            Calls.TYPE,
            Calls.PHONE_ACCOUNT_ID,
            Calls.NUMBER_PRESENTATION
        )

        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }

        val cursor = if (isNougatPlus()) {
            // https://issuetracker.google.com/issues/175198972?pli=1#comment6
            val limitedUri = contentUri.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, maxResults.toString())
                .build()
            val sortOrder = "${Calls.DATE} DESC"
            context.contentResolver.query(limitedUri, projection, selection, selectionParams, sortOrder)
        } else {
            val sortOrder = "${Calls.DATE} DESC LIMIT $maxResults"
            context.contentResolver.query(contentUri, projection, selection, selectionParams, sortOrder)
        }

        val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
        val numbersToContactIDMap = HashMap<String, Int>()
        contactsWithMultipleNumbers.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                numbersToContactIDMap[phoneNumber.value] = contact.contactId
                numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
            }
        }

        cursor?.use {
            if (!cursor.moveToFirst()) {
                return@use
            }

            do {
                val id = cursor.getIntValue(Calls._ID)
                var isUnknownNumber = false
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED
                if (presentationBlocked || number.isNullOrBlank() || number == "-1") {
                    isUnknownNumber = true
                }

                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                if (name.isNullOrEmpty() || name == "-1") {
                    name = number.orEmpty()
                }

                if (name == number && !isUnknownNumber) {
                    if (contactsNumbersMap.containsKey(number)) {
                        name = contactsNumbersMap[number]!!
                    } else {
                        val normalizedNumber = number.normalizePhoneNumber()
                        if (normalizedNumber!!.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                            name = contacts.filter { it.phoneNumbers.isNotEmpty() }.firstOrNull { contact ->
                                val curNumber = contact.phoneNumbers.first().normalizedNumber
                                if (curNumber.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                                    if (curNumber.substring(curNumber.length - COMPARABLE_PHONE_NUMBER_LENGTH) == normalizedNumber.substring(
                                            normalizedNumber.length - COMPARABLE_PHONE_NUMBER_LENGTH
                                        )
                                    ) {
                                        contactsNumbersMap[number] = contact.getNameToDisplay()
                                        return@firstOrNull true
                                    }
                                }
                                false
                            }?.name ?: number
                        }
                    }
                }

                if (name.isEmpty() || name == "-1") {
                    name = context.getString(R.string.unknown)
                }

                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI) ?: ""
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    if (contactPhotosMap.containsKey(number)) {
                        photoUri = contactPhotosMap[number]!!
                    } else {
                        val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                        if (contact != null) {
                            photoUri = contact.photoUri
                            contactPhotosMap[number] = contact.photoUri
                        }
                    }
                }

                val startTS = cursor.getLongValue(Calls.DATE)
                if (previousStartTS == startTS) {
                    continue
                } else {
                    previousStartTS = startTS
                }

                val duration = cursor.getIntValue(Calls.DURATION)
                val type = cursor.getIntValue(Calls.TYPE)
                val accountId = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                val simAccount = accountIdToSimAccountMap[accountId]
                var specificNumber = ""
                var specificType = ""

                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val specificPhoneNumber =
                        contacts.firstOrNull { it.contactId == contactIdWithMultipleNumbers }?.phoneNumbers?.firstOrNull { it.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                recentCalls.add(
                    RecentCall(
                        id = id,
                        phoneNumber = number.orEmpty(),
                        name = name,
                        photoUri = photoUri,
                        startTS = startTS,
                        duration = duration,
                        type = type,
                        simID = simAccount?.id ?: -1,
                        simColor = simAccount?.color ?: -1,
                        specificNumber = specificNumber,
                        specificType = specificType,
                        isUnknownNumber = isUnknownNumber
                    )
                )
            } while (cursor.moveToNext() && recentCalls.size < maxResults)
        }

        if (!filterBlockedNumbers) {
            return recentCalls
        }

        val blockedNumbers = context.getBlockedNumbers()
        return recentCalls.filter { !context.isNumberBlocked(it.phoneNumber, blockedNumbers) }
    }

    fun removeRecentCalls(activity: SimpleActivity, ids: List<Int>, callback: () -> Unit) {
        if (ids.isEmpty()) {
            callback()
            return
        }

        val privateIds = ids.filter { it < 0 }
        val systemIds = ids.filter { it > 0 }

        val removePrivateCalls = {
            if (privateIds.isNotEmpty()) {
                privateCallHistoryStore.removeCallsByIds(privateIds)
            }
        }

        if (systemIds.isEmpty()) {
            ensureBackgroundThread {
                removePrivateCalls()
                callback()
            }
            return
        }

        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            ensureBackgroundThread {
                if (granted) {
                    removeSystemRecentCallsByIds(systemIds)
                }
                removePrivateCalls()
                callback()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun removeAllRecentCalls(activity: SimpleActivity, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            ensureBackgroundThread {
                if (granted) {
                    context.contentResolver.delete(contentUri, null, null)
                }
                privateCallHistoryStore.clear()
                callback()
            }
        }
    }

    fun restoreRecentCalls(activity: SimpleActivity, objects: List<RecentCall>, callback: () -> Unit) {
        val privateCalls = objects.filter { it.isPrivateRecord }
        val publicCalls = objects.filterNot { it.isPrivateRecord }

        if (publicCalls.isEmpty()) {
            ensureBackgroundThread {
                if (privateCalls.isNotEmpty()) {
                    privateCallHistoryStore.addCalls(privateCalls)
                }
                callback()
            }
            return
        }

        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            ensureBackgroundThread {
                if (granted) {
                    val values = publicCalls
                        .sortedBy { it.startTS }
                        .map {
                            ContentValues().apply {
                                put(Calls.NUMBER, it.phoneNumber)
                                put(Calls.TYPE, it.type)
                                put(Calls.DATE, it.startTS)
                                put(Calls.DURATION, it.duration)
                                put(Calls.CACHED_NAME, it.name)
                            }
                        }.toTypedArray()

                    context.contentResolver.bulkInsert(contentUri, values)
                }

                if (privateCalls.isNotEmpty()) {
                    privateCallHistoryStore.addCalls(privateCalls)
                }

                callback()
            }
        }
    }

    private fun removeSystemRecentCallsByIds(ids: List<Int>) {
        ids.chunked(30).forEach { chunk ->
            val selection = "${Calls._ID} IN (${getQuestionMarks(chunk.size)})"
            val selectionArgs = chunk.map { it.toString() }.toTypedArray()
            context.contentResolver.delete(contentUri, selection, selectionArgs)
        }
    }

    @Suppress("DEPRECATION")
    private fun samePhoneNumber(first: String, second: String): Boolean {
        return PhoneNumberUtils.compare(first, second)
    }
}

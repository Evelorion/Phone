package org.fossify.phone.helpers

import android.content.Context
import android.telephony.PhoneNumberUtils
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fossify.phone.extensions.config
import org.fossify.phone.models.RecentCall

class PrivateCallHistoryStore(private val context: Context) {
    companion object {
        const val INITIAL_PRIVATE_CALL_ID = -1000000
        private const val MAX_PRIVATE_CALL_HISTORY_ENTRIES = 500
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun getCalls(): MutableList<RecentCall> {
        val rawValue = context.config.privateCallHistoryEntries
        if (rawValue.isBlank()) {
            return mutableListOf()
        }

        return try {
            json.decodeFromString<List<RecentCall>>(rawValue)
                .map { it.copy(isPrivateRecord = true, groupedCalls = null) }
                .toMutableList()
        } catch (_: SerializationException) {
            mutableListOf()
        } catch (_: IllegalArgumentException) {
            mutableListOf()
        }
    }

    fun addCalls(calls: List<RecentCall>): Int {
        if (calls.isEmpty()) {
            return 0
        }

        val storedCalls = getCalls()
        var addedCount = 0

        calls.sortedBy { it.startTS }.forEach { call ->
            if (storedCalls.none { it.matchesCall(call) }) {
                storedCalls.add(
                    call.copy(
                        id = context.config.privateCallHistoryNextId,
                        isPrivateRecord = true,
                        groupedCalls = null
                    )
                )
                context.config.privateCallHistoryNextId = context.config.privateCallHistoryNextId - 1
                addedCount++
            }
        }

        if (addedCount > 0) {
            saveCalls(storedCalls)
        }

        return addedCount
    }

    fun removeCallsByIds(ids: Collection<Int>) {
        if (ids.isEmpty()) {
            return
        }

        val filteredCalls = getCalls().filterNot { it.id in ids }
        saveCalls(filteredCalls)
    }

    fun clear() {
        context.config.privateCallHistoryEntries = ""
    }

    private fun saveCalls(calls: List<RecentCall>) {
        if (calls.isEmpty()) {
            clear()
            return
        }

        context.config.privateCallHistoryEntries = json.encodeToString(
            calls.sortedByDescending { it.startTS }
                .take(MAX_PRIVATE_CALL_HISTORY_ENTRIES)
                .map { it.copy(isPrivateRecord = true, groupedCalls = null) }
        )
    }

    private fun RecentCall.matchesCall(other: RecentCall): Boolean {
        return startTS == other.startTS &&
            duration == other.duration &&
            type == other.type &&
            PhoneNumberUtils.compare(phoneNumber, other.phoneNumber)
    }
}

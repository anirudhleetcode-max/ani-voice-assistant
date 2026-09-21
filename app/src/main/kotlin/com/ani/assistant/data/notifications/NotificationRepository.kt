package com.ani.assistant.data.notifications

import android.content.Context
import com.ani.assistant.data.store.JsonListStore
import com.ani.nlu.response.NotificationItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

/**
 * A notification Ani was allowed to keep.
 *
 * [preview] is only ever populated when the user's privacy setting asks for previews —
 * the listener drops the text before it reaches this class otherwise, so turning the
 * setting off does not leave old message bodies sitting on disk.
 */
@Serializable
data class NotificationRecord(
    /** The platform's key, used to drop a record when the notification is dismissed. */
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String? = null,
    val preview: String? = null,
    val postedAtMillis: Long,
    val isMessaging: Boolean,
    /** True once Ani has read it out, so "em messages vachayi" does not repeat itself. */
    val readAloud: Boolean = false
) {
    fun toSummaryItem(canonicalApp: String) = NotificationItem(
        appCanonical = canonicalApp,
        appLabel = appLabel,
        sender = sender,
        preview = preview,
        postedAtEpochMillis = postedAtMillis,
        isMessaging = isMessaging
    )
}

/**
 * The buffer of recent notifications that "em messages vachayi?" reads from.
 *
 * Deliberately a short-lived buffer, not an archive: it is capped by count and pruned by
 * age on every read path. Ani needs to answer "what did I miss", which is a question
 * about the last few hours.
 */
class NotificationRepository(context: Context) {

    private val store = JsonListStore(
        context = context,
        fileName = "notifications.json",
        itemSerializer = NotificationRecord.serializer(),
        maxEntries = MAX_RECORDS
    )

    val records: Flow<List<NotificationRecord>> = store.items

    val unread: Flow<List<NotificationRecord>> = records.map { all -> all.filterNot { it.readAloud } }

    suspend fun record(record: NotificationRecord, historyEnabled: Boolean) {
        if (!historyEnabled) return
        store.update { current ->
            // The same conversation updating its notification replaces the old entry
            // rather than stacking; otherwise one chatty group looks like twenty messages.
            listOf(record) + current.filterNot { it.key == record.key }
        }
    }

    suspend fun remove(key: String) = store.removeWhere { it.key == key }

    suspend fun markAllRead() {
        store.update { current -> current.map { it.copy(readAloud = true) } }
    }

    suspend fun snapshot(): List<NotificationRecord> = store.items.first()

    suspend fun pruneOlderThan(retentionHours: Int) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(retentionHours.toLong())
        store.update { current -> current.filter { it.postedAtMillis >= cutoff } }
    }

    suspend fun clear() = store.clear()

    private companion object {
        const val MAX_RECORDS = 200
    }
}

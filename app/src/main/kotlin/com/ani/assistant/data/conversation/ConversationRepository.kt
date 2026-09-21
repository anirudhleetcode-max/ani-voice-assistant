package com.ani.assistant.data.conversation

import android.content.Context
import com.ani.assistant.data.store.JsonListStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Who said it. */
enum class Speaker { USER, ANI }

/**
 * One line of transcript.
 *
 * Text only — Ani never keeps the audio. There is no field here for a recording, and
 * there is nowhere in the app that writes one.
 */
@Serializable
data class ConversationEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    val speaker: Speaker,
    val text: String,
    /** The intent Ani chose, for the diagnostics view. Null for user turns. */
    val intent: String? = null,
    val succeeded: Boolean = true
)

/**
 * The conversation transcript.
 *
 * Honours two settings that the user can change at any time: whether to store history at
 * all, and for how long. Both are enforced here rather than in the UI, so turning history
 * off actually stops the writes instead of just hiding them.
 */
class ConversationRepository(context: Context) {

    private val store = JsonListStore(
        context = context,
        fileName = "conversation_history.json",
        itemSerializer = ConversationEntry.serializer(),
        maxEntries = MAX_ENTRIES
    )

    val entries: Flow<List<ConversationEntry>> = store.items

    /** Most recent first, newest turn at index 0. */
    fun recent(limit: Int): Flow<List<ConversationEntry>> = entries.map { it.take(limit) }

    suspend fun record(entry: ConversationEntry, historyEnabled: Boolean) {
        if (!historyEnabled) return
        store.add(entry)
    }

    suspend fun recordUser(text: String, historyEnabled: Boolean) = record(
        ConversationEntry(
            timestampMillis = System.currentTimeMillis(),
            speaker = Speaker.USER,
            text = text
        ),
        historyEnabled
    )

    suspend fun recordAni(text: String, intent: String?, succeeded: Boolean, historyEnabled: Boolean) =
        record(
            ConversationEntry(
                timestampMillis = System.currentTimeMillis(),
                speaker = Speaker.ANI,
                text = text,
                intent = intent,
                succeeded = succeeded
            ),
            historyEnabled
        )

    fun search(query: String): Flow<List<ConversationEntry>> = entries.map { all ->
        if (query.isBlank()) all else all.filter { it.text.contains(query, ignoreCase = true) }
    }

    suspend fun delete(id: String) = store.removeWhere { it.id == id }

    suspend fun clear() = store.clear()

    /** Drops anything past the user's retention window. Called on app start. */
    suspend fun pruneOlderThan(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong())
        store.update { entries -> entries.filter { it.timestampMillis >= cutoff } }
    }

    private companion object {
        const val MAX_ENTRIES = 1000
    }
}

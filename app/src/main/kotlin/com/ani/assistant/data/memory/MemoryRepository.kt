package com.ani.assistant.data.memory

import android.content.Context
import com.ani.assistant.data.store.JsonListStore
import com.ani.nlu.text.PhoneticKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

/** What kind of thing Ani remembered. */
enum class MemoryCategory {
    /** "Amma ante Lakshmi" — a spoken name mapped to a real contact. */
    CONTACT_ALIAS,

    /** "WA ante WhatsApp" — a nickname for an installed app. */
    APP_ALIAS,

    /** A stated preference, e.g. a default music app. */
    PREFERENCE,

    /** Anything else the user explicitly asked Ani to remember. */
    FACT
}

/**
 * One remembered thing.
 *
 * Everything here got in because the user said so out loud or typed it in the Memory
 * screen. Nothing is inferred from behaviour, and nothing is written without an explicit
 * instruction — which is what makes the Memory screen an honest list rather than a
 * surprise.
 */
@Serializable
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    /** What the user says, e.g. "amma". */
    val key: String,
    /** What it resolves to, e.g. "Lakshmi" or a contact lookup key. */
    val value: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    /** The utterance this came from, so the user can see why Ani knows it. */
    val learnedFrom: String? = null
) {
    /** Phonetic form of [key], so "amma"/"ammaa" both find this entry. */
    val matchKey: String get() = PhoneticKey.of(key)
}

/**
 * Ani's long-term memory: contact aliases, app nicknames and stated preferences.
 *
 * Deliberately small and fully user-visible. Everything in here can be listed, edited and
 * deleted from the Memory screen.
 */
class MemoryRepository(context: Context) {

    private val store = JsonListStore(
        context = context,
        fileName = "memory.json",
        itemSerializer = MemoryEntry.serializer(),
        maxEntries = MAX_ENTRIES
    )

    val entries: Flow<List<MemoryEntry>> = store.items

    fun byCategory(category: MemoryCategory): Flow<List<MemoryEntry>> =
        entries.map { all -> all.filter { it.category == category } }

    /**
     * Remembers [key] -> [value], replacing any previous value for the same spoken key.
     * Replacing rather than appending matters: "amma ante Lakshmi" said twice should not
     * leave two competing aliases.
     */
    suspend fun remember(
        category: MemoryCategory,
        key: String,
        value: String,
        learnedFrom: String? = null
    ) {
        val matchKey = PhoneticKey.of(key)
        store.update { current ->
            val withoutDuplicate = current.filterNot {
                it.category == category && it.matchKey == matchKey
            }
            listOf(
                MemoryEntry(
                    category = category,
                    key = key,
                    value = value,
                    learnedFrom = learnedFrom
                )
            ) + withoutDuplicate
        }
    }

    /** Resolves a spoken name through the user's aliases; null when nothing matches. */
    suspend fun resolveAlias(category: MemoryCategory, spoken: String): String? {
        val matchKey = PhoneticKey.of(spoken)
        return store.items.first()
            .firstOrNull { it.category == category && it.matchKey == matchKey }
            ?.value
    }

    suspend fun forget(id: String) = store.removeWhere { it.id == id }

    suspend fun clear() = store.clear()

    private companion object {
        const val MAX_ENTRIES = 300
    }
}

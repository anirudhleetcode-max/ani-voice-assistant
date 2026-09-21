package com.ani.assistant.data.store

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * A durable, atomically-written list of records on disk.
 *
 * Used for conversation history, remembered facts, custom commands and the notification
 * buffer. DataStore rather than Room because none of these need querying — they are read
 * whole, capped at a few hundred entries, and a schema migration for each one would be
 * more machinery than the problem deserves. If a future feature needs real queries, the
 * repositories above this are the only thing that would change.
 *
 * Writes go through DataStore's write-to-temp-then-rename, so a crash mid-save cannot
 * leave a half-written file.
 */
class JsonListStore<T>(
    context: Context,
    fileName: String,
    itemSerializer: KSerializer<T>,
    /** Oldest entries beyond this are dropped on write. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    private val json = Json {
        ignoreUnknownKeys = true      // adding a field must not orphan existing data
        encodeDefaults = true
        prettyPrint = false
    }

    private val listSerializer = ListSerializer(itemSerializer)

    private val store: DataStore<List<T>> = DataStoreFactory.create(
        serializer = object : Serializer<List<T>> {
            override val defaultValue: List<T> = emptyList()

            override suspend fun readFrom(input: InputStream): List<T> = try {
                json.decodeFromString(listSerializer, input.readBytes().decodeToString())
            } catch (error: SerializationException) {
                // A corrupt file is recoverable data loss; a crash loop is not.
                throw CorruptionException("could not parse $fileName", error)
            }

            override suspend fun writeTo(value: List<T>, output: OutputStream) {
                output.write(json.encodeToString(listSerializer, value).encodeToByteArray())
            }
        },
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            AniLog.w(TAG, "replacing corrupt store", "file" to fileName)
            emptyList()
        },
        produceFile = { context.dataStoreFile(fileName) }
    )

    val items: Flow<List<T>> = store.data

    /** Adds [item] at the head and trims the tail to [maxEntries]. */
    suspend fun add(item: T) {
        store.updateData { current -> (listOf(item) + current).take(maxEntries) }
    }

    suspend fun addAll(newItems: List<T>) {
        store.updateData { current -> (newItems + current).take(maxEntries) }
    }

    suspend fun update(transform: (List<T>) -> List<T>) {
        store.updateData { current -> transform(current).take(maxEntries) }
    }

    suspend fun removeWhere(predicate: (T) -> Boolean) {
        store.updateData { current -> current.filterNot(predicate) }
    }

    suspend fun clear() {
        store.updateData { emptyList() }
    }

    private companion object {
        const val TAG = "AniStore"
        const val DEFAULT_MAX_ENTRIES = 500
    }
}

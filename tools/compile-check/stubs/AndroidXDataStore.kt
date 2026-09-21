@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class CorruptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Serializer<T> {
    val defaultValue: T
    suspend fun readFrom(input: InputStream): T
    suspend fun writeTo(value: T, output: OutputStream)
}

interface DataStore<T> {
    val data: Flow<T>
    suspend fun updateData(transform: suspend (T) -> T): T
}

interface CorruptionHandler<T>

object DataStoreFactory {
    fun <T> create(
        serializer: Serializer<T>,
        corruptionHandler: CorruptionHandler<T>? = null,
        migrations: List<Any> = emptyList(),
        scope: CoroutineScope? = null,
        produceFile: () -> File
    ): DataStore<T> = object : DataStore<T> {
        override val data: Flow<T> = flowOf(serializer.defaultValue)
        override suspend fun updateData(transform: suspend (T) -> T): T = transform(serializer.defaultValue)
    }
}

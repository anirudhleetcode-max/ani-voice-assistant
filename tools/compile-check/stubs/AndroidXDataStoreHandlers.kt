@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.core.handlers

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.CorruptionHandler

class ReplaceFileCorruptionHandler<T>(
    private val produceNewData: (CorruptionException) -> T
) : CorruptionHandler<T>

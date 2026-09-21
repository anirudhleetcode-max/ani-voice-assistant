@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

fun preferencesDataStore(
    name: String,
    corruptionHandler: Any? = null,
    produceMigrations: (Context) -> List<Any> = { emptyList() },
    scope: Any? = null
): ReadOnlyProperty<Context, DataStore<Preferences>> =
    ReadOnlyProperty { _: Context, _: KProperty<*> ->
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(emptyPreferences())
        }
    }

@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

abstract class Preferences {
    class Key<T> internal constructor(val name: String)

    abstract operator fun <T> get(key: Key<T>): T?
    abstract operator fun contains(key: Key<*>): Boolean
}

class MutablePreferences internal constructor() : Preferences() {
    override operator fun <T> get(key: Key<T>): T? = null
    override operator fun contains(key: Key<*>): Boolean = false
    operator fun <T> set(key: Key<T>, value: T) = Unit
    fun <T> remove(key: Key<T>): T? = null
    fun clear() = Unit
}

fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = Preferences.Key(name)
fun intPreferencesKey(name: String): Preferences.Key<Int> = Preferences.Key(name)
fun longPreferencesKey(name: String): Preferences.Key<Long> = Preferences.Key(name)
fun floatPreferencesKey(name: String): Preferences.Key<Float> = Preferences.Key(name)
fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key(name)
fun stringSetPreferencesKey(name: String): Preferences.Key<Set<String>> = Preferences.Key(name)

fun emptyPreferences(): Preferences = MutablePreferences()

suspend fun DataStore<Preferences>.edit(
    transform: suspend (MutablePreferences) -> Unit
): Preferences {
    transform(MutablePreferences())
    return emptyPreferences()
}

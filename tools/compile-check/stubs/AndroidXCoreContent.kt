@file:Suppress("UNUSED_PARAMETER", "unused")

package androidx.core.content

import android.content.Context
import android.content.SharedPreferences

object ContextCompat {
    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int = 0

    @JvmStatic
    fun getSystemService(context: Context, serviceClass: Class<*>): Any? = null

    @JvmStatic
    fun startForegroundService(context: Context, intent: android.content.Intent) = Unit
}

inline fun SharedPreferences.edit(
    commit: Boolean = false,
    action: SharedPreferences.Editor.() -> Unit
) {
    val editor = edit()
    editor.action()
    if (commit) editor.commit() else editor.apply()
}

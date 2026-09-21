package com.ani.assistant.core.log

import android.util.Log
import com.ani.assistant.BuildConfig

/**
 * The only logger in the app.
 *
 * Ani handles contact names, message bodies, notification text and audio. None of it may
 * ever reach logcat, where any app with READ_LOGS on a rooted device — or anyone holding
 * the phone with USB debugging on — can read it. So this logger does not take arbitrary
 * interpolated strings for sensitive values; it takes *shapes*.
 *
 * ```
 * AniLog.d(TAG, "resolved contact", "matches" to matches.size)   // fine
 * AniLog.d(TAG, "calling ${contact.name}")                        // never
 * ```
 *
 * Use [redact] when a value has to appear at all — it keeps the length and first
 * character, which is enough to debug a parsing problem and not enough to identify a
 * person.
 */
object AniLog {

    private const val MAX_TAG_LENGTH = 23

    /** In release builds, debug and info are dropped entirely rather than filtered. */
    private val verboseEnabled: Boolean = BuildConfig.DEBUG

    fun d(tag: String, message: String, vararg fields: Pair<String, Any?>) {
        if (!verboseEnabled) return
        Log.d(tag.trim(), format(message, fields))
    }

    fun i(tag: String, message: String, vararg fields: Pair<String, Any?>) {
        if (!verboseEnabled) return
        Log.i(tag.trim(), format(message, fields))
    }

    fun w(tag: String, message: String, vararg fields: Pair<String, Any?>) {
        Log.w(tag.trim(), format(message, fields))
    }

    /**
     * Errors are always logged, but [throwable] is logged by type and message only —
     * a stack trace can carry user data in exception messages from platform APIs.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null, vararg fields: Pair<String, Any?>) {
        val detail = throwable?.let { " cause=${it.javaClass.simpleName}" }.orEmpty()
        Log.e(tag.trim(), format(message, fields) + detail)
        if (verboseEnabled && throwable != null) {
            Log.e(tag.trim(), "stack", throwable)
        }
    }

    /**
     * "Rahul" -> "R***(5)". Enough to tell two values apart while debugging; not enough
     * to identify anyone from a bug report.
     */
    fun redact(value: String?): String = when {
        value == null -> "null"
        value.isEmpty() -> "empty"
        else -> "${value.first()}***(${value.length})"
    }

    /** For counts and enums, which are safe to log verbatim. */
    private fun format(message: String, fields: Array<out Pair<String, Any?>>): String {
        if (fields.isEmpty()) return message
        return message + fields.joinToString(prefix = " ", separator = " ") { (key, value) ->
            "$key=$value"
        }
    }

    /** Truncates a tag to the length logcat accepts on older platforms. */
    fun tag(name: String): String = if (name.length <= MAX_TAG_LENGTH) name else name.take(MAX_TAG_LENGTH)
}

package com.ani.nlu.intent

import com.ani.nlu.text.Language
import com.ani.nlu.time.TimeSpec

/** Named argument of an intent. */
enum class SlotKey {
    /** Name as spoken — the Android layer resolves it against Contacts and aliases. */
    CONTACT_NAME,

    /** The words to send, already stripped of the quotative "ani". */
    MESSAGE_BODY,

    /** "whatsapp" / "sms" — which app the user asked to send through, if they said. */
    MESSAGE_CHANNEL,

    /** App the user named, as spoken ("insta", "WA"). Resolved against installed apps. */
    APP_NAME,

    /** Free-text music search: artist, song, album, playlist or mood. */
    MUSIC_QUERY,

    /** Music app named by the user, e.g. "spotify lo". */
    MUSIC_PROVIDER,

    /** next / previous / pause / resume / stop. */
    MEDIA_ACTION,

    /** Which notifications to read: "whatsapp", "all", "messages". */
    NOTIFICATION_FILTER,

    /** wifi / bluetooth / dnd / brightness / airplane / battery-saver ... */
    SETTINGS_TARGET,

    /** on / off / toggle. */
    TOGGLE_STATE,

    /** up / down, or an absolute 0..100. */
    LEVEL,

    /** What to be reminded about. */
    REMINDER_TEXT,

    /** Destination for navigation. */
    DESTINATION,

    /** The phrase being taught, for TEACH_COMMAND. */
    COMMAND_PHRASE,

    /** Id of the matched user-defined command. */
    CUSTOM_COMMAND_ID,

    /** "amma" in "amma ante Lakshmi". */
    MEMORY_KEY,

    /** "Lakshmi" in "amma ante Lakshmi". */
    MEMORY_VALUE,

    /** The question to forward to the AI provider. */
    QUESTION
}

/**
 * The classifier's verdict: one intent, its arguments and how sure we are.
 *
 * [needsSlots] is what turns a one-shot classifier into a conversation — "Rahul ki
 * message pampu" parses perfectly but cannot execute until Ani asks "em message?".
 */
data class ParsedCommand(
    val type: IntentType,
    val confidence: Double,
    val slots: Map<SlotKey, String> = emptyMap(),
    val timeSpec: TimeSpec? = null,
    val language: Language = Language.UNKNOWN,
    val normalizedText: String = "",
    val originalText: String = "",
    /** Slots the intent needs before it can run, but which the user did not supply. */
    val needsSlots: List<SlotKey> = emptyList(),
    /**
     * True once the user has approved this exact action.
     *
     * Without this the confirmation gate has no memory. It re-evaluates the same command
     * after the user says "avunu", reaches the same verdict — of course it does, nothing
     * about the command changed — and asks again, forever. The action never runs.
     *
     * Set only by the classifier when it resolves a pending confirmation, and read by the
     * orchestrator and by tools that do their own disambiguation.
     */
    val confirmed: Boolean = false,
    /** Ranked runners-up, kept for diagnostics and for the AI fallback to consider. */
    val alternatives: List<ScoredIntent> = emptyList()
) {
    operator fun get(key: SlotKey): String? = slots[key]

    val isComplete: Boolean get() = needsSlots.isEmpty()

    val risk: ActionRisk get() = type.risk

    fun withSlot(key: SlotKey, value: String): ParsedCommand = copy(
        slots = slots + (key to value),
        needsSlots = needsSlots - key
    )

    companion object {
        fun unknown(original: String, normalized: String, language: Language) = ParsedCommand(
            type = IntentType.UNKNOWN,
            confidence = 0.0,
            language = language,
            normalizedText = normalized,
            originalText = original
        )
    }
}

/** A candidate intent produced by one rule, before the best one is chosen. */
data class ScoredIntent(
    val type: IntentType,
    val score: Double,
    val slots: Map<SlotKey, String> = emptyMap(),
    val timeSpec: TimeSpec? = null,
    val needsSlots: List<SlotKey> = emptyList()
)

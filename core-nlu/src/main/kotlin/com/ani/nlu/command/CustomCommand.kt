package com.ani.nlu.command

import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey

/**
 * One step of a user-defined command.
 *
 * Steps are typed intents with fixed arguments, never free text. "College mode" cannot
 * grow into an arbitrary instruction the assistant improvises on — it is a recorded list
 * of the same actions the user could have asked for one at a time, which is what makes it
 * safe to run without re-confirming every step.
 */
data class CustomAction(
    val intent: IntentType,
    val slots: Map<SlotKey, String> = emptyMap()
)

/**
 * A phrase the user taught Ani, e.g. "college mode" -> enable DND, open Calendar,
 * set volume to 30.
 */
data class CustomCommand(
    val id: String,
    /** What the user says. Matched phonetically, so spelling does not matter. */
    val phrase: String,
    val actions: List<CustomAction>,
    val enabled: Boolean = true,
    /** Shown in the commands list; optional. */
    val description: String = "",
    val createdAtEpochMillis: Long = 0L
) {
    val isRunnable: Boolean get() = enabled && actions.isNotEmpty()
}

/** Result of matching an utterance against the user's saved commands. */
data class CustomCommandMatch(
    val command: CustomCommand,
    /** 0..1 — 1.0 for an exact phonetic match of the whole phrase. */
    val confidence: Double,
    /** Words left over after the phrase, e.g. "college mode" in "rey college mode now". */
    val remainder: String
)

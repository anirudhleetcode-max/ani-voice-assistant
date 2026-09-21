package com.ani.nlu.response

import com.ani.nlu.text.Language

/**
 * How Ani talks.
 *
 * The default is [FRIENDLY] and short, because the whole point of this assistant is that
 * it sounds like a friend answering, not a product reading a status message. "Amma ki
 * call chesthunna ra" is the target; "Your requested contact is being called" is the
 * failure mode.
 */
enum class Persona {
    /** Warm, uses "ra", contracted. The default. */
    FRIENDLY,

    /** Even shorter, very casual. */
    CHILL,

    /** No slang particles, complete sentences. For work contexts. */
    PROFESSIONAL,

    /** Friendly with the occasional aside. Never at the cost of clarity. */
    FUNNY,

    /** Two or three words. Confirmations only. */
    MINIMAL
}

/** How much Ani says. */
enum class Verbosity { SHORT, DETAILED }

/**
 * Everything that shapes a reply: what to say it in, how to say it, and how much.
 */
data class ResponseStyle(
    val language: Language = Language.MIXED,
    val persona: Persona = Persona.FRIENDLY,
    val verbosity: Verbosity = Verbosity.SHORT,
    /** The name the user gave the assistant. */
    val assistantName: String = "Ani"
) {
    /** True when replies should be in Telugu/Tanglish rather than plain English. */
    val speaksTelugu: Boolean
        get() = language == Language.TELUGU || language == Language.MIXED || language == Language.UNKNOWN

    /** The "ra" particle, which some personas drop. */
    val particle: String
        get() = when (persona) {
            Persona.FRIENDLY, Persona.CHILL, Persona.FUNNY -> " ra"
            Persona.PROFESSIONAL, Persona.MINIMAL -> ""
        }

    companion object {
        val DEFAULT = ResponseStyle()
    }
}

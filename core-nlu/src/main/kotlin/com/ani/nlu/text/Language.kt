package com.ani.nlu.text

/**
 * The language Ani believes the user just spoke.
 *
 * [MIXED] is a first-class citizen, not an error case: the target user routinely says
 * "Rey Amma ki WhatsApp lo message pampu" in a single breath.
 */
enum class Language {
    TELUGU,
    ENGLISH,
    MIXED,
    UNKNOWN;

    /** BCP-47 tag to hand to a speech recogniser or TTS engine. */
    fun toLocaleTag(): String = when (this) {
        TELUGU -> "te-IN"
        ENGLISH -> "en-IN"
        // Android's recogniser has no "Tanglish" locale. en-IN transcribes Telugu words
        // into Latin script reasonably well, which is exactly the shape our normaliser
        // expects, so it is the least-bad choice for mixed speech.
        MIXED -> "en-IN"
        UNKNOWN -> "en-IN"
    }
}

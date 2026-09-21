package com.ani.nlu.lexicon

import com.ani.nlu.text.PhoneticKey

/**
 * Undoes Telugu's agglutinated case endings.
 *
 * Telugu glues case markers onto the noun, and speakers are inconsistent about whether
 * they leave a space: "amma ki call chey" and "ammaki call chey" are the same sentence.
 * Without this step the second one hands the contact resolver the name "Ammaki", which
 * matches nobody.
 *
 * Suffixes are only stripped from words the lexicon does *not* already know, so real
 * words that merely end in a suffix-like syllable ("malli", "ani", "nannu") survive.
 */
object Morphology {

    /** Longest first — "loki" must win over "lo". */
    private val caseSuffixes: List<String> = listOf(
        "gaariki", "gaaru", "garu", "gari",
        "loki", "loni", "lonu", "lo",
        "tho", "to", "toti",
        "kki", "ki", "ku",
        "ni", "nu", "na"
    )

    private const val MIN_STEM_LENGTH = 3

    /**
     * Returns [word] with one trailing case marker removed, or [word] unchanged.
     *
     * The check is done on the *surface* word rather than the phonetic key so that the
     * stem we hand back is still something a contact lookup can use verbatim.
     */
    fun stripCaseSuffix(word: String): String {
        if (word.length < MIN_STEM_LENGTH + 2) return word
        if (Lexicon.isKnown(PhoneticKey.of(word))) return word

        for (suffix in caseSuffixes) {
            if (!word.endsWith(suffix, ignoreCase = true)) continue
            val stem = word.dropLast(suffix.length)
            if (stem.length < MIN_STEM_LENGTH) continue
            return stem
        }
        return word
    }

    /** True when [word] carries a case marker we can strip. */
    fun hasCaseSuffix(word: String): Boolean = stripCaseSuffix(word) != word

    /**
     * Dative marker ("ki"/"ku") — the grammatical signal for "to X". It is what tells
     * "Amma ki call chey" that Amma is the *recipient* rather than the caller.
     */
    fun isDativeMarker(word: String): Boolean {
        val lower = word.lowercase()
        return lower == "ki" || lower == "ku" || lower == "kki" || lower == "kee"
    }

    /** Locative marker ("lo") — "Spotify lo" means "in Spotify". */
    fun isLocativeMarker(word: String): Boolean {
        val lower = word.lowercase()
        return lower == "lo" || lower == "loki" || lower == "loni" || lower == "lone"
    }
}

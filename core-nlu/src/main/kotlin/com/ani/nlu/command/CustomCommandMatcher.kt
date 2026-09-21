package com.ani.nlu.command

import com.ani.nlu.lexicon.Lexicon
import com.ani.nlu.lexicon.SemanticTag
import com.ani.nlu.text.Fuzzy
import com.ani.nlu.text.NormalizedText
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.TextNormalizer

/**
 * Matches an utterance against the commands the user taught Ani.
 *
 * Custom commands are checked *before* the built-in rules, so a user who defines
 * "good night" gets their own routine rather than Ani's idea of a bedtime. Matching is
 * phonetic and contiguous: every word of the phrase has to appear in order, which keeps
 * "college mode" from firing on "college ki route chupinchu".
 */
class CustomCommandMatcher(commands: List<CustomCommand> = emptyList()) {

    private data class Indexed(val command: CustomCommand, val keys: List<String>)

    private val indexed: List<Indexed> = commands
        .filter { it.isRunnable }
        .mapNotNull { command ->
            val keys = TextNormalizer.normalize(command.phrase).tokens
                .filterNot { Lexicon.has(it, SemanticTag.F_WAKE) }
                .map { it.key }
                .filter { it.isNotEmpty() }
            if (keys.isEmpty()) null else Indexed(command, keys)
        }
        // Longest phrase first: "good night mode" should beat "good night".
        .sortedByDescending { it.keys.size }

    fun match(raw: String): CustomCommandMatch? = match(TextNormalizer.normalize(raw))

    fun match(text: NormalizedText): CustomCommandMatch? {
        if (indexed.isEmpty() || text.isEmpty) return null
        val tokens = text.tokens.filterNot {
            Lexicon.has(it, SemanticTag.F_WAKE) || Lexicon.has(it, SemanticTag.F_FILLER)
        }
        if (tokens.isEmpty()) return null
        val keys = tokens.map { it.key }

        for (candidate in indexed) {
            val start = indexOfSubsequence(keys, candidate.keys) ?: continue
            val end = start + candidate.keys.size
            val remainder = tokens.filterIndexed { index, _ -> index < start || index >= end }
                .joinToString(" ") { it.text }
            // A phrase that covers the whole utterance is a certain match; a phrase buried
            // in a longer sentence is likelier to be a coincidence.
            val coverage = candidate.keys.size.toDouble() / keys.size
            return CustomCommandMatch(
                command = candidate.command,
                confidence = 0.75 + 0.25 * coverage,
                remainder = remainder
            )
        }
        return null
    }

    /** Index where [needle] appears contiguously in [haystack], allowing near-spellings. */
    private fun indexOfSubsequence(haystack: List<String>, needle: List<String>): Int? {
        if (needle.size > haystack.size) return null
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (offset in needle.indices) {
                if (!keysMatch(haystack[start + offset], needle[offset])) continue@outer
            }
            return start
        }
        return null
    }

    private fun keysMatch(spoken: String, expected: String): Boolean {
        if (spoken == expected) return true
        val tolerance = Fuzzy.toleranceFor(maxOf(spoken.length, expected.length))
        return tolerance > 0 && Fuzzy.levenshtein(spoken, expected) <= tolerance
    }

    companion object {
        fun empty() = CustomCommandMatcher(emptyList())

        /** Normalised form used as the storage key, so duplicates are caught on save. */
        fun phraseKey(phrase: String): String =
            TextNormalizer.normalize(phrase).tokens.joinToString(" ") { PhoneticKey.of(it.text) }
    }
}

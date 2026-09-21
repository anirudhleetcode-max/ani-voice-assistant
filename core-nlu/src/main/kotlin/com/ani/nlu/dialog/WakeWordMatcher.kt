package com.ani.nlu.dialog

import com.ani.nlu.text.Fuzzy
import com.ani.nlu.text.NormalizedText
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.TextNormalizer

/** What the wake matcher found in an utterance. */
data class WakeMatch(
    val matched: Boolean,
    /** The phrase that matched, as configured by the user. */
    val phrase: String? = null,
    /** The utterance with the wake phrase removed — often empty ("Rey" on its own). */
    val remainder: NormalizedText = NormalizedText.EMPTY,
    /** 0..1. Compared against the user's sensitivity setting. */
    val confidence: Double = 0.0
)

/**
 * Recognises the activation phrase and strips it.
 *
 * This runs on text that has *already* been recognised — it is not the low-power hotword
 * detector. The two are deliberately separate: on-device hotword detection decides when
 * to start listening, and this decides whether what was then transcribed really began
 * with "Rey" (which is also how tap-to-talk and typed input reach the same code path).
 *
 * Phrases are matched phonetically, so "rey", "re", "ray" and "రే" all land on the same
 * configured phrase.
 */
class WakeWordMatcher(
    phrases: Collection<String> = DEFAULT_PHRASES,
    /**
     * 0..1, from the user's "wake sensitivity" slider. Higher means more forgiving:
     * it widens the edit distance we accept on a near-miss.
     */
    private val sensitivity: Double = 0.5
) {

    private data class Phrase(val original: String, val keys: List<String>)

    private val phrases: List<Phrase> = phrases
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { phrase ->
            Phrase(
                original = phrase,
                keys = phrase.split(" ").filter { it.isNotBlank() }.map { PhoneticKey.of(it) }
            )
        }
        .filter { it.keys.isNotEmpty() }

    fun match(raw: String): WakeMatch = match(TextNormalizer.normalize(raw))

    fun match(text: NormalizedText): WakeMatch {
        if (text.isEmpty || phrases.isEmpty()) return WakeMatch(matched = false)

        // Longest phrase first so "hey ani" wins over a bare "ani".
        for (phrase in phrases.sortedByDescending { it.keys.size }) {
            val confidence = leadingMatchConfidence(text, phrase)
            if (confidence > 0.0) {
                return WakeMatch(
                    matched = true,
                    phrase = phrase.original,
                    remainder = dropLeading(text, phrase.keys.size),
                    confidence = confidence
                )
            }
        }
        return WakeMatch(matched = false, remainder = text)
    }

    /** True when the utterance was *only* the wake phrase. */
    fun isBareWake(raw: String): Boolean {
        val match = match(raw)
        return match.matched && match.remainder.isEmpty
    }

    private fun leadingMatchConfidence(text: NormalizedText, phrase: Phrase): Double {
        if (text.tokens.size < phrase.keys.size) return 0.0
        var total = 0.0
        for ((offset, expected) in phrase.keys.withIndex()) {
            val actual = text.tokens[offset].key
            val similarity = when {
                actual == expected -> 1.0
                else -> {
                    val allowed = allowedDistance(expected.length)
                    val distance = Fuzzy.levenshtein(actual, expected)
                    if (distance > allowed) return 0.0 else Fuzzy.similarity(actual, expected)
                }
            }
            total += similarity
        }
        return total / phrase.keys.size
    }

    /**
     * Wake phrases are short, so tolerance has to be earned. At the default sensitivity
     * only an exact phonetic match counts; turning the slider up buys one edit.
     */
    private fun allowedDistance(length: Int): Int = when {
        sensitivity >= 0.8 && length >= 3 -> 1
        sensitivity >= 0.6 && length >= 5 -> 1
        else -> 0
    }

    private fun dropLeading(text: NormalizedText, count: Int): NormalizedText {
        val remaining = text.tokens.drop(count).mapIndexed { index, token -> token.copy(index = index) }
        return text.copy(
            normalized = remaining.joinToString(" ") { it.text },
            tokens = remaining
        )
    }

    companion object {
        /** Shipped defaults. The user can replace these entirely in Settings. */
        val DEFAULT_PHRASES = listOf("rey", "ani", "hey ani", "orey", "oi ani")
    }
}

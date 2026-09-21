package com.ani.nlu.text

import kotlin.math.max
import kotlin.math.min

/** Small, dependency-free string-distance helpers used for tolerant lexicon lookup. */
object Fuzzy {

    /** Classic Levenshtein edit distance, O(min(n,m)) memory. */
    fun levenshtein(a: CharSequence, b: CharSequence): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a

        var previous = IntArray(shorter.length + 1) { it }
        var current = IntArray(shorter.length + 1)

        for (i in 1..longer.length) {
            current[0] = i
            for (j in 1..shorter.length) {
                val substitution = previous[j - 1] + if (longer[i - 1] == shorter[j - 1]) 0 else 1
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1), substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[shorter.length]
    }

    /** Similarity in `0.0..1.0`, where 1.0 means identical. */
    fun similarity(a: CharSequence, b: CharSequence): Double {
        val longest = max(a.length, b.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / longest
    }

    /**
     * Edit distance we are willing to forgive for a word of [length] characters.
     *
     * These thresholds are deliberately tight. Phonetic folding already absorbs most
     * spelling variation, so fuzzy matching is only a safety net — and a loose net does
     * real damage: at tolerance 1 on short words, the "singh" of "Arijit Singh" folds to
     * "sing" and lands one edit away from "song", which would misread a singer's name as
     * the word for music.
     */
    fun toleranceFor(length: Int): Int = when {
        length <= 5 -> 0
        length <= 9 -> 1
        else -> 2
    }

    /** True when [candidate] is within the length-scaled tolerance of [target]. */
    fun matches(candidate: String, target: String): Boolean {
        if (candidate == target) return true
        val tolerance = toleranceFor(max(candidate.length, target.length))
        if (tolerance == 0) return false
        return levenshtein(candidate, target) <= tolerance
    }
}

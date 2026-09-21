package com.ani.nlu.text

/** One word of user speech, kept alongside the forms the engine matches against. */
data class Token(
    /** The word exactly as it appeared after transliteration, minus punctuation. */
    val text: String,
    /** [PhoneticKey] of [text] — what lexicon lookups compare. */
    val key: String,
    /** Position in [NormalizedText.tokens]. */
    val index: Int
) {
    val isNumeric: Boolean get() = text.isNotEmpty() && text.all { it.isDigit() }
}

/** The result of running raw recogniser output through [TextNormalizer]. */
data class NormalizedText(
    val original: String,
    val normalized: String,
    val tokens: List<Token>,
    val hadTeluguScript: Boolean
) {
    val isEmpty: Boolean get() = tokens.isEmpty()

    fun keys(): List<String> = tokens.map { it.key }

    fun textFrom(startIndex: Int, endIndexExclusive: Int = tokens.size): String =
        tokens.subList(
            startIndex.coerceIn(0, tokens.size),
            endIndexExclusive.coerceIn(0, tokens.size)
        ).joinToString(" ") { it.text }

    /** Index of the first token whose key is in [keySet], or -1. */
    fun indexOfKey(keySet: Set<String>): Int = tokens.indexOfFirst { it.key in keySet }

    fun containsKey(keySet: Set<String>): Boolean = indexOfKey(keySet) >= 0

    companion object {
        val EMPTY = NormalizedText("", "", emptyList(), false)
    }
}

/**
 * Turns whatever the recogniser produced into a stable token stream.
 *
 * Pipeline: transliterate Telugu script -> lowercase -> strip punctuation ->
 * collapse elongations ("reyyyy" -> "rey") -> tokenise -> attach phonetic keys.
 *
 * Nothing here is intent-aware; it is pure text hygiene so that every later stage
 * sees one canonical shape.
 */
object TextNormalizer {

    fun normalize(input: String): NormalizedText {
        if (input.isBlank()) return NormalizedText.EMPTY

        val hadTelugu = Transliterator.containsTeluguScript(input)
        val latin = Transliterator.transliterate(input)

        val cleaned = buildString(latin.length) {
            for (c in latin) {
                when {
                    c.isLetterOrDigit() -> append(c.lowercaseChar())
                    c == '\'' -> Unit // "isn't" -> "isnt"
                    c.isWhitespace() -> append(' ')
                    // Hyphens and slashes join words that should be separate tokens.
                    else -> append(' ')
                }
            }
        }

        val words = cleaned.split(' ').filter { it.isNotBlank() }.map { collapseElongation(it) }
        val tokens = words.mapIndexed { index, word -> Token(word, PhoneticKey.of(word), index) }
            .filter { it.key.isNotEmpty() }
            // Re-index after filtering so indices stay contiguous.
            .mapIndexed { index, token -> token.copy(index = index) }

        return NormalizedText(
            original = input,
            normalized = tokens.joinToString(" ") { it.text },
            tokens = tokens,
            hadTeluguScript = hadTelugu
        )
    }

    /**
     * "reyyyy" -> "rey", "sarrreee" -> "sare".
     *
     * Runs of three or more identical characters are expressive lengthening and collapse
     * to one. Doubles are left alone because they are meaningful in Telugu spelling
     * ("amma", "pettu") even though [PhoneticKey] later folds them too.
     */
    internal fun collapseElongation(word: String): String {
        if (word.length < 3) return word
        val out = StringBuilder(word.length)
        var index = 0
        while (index < word.length) {
            val c = word[index]
            var runLength = 1
            while (index + runLength < word.length && word[index + runLength] == c) runLength++
            out.append(if (runLength >= 3) c.toString() else c.toString().repeat(runLength))
            index += runLength
        }
        return out.toString()
    }
}

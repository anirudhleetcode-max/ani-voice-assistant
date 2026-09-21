package com.ani.nlu.text

/**
 * Converts Telugu script (Unicode block U+0C00..U+0C7F) into the Latin "Tanglish"
 * spelling that Telugu speakers actually type.
 *
 * Why this exists: a speech recogniser running with the `te-IN` locale returns native
 * script ("అమ్మకి కాల్ చెయ్"), while the same sentence spoken to an `en-IN` recogniser
 * comes back as Latin ("amma ki call chey"). Everything downstream — lexicon, intent
 * rules, tests — works in one space only. Transliterating here means the rest of the
 * engine never has to know which recogniser produced the text.
 *
 * This is a phonetic transliteration tuned for matching, not a scholarly ISO-15919
 * romanisation: it deliberately produces the spelling a person would type.
 */
object Transliterator {

    private const val TELUGU_START = 'ఀ'
    private const val TELUGU_END = '౿'

    private const val VIRAMA = '్'
    private const val ANUSVARA = 'ం'
    private const val VISARGA = 'ః'
    private const val CANDRABINDU = 'ఁ'

    /** Independent vowels. */
    private val vowels: Map<Char, String> = mapOf(
        'అ' to "a",   // అ
        'ఆ' to "aa",  // ఆ
        'ఇ' to "i",   // ఇ
        'ఈ' to "ee",  // ఈ
        'ఉ' to "u",   // ఉ
        'ఊ' to "oo",  // ఊ
        'ఋ' to "ru",  // ఋ
        'ౠ' to "ru",  // ౠ
        'ఌ' to "lu",  // ఌ
        'ఎ' to "e",   // ఎ
        'ఏ' to "e",   // ఏ
        'ఐ' to "ai",  // ఐ
        'ఒ' to "o",   // ఒ
        'ఓ' to "o",   // ఓ
        'ఔ' to "au"   // ఔ
    )

    /** Dependent vowel signs (matras) that replace a consonant's inherent "a". */
    private val matras: Map<Char, String> = mapOf(
        'ా' to "aa",  // ా
        'ి' to "i",   // ి
        'ీ' to "ee",  // ీ
        'ు' to "u",   // ు
        'ూ' to "oo",  // ూ
        'ృ' to "ru",  // ృ
        'ౄ' to "ru",  // ౄ
        'ె' to "e",   // ె
        'ే' to "e",   // ే
        'ై' to "ai",  // ై
        'ొ' to "o",   // ొ
        'ో' to "o",   // ో
        'ౌ' to "au"   // ౌ
    )

    /** Consonants, without their inherent vowel. */
    private val consonants: Map<Char, String> = mapOf(
        'క' to "k",   'ఖ' to "kh",  'గ' to "g",   'ఘ' to "gh",  'ఙ' to "ng",
        'చ' to "ch",  'ఛ' to "chh", 'జ' to "j",   'ఝ' to "jh",  'ఞ' to "ny",
        'ట' to "t",   'ఠ' to "th",  'డ' to "d",   'ఢ' to "dh",  'ణ' to "n",
        'త' to "t",   'థ' to "th",  'ద' to "d",   'ధ' to "dh",  'న' to "n",
        'ప' to "p",   'ఫ' to "ph",  'బ' to "b",   'భ' to "bh",  'మ' to "m",
        'య' to "y",   'ర' to "r",   'ఱ' to "r",   'ల' to "l",   'ళ' to "l",
        'వ' to "v",   'శ' to "sh",  'ష' to "sh",  'స' to "s",   'హ' to "h",
        'ఴ' to "l",   'ౘ' to "ts",  'ౙ' to "dz"
    )

    private val digits: Map<Char, Char> = ('౦'..'౯')
        .mapIndexed { index, ch -> ch to ('0' + index) }
        .toMap()

    /**
     * Anusvara assimilates to whatever consonant follows it, and Tanglish spelling
     * follows the sound: "ఎంత" is typed "enta", "సంబరం" is typed "sambaram". Getting this
     * wrong is not cosmetic — "emta" is two edits away from "enta" and would miss the
     * lexicon entirely.
     */
    private fun nasalFor(text: String, fromIndex: Int): Char {
        val next = text.getOrNull(fromIndex) ?: return 'm'
        val following = consonants[next] ?: return 'm'
        return if (following.first() in "pbm") 'm' else 'n'
    }

    /** True when [text] contains at least one Telugu code point. */
    fun containsTeluguScript(text: String): Boolean = text.any { it in TELUGU_START..TELUGU_END }

    /** True when [text] is written predominantly in Telugu script. */
    fun isPredominantlyTeluguScript(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return false
        val telugu = text.count { it in TELUGU_START..TELUGU_END && it.isLetter() }
        return telugu * 2 >= letters
    }

    /**
     * Transliterates every Telugu run in [text] to Latin, leaving any text that is
     * already Latin (very common in mixed dictation) exactly as it was.
     */
    fun transliterate(text: String): String {
        if (!containsTeluguScript(text)) return text

        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                digits.containsKey(ch) -> {
                    out.append(digits.getValue(ch))
                    i++
                }

                vowels.containsKey(ch) -> {
                    out.append(vowels.getValue(ch))
                    i++
                }

                consonants.containsKey(ch) -> {
                    out.append(consonants.getValue(ch))
                    i++
                    // A consonant carries an inherent "a" unless a matra or a virama follows.
                    val next = text.getOrNull(i)
                    when {
                        next != null && matras.containsKey(next) -> {
                            out.append(matras.getValue(next))
                            i++
                        }

                        next == VIRAMA -> {
                            i++ // vowel suppressed; the next consonant clusters onto this one
                        }

                        else -> out.append("a")
                    }
                }

                ch == ANUSVARA || ch == CANDRABINDU -> {
                    out.append(nasalFor(text, i + 1))
                    i++
                }

                ch == VISARGA -> {
                    out.append("h")
                    i++
                }

                ch == VIRAMA -> i++ // stray virama

                ch in TELUGU_START..TELUGU_END -> i++ // unmapped Telugu mark: drop it

                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

}

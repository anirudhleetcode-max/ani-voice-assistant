package com.ani.nlu.text

/**
 * Folds a word down to a spelling-insensitive phonetic key.
 *
 * Tanglish has no orthography. The same word arrives as `chey` / `cheyi` / `cheyyi` /
 * `che` / `chei`, and the *same* word arrives from a Telugu-script recogniser as `chey`
 * after transliteration. Matching on literal strings therefore fails constantly, which is
 * why every lexicon lookup in Ani goes through this function on both sides.
 *
 * The folding rules are deliberately aggressive and lossy — a key is only ever compared
 * against another key, never shown to the user.
 *
 * Examples:
 * ```
 * "call"     -> "kal"     "kaal"     -> "kal"     (English + Tanglish collide, as intended)
 * "chey"     -> "key"     "chei"     -> "key"
 * "vachayi"  -> "vakayi"  "vacchayi" -> "vakayi"
 * "phone"    -> "pone"    "fone"     -> "pone"
 * "whatsapp" -> "vatsap"
 * ```
 */
object PhoneticKey {

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

    fun of(word: String): String {
        if (word.isEmpty()) return ""

        var s = word.lowercase().filter { it.isLetterOrDigit() }
        if (s.isEmpty()) return ""
        if (s.all { it.isDigit() }) return s

        s = s.replace("x", "ks").replace("q", "k").replace("z", "j")

        // 1. Aspiration is not contrastive in casual Tanglish spelling: kh->k, th->t,
        //    ch->c, sh->s, ph->p. Drop an "h" that follows a consonant.
        s = buildString(s.length) {
            for ((index, c) in s.withIndex()) {
                if (c == 'h' && index > 0) {
                    val previous = s[index - 1]
                    if (previous.isLetter() && previous !in VOWELS) continue
                }
                append(c)
            }
        }

        // 2. Collapse consonants that speakers use interchangeably.
        s = s.map {
            when (it) {
                'c' -> 'k'
                'w' -> 'v'
                'f' -> 'p'
                else -> it
            }
        }.joinToString("")

        // 3. Vowel length is not reliable ("paata"/"pata", "ee"/"i", "oo"/"u").
        s = s.replace("ee", "i")
            .replace("ie", "i")
            .replace("oo", "u")
            .replace("ou", "u")
            .replace("aa", "a")
            // "chei" and "chey" are one sound; so are "sarei" and "sarey".
            .replace("ei", "ey")

        // 4. Gemination carries no meaning for us: "pettu" -> "petu", "amma" -> "ama".
        return buildString(s.length) {
            for (c in s) {
                if (isNotEmpty() && last() == c) continue
                append(c)
            }
        }
    }
}

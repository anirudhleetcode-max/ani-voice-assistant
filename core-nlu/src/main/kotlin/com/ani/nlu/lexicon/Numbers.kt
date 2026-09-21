package com.ani.nlu.lexicon

import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.Token

/**
 * Spoken numbers in Telugu and English.
 *
 * "repu edu gantalaki lepu" and "wake me at 7 tomorrow" have to reach the same alarm.
 * Digits are handled by the tokenizer; this table covers the spelled-out forms.
 */
object Numbers {

    private val words: Map<String, Int> = buildMap {
        fun reg(value: Int, vararg spellings: String) {
            for (spelling in spellings) put(PhoneticKey.of(spelling), value)
        }

        // Telugu
        reg(0, "sunna", "zero")
        reg(1, "okati", "oka", "okka", "one")
        reg(2, "rendu", "rendo", "two")
        reg(3, "moodu", "mudu", "three")
        reg(4, "naalugu", "nalugu", "four")
        reg(5, "aidu", "ayidu", "five")
        reg(6, "aaru", "aru", "six")
        reg(7, "edu", "edhu", "seven")
        reg(8, "enimidi", "eight")
        reg(9, "tommidi", "tomidi", "nine")
        reg(10, "padi", "ten")
        reg(11, "padakondu", "eleven")
        reg(12, "pannendu", "panendu", "twelve")
        reg(13, "padamudu", "thirteen")
        reg(14, "padnalugu", "fourteen")
        reg(15, "padihenu", "padihenu", "fifteen")
        reg(16, "padaaru", "sixteen")
        reg(17, "padihedu", "seventeen")
        reg(18, "padhenimidi", "eighteen")
        reg(19, "pandommidi", "nineteen")
        reg(20, "iravai", "twenty")
        reg(25, "irvai aidu", "twentyfive")
        reg(30, "muppai", "thirty")
        reg(40, "nalabhai", "nalabai", "forty")
        reg(45, "fortyfive")
        reg(50, "yaabhai", "yabhai", "fifty")
        reg(60, "aravai", "sixty")
    }

    /** All spellings the lexicon should tag as number words. */
    fun allWords(): List<String> = words.keys.toList()

    /** Numeric value of a spelled-out number, or null. */
    fun valueOfKey(key: String): Int? = words[key]

    /**
     * Numeric value of [token] whether it arrived as digits ("7") or as a word
     * ("edu" / "seven").
     */
    fun valueOf(token: Token): Int? =
        if (token.isNumeric) token.text.toIntOrNull() else words[token.key]

    /** True when [token] is any kind of number. */
    fun isNumber(token: Token): Boolean = valueOf(token) != null
}

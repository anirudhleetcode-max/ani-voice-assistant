package com.ani.nlu.lexicon

import com.ani.nlu.text.Language
import com.ani.nlu.text.NormalizedText
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.Transliterator

/**
 * Decides whether an utterance was Telugu, English or the mix the target user actually
 * speaks.
 *
 * This is not academic language ID. Its job is narrow and practical: pick the voice and
 * response style Ani replies in. Getting it slightly wrong costs a slightly odd-sounding
 * sentence, never a wrong action — intent classification is language-agnostic by design.
 */
object LanguageDetector {

    /**
     * Words that are unambiguously Telugu once romanised. Kept separate from the main
     * lexicon because many lexicon entries (call, play, open, battery) are English
     * loanwords that Telugu speakers use constantly and therefore prove nothing.
     */
    private val teluguMarkers: Set<String> = setOf(
        "chey", "cheyyi", "cheyi", "chesi", "chestha", "chesthunna", "chesina",
        "pettu", "petti", "pettandi", "pettuko",
        "pampu", "pampinchu", "pampali",
        "vachayi", "vacchayi", "vachindi", "vastundi", "unnayi", "undi", "ledu", "leda",
        "cheppu", "chepu", "cheppandi", "chaduvu", "chadavu",
        "amma", "nanna", "akka", "anna", "thammudu", "chelli",
        "nenu", "naaku", "nannu", "naa", "nuvvu", "meeru",
        "repu", "ivala", "ninna", "ippudu", "malli", "tarvata",
        "enti", "emiti", "entha", "ekkada", "eppudu", "enduku", "evaru",
        "paata", "paatalu", "roju", "ganta", "gantalu", "nimisham", "nimishalu",
        "avunu", "vaddu", "kaadu", "sare", "gurthu", "lepu", "levali",
        "teruvu", "aapu", "penchu", "taggu", "veliginchu", "arpu",
        "chupinchu", "choodu", "vetuku", "piluvu", "kottu",
        "udayam", "sayantram", "ratri", "madhyanam", "anni", "konchem",
        "ra", "rey", "orey", "oye", "ani", "ane", "kada", "mari"
    ).map { PhoneticKey.of(it) }.toSet()

    /**
     * English words that really do indicate English.
     *
     * Deliberately short. Telugu speakers borrow English content words wholesale — "call",
     * "message", "battery", "play", "song", "morning" are all ordinary Telugu speech in
     * Hyderabad — so counting them as evidence would report almost every Telugu sentence
     * as mixed. What survives is grammar: articles, pronouns, auxiliaries, and the day
     * words Telugu has its own versions of ("repu", "ivala").
     */
    private val englishMarkers: Set<String> = setOf(
        "the", "is", "are", "was", "were", "and", "but", "with", "from", "about",
        "my", "me", "you", "your", "he", "she", "we", "they", "this", "that", "some",
        "what", "when", "where", "why", "who", "how", "which",
        "can", "could", "would", "should", "will", "did", "does",
        "please", "tomorrow", "today", "yesterday"
    ).map { PhoneticKey.of(it) }.toSet()

    /**
     * Classifies [text]. Native Telugu script short-circuits to [Language.TELUGU]
     * because there is nothing to infer.
     */
    fun detect(text: NormalizedText): Language {
        if (text.isEmpty) return Language.UNKNOWN
        if (text.hadTeluguScript && Transliterator.isPredominantlyTeluguScript(text.original)) {
            return Language.TELUGU
        }

        var telugu = 0
        var english = 0
        for (token in text.tokens) {
            val stem = PhoneticKey.of(Morphology.stripCaseSuffix(token.text))
            val isTelugu = token.key in teluguMarkers || stem in teluguMarkers
            val isEnglish = token.key in englishMarkers || stem in englishMarkers
            // A word counted for both sides proves nothing; skip it.
            when {
                isTelugu && !isEnglish -> telugu++
                isEnglish && !isTelugu -> english++
            }
        }

        return when {
            telugu == 0 && english == 0 -> Language.UNKNOWN
            telugu > 0 && english > 0 -> Language.MIXED
            telugu > 0 -> Language.TELUGU
            else -> Language.ENGLISH
        }
    }

    /**
     * The language Ani should *reply* in, given what it heard and what the user chose
     * in settings.
     */
    fun replyLanguage(detected: Language, preference: Language?): Language = when (preference) {
        null, Language.UNKNOWN -> if (detected == Language.UNKNOWN) Language.MIXED else detected
        else -> preference
    }
}

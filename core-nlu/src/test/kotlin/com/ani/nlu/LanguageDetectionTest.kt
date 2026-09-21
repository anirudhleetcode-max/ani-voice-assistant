package com.ani.nlu

import com.ani.nlu.lexicon.LanguageDetector
import com.ani.nlu.text.Language
import com.ani.nlu.text.TextNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageDetectionTest {

    private fun detect(text: String): Language =
        LanguageDetector.detect(TextNormalizer.normalize(text))

    @Test
    fun `native script is Telugu`() {
        assertEquals(Language.TELUGU, detect("అమ్మకి కాల్ చెయ్"))
    }

    @Test
    fun `romanised Telugu is Telugu`() {
        assertEquals(Language.TELUGU, detect("amma ki call chey"))
        assertEquals(Language.TELUGU, detect("em messages vachayi"))
    }

    @Test
    fun `plain English is English`() {
        assertEquals(Language.ENGLISH, detect("what messages did i get"))
        assertEquals(Language.ENGLISH, detect("please call my dad"))
    }

    @Test
    fun `code switching is reported as mixed`() {
        assertEquals(Language.MIXED, detect("tomorrow morning 7 ki nannu lepu"))
        assertEquals(Language.MIXED, detect("my amma ki call chey"))
    }

    @Test
    fun `loanwords alone do not decide the language`() {
        // "battery" is used identically in both languages, so it proves nothing.
        assertEquals(Language.UNKNOWN, detect("battery"))
    }

    @Test
    fun `an explicit preference overrides detection`() {
        assertEquals(Language.ENGLISH, LanguageDetector.replyLanguage(Language.TELUGU, Language.ENGLISH))
        assertEquals(Language.TELUGU, LanguageDetector.replyLanguage(Language.ENGLISH, Language.TELUGU))
    }

    @Test
    fun `with no preference Ani answers in the language it heard`() {
        assertEquals(Language.TELUGU, LanguageDetector.replyLanguage(Language.TELUGU, null))
        assertEquals(Language.MIXED, LanguageDetector.replyLanguage(Language.UNKNOWN, null))
    }
}

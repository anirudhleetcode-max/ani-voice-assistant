package com.ani.nlu

import com.ani.nlu.text.Fuzzy
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.TextNormalizer
import com.ani.nlu.text.Transliterator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextNormalizationTest {

    @Test
    fun `spelling variants of the same word collapse onto one key`() {
        val groups = listOf(
            listOf("chey", "cheyy", "chei"),
            listOf("call", "kaal", "caal"),
            listOf("phone", "fone", "phon e".replace(" ", "")),
            listOf("vachayi", "vacchayi", "vachchayi"),
            listOf("pettu", "petu", "pettu"),
            listOf("paata", "pata", "paataa"),
            listOf("whatsapp", "watsapp", "vatsapp"),
            listOf("chesthunna", "chestunna", "chesthunnaa"),
            listOf("gurthu", "gurtu", "gurthu")
        )
        for (group in groups) {
            val keys = group.map { PhoneticKey.of(it) }.toSet()
            assertEquals(1, keys.size, "these should fold together: $group -> $keys")
        }
    }

    @Test
    fun `words that mean different things keep different keys`() {
        // "singh" must not collide with "song", or every Arijit Singh request breaks.
        assertTrue(PhoneticKey.of("singh") != PhoneticKey.of("song"))
        assertTrue(PhoneticKey.of("aapu") != PhoneticKey.of("open"))
        assertTrue(PhoneticKey.of("amma") != PhoneticKey.of("anna"))
    }

    @Test
    fun `expressive lengthening is collapsed`() {
        assertEquals("rey", TextNormalizer.collapseElongation("reyyyy"))
        assertEquals("sare", TextNormalizer.collapseElongation("sarrreee"))
        // Doubles are meaningful spelling and survive this stage.
        assertEquals("amma", TextNormalizer.collapseElongation("amma"))
    }

    @Test
    fun `punctuation and case are stripped`() {
        val text = TextNormalizer.normalize("Rey!! Amma-ki  CALL chey??")
        assertEquals("rey amma ki call chey", text.normalized)
        assertEquals(5, text.tokens.size)
    }

    @Test
    fun `Telugu script transliterates to the Tanglish people type`() {
        assertEquals("amma", Transliterator.transliterate("అమ్మ"))
        assertEquals("enta", Transliterator.transliterate("ఎంత"))
        assertEquals("repu", Transliterator.transliterate("రేపు"))
        assertEquals("chey", Transliterator.transliterate("చెయ్"))
    }

    @Test
    fun `mixed script leaves Latin words untouched`() {
        val mixed = Transliterator.transliterate("Spotify లో play చెయ్")
        assertEquals("Spotify lo play chey", mixed)
    }

    @Test
    fun `script and Latin spellings of a word produce the same key`() {
        val fromScript = PhoneticKey.of(Transliterator.transliterate("అమ్మ"))
        assertEquals(PhoneticKey.of("amma"), fromScript)
    }

    @Test
    fun `fuzzy tolerance is tight enough to keep short words apart`() {
        assertEquals(0, Fuzzy.toleranceFor(4))
        assertEquals(0, Fuzzy.toleranceFor(5))
        assertEquals(1, Fuzzy.toleranceFor(7))
        assertTrue(!Fuzzy.matches("sing", "song"))
        assertTrue(Fuzzy.matches("notification", "notificaton"))
    }

    @Test
    fun `normalizing blank input yields the empty result`() {
        assertTrue(TextNormalizer.normalize("").isEmpty)
        assertTrue(TextNormalizer.normalize("   \n ").isEmpty)
        assertTrue(TextNormalizer.normalize("!!!").isEmpty)
    }
}

package com.ani.nlu

import com.ani.nlu.dialog.WakeWordMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WakeWordTest {

    private val defaults = WakeWordMatcher()

    @Test
    fun `default phrases are recognised`() {
        for (utterance in listOf("rey", "Rey", "REY", "reyyy", "ani", "hey ani", "orey", "oi ani")) {
            assertTrue(defaults.match(utterance).matched, "\"$utterance\" should wake Ani")
        }
    }

    @Test
    fun `the wake phrase is stripped from the command`() {
        val match = defaults.match("rey amma ki call chey")
        assertTrue(match.matched)
        assertEquals("amma ki call chey", match.remainder.normalized)
    }

    @Test
    fun `a multi word phrase strips all of its words`() {
        val match = defaults.match("hey ani battery entha undi")
        assertTrue(match.matched)
        assertEquals("battery entha undi", match.remainder.normalized)
    }

    @Test
    fun `the longest matching phrase wins`() {
        assertEquals("hey ani", defaults.match("hey ani open whatsapp").phrase)
    }

    @Test
    fun `a bare wake word is detected as such`() {
        assertTrue(defaults.isBareWake("rey"))
        assertTrue(defaults.isBareWake("Rey!"))
        assertTrue(!defaults.isBareWake("rey call amma"))
    }

    @Test
    fun `the wake phrase is user configurable`() {
        val custom = WakeWordMatcher(listOf("oye ani", "chinna"))
        assertTrue(custom.match("chinna battery entha").matched)
        assertTrue(custom.match("oye ani battery entha").matched)
        // The shipped defaults no longer apply once the user replaces them.
        assertTrue(!custom.match("rey battery entha").matched)
    }

    @Test
    fun `the phrase has to lead the utterance`() {
        // "rey" buried in the middle of a sentence is just a word, not an activation.
        assertTrue(!defaults.match("battery rey").matched)
        assertTrue(!defaults.match("amma ki call chey rey").matched)
    }

    @Test
    fun `low sensitivity requires an exact phonetic match`() {
        val strict = WakeWordMatcher(listOf("rey"), sensitivity = 0.0)
        assertTrue(strict.match("rey").matched)
        assertTrue(!strict.match("grey").matched)
    }

    @Test
    fun `high sensitivity forgives one edit on longer phrases`() {
        val loose = WakeWordMatcher(listOf("chinna"), sensitivity = 0.9)
        assertTrue(loose.match("chinnaa battery").matched)
    }

    @Test
    fun `text without a wake phrase is passed through untouched`() {
        val match = defaults.match("battery entha undi")
        assertTrue(!match.matched)
        assertEquals("battery entha undi", match.remainder.normalized)
    }
}

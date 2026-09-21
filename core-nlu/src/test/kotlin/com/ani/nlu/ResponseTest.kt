package com.ani.nlu

import com.ani.nlu.response.Persona
import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.response.Responses
import com.ani.nlu.response.TimePhrasing
import com.ani.nlu.text.Language
import com.ani.nlu.text.TextNormalizer
import com.ani.nlu.time.TeluguTimeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseTest {

    private val friendly = ResponseStyle(language = Language.TELUGU, persona = Persona.FRIENDLY)
    private val professional = ResponseStyle(language = Language.TELUGU, persona = Persona.PROFESSIONAL)
    private val english = ResponseStyle(language = Language.ENGLISH)

    @Test
    fun `the default voice sounds like a friend, not a status message`() {
        assertEquals("Cheppu ra.", Responses.wakeAcknowledgement(friendly))
        assertEquals("Amma ki call chesthunna ra.", Responses.callingContact("Amma", friendly))
        assertEquals("Spotify open chesa ra.", Responses.openedApp("Spotify", friendly))
    }

    @Test
    fun `the professional persona drops the slang particle`() {
        assertTrue(!Responses.callingContact("Amma", professional).contains(" ra"))
        assertEquals("Cheppandi.", Responses.wakeAcknowledgement(professional))
    }

    @Test
    fun `English replies are plain English`() {
        assertEquals("Calling Amma.", Responses.callingContact("Amma", english))
        assertEquals("Opened Spotify.", Responses.openedApp("Spotify", english))
    }

    @Test
    fun `failure messages never blame the user or leak internals`() {
        val messages = listOf(
            Responses.didNotCatch(friendly),
            Responses.didNotUnderstand(friendly),
            Responses.dontKnowHow(friendly),
            Responses.noInternet(friendly),
            Responses.appNotInstalled("Spotify", friendly),
            Responses.notificationAccessMissing(friendly)
        )
        for (message in messages) {
            assertTrue(message.isNotBlank())
            assertTrue(!message.contains("Exception"), message)
            assertTrue(!message.contains("null"), message)
        }
    }

    @Test
    fun `handing a message off is not described as sending it`() {
        val handoff = Responses.messageHandedOff("Rahul", "WhatsApp", friendly)
        assertTrue(handoff.contains("ready"), handoff)
        assertTrue(!handoff.contains("pampesa"), "must not claim the message went out: $handoff")
    }

    @Test
    fun `opening a search is not described as playing`() {
        val opened = Responses.openedMusicSearch("Kesariya", "Spotify", friendly)
        assertTrue(!opened.contains("play chesthunna"), opened)
        assertTrue(opened.contains("search"), opened)
    }

    @Test
    fun `an ambiguous hour is asked about, not guessed`() {
        assertEquals("Morning 7 aa, evening 7 aa?", Responses.askMorningOrEvening(7, friendly))
    }

    @Test
    fun `times are read back in the user's own words`() {
        fun phrase(text: String, style: ResponseStyle = friendly) =
            TimePhrasing.describe(TeluguTimeParser.parse(TextNormalizer.normalize(text))!!, style)

        assertEquals("repu morning 7 ki", phrase("repu morning 7 ki"))
        assertEquals("evening 6 ki", phrase("ivala evening 6 ki").removePrefix("ivala "))
        assertEquals("20 nimishalu", phrase("20 minutes lo"))
        assertEquals("20 minutes", phrase("20 minutes lo", english))
        assertEquals("1 ganta", phrase("1 hour tarvata"))
    }

    @Test
    fun `the alarm confirmation repeats the time back`() {
        val spec = TeluguTimeParser.parse(TextNormalizer.normalize("repu morning 7 ki"))!!
        val said = Responses.alarmSet(spec, friendly)
        assertTrue(said.contains("repu morning 7 ki"), said)
    }

    @Test
    fun `battery replies mention charging only when charging`() {
        assertEquals("62 percent undi ra.", Responses.batteryLevel(62, charging = false, friendly))
        assertTrue(Responses.batteryLevel(62, charging = true, friendly).contains("charge"))
    }
}

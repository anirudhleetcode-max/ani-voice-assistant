package com.ani.assistant.settings

import com.ani.assistant.data.settings.AniSettings
import com.ani.nlu.dialog.WakeWordMatcher
import com.ani.nlu.response.Persona
import com.ani.nlu.text.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniSettingsTest {

    @Test
    fun `defaults are the safe ones`() {
        val defaults = AniSettings.DEFAULT
        // Nothing that reads private data is on until the user turns it on.
        assertTrue(defaults.allowedNotificationPackages.isEmpty())
        assertTrue(defaults.hideNotificationsOnLockScreen)
        assertTrue(!defaults.allowLockScreenActivation)
        assertTrue(!defaults.onboardingCompleted)
    }

    @Test
    fun `clearing the wake phrase list falls back to the shipped defaults`() {
        val settings = AniSettings.DEFAULT.copy(wakePhrases = emptyList())
        assertEquals(WakeWordMatcher.DEFAULT_PHRASES, settings.effectiveWakePhrases())
    }

    @Test
    fun `blank wake phrases are ignored`() {
        val settings = AniSettings.DEFAULT.copy(wakePhrases = listOf("rey", "  ", ""))
        assertEquals(listOf("rey"), settings.effectiveWakePhrases())
    }

    @Test
    fun `with no language preference Ani answers in whatever it heard`() {
        val settings = AniSettings.DEFAULT.copy(language = null)
        assertEquals(Language.TELUGU, settings.responseStyle(Language.TELUGU).language)
        assertEquals(Language.ENGLISH, settings.responseStyle(Language.ENGLISH).language)
    }

    @Test
    fun `an explicit language preference wins over what was heard`() {
        val settings = AniSettings.DEFAULT.copy(language = Language.ENGLISH)
        assertEquals(Language.ENGLISH, settings.responseStyle(Language.TELUGU).language)
    }

    @Test
    fun `an unrecognised utterance still gets a speakable style`() {
        val style = AniSettings.DEFAULT.responseStyle(Language.UNKNOWN)
        assertEquals(Language.MIXED, style.language)
        assertTrue(style.speaksTelugu)
    }

    @Test
    fun `the default persona uses the casual particle`() {
        assertEquals(Persona.FRIENDLY, AniSettings.DEFAULT.persona)
        assertEquals(" ra", AniSettings.DEFAULT.responseStyle(Language.TELUGU).particle)
    }
}

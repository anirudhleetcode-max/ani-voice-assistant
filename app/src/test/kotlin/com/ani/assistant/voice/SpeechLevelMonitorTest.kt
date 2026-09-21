package com.ani.assistant.voice

import com.ani.assistant.voice.audio.AudioVerdict
import com.ani.assistant.voice.audio.SpeechLevelMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling "you were too quiet" apart from "nothing reached the microphone".
 *
 * These are the two failures that both used to end in "sarigga vinapadatledu ra". They
 * need completely different responses: one asks the user to speak up, the other means
 * another recorder had the microphone and nothing the user does will help.
 */
class SpeechLevelMonitorTest {

    private fun monitorOf(vararg readings: Float) = SpeechLevelMonitor().apply {
        readings.forEach(::onRms)
    }

    @Test
    fun `a flat line at the floor is no audio, not a quiet speaker`() {
        // What a second recorder over a live one actually looks like: the level never
        // moves at all, because there is no stream behind it.
        val snapshot = monitorOf(-2f, -2f, -2f, -2f, -2f, -2f, -2f, -2f).snapshot()

        assertEquals(AudioVerdict.NO_AUDIO, snapshot.verdict)
    }

    @Test
    fun `digital zero across the attempt is no audio`() {
        val snapshot = monitorOf(0f, 0f, 0f, 0f, 0f, 0f).snapshot()

        assertEquals(AudioVerdict.NO_AUDIO, snapshot.verdict)
    }

    @Test
    fun `a whisper moves the needle and is reported as low, not absent`() {
        // A very quiet voice still lifts the level off the floor. This is the case that
        // must never be confused with the one above.
        val snapshot = monitorOf(-1f, 0.5f, 1.4f, 2.2f, 1.1f, 0.4f, 1.8f, -0.5f).snapshot()

        assertEquals(AudioVerdict.LOW_AUDIO, snapshot.verdict)
    }

    @Test
    fun `normal speech is normal`() {
        val snapshot = monitorOf(-1f, 2f, 5.5f, 6.8f, 4.2f, 7.1f, 3.3f, 0.5f).snapshot()

        assertEquals(AudioVerdict.NORMAL_AUDIO, snapshot.verdict)
    }

    @Test
    fun `sustained maximum level reads as clipped`() {
        val snapshot = monitorOf(9.8f, 10f, 9.9f, 10f, 9.7f, 10f, 9.6f, 10f).snapshot()

        assertEquals(AudioVerdict.CLIPPED, snapshot.verdict)
    }

    @Test
    fun `one loud peak in otherwise normal speech is not clipping`() {
        val snapshot = monitorOf(1f, 3f, 10f, 4f, 2.5f, 5f, 3.5f, 2f).snapshot()

        assertEquals(AudioVerdict.NORMAL_AUDIO, snapshot.verdict)
    }

    @Test
    fun `too few readings says unknown rather than guessing`() {
        val snapshot = monitorOf(3f, 4f).snapshot()

        assertEquals(AudioVerdict.UNKNOWN, snapshot.verdict)
        assertEquals(0, snapshot.readings)
    }

    @Test
    fun `NaN readings from an OEM recognizer are ignored`() {
        // Some builds emit NaN before the first buffer arrives. One of those must not
        // poison min, max or the mean.
        val snapshot = monitorOf(Float.NaN, 2f, 5f, Float.NaN, 6f, 4f, 3f, 5.5f).snapshot()

        assertEquals(6, snapshot.readings)
        assertEquals(AudioVerdict.NORMAL_AUDIO, snapshot.verdict)
        assertTrue(snapshot.meanDb.isFinite())
    }

    @Test
    fun `reset clears the previous attempt`() {
        val monitor = monitorOf(9.9f, 10f, 10f, 9.8f, 10f, 10f)
        monitor.reset()
        listOf(0f, 0f, 0f, 0f, 0f, 0f).forEach(monitor::onRms)

        assertEquals(AudioVerdict.NO_AUDIO, monitor.snapshot().verdict)
    }

    @Test
    fun `the description carries levels and never a transcript`() {
        val description = monitorOf(1f, 2f, 3f, 4f, 5f, 6f).snapshot().describe()

        assertTrue(description.contains("verdict="))
        assertTrue(description.contains("readings=6"))
    }
}

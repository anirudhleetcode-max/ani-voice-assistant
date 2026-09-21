package com.ani.assistant.voice

import com.ani.assistant.voice.audio.AudioVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Ani says when it heard nothing, and how many times it tries first.
 *
 * The rule being pinned: a failure Ani caused is never reported as a failure the user
 * caused. Telling someone to repeat themselves when the microphone was never available
 * wastes their breath and hides the bug.
 */
class RecognitionRetryPolicyTest {

    private val first = 1
    private val last = RecognitionRetryPolicy.MAX_ATTEMPTS

    @Test
    fun `no audio at all is a microphone problem, not a diction problem`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.NOT_UNDERSTOOD,
            verdict = AudioVerdict.NO_AUDIO,
            attempt = last
        )

        assertFalse(outcome.retry)
        assertEquals(FailureMessage.MICROPHONE_BLOCKED, outcome.message)
    }

    @Test
    fun `no audio is retried once in silence before it is announced`() {
        // Re-acquiring the microphone can genuinely fix it, so try — but quietly, and
        // only once.
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.NOT_UNDERSTOOD,
            verdict = AudioVerdict.NO_AUDIO,
            attempt = first
        )

        assertTrue(outcome.retry)
        assertEquals(FailureMessage.NONE, outcome.message)
    }

    @Test
    fun `audio arrived but the words did not resolve, so ask once and listen again`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.NOT_UNDERSTOOD,
            verdict = AudioVerdict.NORMAL_AUDIO,
            attempt = first
        )

        assertTrue(outcome.retry)
        assertEquals(FailureMessage.DID_NOT_CATCH, outcome.message)
    }

    @Test
    fun `the second empty attempt asks differently and then stops`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.NOT_UNDERSTOOD,
            verdict = AudioVerdict.NORMAL_AUDIO,
            attempt = last
        )

        assertFalse(outcome.retry)
        assertEquals(FailureMessage.SAY_IT_AGAIN, outcome.message)
    }

    @Test
    fun `a quiet speaker is still asked to repeat, not told the microphone is broken`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.NOT_UNDERSTOOD,
            verdict = AudioVerdict.LOW_AUDIO,
            attempt = last
        )

        assertEquals(FailureMessage.SAY_IT_AGAIN, outcome.message)
    }

    @Test
    fun `silence is never announced`() {
        // The wake word fired on something that was not the user, or they walked off.
        // Narrating that is nagging.
        for (attempt in 1..last) {
            val outcome = RecognitionRetryPolicy.decide(
                error = SpeechError.NO_SPEECH,
                verdict = AudioVerdict.NORMAL_AUDIO,
                attempt = attempt
            )
            assertEquals(FailureMessage.NONE, outcome.message)
        }
    }

    @Test
    fun `a missing permission is reported immediately and never retried`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.PERMISSION_MISSING,
            verdict = AudioVerdict.NO_AUDIO,
            attempt = first
        )

        assertFalse(outcome.retry)
        assertEquals(FailureMessage.PERMISSION_MISSING, outcome.message)
    }

    @Test
    fun `no recognizer installed is reported immediately`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.RECOGNIZER_UNAVAILABLE,
            verdict = AudioVerdict.UNKNOWN,
            attempt = first
        )

        assertFalse(outcome.retry)
        assertEquals(FailureMessage.NO_RECOGNIZER, outcome.message)
    }

    @Test
    fun `a network failure names the network`() {
        val outcome = RecognitionRetryPolicy.decide(
            error = SpeechError.NETWORK,
            verdict = AudioVerdict.NORMAL_AUDIO,
            attempt = first
        )

        assertEquals(FailureMessage.NO_INTERNET, outcome.message)
    }

    @Test
    fun `a busy recognizer is retried quietly then named`() {
        assertTrue(
            RecognitionRetryPolicy.decide(SpeechError.BUSY, AudioVerdict.UNKNOWN, first).retry
        )
        assertEquals(
            FailureMessage.MICROPHONE_BLOCKED,
            RecognitionRetryPolicy.decide(SpeechError.BUSY, AudioVerdict.UNKNOWN, last).message
        )
    }

    @Test
    fun `nothing retries past the attempt limit`() {
        val errors = listOf(
            SpeechError.NOT_UNDERSTOOD, SpeechError.NO_SPEECH, SpeechError.BUSY,
            SpeechError.MICROPHONE_UNAVAILABLE, SpeechError.OTHER, null
        )
        val verdicts = AudioVerdict.entries

        for (error in errors) {
            for (verdict in verdicts) {
                val outcome = RecognitionRetryPolicy.decide(error, verdict, last)
                assertFalse(
                    "retried past the limit for $error / $verdict",
                    outcome.retry
                )
            }
        }
    }
}

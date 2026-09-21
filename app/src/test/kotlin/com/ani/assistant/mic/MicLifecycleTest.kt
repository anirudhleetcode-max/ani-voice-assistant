package com.ani.assistant.mic

import com.ani.assistant.voice.mic.MicEvent
import com.ani.assistant.voice.mic.MicLifecycle
import com.ani.assistant.voice.mic.MicOwner
import com.ani.assistant.voice.mic.MicStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-to-action pipeline, tested without a phone.
 *
 * The reported failure was that a command works when typed or tapped and does nothing
 * when it arrives through "Rey". Two things can cause that, and only one of them is the
 * launcher: the other is the microphone. If the wake engine and `SpeechRecognizer` are
 * open at the same time, nothing throws — the second recorder just receives silence, the
 * command is never transcribed, and the failure looks like bad recognition.
 *
 * So these tests assert the two properties that matter and cannot be eyeballed:
 *
 *  - exactly one component ever holds the microphone, checked after *every* transition;
 *  - every way an exchange can die still ends with the wake engine coming back.
 */
class MicLifecycleTest {

    /** Records transitions so a test can assert the sequence, not just the end state. */
    private val seen = mutableListOf<Triple<MicStage, MicStage, MicEvent>>()
    private val mic = MicLifecycle { from, to, event -> seen += Triple(from, to, event) }

    /** Drives [events] and fails the moment two owners could coexist. */
    private fun run(vararg events: MicEvent) {
        for (event in events) {
            mic.on(event)
            assertTrue(
                "single-owner invariant broken at ${mic.stage} after $event",
                mic.holdsSingleOwnerInvariant()
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // FLOW 1: "Rey" -> "Annayya ki call chey" -> confirm -> the dialler opens
    // ---------------------------------------------------------------------------------

    @Test
    fun `a whole wake-initiated call runs and re-arms`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED,
            MicEvent.COMMAND_CAPTURED,
            // "Annayya ki call cheyyana?" — Ani asks, then listens again without a
            // second "Rey". This is the turn the confirmation loop used to die on.
            MicEvent.SPEECH_STARTED,
            MicEvent.SPEECH_FINISHED,
            MicEvent.COMMAND_LISTENING_STARTED,
            MicEvent.COMMAND_CAPTURED,
            // "Avunu" -> the dialler actually launches.
            MicEvent.ACTION_STARTED,
            MicEvent.ACTION_FINISHED,
            MicEvent.SPEECH_STARTED,
            MicEvent.SPEECH_FINISHED,
            MicEvent.EXCHANGE_ENDED
        )

        assertEquals(MicStage.WAKE_REARM, mic.stage)

        run(MicEvent.WAKE_ENGINE_STARTED)
        assertEquals(MicStage.WAKE_LISTENING, mic.stage)
        assertEquals(MicOwner.WAKE_ENGINE, mic.owner)
    }

    @Test
    fun `the command recorder never opens before the wake engine has let go`() {
        run(MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED)

        // The wake engine is still holding AudioRecord at this instant.
        assertEquals(MicOwner.WAKE_ENGINE, mic.owner)
        assertFalse(mic.canStartCommandRecognizer())

        // Asking anyway is refused rather than quietly allowed.
        assertFalse(mic.on(MicEvent.COMMAND_LISTENING_STARTED))
        assertEquals(MicStage.WAKE_DETECTED, mic.stage)

        run(MicEvent.WAKE_AUDIO_RELEASED)
        assertTrue(mic.canStartCommandRecognizer())
        assertEquals(MicOwner.NONE, mic.owner)
    }

    @Test
    fun `the wake engine cannot be re-armed while the command recorder is open`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED
        )

        assertEquals(MicOwner.COMMAND_RECOGNIZER, mic.owner)
        assertFalse(mic.canStartWakeEngine())
        assertFalse(mic.on(MicEvent.WAKE_ENGINE_STARTED))
        assertEquals(MicStage.COMMAND_LISTENING, mic.stage)
    }

    @Test
    fun `nothing holds the microphone while Ani is speaking`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED,
            MicEvent.COMMAND_CAPTURED,
            MicEvent.SPEECH_STARTED
        )

        // This is what stops "Rey" inside Ani's own reply from waking it again.
        assertEquals(MicStage.SPEAKING, mic.stage)
        assertEquals(MicOwner.NONE, mic.owner)
        assertFalse(mic.canStartWakeEngine())
    }

    // ---------------------------------------------------------------------------------
    // Nothing leaves Ani deaf
    // ---------------------------------------------------------------------------------

    @Test
    fun `a command session that times out still re-arms the wake engine`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED,
            // The user said nothing. The session times out and the exchange ends here.
            MicEvent.EXCHANGE_ENDED
        )

        assertEquals(MicStage.WAKE_REARM, mic.stage)
        assertTrue(mic.canStartWakeEngine())
    }

    @Test
    fun `a recognizer that never starts still re-arms the wake engine`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            // SpeechRecognizer never reported that it was listening — another app has the
            // microphone, or the service is missing. The exchange is abandoned.
            MicEvent.EXCHANGE_ENDED
        )

        assertEquals(MicStage.WAKE_REARM, mic.stage)
        assertTrue(mic.canStartWakeEngine())
    }

    @Test
    fun `an action that fails mid-launch still re-arms the wake engine`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED,
            MicEvent.COMMAND_CAPTURED,
            MicEvent.ACTION_STARTED,
            // The launcher threw. ACTION_FINISHED never arrived.
            MicEvent.EXCHANGE_ENDED
        )

        assertEquals(MicStage.WAKE_REARM, mic.stage)
        assertEquals(MicOwner.NONE, mic.owner)
    }

    @Test
    fun `an exchange can end from every stage it can reach`() {
        val stages = listOf(
            listOf(MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED),
            listOf(MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED, MicEvent.WAKE_AUDIO_RELEASED),
            listOf(
                MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED,
                MicEvent.WAKE_AUDIO_RELEASED, MicEvent.COMMAND_LISTENING_STARTED
            ),
            listOf(
                MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED,
                MicEvent.WAKE_AUDIO_RELEASED, MicEvent.COMMAND_LISTENING_STARTED,
                MicEvent.COMMAND_CAPTURED
            ),
            listOf(
                MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED,
                MicEvent.WAKE_AUDIO_RELEASED, MicEvent.COMMAND_LISTENING_STARTED,
                MicEvent.COMMAND_CAPTURED, MicEvent.SPEECH_STARTED
            ),
            listOf(
                MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED,
                MicEvent.WAKE_AUDIO_RELEASED, MicEvent.COMMAND_LISTENING_STARTED,
                MicEvent.COMMAND_CAPTURED, MicEvent.ACTION_STARTED
            )
        )

        for (path in stages) {
            val machine = MicLifecycle()
            path.forEach(machine::on)
            val reached = machine.stage
            assertTrue(
                "EXCHANGE_ENDED refused from $reached",
                machine.on(MicEvent.EXCHANGE_ENDED)
            )
            assertEquals("stuck after $reached", MicStage.WAKE_REARM, machine.stage)
            assertNotEquals(MicOwner.WAKE_ENGINE, machine.owner)
        }
    }

    // ---------------------------------------------------------------------------------

    @Test
    fun `stopping the service wins from anywhere`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED,
            MicEvent.SERVICE_STOPPED
        )

        assertEquals(MicStage.IDLE, mic.stage)
        assertEquals(MicOwner.NONE, mic.owner)
    }

    @Test
    fun `a repeated release is a harmless no-op, not a refusal`() {
        run(MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_DETECTED, MicEvent.WAKE_AUDIO_RELEASED)

        assertTrue(mic.on(MicEvent.WAKE_AUDIO_RELEASED))
        assertEquals(MicStage.WAKE_AUDIO_RELEASE, mic.stage)
    }

    @Test
    fun `ending an exchange that never began is refused`() {
        run(MicEvent.WAKE_ENGINE_STARTED)

        assertFalse(mic.on(MicEvent.EXCHANGE_ENDED))
        assertEquals(MicStage.WAKE_LISTENING, mic.stage)
        assertEquals("no exchange in progress", mic.lastRejection?.reason)
    }

    @Test
    fun `a wake heard while already awake cannot start a second exchange`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED,
            MicEvent.COMMAND_LISTENING_STARTED
        )

        assertFalse(mic.on(MicEvent.WAKE_DETECTED))
        assertEquals(MicStage.COMMAND_LISTENING, mic.stage)
    }

    @Test
    fun `every transition is reported exactly once`() {
        run(
            MicEvent.WAKE_ENGINE_STARTED,
            MicEvent.WAKE_DETECTED,
            MicEvent.WAKE_AUDIO_RELEASED
        )

        assertEquals(
            listOf(
                Triple(MicStage.IDLE, MicStage.WAKE_LISTENING, MicEvent.WAKE_ENGINE_STARTED),
                Triple(MicStage.WAKE_LISTENING, MicStage.WAKE_DETECTED, MicEvent.WAKE_DETECTED),
                Triple(
                    MicStage.WAKE_DETECTED,
                    MicStage.WAKE_AUDIO_RELEASE,
                    MicEvent.WAKE_AUDIO_RELEASED
                )
            ),
            seen
        )
    }
}

package com.ani.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a turn's seconds went.
 *
 * "Ani is slow" can mean endpointing, a networked recogniser, classification, a cold TTS
 * engine or audio playback. Those have nothing in common except how they feel, and
 * guessing between them is how the silence windows got widened to 2.5 s for accuracy and
 * then blamed for latency. The arithmetic that settles it is worth testing.
 */
class TurnTimelineTest {

    private var clock = 0L
    private val timeline = TurnTimeline { clock }

    private fun advance(by: Long) {
        clock += by
    }

    @Test
    fun `segments measure the gap between the stages they name`() {
        timeline.start()
        advance(40); timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(300); timeline.mark(TurnStage.FIRST_PARTIAL)
        advance(700); timeline.mark(TurnStage.END_OF_SPEECH)
        advance(1_200); timeline.mark(TurnStage.FINAL_TRANSCRIPT)
        advance(90); timeline.mark(TurnStage.NLU_COMPLETE)
        advance(10); timeline.mark(TurnStage.TTS_REQUESTED)
        advance(1_800); timeline.mark(TurnStage.TTS_FIRST_AUDIO)

        assertEquals(300L, timeline.between(TurnStage.COMMAND_LISTENING, TurnStage.FIRST_PARTIAL))
        // The endpointing cost, isolated: this is what the silence windows control.
        assertEquals(1_200L, timeline.between(TurnStage.END_OF_SPEECH, TurnStage.FINAL_TRANSCRIPT))
        assertEquals(90L, timeline.between(TurnStage.FINAL_TRANSCRIPT, TurnStage.NLU_COMPLETE))
        // A cold TTS engine, which is a completely different fix from either.
        assertEquals(1_800L, timeline.between(TurnStage.TTS_REQUESTED, TurnStage.TTS_FIRST_AUDIO))
    }

    @Test
    fun `a stage that never happened is absent rather than zero`() {
        // A turn where TTS never started is a different fact from one where it started
        // instantly. Reporting zero would hide the first behind the second.
        timeline.start()
        advance(50); timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(500); timeline.mark(TurnStage.FINAL_TRANSCRIPT)

        assertNull(timeline.between(TurnStage.TTS_REQUESTED, TurnStage.TTS_FIRST_AUDIO))
        assertFalse(timeline.describe().contains("ttsRequestToAudio"))
        assertTrue(timeline.describe().contains("listeningToFinal=500"))
    }

    @Test
    fun `the first occurrence of a stage wins`() {
        // Partials keep arriving. Only the first one is evidence of how fast the chain
        // came alive; the rest would quietly overwrite that with a later number.
        timeline.start()
        advance(100); timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(200); timeline.mark(TurnStage.FIRST_PARTIAL)
        advance(400); timeline.mark(TurnStage.FIRST_PARTIAL)

        assertEquals(200L, timeline.between(TurnStage.COMMAND_LISTENING, TurnStage.FIRST_PARTIAL))
    }

    @Test
    fun `marking before start begins the turn rather than being lost`() {
        timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(250)
        timeline.mark(TurnStage.FINAL_TRANSCRIPT)

        assertEquals(250L, timeline.between(TurnStage.COMMAND_LISTENING, TurnStage.FINAL_TRANSCRIPT))
    }

    @Test
    fun `total spans from the start to the last stage reached`() {
        timeline.start()
        advance(120); timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(2_000); timeline.mark(TurnStage.FINAL_TRANSCRIPT)
        advance(80); timeline.mark(TurnStage.NLU_COMPLETE)

        assertEquals(2_200L, timeline.total())
    }

    @Test
    fun `restarting clears the previous turn`() {
        timeline.start()
        advance(500); timeline.mark(TurnStage.FINAL_TRANSCRIPT)

        timeline.start()
        advance(30); timeline.mark(TurnStage.COMMAND_LISTENING)

        assertNull(timeline.at(TurnStage.FINAL_TRANSCRIPT))
        assertEquals(30L, timeline.sinceStart(TurnStage.COMMAND_LISTENING))
    }

    @Test
    fun `an untouched timeline says so instead of printing zeros`() {
        assertEquals("no stages recorded", timeline.describe())
        assertNull(timeline.total())
    }

    @Test
    fun `the snapshot is relative to the start of the turn`() {
        timeline.start()
        advance(100); timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(900); timeline.mark(TurnStage.FINAL_TRANSCRIPT)

        val snapshot = timeline.snapshot()

        assertEquals(100L, snapshot[TurnStage.COMMAND_LISTENING])
        assertEquals(1_000L, snapshot[TurnStage.FINAL_TRANSCRIPT])
    }

    @Test
    fun `the description carries no transcript, only durations`() {
        timeline.start()
        advance(10); timeline.mark(TurnStage.COMMAND_LISTENING)
        advance(20); timeline.mark(TurnStage.FINAL_TRANSCRIPT)

        val line = timeline.describe()

        assertTrue(line.all { it.isLetterOrDigit() || it in " =" })
    }
}

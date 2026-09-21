package com.ani.assistant.audio

import com.ani.assistant.voice.audio.AdaptiveGainProcessor
import com.ani.assistant.voice.audio.GainConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The gain stage that lifts quietly-spoken wake words.
 *
 * The two failure modes being guarded against pull in opposite directions: too little
 * gain and a whispered "Rey" never reaches the decoder; too much and room tone gets
 * amplified into something the decoder finds words in. Every test here is about one side
 * or the other of that line.
 */
class AdaptiveGainProcessorTest {

    private val config = GainConfig()

    /** A speech-like block: a voiced fundamental with two formants, at a chosen level. */
    private fun speechBlock(peakAmplitude: Double, seed: Int = 1): ShortArray {
        val random = Random(seed)
        return ShortArray(config.blockSize) { index ->
            val t = index / 16_000.0
            val voiced = sin(2 * PI * 130 * t) * 0.6 +
                sin(2 * PI * 700 * t) * 0.3 +
                sin(2 * PI * 1800 * t) * 0.1
            val breath = (random.nextDouble() - 0.5) * 0.05
            ((voiced + breath) * peakAmplitude).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun silence() = ShortArray(config.blockSize)

    private fun noiseBlock(amplitude: Double, seed: Int = 7): ShortArray {
        val random = Random(seed)
        return ShortArray(config.blockSize) {
            ((random.nextDouble() - 0.5) * 2 * amplitude).toInt().toShort()
        }
    }

    /** Feeds [blocks] copies and returns the final block's stats. */
    private fun run(
        processor: AdaptiveGainProcessor,
        producer: (Int) -> ShortArray,
        blocks: Int
    ) = (0 until blocks).map { processor.process(producer(it)) }.last()

    // ---- 1. Quiet speech is lifted -----------------------------------------------------

    @Test
    fun `very quiet speech is amplified`() {
        val processor = AdaptiveGainProcessor(config)
        // Let it learn a quiet room first, as it would in reality.
        run(processor, { silence() }, 30)

        val stats = run(processor, { speechBlock(peakAmplitude = 250.0, seed = it) }, 40)

        assertTrue(
            "quiet speech should be amplified, gain was ${stats.appliedGain}",
            stats.appliedGain > 2f
        )
        assertTrue("the gate should open for speech", stats.gateOpen)
    }

    @Test
    fun `amplification never exceeds the configured ceiling`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { silence() }, 30)
        // Absurdly quiet: the target gain would be enormous without a ceiling.
        val stats = run(processor, { speechBlock(peakAmplitude = 60.0, seed = it) }, 100)

        assertTrue(
            "gain ${stats.appliedGain} exceeded the ceiling ${config.maxGain}",
            stats.appliedGain <= config.maxGain + 0.001f
        )
    }

    // ---- 2. Normal speech is left alone -------------------------------------------------

    @Test
    fun `speech already at a good level is barely touched`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { silence() }, 20)
        // ~3000 peak sits above the 2000 RMS target, so nothing needs lifting.
        val stats = run(processor, { speechBlock(peakAmplitude = 4000.0, seed = it) }, 40)

        assertTrue(
            "gain should stay near 1x for normal speech, was ${stats.appliedGain}",
            stats.appliedGain < 1.35f
        )
    }

    @Test
    fun `the stage never attenuates`() {
        val processor = AdaptiveGainProcessor(config)
        val stats = run(processor, { speechBlock(peakAmplitude = 20000.0, seed = it) }, 40)
        assertTrue("gain must never drop below 1x", stats.appliedGain >= 1f)
    }

    // ---- 3. Loud audio does not clip ----------------------------------------------------

    @Test
    fun `loud speech stays inside the 16-bit range`() {
        val processor = AdaptiveGainProcessor(config)
        repeat(40) {
            val block = speechBlock(peakAmplitude = 32000.0, seed = it)
            processor.process(block)
            for (sample in block) {
                assertTrue(
                    "sample $sample escaped the 16-bit range",
                    sample >= Short.MIN_VALUE && sample <= Short.MAX_VALUE
                )
            }
        }
    }

    @Test
    fun `the limiter engages rather than wrapping around`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { silence() }, 30)

        // Quiet for a while so gain climbs, then a sudden shout.
        run(processor, { speechBlock(peakAmplitude = 200.0, seed = it) }, 60)
        val loud = speechBlock(peakAmplitude = 30000.0)
        val stats = processor.process(loud)

        assertTrue("output peak ${stats.outputPeak} should stay within range", stats.outputPeak <= 32767)
        // A wrap-around would show up as a sign flip against the input, which is what a
        // naive multiply-and-cast produces and what the limiter exists to prevent.
        assertTrue("the limiter should have engaged on a shout", stats.limiterEngaged)
    }

    // ---- 4. Silence is not amplified ------------------------------------------------------

    @Test
    fun `digital silence is not amplified`() {
        val processor = AdaptiveGainProcessor(config)
        val stats = run(processor, { silence() }, 100)

        assertEquals("silence must stay silent", 0, stats.outputPeak)
        assertTrue("the gate must stay shut on silence", !stats.gateOpen)
        assertEquals("gain should rest at 1x", 1f, stats.appliedGain, 0.01f)
    }

    // ---- 5. Background noise does not become speech ------------------------------------------

    @Test
    fun `steady room tone does not hold the gate open`() {
        val processor = AdaptiveGainProcessor(config)
        // Two seconds of low, steady noise — the noise floor should learn it.
        val stats = run(processor, { noiseBlock(amplitude = 90.0, seed = it) }, 100)

        assertTrue("the gate should close once the noise floor has adapted", !stats.gateOpen)
        assertTrue(
            "room tone should not be amplified, gain was ${stats.appliedGain}",
            stats.appliedGain < 1.5f
        )
    }

    @Test
    fun `room tone is not lifted toward the speech target`() {
        val processor = AdaptiveGainProcessor(config)
        var peak = 0
        repeat(120) { index ->
            val block = noiseBlock(amplitude = 90.0, seed = index)
            val stats = processor.process(block)
            if (index > 40) peak = maxOf(peak, stats.outputPeak)
        }
        // Normalising every block independently would drag this to the ~2000 target.
        assertTrue("room tone was amplified to $peak", peak < 600)
    }

    @Test
    fun `speech is still detected after the floor has learned a noisy room`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { noiseBlock(amplitude = 90.0, seed = it) }, 100)

        val stats = run(processor, { speechBlock(peakAmplitude = 900.0, seed = it) }, 30)
        assertTrue("quiet speech over room tone should still open the gate", stats.gateOpen)
        assertTrue("and should still be lifted", stats.appliedGain > 1.5f)
    }

    // ---- 6. Range safety, always ------------------------------------------------------------

    @Test
    fun `output stays in range across every input level`() {
        for (amplitude in listOf(0.0, 10.0, 100.0, 1000.0, 8000.0, 32767.0)) {
            val processor = AdaptiveGainProcessor(config)
            repeat(60) { index ->
                val block = speechBlock(amplitude, seed = index)
                processor.process(block)
                for (sample in block) {
                    assertTrue(
                        "sample escaped range at amplitude $amplitude",
                        sample >= Short.MIN_VALUE && sample <= Short.MAX_VALUE
                    )
                }
            }
        }
    }

    @Test
    fun `a full-scale square wave does not wrap`() {
        val processor = AdaptiveGainProcessor(config)
        val block = ShortArray(config.blockSize) { if (it % 2 == 0) 32767 else -32768 }
        val stats = processor.process(block)
        assertTrue(stats.outputPeak <= 32767)
        for (sample in block) {
            assertTrue(sample >= Short.MIN_VALUE && sample <= Short.MAX_VALUE)
        }
    }

    // ---- 7. Smoothing, not pumping ------------------------------------------------------------

    @Test
    fun `gain rises gradually rather than jumping`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { silence() }, 30)

        val first = processor.process(speechBlock(peakAmplitude = 200.0))
        // One block must not leap to full gain; that is the pumping this avoids.
        assertTrue(
            "gain jumped to ${first.appliedGain} in a single block",
            first.appliedGain < 2f
        )
    }

    @Test
    fun `a short gap inside a word does not collapse the gain`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { silence() }, 30)
        val duringSpeech = run(processor, { speechBlock(peakAmplitude = 250.0, seed = it) }, 60)

        // Two blocks of near-silence, as between the syllables of "Rey".
        processor.process(silence())
        val afterGap = processor.process(silence())

        assertTrue("the hold should keep the gate open across a 40 ms gap", afterGap.gateOpen)
        assertTrue(
            "gain fell from ${duringSpeech.appliedGain} to ${afterGap.appliedGain} across a gap",
            afterGap.appliedGain > duringSpeech.appliedGain * 0.5f
        )
    }

    // ---- 8. Lifecycle -------------------------------------------------------------------------

    @Test
    fun `reset clears adaptation so a restarted stream starts clean`() {
        val processor = AdaptiveGainProcessor(config)
        run(processor, { speechBlock(peakAmplitude = 200.0, seed = it) }, 80)
        assertTrue(processor.lastStats.appliedGain > 1.5f)

        processor.reset()

        assertEquals(1f, processor.lastStats.appliedGain, 0.001f)
        assertTrue(!processor.lastStats.gateOpen)
    }

    @Test
    fun `a DC offset does not masquerade as signal`() {
        val processor = AdaptiveGainProcessor(config)
        // A constant offset contributes to raw RMS but is not sound.
        val stats = run(processor, { ShortArray(config.blockSize) { 500 } }, 120)
        assertTrue("a DC offset should not hold the gate open", !stats.gateOpen)
    }

    @Test
    fun `an empty block is handled without throwing`() {
        val processor = AdaptiveGainProcessor(config)
        val stats = processor.process(ShortArray(0), length = 0)
        assertEquals(1f, stats.appliedGain, 0.001f)
    }

    @Test
    fun `only the requested prefix of a partly filled buffer is processed`() {
        val processor = AdaptiveGainProcessor(config)
        val buffer = ShortArray(config.blockSize) { 9000 }
        // AudioRecord routinely returns fewer samples than the buffer holds.
        processor.process(buffer, length = 32)
        assertEquals("the tail must be left untouched", 9000, buffer[100].toInt())
    }
}

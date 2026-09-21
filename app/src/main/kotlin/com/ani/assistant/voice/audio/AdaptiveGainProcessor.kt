package com.ani.assistant.voice.audio

import kotlin.math.abs
import kotlin.math.sqrt

/** What the gain stage did to one block, for diagnostics. Carries no audio. */
data class GainFrameStats(
    /** RMS of the block before gain, in PCM16 LSB (0..32767). */
    val inputRms: Float,
    /** Peak absolute sample after gain and limiting. */
    val outputPeak: Int,
    /** The slow-adapting background estimate, same units as [inputRms]. */
    val noiseFloorRms: Float,
    /** Gain actually applied to this block. 1.0 means untouched. */
    val appliedGain: Float,
    /** True when the block was loud enough to be treated as possible speech. */
    val gateOpen: Boolean,
    /** True when the limiter had to pull a sample back. */
    val limiterEngaged: Boolean
)

/**
 * Tuning for [AdaptiveGainProcessor]. All levels are PCM16 LSB, not dBFS.
 *
 * The defaults are chosen for one job: make a quietly-spoken "Rey" reach the recogniser
 * at a usable level without turning room tone into speech.
 */
data class GainConfig(
    /** Samples per processing block. 320 = 20 ms at 16 kHz. */
    val blockSize: Int = 320,

    /**
     * Level the gain stage aims for on speech. ~2000 LSB is about -24 dBFS, a comfortable
     * speech level that leaves plenty of headroom before the limiter.
     */
    val targetRms: Float = 2000f,

    /**
     * Hard ceiling on amplification. 8x is +18 dB.
     *
     * This is a deliberate limit rather than "as much as it takes". Gain applied after the
     * ADC cannot recover detail that was never captured — it amplifies the quantisation
     * noise along with the voice — so past a point more gain buys noise, not intelligibility.
     */
    val maxGain: Float = 8f,

    /**
     * Never below 1.0. The stage exists to lift quiet speech, not to ride loud speech
     * down; attenuating would throw away signal the recogniser is perfectly happy with.
     */
    val minGain: Float = 1f,

    /** How far above the noise floor a block must sit to count as possible speech. 2.0 = +6 dB. */
    val gateMarginOverNoise: Float = 2.0f,

    /**
     * Absolute floor, whatever the noise estimate says. Below this the block is within the
     * microphone's own noise and there is nothing to amplify but hiss.
     */
    val absoluteGateRms: Float = 30f,

    /** Per-block coefficient for the noise estimate falling toward a quieter background. */
    val noiseFloorFallCoefficient: Float = 0.25f,

    /** Per-block coefficient for it rising. Deliberately slow, so speech cannot raise it. */
    val noiseFloorRiseCoefficient: Float = 0.002f,

    /** Per-block coefficient for gain increasing. Slow, so noise is never ramped up. */
    val gainRiseCoefficient: Float = 0.06f,

    /** Per-block coefficient for gain decreasing. Fast, so a sudden loud word cannot clip. */
    val gainFallCoefficient: Float = 0.40f,

    /**
     * Blocks to hold the gate open after speech stops. 10 blocks = 200 ms.
     *
     * Without a hold, the gap between "R" and "ey" closes the gate and the gain collapses
     * mid-word, which is exactly the pumping that makes a word unrecognisable.
     */
    val gateHoldBlocks: Int = 10,

    /** Limiter threshold as a fraction of full scale. */
    val limiterThreshold: Float = 0.85f,

    /** Compression ratio above the threshold. 4 means 4 dB in for 1 dB out. */
    val limiterRatio: Float = 4f,

    /** Cut-off coefficient for the DC-removal high-pass. */
    val dcRemovalCoefficient: Float = 0.995f
) {
    init {
        require(blockSize > 0) { "blockSize must be positive" }
        require(maxGain >= minGain) { "maxGain must be at least minGain" }
        require(minGain >= 1f) { "minGain below 1.0 would attenuate speech" }
        require(limiterRatio >= 1f) { "limiterRatio must be at least 1.0" }
    }
}

/**
 * Lifts quiet speech to a level the recogniser can work with, without lifting the room
 * with it.
 *
 * **Why this exists.** Vosk's own `SpeechService` owns its `AudioRecord` and hands PCM
 * straight to the recogniser, so there was nowhere to put a gain stage. This processor
 * sits in the gap that replacing `SpeechService` opened up.
 *
 * **What it honestly does and does not do.** Kaldi — which Vosk wraps — already applies
 * cepstral mean normalisation, so the absolute level of the signal is *largely* normalised
 * away inside the feature pipeline. This stage is therefore not a magic sensitivity knob.
 * What it does buy is real but narrower: it keeps a very quiet utterance clear of every
 * fixed threshold between the microphone and the decoder, and it stops our own energy gate
 * from discarding the quietest frames. Gain applied after the ADC cannot recover detail
 * the converter never resolved, which is why [GainConfig.maxGain] is a deliberate ceiling
 * rather than "whatever it takes".
 *
 * **The design rule that keeps false positives down:** the gate controls *amplification*,
 * never *passage*. Every block reaches the recogniser either way. A gate that dropped
 * audio would be the fastest way to destroy the quiet speech this is meant to rescue,
 * and a gate that amplified everything would hand the recogniser a room full of
 * loud hiss to find words in.
 *
 * Not thread-safe; it holds per-stream state and belongs to one capture loop.
 */
class AdaptiveGainProcessor(private val config: GainConfig = GainConfig()) {

    private var noiseFloorRms: Float = config.absoluteGateRms
    private var currentGain: Float = 1f
    private var gateHoldRemaining: Int = 0

    /** Running DC estimate for the high-pass. */
    private var dcEstimate: Float = 0f

    /** Last computed stats, for diagnostics. */
    var lastStats: GainFrameStats = GainFrameStats(0f, 0, config.absoluteGateRms, 1f, false, false)
        private set

    /** Clears all adaptive state. Call whenever capture restarts on a new stream. */
    fun reset() {
        noiseFloorRms = config.absoluteGateRms
        currentGain = 1f
        gateHoldRemaining = 0
        dcEstimate = 0f
        lastStats = GainFrameStats(0f, 0, config.absoluteGateRms, 1f, false, false)
    }

    /**
     * Processes [length] samples of [samples] in place.
     *
     * @return stats for the block, for diagnostics. Never contains audio.
     */
    fun process(samples: ShortArray, length: Int = samples.size): GainFrameStats {
        if (length <= 0) return lastStats

        // 1. Remove any DC bias. A constant offset contributes to RMS without being
        //    sound, which would hold the gate open on a perfectly silent microphone.
        var sumOfSquares = 0.0
        for (index in 0 until length) {
            dcEstimate += (samples[index] - dcEstimate) * (1f - config.dcRemovalCoefficient)
            val centred = samples[index] - dcEstimate
            sumOfSquares += (centred * centred).toDouble()
        }
        val inputRms = sqrt(sumOfSquares / length).toFloat()

        // 2. Track the background. Falls quickly toward a quieter room, rises very slowly,
        //    so a burst of speech cannot drag the estimate up and close the gate on itself.
        val coefficient = if (inputRms < noiseFloorRms) {
            config.noiseFloorFallCoefficient
        } else {
            config.noiseFloorRiseCoefficient
        }
        noiseFloorRms += (inputRms - noiseFloorRms) * coefficient
        noiseFloorRms = noiseFloorRms.coerceAtLeast(1f)

        // 3. Gate: is this plausibly speech rather than the room?
        val gateThreshold = maxOf(noiseFloorRms * config.gateMarginOverNoise, config.absoluteGateRms)
        val aboveThreshold = inputRms > gateThreshold
        if (aboveThreshold) {
            gateHoldRemaining = config.gateHoldBlocks
        } else if (gateHoldRemaining > 0) {
            gateHoldRemaining--
        }
        val gateOpen = aboveThreshold || gateHoldRemaining > 0

        // 4. Target gain. Only ever lifts, never attenuates, and only while the gate is
        //    open — amplifying a closed gate is precisely how a noise floor becomes a
        //    false wake word.
        val targetGain = if (gateOpen && inputRms > 1f) {
            (config.targetRms / inputRms).coerceIn(config.minGain, config.maxGain)
        } else {
            config.minGain
        }

        // 5. Smooth it. Rising slowly stops the stage ramping up room tone between words;
        //    falling quickly stops a suddenly louder syllable from clipping.
        val smoothing = if (targetGain < currentGain) {
            config.gainFallCoefficient
        } else {
            config.gainRiseCoefficient
        }
        currentGain += (targetGain - currentGain) * smoothing
        currentGain = currentGain.coerceIn(config.minGain, config.maxGain)

        // 6. Apply, limit, and write back inside the 16-bit range.
        var outputPeak = 0
        var limiterEngaged = false
        for (index in 0 until length) {
            val amplified = (samples[index] - dcEstimate) * currentGain
            val limited = limit(amplified)
            if (limited != amplified) limiterEngaged = true

            val clamped = limited.coerceIn(MIN_SAMPLE, MAX_SAMPLE).toInt()
            samples[index] = clamped.toShort()

            // abs(-32768) is 32768, which does not fit the 0..32767 range this field
            // documents — the negative rail is one louder than the positive one. Report
            // the clamp rather than a number that cannot be a peak amplitude.
            val magnitude = abs(clamped).coerceAtMost(MAX_SAMPLE.toInt())
            if (magnitude > outputPeak) outputPeak = magnitude
        }

        val stats = GainFrameStats(
            inputRms = inputRms,
            outputPeak = outputPeak,
            noiseFloorRms = noiseFloorRms,
            appliedGain = currentGain,
            gateOpen = gateOpen,
            limiterEngaged = limiterEngaged
        )
        lastStats = stats
        return stats
    }

    /**
     * Soft-knee limiter.
     *
     * Below the threshold the sample is untouched, so normal speech passes through bit for
     * bit. Above it, the excess is divided by the ratio rather than clipped flat — hard
     * clipping generates harmonics right across the spectrum, and the recogniser hears
     * those as consonants that were never spoken.
     */
    private fun limit(sample: Float): Float {
        val threshold = config.limiterThreshold * MAX_SAMPLE
        val magnitude = abs(sample)
        if (magnitude <= threshold) return sample

        val excess = magnitude - threshold
        val compressed = threshold + excess / config.limiterRatio
        return if (sample < 0) -compressed else compressed
    }

    private companion object {
        const val MAX_SAMPLE = 32767f
        const val MIN_SAMPLE = -32768f
    }
}

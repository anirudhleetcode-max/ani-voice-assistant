package com.ani.assistant.voice.audio

/**
 * The capture settings actually negotiated with the device.
 *
 * Every field here was probed rather than assumed, which is the point: the previous
 * implementation hard-coded 16 kHz and a fixed 200 ms buffer and had no way to find out
 * whether either was right for the phone it was running on.
 */
data class AudioCaptureConfig(
    /** `MediaRecorder.AudioSource` constant actually opened. */
    val audioSource: Int,
    /** Rate the microphone is running at. */
    val captureSampleRate: Int,
    /** Rate the recogniser needs; resampled to if it differs. */
    val recognizerSampleRate: Int,
    /** `AudioRecord` buffer in bytes, from `getMinBufferSize` times a safety multiplier. */
    val bufferSizeBytes: Int,
    /** Samples read per iteration. Sets the latency floor. */
    val readChunkSamples: Int,
    /** True when the platform's automatic gain control was found and enabled. */
    val hardwareAgcEnabled: Boolean,
    /** True when the platform's noise suppressor was found and left enabled. */
    val noiseSuppressorEnabled: Boolean
) {
    val needsResampling: Boolean get() = captureSampleRate != recognizerSampleRate

    /** Human summary for Diagnostics. */
    fun describe(): String = buildString {
        append(audioSourceName(audioSource))
        append(" · ")
        append(captureSampleRate)
        append(" Hz")
        if (needsResampling) append(" → $recognizerSampleRate Hz")
        append(" · mono PCM16 · ")
        append(bufferSizeBytes)
        append(" B buffer · ")
        append(readChunkSamples)
        append(" sample reads")
        if (hardwareAgcEnabled) append(" · HW AGC on")
        if (noiseSuppressorEnabled) append(" · NS on")
    }

    companion object {
        /** Vosk's models are 16 kHz. This is not negotiable on the recogniser's side. */
        const val RECOGNIZER_SAMPLE_RATE = 16_000

        /**
         * Rates to try, best first.
         *
         * 16 kHz first because it needs no resampling at all. The rest are the rates
         * phones commonly expose; 44.1 is last because it is the only one here that needs
         * fractional resampling.
         */
        val CANDIDATE_SAMPLE_RATES = listOf(16_000, 32_000, 48_000, 8_000, 22_050, 44_100)

        /**
         * Multiplier on `getMinBufferSize`.
         *
         * The minimum is the point at which a late read drops audio. Four times it costs a
         * few kilobytes and buys the read loop enough slack to survive the scheduler
         * pausing it, which matters most with the screen off — exactly when nobody is
         * watching to notice a dropout.
         */
        const val BUFFER_SIZE_MULTIPLIER = 4

        /**
         * Samples per read. 320 = 20 ms at 16 kHz.
         *
         * The old path read 200 ms at a time, which put a floor of 200 ms under wake
         * latency before the decoder had even seen the audio. 20 ms blocks also give the
         * gain stage ten times the resolution to track a syllable.
         */
        const val READ_CHUNK_SAMPLES = 320

        /**
         * Picks the best capture rate the device will actually give us.
         *
         * Pure so it can be tested without a microphone: [supportedRates] is whatever the
         * caller found by probing `AudioRecord.getMinBufferSize`.
         */
        fun chooseSampleRate(supportedRates: Collection<Int>): Int? =
            CANDIDATE_SAMPLE_RATES.firstOrNull { it in supportedRates }

        fun audioSourceName(source: Int): String = when (source) {
            6 -> "VOICE_RECOGNITION"
            1 -> "MIC"
            7 -> "VOICE_COMMUNICATION"
            9 -> "UNPROCESSED"
            else -> "source $source"
        }
    }
}

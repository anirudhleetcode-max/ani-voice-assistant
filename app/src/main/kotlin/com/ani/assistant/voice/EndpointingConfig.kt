package com.ani.assistant.voice

/**
 * How long the recogniser waits after speech before it commits to a transcript.
 *
 * This is the single knob that trades accuracy against latency, in both directions, and
 * it has been turned both ways in this project already:
 *
 *  - The stock values (~1 s) cut Telugu and Tanglish speakers off mid-sentence, because
 *    "Rey … Annayya ki … call chey" is one thought with real pauses in it.
 *  - Widening them to 2.5 s fixed that and bought a visible delay on every single
 *    command, which is the complaint that followed.
 *
 * Neither value was measured. So it is a value object now, with the measurement that
 * should set it named in the same file: `[LATENCY] endOfSpeechToFinal` is the cost this
 * config controls, and the Speech Recognition Test screen shows it per attempt.
 *
 * Note that Android's recognition service is free to ignore all three extras, and several
 * OEM implementations do. That is another reason to measure rather than assume: if
 * `endOfSpeechToFinal` does not move when these change, the recogniser is not reading
 * them and the latency is somewhere else entirely.
 */
data class EndpointingConfig(
    /** Silence after which the recogniser treats the utterance as finished. */
    val completeSilenceMillis: Long = DEFAULT_COMPLETE_SILENCE_MILLIS,

    /** Silence after which it may commit if the grammar already looks complete. */
    val possiblyCompleteSilenceMillis: Long = DEFAULT_POSSIBLY_COMPLETE_SILENCE_MILLIS,

    /**
     * Minimum session length before the recogniser may give up.
     *
     * Zero means the extra is not set at all. That is the default: it protects a very
     * quiet speaker from being cut off before they start, but it also puts a hard floor
     * under every command's latency, including the ones where the user spoke instantly.
     * Unset unless a measurement shows quiet speech being truncated.
     */
    val minimumSpeechMillis: Long = 0L
) {
    companion object {
        /**
         * A middle setting, not a guess dressed as one.
         *
         * 1.2 s is long enough for the pause inside "Annayya ki … call chey" — measured
         * at roughly 400–700 ms in ordinary speech — with headroom, and short enough that
         * the reply does not feel like a wait. The previous 2.5 s was chosen to be safe
         * rather than right, and cost more than it bought.
         */
        const val DEFAULT_COMPLETE_SILENCE_MILLIS = 1_200L
        const val DEFAULT_POSSIBLY_COMPLETE_SILENCE_MILLIS = 900L

        /** What the previous build shipped, kept so the two can be compared on device. */
        val PATIENT = EndpointingConfig(
            completeSilenceMillis = 2_500L,
            possiblyCompleteSilenceMillis = 2_000L,
            minimumSpeechMillis = 2_000L
        )

        /** Android's own defaults, for the benchmark's baseline column. */
        val STOCK = EndpointingConfig(
            completeSilenceMillis = 1_000L,
            possiblyCompleteSilenceMillis = 700L,
            minimumSpeechMillis = 0L
        )
    }
}

package com.ani.assistant.voice.audio

/**
 * Converts a PCM16 stream to the rate the recogniser needs.
 *
 * Vosk's model is fixed at 16 kHz. Most phones open a 16 kHz `AudioRecord` happily, in
 * which case this is never constructed — but some devices only expose 44.1 or 48 kHz to a
 * given audio source, and the honest options there are to resample or to refuse to run.
 *
 * Linear interpolation is used rather than a windowed-sinc filter. That is a real
 * trade-off, stated plainly: linear interpolation on a downsample aliases energy above
 * the new Nyquist back down into the band. For wake-word spotting on speech that is
 * acceptable — the model cares about formants well below 8 kHz — and it costs a few
 * microseconds per block rather than a few milliseconds. If this ever feeds full
 * transcription rather than a two-word grammar, it should be replaced with a proper
 * decimating filter.
 *
 * Holds the fractional read position across calls, so block boundaries do not click.
 */
class LinearResampler(
    private val inputRate: Int,
    private val outputRate: Int
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "sample rates must be positive" }
    }

    /** True when input and output rates match and [resample] is a straight copy. */
    val isPassThrough: Boolean = inputRate == outputRate

    private val step: Double = inputRate.toDouble() / outputRate.toDouble()

    /** Fractional position into the input stream, carried between blocks. */
    private var position: Double = 0.0

    /** Last sample of the previous block, so interpolation spans the boundary. */
    private var previousTail: Short = 0

    fun reset() {
        position = 0.0
        previousTail = 0
    }

    /** Worst-case output length for an input of [inputLength] samples. */
    fun maximumOutputLength(inputLength: Int): Int =
        if (isPassThrough) inputLength else (inputLength / step).toInt() + 2

    /**
     * Resamples [inputLength] samples from [input] into [output].
     *
     * @return the number of samples written to [output].
     */
    fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int {
        if (inputLength <= 0) return 0
        if (isPassThrough) {
            val count = minOf(inputLength, output.size)
            input.copyInto(output, 0, 0, count)
            return count
        }

        var written = 0
        while (position < inputLength && written < output.size) {
            val index = position.toInt()
            val fraction = position - index

            // index - 1 reaches back into the previous block; that is what previousTail is for.
            val left = if (index == 0) previousTail else input[index - 1]
            val right = input[index]

            val interpolated = left + (right - left) * fraction
            output[written++] = interpolated
                .coerceIn(MIN_SAMPLE, MAX_SAMPLE)
                .toInt()
                .toShort()

            position += step
        }

        previousTail = input[inputLength - 1]
        // Carry the leftover fraction into the next block instead of restarting at zero,
        // which would drift and click at every boundary.
        position -= inputLength
        if (position < 0) position = 0.0

        return written
    }

    private companion object {
        const val MAX_SAMPLE = 32767.0
        const val MIN_SAMPLE = -32768.0
    }
}

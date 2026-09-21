package com.ani.assistant.voice.audio

/**
 * What the microphone was actually doing during one recognition attempt.
 *
 * This exists so Ani can stop saying "sarigga vinapadatledu ra" — *I didn't catch that* —
 * when the truth is that no audio arrived at all. Those are different failures with
 * different fixes, and telling the user to repeat themselves louder is useless advice for
 * the second one.
 */
enum class AudioVerdict {
    /** Not enough level readings to say anything. Say nothing, then. */
    UNKNOWN,

    /**
     * The recogniser ran and the level never moved off the floor.
     *
     * Almost always microphone ownership: something else — usually Ani's own wake
     * engine — still had the recorder open, so `SpeechRecognizer` got a stream of
     * silence. It is never the user speaking too quietly; a whisper still moves the
     * needle.
     */
    NO_AUDIO,

    /** Audio arrived but stayed close to the floor. A genuinely quiet speaker. */
    LOW_AUDIO,

    /** A healthy speech signal. */
    NORMAL_AUDIO,

    /** Loud enough to be pinned at the top, where consonants stop being distinguishable. */
    CLIPPED
}

/** A read-only summary. Levels only — never a sample, never a transcript. */
data class SpeechLevelSnapshot(
    val readings: Int,
    val minDb: Float,
    val maxDb: Float,
    val meanDb: Float,
    /** Fraction of readings above the silence threshold, 0..1. */
    val aboveSilenceRatio: Float,
    val verdict: AudioVerdict
) {
    val spanDb: Float get() = if (readings == 0) 0f else maxDb - minDb

    /** One line for logcat. Contains no audio and nothing the user said. */
    fun describe(): String = if (readings == 0) {
        "verdict=UNKNOWN readings=0"
    } else {
        "verdict=${verdict.name} readings=$readings " +
            "min=%.1fdB max=%.1fdB mean=%.1fdB span=%.1fdB aboveSilence=%.2f".format(
                minDb, maxDb, meanDb, spanDb, aboveSilenceRatio
            )
    }

    companion object {
        val EMPTY = SpeechLevelSnapshot(0, 0f, 0f, 0f, 0f, AudioVerdict.UNKNOWN)
    }
}

/**
 * Accumulates `RecognitionListener.onRmsChanged` into a verdict.
 *
 * **What this can and cannot tell you.** `SpeechRecognizer` owns the microphone while it
 * runs, so the app cannot read the PCM itself; `onRmsChanged` is the only level signal
 * the platform exposes. Its scale is documented loosely — "typically -2 to 10 dB" — and
 * OEMs vary. So this is deliberately used for one thing only: choosing a *more truthful
 * failure message*. It never rejects a transcript, never gates recognition, and never
 * overrides a result the recogniser did produce.
 *
 * For real PCM figures — RMS, peak, noise floor in LSB — the Mic Test screen opens its
 * own recorder while nothing else holds it. That is the measurement to trust; this is the
 * one available during an actual command.
 */
class SpeechLevelMonitor(
    /** Below this, a reading counts as the floor rather than as speech. */
    private val silenceDb: Float = SILENCE_DB,
    private val lowSpeechDb: Float = LOW_SPEECH_DB,
    private val clippingDb: Float = CLIPPING_DB
) {

    private var readings = 0
    private var minDb = Float.MAX_VALUE
    private var maxDb = -Float.MAX_VALUE
    private var sumDb = 0.0
    private var aboveSilence = 0
    private var atCeiling = 0

    fun reset() {
        readings = 0
        minDb = Float.MAX_VALUE
        maxDb = -Float.MAX_VALUE
        sumDb = 0.0
        aboveSilence = 0
        atCeiling = 0
    }

    fun onRms(db: Float) {
        // Some OEM recognisers emit NaN before the first buffer arrives.
        if (db.isNaN() || db.isInfinite()) return
        readings++
        if (db < minDb) minDb = db
        if (db > maxDb) maxDb = db
        sumDb += db.toDouble()
        if (db > silenceDb) aboveSilence++
        if (db >= clippingDb) atCeiling++
    }

    fun snapshot(): SpeechLevelSnapshot {
        if (readings < MINIMUM_READINGS) return SpeechLevelSnapshot.EMPTY

        val mean = (sumDb / readings).toFloat()
        val ratio = aboveSilence.toFloat() / readings
        val span = maxDb - minDb

        // Fraction of readings pinned at the top, which is what clipping means. The
        // fraction *above silence* is near 1.0 for any healthy speech, so using that
        // here called normal speech with one loud syllable "clipped".
        val ceilingRatio = atCeiling.toFloat() / readings

        val verdict = when {
            // Nothing ever rose off the floor, and the signal barely moved. A recorder
            // delivering real room tone still wanders by more than this.
            ratio < SILENT_RATIO -> AudioVerdict.NO_AUDIO
            span < FLAT_SPAN_DB && maxDb <= silenceDb -> AudioVerdict.NO_AUDIO
            ceilingRatio > CLIPPING_RATIO -> AudioVerdict.CLIPPED
            maxDb < lowSpeechDb -> AudioVerdict.LOW_AUDIO
            else -> AudioVerdict.NORMAL_AUDIO
        }

        return SpeechLevelSnapshot(
            readings = readings,
            minDb = minDb,
            maxDb = maxDb,
            meanDb = mean,
            aboveSilenceRatio = ratio,
            verdict = verdict
        )
    }

    companion object {
        /**
         * Android documents `onRmsChanged` as roughly -2..10 dB. Zero sits just above
         * where a quiet room settles on every device this was checked against, and well
         * below where any speech lands.
         */
        const val SILENCE_DB = 0f

        /** Below this peak, the speaker is quiet but audible. */
        const val LOW_SPEECH_DB = 3f

        /** At or above this, the level is pinned at the top of the reported range. */
        const val CLIPPING_DB = 9.5f

        /** Fewer readings than this and the attempt was too short to judge. */
        const val MINIMUM_READINGS = 5

        /** Below this fraction above silence, treat the stream as dead. */
        const val SILENT_RATIO = 0.03f

        /**
         * Above this fraction *at the ceiling*, treat it as clipping rather than loud.
         *
         * Half the attempt pinned at the top is a speaker too close to the microphone.
         * One syllable up there is just a syllable.
         */
        const val CLIPPING_RATIO = 0.5f

        /** A span narrower than this is a flat line, not a room. */
        const val FLAT_SPAN_DB = 1.0f
    }
}

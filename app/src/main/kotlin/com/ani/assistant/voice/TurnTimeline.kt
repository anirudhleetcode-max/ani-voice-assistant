package com.ani.assistant.voice

/** One stage boundary in a turn. Ordered as they occur. */
enum class TurnStage {
    /** The wake phrase was recognised. Absent for a turn started by tapping the orb. */
    WAKE_DETECTED,

    /** The microphone handover completed and the recogniser was asked to start. */
    COMMAND_LISTENING,

    /** The recogniser said it was ready for speech. */
    RECOGNIZER_READY,

    /** The first partial hypothesis arrived — the earliest proof the chain is alive. */
    FIRST_PARTIAL,

    /** The recogniser decided the user had stopped talking. */
    END_OF_SPEECH,

    /** The final transcript arrived. */
    FINAL_TRANSCRIPT,

    /** Classification and tool execution finished. */
    NLU_COMPLETE,

    /** `TextToSpeech.speak` was called. */
    TTS_REQUESTED,

    /** The first audio of the reply actually played. */
    TTS_FIRST_AUDIO,

    /** The reply finished playing. */
    TTS_COMPLETE
}

/**
 * Where a turn's seconds went.
 *
 * Built because "Ani is slow" is not a diagnosis. The delay could be endpointing, a
 * networked recogniser, classification, a cold TTS engine or audio playback, and those
 * have nothing in common except the user's experience of them. Guessing between them is
 * how you end up tuning the wrong constant — and the silence windows were widened for
 * accuracy in a previous round, which bought exactly the latency being complained about
 * now. That trade should be visible in numbers rather than argued about.
 *
 * Pure and injectable so the arithmetic is unit-tested rather than eyeballed in logcat.
 */
class TurnTimeline(private val nowMillis: () -> Long = System::currentTimeMillis) {

    private val marks = LinkedHashMap<TurnStage, Long>()

    /** Milliseconds since [start], or null when the stage was never reached. */
    private var startedAt: Long? = null

    fun start() {
        marks.clear()
        startedAt = nowMillis()
    }

    /** Records [stage] if it has not already been recorded. First occurrence wins. */
    fun mark(stage: TurnStage) {
        if (startedAt == null) start()
        marks.putIfAbsent(stage, nowMillis())
    }

    fun at(stage: TurnStage): Long? = marks[stage]

    /** Milliseconds between two stages, or null when either is missing. */
    fun between(from: TurnStage, to: TurnStage): Long? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        return b - a
    }

    /** Milliseconds from [start] to [stage]. */
    fun sinceStart(stage: TurnStage): Long? {
        val began = startedAt ?: return null
        val mark = marks[stage] ?: return null
        return mark - began
    }

    /** Total elapsed time to the last stage reached. */
    fun total(): Long? {
        val began = startedAt ?: return null
        val last = marks.values.maxOrNull() ?: return null
        return last - began
    }

    /**
     * The `[LATENCY]` line.
     *
     * Every segment that was not reached is simply absent rather than reported as zero:
     * a turn where TTS never started is a different fact from one where it started
     * instantly, and a zero would hide it.
     */
    fun describe(): String = buildString {
        fun segment(label: String, value: Long?) {
            if (value != null) {
                if (isNotEmpty()) append(' ')
                append(label).append('=').append(value)
            }
        }

        segment("wakeToListening", between(TurnStage.WAKE_DETECTED, TurnStage.COMMAND_LISTENING))
        segment("listeningToReady", between(TurnStage.COMMAND_LISTENING, TurnStage.RECOGNIZER_READY))
        segment("listeningToFirstPartial", between(TurnStage.COMMAND_LISTENING, TurnStage.FIRST_PARTIAL))
        segment("listeningToFinal", between(TurnStage.COMMAND_LISTENING, TurnStage.FINAL_TRANSCRIPT))
        // The endpointing cost specifically: how long after the user stopped talking the
        // recogniser took to commit. This is the number the silence windows control, and
        // the one to look at before touching them.
        segment("endOfSpeechToFinal", between(TurnStage.END_OF_SPEECH, TurnStage.FINAL_TRANSCRIPT))
        segment("finalToNLU", between(TurnStage.FINAL_TRANSCRIPT, TurnStage.NLU_COMPLETE))
        segment("nluToTtsRequest", between(TurnStage.NLU_COMPLETE, TurnStage.TTS_REQUESTED))
        segment("ttsRequestToAudio", between(TurnStage.TTS_REQUESTED, TurnStage.TTS_FIRST_AUDIO))
        segment("ttsAudioToComplete", between(TurnStage.TTS_FIRST_AUDIO, TurnStage.TTS_COMPLETE))
        segment("total", total())

        if (isEmpty()) append("no stages recorded")
    }

    /** A snapshot for the benchmark screen. */
    fun snapshot(): Map<TurnStage, Long> {
        val began = startedAt ?: return emptyMap()
        return marks.mapValues { (_, at) -> at - began }
    }
}

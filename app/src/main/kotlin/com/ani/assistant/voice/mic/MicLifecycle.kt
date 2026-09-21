package com.ani.assistant.voice.mic

/**
 * Who is holding the microphone.
 *
 * Android gives one `AudioRecord` the input stream and quietly starves the others. Two
 * components in this app want it — the wake engine and the command recogniser — so the
 * only safe arrangement is that exactly one of them exists at a time. [MicOwner] is how
 * that is stated rather than assumed.
 */
enum class MicOwner {
    /** Nobody is recording. */
    NONE,

    /** The wake engine's [android.media.AudioRecord] is open. */
    WAKE_ENGINE,

    /** `SpeechRecognizer` is recording the command. */
    COMMAND_RECOGNIZER
}

/**
 * Every position the microphone can be in between "Rey" and being ready for the next one.
 *
 * The stages exist because the failure they prevent is invisible. When the wake engine and
 * the command recogniser overlap by even a few hundred milliseconds, nothing throws:
 * the second recorder simply receives silence, the command is never transcribed, and Ani
 * answers "I didn't catch that" — or, worse, hears the tail of its own chime. The bug
 * looks like bad recognition, so it gets chased in the recogniser, where it is not.
 */
enum class MicStage(val owner: MicOwner) {

    /** The service is not listening at all. */
    IDLE(MicOwner.NONE),

    /** The wake engine is running and waiting for the phrase. */
    WAKE_LISTENING(MicOwner.WAKE_ENGINE),

    /** The phrase was heard. The wake engine still owns the microphone at this instant. */
    WAKE_DETECTED(MicOwner.WAKE_ENGINE),

    /**
     * The wake engine has been torn down and the microphone is free.
     *
     * This stage has to exist as a stage. Making it implicit is what produced the original
     * race: the code released the engine and started the recogniser in the same breath,
     * and on a device with the screen off the release had not finished.
     */
    WAKE_AUDIO_RELEASE(MicOwner.NONE),

    /** `SpeechRecognizer` owns the microphone and the user is speaking the command. */
    COMMAND_LISTENING(MicOwner.COMMAND_RECOGNIZER),

    /** Classifying and running tools. Nothing is recording. */
    PROCESSING(MicOwner.NONE),

    /**
     * An action is being launched — a call, Spotify, an app.
     *
     * Separate from [PROCESSING] so a launch that blocks or opens another app is visible
     * in a log as its own stage rather than an unexplained gap.
     */
    ACTION_EXECUTING(MicOwner.NONE),

    /**
     * Ani is talking.
     *
     * The wake engine must not be running here. If it were, "Rey" inside Ani's own reply
     * would wake it, and it would start a conversation with itself.
     */
    SPEAKING(MicOwner.NONE),

    /** The exchange is over and the wake engine is being started again. */
    WAKE_REARM(MicOwner.NONE);

    /** True while the exchange belongs to the user rather than the wake loop. */
    val isExchange: Boolean
        get() = this == COMMAND_LISTENING || this == PROCESSING ||
            this == ACTION_EXECUTING || this == SPEAKING
}

/** The things that happen to the pipeline. */
enum class MicEvent {
    /** The service entered the foreground and the wake engine started. */
    WAKE_ENGINE_STARTED,

    /** The wake phrase was recognised. */
    WAKE_DETECTED,

    /** The wake engine has been released and its recorder is closed. */
    WAKE_AUDIO_RELEASED,

    /** `SpeechRecognizer` reported that it is recording. */
    COMMAND_LISTENING_STARTED,

    /** A final transcript arrived, or the recogniser gave up. */
    COMMAND_CAPTURED,

    /** A tool is launching something. */
    ACTION_STARTED,

    /** The launch finished, whatever its outcome. */
    ACTION_FINISHED,

    /** TTS started speaking the reply. */
    SPEECH_STARTED,

    /** TTS finished, or was stopped. */
    SPEECH_FINISHED,

    /** The exchange is over by any route, including failure. */
    EXCHANGE_ENDED,

    /** The wake engine is running again and the loop is closed. */
    WAKE_REARMED,

    /** The service is stopping: user request, permission revoked, or destroy. */
    SERVICE_STOPPED
}

/** Why a transition was refused. Only ever a shape — never anything the user said. */
data class MicTransitionRejected(val from: MicStage, val event: MicEvent, val reason: String)

/**
 * The microphone lifecycle, as an explicit machine.
 *
 * It is deliberately pure: no Android types, no coroutines, no I/O. That is what makes
 * "wake detected, then the command session times out, and the wake engine comes back" a
 * unit test rather than a thing you find out about on a phone in a dark room.
 *
 * Two rules it enforces, both of which were broken in the field:
 *
 *  1. **One owner.** [MicStage.owner] is checked on every transition. It is impossible to
 *     reach [MicStage.COMMAND_LISTENING] without passing through
 *     [MicStage.WAKE_AUDIO_RELEASE] first, because that is the only stage that hands the
 *     microphone back.
 *  2. **Nothing is terminal but a stop.** Every failure path — a timeout, a dead
 *     recogniser, an action that throws — routes to [MicStage.WAKE_REARM], never to
 *     [MicStage.IDLE]. A state machine that can get stuck is an assistant that goes deaf
 *     until the user notices and reboots it.
 */
class MicLifecycle(
    private val onTransition: (MicStage, MicStage, MicEvent) -> Unit = { _, _, _ -> }
) {

    var stage: MicStage = MicStage.IDLE
        private set

    /** Whoever is holding the microphone right now, derived rather than tracked. */
    val owner: MicOwner get() = stage.owner

    /** Last refusal, for diagnostics. Cleared by the next accepted transition. */
    var lastRejection: MicTransitionRejected? = null
        private set

    /**
     * Applies [event].
     *
     * @return true when the stage changed or the event was a legitimate no-op; false when
     *         the event made no sense in the current stage, which is a bug worth logging
     *         and never a reason to stop listening.
     */
    @Synchronized
    fun on(event: MicEvent): Boolean {
        // A stop wins from anywhere. There is no stage in which "the service is going
        // away" should be argued with.
        if (event == MicEvent.SERVICE_STOPPED) {
            moveTo(MicStage.IDLE, event)
            return true
        }

        // So does the end of an exchange. It is the path every failure takes, so it must
        // be reachable from every stage an exchange can die in.
        if (event == MicEvent.EXCHANGE_ENDED) {
            return if (stage.isExchange || stage == MicStage.WAKE_DETECTED ||
                stage == MicStage.WAKE_AUDIO_RELEASE
            ) {
                moveTo(MicStage.WAKE_REARM, event)
                true
            } else {
                reject(event, "no exchange in progress")
            }
        }

        val next = when (stage to event) {
            MicStage.IDLE to MicEvent.WAKE_ENGINE_STARTED -> MicStage.WAKE_LISTENING
            MicStage.WAKE_REARM to MicEvent.WAKE_ENGINE_STARTED -> MicStage.WAKE_LISTENING
            MicStage.WAKE_REARM to MicEvent.WAKE_REARMED -> MicStage.WAKE_LISTENING

            MicStage.WAKE_LISTENING to MicEvent.WAKE_DETECTED -> MicStage.WAKE_DETECTED
            MicStage.WAKE_DETECTED to MicEvent.WAKE_AUDIO_RELEASED -> MicStage.WAKE_AUDIO_RELEASE

            MicStage.WAKE_AUDIO_RELEASE to MicEvent.COMMAND_LISTENING_STARTED -> MicStage.COMMAND_LISTENING
            MicStage.COMMAND_LISTENING to MicEvent.COMMAND_CAPTURED -> MicStage.PROCESSING

            MicStage.PROCESSING to MicEvent.ACTION_STARTED -> MicStage.ACTION_EXECUTING
            MicStage.ACTION_EXECUTING to MicEvent.ACTION_FINISHED -> MicStage.PROCESSING

            MicStage.PROCESSING to MicEvent.SPEECH_STARTED -> MicStage.SPEAKING
            MicStage.SPEAKING to MicEvent.SPEECH_FINISHED -> MicStage.PROCESSING

            // A follow-up question: Ani asked something, so the microphone opens again
            // without a second "Rey". The wake engine is still down, so there is still
            // only one owner.
            MicStage.PROCESSING to MicEvent.COMMAND_LISTENING_STARTED -> MicStage.COMMAND_LISTENING

            else -> null
        }

        if (next == null) {
            // Re-releasing an already-released microphone, or re-arming an engine that is
            // already armed, is harmless and happens on retry paths. Say so rather than
            // logging a scary refusal.
            if (isIdempotent(event)) return true
            return reject(event, "not valid in ${stage.name}")
        }

        moveTo(next, event)
        return true
    }

    /** True when the wake engine may be started without stealing the command's microphone. */
    @Synchronized
    fun canStartWakeEngine(): Boolean =
        stage == MicStage.IDLE || stage == MicStage.WAKE_REARM || stage == MicStage.WAKE_LISTENING

    /** True when `SpeechRecognizer` may be started. */
    @Synchronized
    fun canStartCommandRecognizer(): Boolean =
        stage == MicStage.WAKE_AUDIO_RELEASE || stage == MicStage.PROCESSING

    /**
     * The invariant, checkable at any moment.
     *
     * Exposed so a test can assert it after every single transition instead of trusting
     * that the table above is right.
     */
    fun holdsSingleOwnerInvariant(): Boolean = when (stage) {
        MicStage.WAKE_LISTENING, MicStage.WAKE_DETECTED -> owner == MicOwner.WAKE_ENGINE
        MicStage.COMMAND_LISTENING -> owner == MicOwner.COMMAND_RECOGNIZER
        else -> owner == MicOwner.NONE
    }

    private fun isIdempotent(event: MicEvent): Boolean = when (event) {
        MicEvent.WAKE_AUDIO_RELEASED -> owner != MicOwner.WAKE_ENGINE
        MicEvent.WAKE_ENGINE_STARTED, MicEvent.WAKE_REARMED -> stage == MicStage.WAKE_LISTENING
        MicEvent.ACTION_FINISHED -> stage == MicStage.PROCESSING
        MicEvent.SPEECH_FINISHED -> stage == MicStage.PROCESSING
        else -> false
    }

    private fun moveTo(next: MicStage, event: MicEvent) {
        val previous = stage
        stage = next
        lastRejection = null
        if (previous != next) onTransition(previous, next, event)
    }

    private fun reject(event: MicEvent, reason: String): Boolean {
        lastRejection = MicTransitionRejected(stage, event, reason)
        return false
    }
}

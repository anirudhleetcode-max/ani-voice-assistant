package com.ani.assistant.voice

/**
 * What Ani is doing right now.
 *
 * Drives both the orb animation and what the microphone is actually doing, so the two
 * can never disagree — if the screen says "Listening...", the recogniser is running.
 */
enum class VoiceState {
    /** Not listening at all. Tap-to-talk only. */
    IDLE,

    /** The wake-word loop is running, waiting for "Rey". */
    WAITING_FOR_WAKE,

    /** The wake phrase was just heard. Brief; plays the activation sound. */
    WAKE_DETECTED,

    /** Recording a command. */
    LISTENING,

    /** Classifying, and possibly asking the backend. */
    PROCESSING,

    /** Speaking a reply. */
    SPEAKING,

    /** Something went wrong; the message is in VoiceSession.lastError. */
    ERROR;

    val isActive: Boolean
        get() = this == LISTENING || this == PROCESSING || this == SPEAKING || this == WAKE_DETECTED
}

/** A transcription result. */
data class SpeechResult(
    val text: String,
    /** 0..1 from the recogniser, when it reports one. */
    val confidence: Float,
    /** True while the user is still speaking. */
    val isPartial: Boolean
)

/** Why recognition stopped without a result. */
enum class SpeechError {
    /** Nothing was said. Normal, not worth mentioning to the user. */
    NO_SPEECH,

    /** Audio came through but nothing could be made of it. */
    NOT_UNDERSTOOD,

    /** The recogniser needs the network and it is not there. */
    NETWORK,

    /**
     * The recogniser could not get audio.
     *
     * Usually another recorder is open — including one of Ani's own. Kept separate from
     * [PERMISSION_MISSING] because the remedy is completely different.
     */
    MICROPHONE_UNAVAILABLE,

    /** RECORD_AUDIO is not granted. */
    PERMISSION_MISSING,

    /** No speech recognition service is installed on the device at all. */
    RECOGNIZER_UNAVAILABLE,

    /** The recogniser is already running; the caller raced itself. */
    BUSY,

    OTHER
}

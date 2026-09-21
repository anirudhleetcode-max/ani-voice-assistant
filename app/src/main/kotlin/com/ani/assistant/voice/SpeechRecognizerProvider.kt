package com.ani.assistant.voice

import com.ani.assistant.voice.audio.SpeechLevelMonitor
import com.ani.assistant.voice.audio.SpeechLevelSnapshot
import com.ani.nlu.text.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Which recogniser actually ran. Never inferred — always reported. */
enum class RecognizerKind {
    /** `SpeechRecognizer.createSpeechRecognizer` — usually Google's, usually networked. */
    PLATFORM,

    /** `SpeechRecognizer.createOnDeviceSpeechRecognizer`, API 33+. */
    ON_DEVICE
}

/** One streaming recognition attempt. */
sealed interface SpeechEvent {

    /**
     * Emitted before anything else, naming the recogniser that is about to run.
     *
     * Exists because a silent fallback between the on-device and the networked recogniser
     * makes a failure impossible to diagnose: the two behave differently for Telugu, and
     * "which one ran" is the first question worth asking.
     */
    data class Started(val kind: RecognizerKind, val localeTag: String) : SpeechEvent

    data object ReadyForSpeech : SpeechEvent
    data object BeginningOfSpeech : SpeechEvent
    data object EndOfSpeech : SpeechEvent

    data class Partial(val result: SpeechResult) : SpeechEvent

    data class Final(
        val result: SpeechResult,
        /** What the level did while this was captured. Advisory; see [SpeechLevelMonitor]. */
        val audio: SpeechLevelSnapshot = SpeechLevelSnapshot.EMPTY
    ) : SpeechEvent

    data class Failed(
        val error: SpeechError,
        /**
         * What the level did before it failed.
         *
         * This is what separates "you spoke too quietly" from "nothing reached the
         * microphone" — two failures that used to produce the same apology.
         */
        val audio: SpeechLevelSnapshot = SpeechLevelSnapshot.EMPTY,
        /** The platform's own error constant, for the log. */
        val platformCode: Int? = null
    ) : SpeechEvent
}

/**
 * Speech-to-text, behind an interface.
 *
 * The interface exists because the right recogniser for this app does not exist yet.
 * Android's built-in one handles `te-IN` on most devices and handles Telugu-English code
 * switching badly on all of them — it will transcribe "Spotify lo Arijit Singh play chey"
 * reasonably in `en-IN` and mangle it in `te-IN`, or the reverse, depending on the device
 * and the speaker.
 *
 * So the app targets the abstraction, [AndroidSpeechRecognizerProvider] implements what
 * ships on the device today, and a cloud or on-device provider with real code-switching
 * support can be dropped in without touching anything above this line. See
 * TROUBLESHOOTING.md for what this means in practice for a Telugu speaker.
 */
interface SpeechRecognizerProvider {

    /**
     * Microphone level, 0..1, for the orb animation.
     *
     * Deliberately **not** a [SpeechEvent]. `onRmsChanged` fires ten or more times a
     * second while a transcript arrives once, so putting both down one channel lets the
     * disposable traffic queue ahead of the irreplaceable traffic — which either delays
     * the transcript or, with a bounded buffer, loses it. A `StateFlow` conflates by
     * nature: a level nobody read is simply overwritten, and the event channel is left
     * carrying a handful of events per turn where nothing can crowd anything out.
     */
    val audioLevel: StateFlow<Float>

    /** Whether recognition is possible at all on this device right now. */
    fun isAvailable(): Boolean

    /**
     * Runs one recognition attempt, emitting events until a [SpeechEvent.Final] or
     * [SpeechEvent.Failed]. Cancelling the collector stops the microphone.
     *
     * @param language the locale to bias towards; see [Language.toLocaleTag]
     * @param partialResults whether to emit [SpeechEvent.Partial]. The wake-word loop
     *        needs them (to react the instant "Rey" is heard); command capture does not.
     */
    fun listen(language: Language, partialResults: Boolean = true): Flow<SpeechEvent>

    /** Locales the installed recogniser claims to support, for Diagnostics. */
    suspend fun supportedLanguages(): List<String>

    fun release()
}

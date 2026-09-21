package com.ani.assistant.voice

import com.ani.nlu.text.Language
import kotlinx.coroutines.flow.Flow

/** One streaming recognition attempt. */
sealed interface SpeechEvent {
    data object ReadyForSpeech : SpeechEvent
    data object BeginningOfSpeech : SpeechEvent

    /** Microphone level, 0..1, for the orb animation. */
    data class AudioLevel(val level: Float) : SpeechEvent

    data class Partial(val result: SpeechResult) : SpeechEvent
    data class Final(val result: SpeechResult) : SpeechEvent
    data class Failed(val error: SpeechError) : SpeechEvent
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

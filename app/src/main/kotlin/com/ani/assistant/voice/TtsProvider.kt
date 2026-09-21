package com.ani.assistant.voice

import com.ani.nlu.text.Language

/** A voice the engine offers. */
data class TtsVoice(
    val name: String,
    val localeTag: String,
    val displayName: String,
    /** False when the voice has to be downloaded before it can speak. */
    val isInstalled: Boolean,
    val isNetworkRequired: Boolean
)

/** What the TTS engine can actually do for a language. */
enum class TtsAvailability {
    READY,

    /** The engine supports the language but the voice data is not on the device. */
    NEEDS_DOWNLOAD,

    /** No voice for this language on this device at all. */
    UNSUPPORTED,

    /** The engine failed to initialise. */
    UNAVAILABLE
}

/**
 * Text-to-speech, behind an interface for the same reason as [SpeechRecognizerProvider].
 *
 * The Telugu situation is the point. Many Android devices ship without a Telugu voice, and
 * speaking Telugu words through an English voice produces something a Telugu speaker will
 * find worse than useless — "Amma ki call chesthunna" read with English phonetics is not
 * Telugu with an accent, it is noise.
 *
 * So the contract here includes [availabilityFor]: callers are expected to ask before
 * assuming, and Ani tells the user to install the Telugu voice rather than mispronouncing
 * at them.
 */
interface TtsProvider {

    /** Fires once the engine has initialised, or immediately if it already has. */
    suspend fun awaitReady(): Boolean

    fun availabilityFor(language: Language): TtsAvailability

    suspend fun voicesFor(language: Language): List<TtsVoice>

    /**
     * Gets the engine ready to speak [language] without speaking anything.
     *
     * Called while the user is still talking, so that binding to the TTS service and
     * loading a voice — a second or more from cold — happens in parallel with
     * recognition instead of after it. Doing that work when the reply is already
     * composed is what produces "the text appeared, then the audio came much later".
     *
     * Safe to call repeatedly; a warm engine returns immediately.
     */
    suspend fun prepare(language: Language)

    /**
     * Speaks [text] and suspends until it finishes, so the caller can resume listening at
     * the right moment rather than talking over itself.
     *
     * @param onFirstAudio invoked when audio actually starts playing, which is a
     *        different moment from when `speak` was called — the gap between the two is
     *        synthesis, and it is the one worth measuring separately.
     * @return true when the utterance completed, false if it was interrupted or failed
     */
    suspend fun speak(
        text: String,
        language: Language,
        onFirstAudio: () -> Unit = {}
    ): Boolean

    fun stop()

    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun setVoice(voiceName: String?)

    fun shutdown()
}

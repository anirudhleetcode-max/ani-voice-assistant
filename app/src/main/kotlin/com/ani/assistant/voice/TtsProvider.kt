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
     * Speaks [text] and suspends until it finishes, so the caller can resume listening at
     * the right moment rather than talking over itself.
     *
     * @return true when the utterance completed, false if it was interrupted or failed
     */
    suspend fun speak(text: String, language: Language): Boolean

    fun stop()

    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun setVoice(voiceName: String?)

    fun shutdown()
}

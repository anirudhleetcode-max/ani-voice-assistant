package com.ani.assistant.voice.wake

import kotlinx.coroutines.flow.Flow

/** A wake phrase was heard. */
data class WakeDetection(
    val phrase: String,
    /** 0..1. Engines that do not report a score return 1.0. */
    val confidence: Double,
    /** Anything the user said after the phrase in the same breath, if the engine caught it. */
    val trailingText: String? = null,
    val atEpochMillis: Long = System.currentTimeMillis()
)

/**
 * How eagerly to accept a near-miss.
 *
 * "Rey" is an extremely common word in casual Telugu — it is how people address each
 * other. A wake word that fires on every "rey" in a conversation is unusable, so the
 * default sits at MEDIUM and LOW is a genuine option rather than a token setting.
 */
enum class WakeSensitivity(
    /** Edit-distance tolerance handed to WakeWordMatcher, 0..1. */
    val matcherSensitivity: Double,
    /** Porcupine's own detection threshold, 0..1. */
    val porcupineSensitivity: Float
) {
    /** Exact phonetic match, and only on a completed utterance. Fewest false wakes. */
    LOW(0.0, 0.3f),

    /** The default. */
    MEDIUM(0.6, 0.5f),

    /** Forgives a near-miss. Wakes more reliably, and more often by mistake. */
    HIGH(0.9, 0.75f)
}

/** Which engine. Stored in settings, shown in Diagnostics. */
enum class WakeWordEngineId {
    /** Vosk grammar-mode keyword spotting. Free, Apache-2.0, fully on-device. The default. */
    VOSK,

    /** Picovoice Porcupine. Best battery, but needs a paid AccessKey since 30 June 2026. */
    PORCUPINE,

    /** The platform speech recogniser in a loop. Works everywhere, costs the most battery. */
    PLATFORM_RECOGNIZER
}

/** Whether an engine can actually run right now, and what is missing if not. */
sealed interface WakeEngineAvailability {
    data object Ready : WakeEngineAvailability

    /** Vosk: the acoustic model is not on the device yet. */
    data class NeedsModel(val sizeMegabytes: Int, val howToInstall: String) : WakeEngineAvailability

    /** Porcupine: no AccessKey, or no custom keyword file for the chosen phrase. */
    data class NeedsCredentials(val what: String) : WakeEngineAvailability

    /** Microphone permission, or no recogniser installed. */
    data class Blocked(val reason: String) : WakeEngineAvailability

    data class Error(val reason: String) : WakeEngineAvailability

    val isReady: Boolean get() = this is Ready
}

/**
 * Listens for the activation phrase.
 *
 * Three implementations ship, and the differences between them are real rather than
 * cosmetic — battery cost, whether the phrase can be anything the user likes, and whether
 * money is involved. [WakeWordEngineFactory] picks one; Diagnostics names which.
 *
 * Every implementation must hold to the same three rules:
 *
 *  1. **Detection is on-device.** No implementation may stream microphone audio to a
 *     server to decide whether the wake word was spoken.
 *  2. **Nothing is recorded.** Audio is processed frame by frame and discarded. No
 *     implementation writes audio to disk.
 *  3. **[setPaused] genuinely stops detection.** It is what stops Ani hearing its own
 *     reply and waking itself, and a no-op implementation would break that.
 */
interface WakeWordEngine {

    val id: WakeWordEngineId

    /** Shown in Settings and Diagnostics. */
    val displayName: String

    /** Plain-language battery and privacy note, shown next to the toggle. */
    val costDescription: String

    /**
     * Whether the user can set the phrase to anything they like.
     *
     * False for Porcupine without a custom keyword file: it can only detect phrases it
     * has a trained model for. The settings UI greys out the phrase field rather than
     * accepting input it will ignore.
     */
    val supportsCustomPhrase: Boolean

    /** Re-checked on every start; the user can delete a model or revoke a key at any time. */
    suspend fun availability(): WakeEngineAvailability

    /**
     * Emits once per detection, running until the collector is cancelled.
     *
     * Implementations must release the microphone the moment collection stops.
     */
    fun detections(): Flow<WakeDetection>

    /**
     * Suspends and resumes detection.
     *
     * Called with `true` for the whole time Ani is speaking. Without it, "Rey" in Ani's
     * own reply re-triggers the wake word and the assistant talks to itself.
     */
    fun setPaused(paused: Boolean)

    /**
     * Releases the microphone and does not return until it is genuinely free.
     *
     * The plain [release] is fire-and-forget: it asks the capture loop to stop and
     * returns, which reads as "done" while a blocking `AudioRecord.read` on another
     * thread is still in flight. Starting `SpeechRecognizer` in that window gets a
     * recorder that opens without error and delivers silence, so recognition ends in
     * `ERROR_NO_MATCH` and the user is told they were not heard clearly when in fact they
     * were not heard at all.
     *
     * @return true when the recorder is confirmed stopped. **A caller that gets false
     *         must not start a second recorder** — it must report a microphone problem.
     */
    suspend fun releaseAndAwait(timeoutMillis: Long = DEFAULT_RELEASE_TIMEOUT_MILLIS): Boolean {
        release()
        return true
    }

    fun release()

    companion object {
        /** Long enough for an orderly unwind of a 20 ms read loop, many times over. */
        const val DEFAULT_RELEASE_TIMEOUT_MILLIS = 1_000L
    }
}

package com.ani.assistant.voice.wake

import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.SpeechError
import com.ani.assistant.voice.SpeechEvent
import com.ani.assistant.voice.SpeechRecognizerProvider
import com.ani.nlu.dialog.WakeWordMatcher
import com.ani.nlu.text.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Wake-word detection by running the platform speech recogniser in a loop.
 * **Last resort, and it should be read as one.**
 *
 * This works on every device with no model to download and no licence to buy, which is
 * why it is still here. Everything else about it is worse than the alternatives:
 *
 *  - It is the most battery-hungry of the three by a wide margin.
 *  - On most devices the platform recogniser sends audio to Google's servers to
 *    transcribe it, which means the wake word is **not** detected on-device. That is a
 *    materially different privacy position from [VoskWakeWordEngine], and the app says so
 *    in [costDescription] rather than burying it.
 *
 * It is never chosen automatically over Vosk. It exists for the case where the user has
 * not downloaded the wake model and wants tap-free activation anyway, having been told
 * what that costs.
 *
 * The delay between restarts is not cosmetic: restarting the recogniser in a tight loop
 * makes several OEM implementations return ERROR_RECOGNIZER_BUSY forever.
 */
class PlatformRecognizerWakeEngine(
    private val recognizer: SpeechRecognizerProvider,
    private val phrasesProvider: () -> List<String>,
    private val sensitivityProvider: () -> WakeSensitivity,
    private val languageProvider: () -> Language = { Language.MIXED },
    private val hasMicrophonePermission: () -> Boolean = { true }
) : WakeWordEngine {

    override val id = WakeWordEngineId.PLATFORM_RECOGNIZER
    override val displayName = "Phone's speech recogniser"
    override val supportsCustomPhrase = true

    override val costDescription: String =
        "Uses the phone's own speech recogniser continuously. Needs no download, but it " +
            "uses noticeably more battery, and on most phones that recogniser sends audio " +
            "to Google to transcribe — so the wake word is not detected on the device."

    @Volatile
    private var paused: Boolean = false

    override suspend fun availability(): WakeEngineAvailability = when {
        !hasMicrophonePermission() ->
            WakeEngineAvailability.Blocked("Microphone permission is not granted.")

        !recognizer.isAvailable() ->
            WakeEngineAvailability.Blocked("No speech recogniser is installed on this phone.")

        else -> WakeEngineAvailability.Ready
    }

    override fun detections(): Flow<WakeDetection> = flow {
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            if (paused) {
                delay(PAUSE_POLL_MILLIS)
                continue
            }

            val matcher = WakeWordMatcher(
                phrases = phrasesProvider(),
                sensitivity = sensitivityProvider().matcherSensitivity
            )

            var detected: WakeDetection? = null
            var failure: SpeechError? = null

            try {
                recognizer.listen(languageProvider(), partialResults = true).collect { event ->
                    when (event) {
                        // Reacting to partials means Ani wakes the instant "Rey" is heard
                        // rather than after the recogniser decides the sentence ended.
                        is SpeechEvent.Partial ->
                            if (detected == null) detected = matcher.match(event.result.text).toDetection()

                        is SpeechEvent.Final ->
                            if (detected == null) detected = matcher.match(event.result.text).toDetection()

                        is SpeechEvent.Failed -> failure = event.error
                        else -> Unit
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AniLog.w(TAG, "wake loop iteration failed", "type" to error.javaClass.simpleName)
                failure = SpeechError.OTHER
            }

            val hit = detected
            if (hit != null && !paused) {
                consecutiveFailures = 0
                emit(hit)
            }

            consecutiveFailures = if (failure != null && hit == null) consecutiveFailures + 1 else 0
            delay(
                when {
                    failure == SpeechError.MICROPHONE_UNAVAILABLE -> MICROPHONE_BUSY_BACKOFF_MILLIS
                    consecutiveFailures >= FAILURE_BACKOFF_THRESHOLD -> LONG_BACKOFF_MILLIS
                    else -> RESTART_DELAY_MILLIS
                }
            )
        }
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    override fun release() = Unit

    private fun com.ani.nlu.dialog.WakeMatch.toDetection(): WakeDetection? {
        if (!matched) return null
        return WakeDetection(
            phrase = phrase.orEmpty(),
            confidence = confidence,
            trailingText = remainder.normalized.takeIf { it.isNotBlank() }
        )
    }

    private companion object {
        const val TAG = "AniPlatformWake"
        const val RESTART_DELAY_MILLIS = 350L
        const val LONG_BACKOFF_MILLIS = 5_000L
        const val MICROPHONE_BUSY_BACKOFF_MILLIS = 10_000L
        const val PAUSE_POLL_MILLIS = 250L
        const val FAILURE_BACKOFF_THRESHOLD = 5
    }
}

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
 * Wake-word detection using the platform speech recogniser in a loop.
 *
 * **This is an honest compromise, and it is worth stating plainly rather than burying.**
 *
 * A real always-on assistant uses a tiny dedicated hotword model — a few hundred
 * kilobytes, running on the DSP, costing almost nothing. Android exposes such a thing
 * through `AlwaysOnHotwordDetector`, but only to an app the user has set as the system
 * voice interaction service, only on hardware with a supported DSP, and only for a phrase
 * the platform will enrol. Most devices will not offer it to a third-party app at all.
 *
 * The alternative that always works is this one: run the normal recogniser in a loop and
 * check each result for the phrase. It is accurate, it needs no model download, and it
 * costs real battery — meaningfully more than a DSP hotword, and it keeps the microphone
 * indicator lit, which is Android telling the user the truth about what is happening.
 *
 * So: wake-word listening is **off by default**, the Settings screen states the battery
 * cost in plain language, and the foreground notification makes the state impossible to
 * miss. Tap-to-talk costs nothing and is the recommended default.
 *
 * Recognition is restarted after each result, with a short pause. The pause is not
 * cosmetic: restarting the recogniser instantly in a tight loop makes some OEM
 * implementations throw ERROR_RECOGNIZER_BUSY forever.
 */
class SpeechWakeWordDetector(
    private val recognizer: SpeechRecognizerProvider,
    private val matcherProvider: () -> WakeWordMatcher,
    private val languageProvider: () -> Language = { Language.MIXED }
) : WakeWordDetector {

    override val costDescription: String =
        "Uses the phone's speech recogniser continuously. Accurate, but it uses noticeably " +
            "more battery and keeps the microphone indicator on."

    override fun isSupported(): Boolean = recognizer.isAvailable()

    override fun detections(): Flow<WakeDetection> = flow {
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            val matcher = matcherProvider()
            var detected: WakeDetection? = null
            var failure: SpeechError? = null

            try {
                recognizer.listen(languageProvider(), partialResults = true).collect { event ->
                    when (event) {
                        // Reacting to partials means Ani wakes the instant "Rey" is heard
                        // rather than after the recogniser decides the sentence ended.
                        is SpeechEvent.Partial -> {
                            if (detected == null) {
                                detected = matcher.match(event.result.text).toDetection()
                            }
                        }

                        is SpeechEvent.Final -> {
                            if (detected == null) {
                                detected = matcher.match(event.result.text).toDetection()
                            }
                        }

                        is SpeechEvent.Failed -> failure = event.error
                        else -> Unit
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AniLog.w(TAG, "wake loop iteration failed", "error" to error.javaClass.simpleName)
                failure = SpeechError.OTHER
            }

            detected?.let {
                consecutiveFailures = 0
                emit(it)
            }

            // Back off when the recogniser is unhappy, rather than hammering it.
            consecutiveFailures = if (failure != null && detected == null) consecutiveFailures + 1 else 0
            val pause = when {
                failure == SpeechError.MICROPHONE_UNAVAILABLE -> MICROPHONE_BUSY_BACKOFF_MILLIS
                consecutiveFailures >= FAILURE_BACKOFF_THRESHOLD -> LONG_BACKOFF_MILLIS
                else -> RESTART_DELAY_MILLIS
            }
            delay(pause)
        }
    }

    private fun com.ani.nlu.dialog.WakeMatch.toDetection(): WakeDetection? {
        if (!matched) return null
        return WakeDetection(
            phrase = phrase.orEmpty(),
            confidence = confidence,
            trailingText = remainder.normalized.takeIf { it.isNotBlank() }
        )
    }

    private companion object {
        const val TAG = "AniWakeWord"
        const val RESTART_DELAY_MILLIS = 350L
        const val LONG_BACKOFF_MILLIS = 5_000L
        const val MICROPHONE_BUSY_BACKOFF_MILLIS = 10_000L
        const val FAILURE_BACKOFF_THRESHOLD = 5
    }
}

package com.ani.assistant.voice.wake

import com.ani.assistant.core.log.AniLog
import com.ani.nlu.dialog.WakeWordMatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.LogLevel
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Wake-word detection with Vosk in grammar mode. **The default engine.**
 *
 * Grammar mode is what makes this a keyword spotter rather than a speech recogniser:
 * instead of searching tens of thousands of words, the decoding graph is restricted to
 * the wake phrases plus `[unk]` ("anything else"). Two things follow, and both matter
 * here — the search space collapses, so CPU and battery cost drop a long way below
 * running a full recogniser; and false positives drop, because similar-sounding words are
 * not in the graph to be confused with.
 *
 * Why this is the default rather than Porcupine:
 *
 *  - **Free and Apache-2.0.** Picovoice discontinued its free tier on 30 June 2026, so
 *    Porcupine now needs a paid plan. That is a fine trade for a product; it is a poor
 *    default for a personal assistant.
 *  - **Any phrase works.** Porcupine can only detect phrases it has a trained `.ppn` for.
 *    Vosk will spot anything in the model's lexicon, so "Rey" — and whatever the user
 *    changes it to — works without training anything.
 *  - **Nothing leaves the phone.** The model is on disk and the audio never goes anywhere.
 *
 * What it costs: about 40 MB on disk for the acoustic model, and more CPU than a
 * purpose-built hotword DSP would use. [PorcupineWakeWordEngine] is the option for
 * someone who wants the last of the battery back and has a licence.
 */
class VoskWakeWordEngine(
    private val modelStore: VoskModelStore,
    private val phrasesProvider: () -> List<String>,
    private val sensitivityProvider: () -> WakeSensitivity,
    private val hasMicrophonePermission: () -> Boolean
) : WakeWordEngine {

    override val id = WakeWordEngineId.VOSK
    override val displayName = "On-device (Vosk)"
    override val supportsCustomPhrase = true

    override val costDescription: String =
        "Runs a small speech model on the phone, listening only for your wake phrase. " +
            "Nothing is uploaded and nothing is recorded. Uses about 40 MB of storage and " +
            "a modest amount of battery."

    @Volatile
    private var speechService: SpeechService? = null

    @Volatile
    private var paused: Boolean = false

    /**
     * True when the wake phrase was not in the model's vocabulary and detection fell back
     * to full recognition. Surfaced in Diagnostics — the behaviour still works, but it
     * costs more battery and the user deserves to know which mode they are in.
     */
    @Volatile
    var usingFullVocabulary: Boolean = false
        private set

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun availability(): WakeEngineAvailability = when {
        !hasMicrophonePermission() -> WakeEngineAvailability.Blocked("Microphone permission is not granted.")

        !modelStore.isInstalled() -> WakeEngineAvailability.NeedsModel(
            sizeMegabytes = VoskModelStore.APPROXIMATE_SIZE_MEGABYTES,
            howToInstall = "Download the wake-word model once. It then works with no internet."
        )

        else -> WakeEngineAvailability.Ready
    }

    override fun detections(): Flow<WakeDetection> = callbackFlow {
        val modelPath = modelStore.installedPath()
        if (modelPath == null) {
            AniLog.w(TAG, "wake model not installed")
            close()
            return@callbackFlow
        }
        if (!hasMicrophonePermission()) {
            AniLog.w(TAG, "microphone permission missing")
            close()
            return@callbackFlow
        }

        runCatching { LibVosk.setLogLevel(LogLevel.WARNINGS) }

        val phrases = phrasesProvider().filter { it.isNotBlank() }.ifEmpty { listOf("rey") }
        val matcher = WakeWordMatcher(
            phrases = phrases,
            sensitivity = sensitivityProvider().matcherSensitivity
        )
        // At low sensitivity only a completed utterance counts, which all but removes
        // false wakes at the cost of a beat more latency.
        val acceptPartials = sensitivityProvider() != WakeSensitivity.LOW

        var model: Model? = null
        var recognizer: Recognizer? = null
        var service: SpeechService? = null

        try {
            model = Model(modelPath)
            recognizer = buildRecognizer(model, phrases)
            service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service

            val listener = object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (!acceptPartials) return
                    emitIfWake(hypothesis, "partial", matcher) { trySend(it) }
                }

                override fun onResult(hypothesis: String?) {
                    emitIfWake(hypothesis, "text", matcher) { trySend(it) }
                }

                override fun onFinalResult(hypothesis: String?) {
                    emitIfWake(hypothesis, "text", matcher) { trySend(it) }
                }

                override fun onError(exception: Exception?) {
                    AniLog.w(TAG, "vosk error", "type" to (exception?.javaClass?.simpleName ?: "unknown"))
                }

                override fun onTimeout() = Unit
            }

            service.startListening(listener)
            // Respect a pause that was requested before the flow started.
            service.setPause(paused)
            AniLog.i(
                TAG,
                "wake engine listening",
                "phrases" to phrases.size,
                "fullVocabulary" to usingFullVocabulary
            )
        } catch (error: Exception) {
            AniLog.e(TAG, "could not start wake engine", error)
            runCatching { service?.shutdown() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            speechService = null
            close()
            return@callbackFlow
        }

        awaitClose {
            speechService = null
            runCatching { service.stop() }
            runCatching { service.shutdown() }
            runCatching { recognizer.close() }
            runCatching { model.close() }
            AniLog.i(TAG, "wake engine stopped")
        }
    }

    /**
     * Grammar mode where possible, full recognition where not.
     *
     * A grammar can only contain words the model's lexicon knows. "Rey" is not guaranteed
     * to be in an English lexicon, and a user-chosen phrase certainly is not, so an
     * out-of-vocabulary word makes the grammar constructor throw. Falling back to full
     * recognition keeps the feature working — it just costs more CPU, which Diagnostics
     * reports rather than hides.
     */
    private fun buildRecognizer(model: Model, phrases: List<String>): Recognizer {
        val grammar = buildGrammar(phrases)
        return try {
            Recognizer(model, SAMPLE_RATE, grammar).also { usingFullVocabulary = false }
        } catch (error: Exception) {
            AniLog.w(
                TAG,
                "wake phrase is outside the model vocabulary; using full recognition",
                "type" to error.javaClass.simpleName
            )
            Recognizer(model, SAMPLE_RATE).also { usingFullVocabulary = true }
        }
    }

    /** `["rey", "hey ani", "[unk]"]` — the phrases, plus a bucket for everything else. */
    private fun buildGrammar(phrases: List<String>): String {
        val words = phrases.map { phrase ->
            phrase.lowercase().filter { it.isLetter() || it.isWhitespace() }.trim()
        }.filter { it.isNotEmpty() }.distinct()
        val quoted = (words + UNKNOWN_TOKEN).joinToString(", ") { "\"$it\"" }
        return "[$quoted]"
    }

    private inline fun emitIfWake(
        hypothesis: String?,
        field: String,
        matcher: WakeWordMatcher,
        emit: (WakeDetection) -> Unit
    ) {
        if (paused) return
        val text = extractText(hypothesis, field) ?: return
        if (text.isBlank()) return

        val match = matcher.match(text)
        if (!match.matched) return

        emit(
            WakeDetection(
                phrase = match.phrase.orEmpty(),
                confidence = match.confidence,
                trailingText = match.remainder.normalized.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun extractText(hypothesis: String?, field: String): String? {
        if (hypothesis.isNullOrBlank()) return null
        return runCatching {
            (json.parseToJsonElement(hypothesis) as? JsonObject)
                ?.get(field)
                ?.jsonPrimitive
                ?.content
        }.getOrNull()
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        runCatching { speechService?.setPause(paused) }
    }

    override fun release() {
        runCatching { speechService?.shutdown() }
        speechService = null
    }

    private companion object {
        const val TAG = "AniVoskWake"
        const val SAMPLE_RATE = 16_000f

        /** Vosk's own token for "something that is not in the grammar". */
        const val UNKNOWN_TOKEN = "[unk]"
    }
}

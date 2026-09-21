package com.ani.assistant.voice.wake

import com.ani.assistant.core.log.AniLog
import com.ani.nlu.dialog.WakeWordMatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.ani.assistant.voice.audio.GainConfig
import com.ani.assistant.voice.audio.WakeAudioDiagnostics
import com.ani.assistant.voice.audio.WakeAudioPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.vosk.LogLevel
import org.vosk.LibVosk
import org.vosk.Model
import org.vosk.Recognizer

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
    private val hasMicrophonePermission: () -> Boolean,
    /** Live signal statistics, so quiet speech can be tuned on a real phone. */
    private val diagnostics: WakeAudioDiagnostics? = null,
    private val gainConfig: GainConfig = GainConfig()
) : WakeWordEngine {

    override val id = WakeWordEngineId.VOSK
    override val displayName = "On-device (Vosk)"
    override val supportsCustomPhrase = true

    override val costDescription: String =
        "Runs a small speech model on the phone, listening only for your wake phrase. " +
            "Nothing is uploaded and nothing is recorded. Uses about 40 MB of storage and " +
            "a modest amount of battery."

    /** The capture settings actually negotiated, for Diagnostics. Null while stopped. */
    val captureDescription: String?
        get() = pipeline?.config?.describe()

    @Volatile
    private var pipeline: WakeAudioPipeline? = null

    @Volatile
    private var paused: Boolean = false

    /** Set false to unwind the capture loop from outside it. */
    @Volatile
    private var capturing: Boolean = false

    /**
     * Counted down by the capture thread once it has closed the recorder.
     *
     * The whole point of the handover bug is that "we asked it to stop" and "it stopped"
     * are different facts, and only the capture thread knows the second one. A latch is
     * the smallest thing that can carry that answer back to a caller that is about to
     * open a second recorder.
     */
    @Volatile
    private var captureFinished: CountDownLatch? = null

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

        // Partials are what make a quiet word detectable in reasonable time: the decoder
        // offers a hypothesis mid-utterance, well before it decides the utterance ended.
        // At LOW sensitivity they are ignored, trading latency for the fewest false wakes.
        val acceptPartials = sensitivityProvider() != WakeSensitivity.LOW

        var model: Model? = null
        var recognizer: Recognizer? = null
        val audio = WakeAudioPipeline(gainConfig = gainConfig, diagnostics = diagnostics)

        try {
            model = Model(modelPath)
            recognizer = buildRecognizer(model, phrases)
            if (audio.open() == null) {
                error("microphone could not be opened")
            }
            pipeline = audio
        } catch (error: Exception) {
            AniLog.e(TAG, "could not start wake engine", error)
            diagnostics?.onCaptureError("The wake engine could not start.")
            runCatching { audio.close() }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            pipeline = null
            close()
            return@callbackFlow
        }

        val activeRecognizer = recognizer
        val finished = CountDownLatch(1)
        captureFinished = finished
        capturing = true

        // The read loop blocks, so it gets its own thread rather than stalling a
        // dispatcher that other work shares.
        val captureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                audio.captureInto(
                    isActive = { capturing },
                    isPaused = { paused }
                ) { buffer, length ->
                    // acceptWaveForm returns true when the decoder has settled on an
                    // utterance; false means a partial hypothesis is available.
                    val settled = activeRecognizer.acceptWaveForm(buffer, length)
                    if (settled) {
                        val text = extractText(activeRecognizer.result, "text")
                        if (!text.isNullOrBlank()) {
                            diagnostics?.onFinal(text)
                            emitIfWake(text, matcher) { trySend(it) }
                        }
                    } else if (acceptPartials) {
                        val partial = extractText(activeRecognizer.partialResult, "partial")
                        if (!partial.isNullOrBlank()) {
                            diagnostics?.onPartial(partial)
                            emitIfWake(partial, matcher) { trySend(it) }
                        }
                    }
                }
            } catch (error: Exception) {
                AniLog.e(TAG, "capture loop failed", error)
                diagnostics?.onCaptureError("The microphone stopped unexpectedly.")
            } finally {
                // The recorder is closed *by the thread that was reading it*. Closing an
                // AudioRecord from another thread while this one is blocked inside
                // read() is a native crash waiting to happen, and it is also what let
                // the microphone look free while it was not.
                runCatching { audio.close() }
                finished.countDown()
                close()
            }
        }

        AniLog.i(
            TAG,
            "wake engine listening",
            "phrases" to phrases.size,
            "fullVocabulary" to usingFullVocabulary,
            "partials" to acceptPartials
        )

        awaitClose {
            capturing = false
            captureJob.cancel()
            pipeline = null
            // Idempotent, and a no-op when the capture thread already closed it. It is
            // here for the case where the loop never started at all.
            runCatching { audio.close() }
            finished.countDown()
            runCatching { activeRecognizer.close() }
            runCatching { model.close() }
            diagnostics?.onCaptureClosed()
            AniLog.i(TAG, "[WAKE] engine stopped")
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

    /**
     * Emits only when the phrase matcher agrees, which is the false-positive defence.
     *
     * Deliberately *not* a substring test. `transcript.contains("rey")` would fire on
     * "grey", "prey" and on "rey" buried anywhere in a sentence; [WakeWordMatcher]
     * requires the phrase to lead the utterance and matches it phonetically rather than
     * literally. Making quiet speech audible is an audio problem, and it is solved in the
     * audio path — not by loosening this.
     */
    private inline fun emitIfWake(
        text: String,
        matcher: WakeWordMatcher,
        emit: (WakeDetection) -> Unit
    ) {
        if (paused) return
        if (text.isBlank()) return

        val match = matcher.match(text)
        if (!match.matched) return

        val detection = WakeDetection(
            phrase = match.phrase.orEmpty(),
            confidence = match.confidence,
            trailingText = match.remainder.normalized.takeIf { it.isNotBlank() }
        )
        diagnostics?.onDetection(detection.atEpochMillis)
        emit(detection)
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

    /**
     * Suspends forwarding without giving up the microphone.
     *
     * Capture keeps draining so the buffer cannot overflow and the noise estimate stays
     * current; nothing reaches the decoder. Full microphone release is [release], which is
     * what the service calls before the command recogniser needs the microphone.
     */
    override fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    override fun release() {
        capturing = false
        runCatching { pipeline?.close() }
        pipeline = null
    }

    /**
     * Stops capture and waits for the recorder to actually be closed.
     *
     * This is the method the microphone handover depends on. [release] asks; this one
     * confirms, and returns false rather than letting a caller open `SpeechRecognizer`
     * over a recorder that Android still has open — which does not throw, delivers
     * silence, and surfaces as `ERROR_NO_MATCH`.
     */
    override suspend fun releaseAndAwait(timeoutMillis: Long): Boolean {
        val latch = captureFinished
        val active = pipeline
        capturing = false

        val drained = if (latch == null) {
            true
        } else {
            // await() blocks, so it goes on IO. The wait is bounded and normally tens of
            // milliseconds: reads are 20 ms, so the loop notices `capturing` almost at
            // once.
            withContext(Dispatchers.IO) { latch.await(timeoutMillis, TimeUnit.MILLISECONDS) }
        }

        runCatching { active?.close() }
        pipeline = null
        captureFinished = null

        val stillRecording = active?.isRecording == true
        val released = drained && !stillRecording

        AniLog.i(
            TAG,
            "[MIC] wake microphone release",
            "drained" to drained,
            "stillRecording" to stillRecording,
            "released" to released
        )
        if (!released) {
            AniLog.w(TAG, "[MIC] wake engine did not confirm release within the timeout")
        }
        return released
    }

    private companion object {
        const val TAG = "AniVoskWake"
        const val SAMPLE_RATE = 16_000f

        /** Vosk's own token for "something that is not in the grammar". */
        const val UNKNOWN_TOKEN = "[unk]"
    }
}

package com.ani.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.audio.SpeechLevelMonitor
import com.ani.nlu.text.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The platform recogniser.
 *
 * Three Android details shape this class:
 *
 * 1. [SpeechRecognizer] must be created and driven from the main thread. Every call is
 *    marshalled there; getting this wrong produces a silent no-op rather than an error.
 * 2. A recogniser instance cannot be reused across overlapping sessions, and destroying
 *    one mid-callback crashes on some OEM builds. So each [listen] owns its instance and
 *    tears it down in `awaitClose`.
 * 3. It opens its own microphone. Nothing else in this app may hold one at the same
 *    time — see `MicArbiter`, which is what callers must go through.
 *
 * **Two bugs this file used to carry**, both of which showed up as Ani repeatedly saying
 * "sarigga vinapadatledu ra" however loudly the user spoke:
 *
 * - *Results could be silently dropped.* Every callback used `trySend` into a
 *   `callbackFlow` with the default 64-slot buffer, and `onRmsChanged` fires ten or more
 *   times a second. A slow collector filled the buffer with level updates and the
 *   `onResults` send then failed — `trySend` returns a result nobody was reading. The
 *   transcript existed and was thrown away. The channel is now unbounded, and a failed
 *   send of a result or an error is logged rather than ignored.
 * - *Every failure blamed the user's diction.* `ERROR_NO_MATCH` became "I didn't catch
 *   that", which is right when audio arrived and wrong when it did not. Levels are now
 *   accumulated through [SpeechLevelMonitor] and reported with the failure, so the
 *   caller can tell the two apart.
 *
 * `EXTRA_PREFER_OFFLINE` is not set: Telugu recognition is substantially better with the
 * network available, and forcing offline would quietly degrade the main use case.
 */
class AndroidSpeechRecognizerProvider(
    private val context: Context,
    /**
     * Whether to try `createOnDeviceSpeechRecognizer` first.
     *
     * Off by default, and deliberately so. On-device recognition needs a language pack
     * the user has downloaded, and for `en-IN` with Telugu words in it the networked
     * recogniser is markedly better. It is a switch rather than a guess, and whichever
     * one runs is announced through [SpeechEvent.Started].
     */
    private val preferOnDevice: () -> Boolean = { false }
) : SpeechRecognizerProvider {

    /** What ran last, for the Mic Test screen. Never a transcript. */
    @Volatile
    var lastRecognizerKind: RecognizerKind? = null
        private set

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Whether this device can recognise without the network at all. */
    fun isOnDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)

    override fun listen(language: Language, partialResults: Boolean): Flow<SpeechEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                AniLog.w(TAG, "[COMMAND] no recognition service installed")
                trySend(SpeechEvent.Failed(SpeechError.RECOGNIZER_UNAVAILABLE))
                close()
                return@callbackFlow
            }

            val localeTag = language.toLocaleTag()
            val levels = SpeechLevelMonitor()

            /** Results and errors must never be dropped; a lost send here is a real bug. */
            fun sendCritical(event: SpeechEvent) {
                val sent = trySend(event).isSuccess
                if (!sent) AniLog.e(TAG, "[COMMAND] dropped a critical event", null, "event" to event::class.simpleName)
            }

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    AniLog.i(TAG, "[COMMAND] onReadyForSpeech")
                    trySend(SpeechEvent.ReadyForSpeech)
                }

                override fun onBeginningOfSpeech() {
                    AniLog.i(TAG, "[COMMAND] onBeginningOfSpeech")
                    trySend(SpeechEvent.BeginningOfSpeech)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    levels.onRms(rmsdB)
                    // The platform reports roughly -2..10 dB. Normalise for the orb.
                    trySend(SpeechEvent.AudioLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    AniLog.i(TAG, "[COMMAND] onEndOfSpeech")
                    trySend(SpeechEvent.EndOfSpeech)
                }

                override fun onError(error: Int) {
                    val snapshot = levels.snapshot()
                    // The exact platform constant, by name. "It didn't work" is not a
                    // diagnosis, and ERROR_NO_MATCH with no audio means something very
                    // different from ERROR_NO_MATCH with a healthy signal.
                    AniLog.w(
                        TAG,
                        "[COMMAND] onError",
                        "code" to error,
                        "name" to errorName(error),
                        "audio" to snapshot.describe()
                    )
                    sendCritical(
                        SpeechEvent.Failed(
                            error = error.toSpeechError(),
                            audio = snapshot,
                            platformCode = error
                        )
                    )
                    close()
                }

                override fun onResults(results: Bundle?) {
                    val snapshot = levels.snapshot()
                    val result = results.bestResult(isPartial = false)
                    if (result == null) {
                        AniLog.w(TAG, "[COMMAND] onResults with no usable transcript", "audio" to snapshot.describe())
                        sendCritical(SpeechEvent.Failed(SpeechError.NOT_UNDERSTOOD, snapshot))
                    } else {
                        // Length and confidence only. The transcript itself is what the
                        // user said and never goes to logcat.
                        AniLog.i(
                            TAG,
                            "[COMMAND] onResults",
                            "length" to result.text.length,
                            "confidence" to "%.2f".format(result.confidence),
                            "audio" to snapshot.describe()
                        )
                        sendCritical(SpeechEvent.Final(result, snapshot))
                    }
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.bestResult(isPartial = true)?.let {
                        AniLog.d(TAG, "[COMMAND] onPartialResults", "length" to it.text.length)
                        trySend(SpeechEvent.Partial(it))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }

            val recognizer = withContext(Dispatchers.Main) {
                createRecognizer()
            }
            if (recognizer == null) {
                AniLog.e(TAG, "[COMMAND] could not create a recogniser")
                sendCritical(SpeechEvent.Failed(SpeechError.RECOGNIZER_UNAVAILABLE))
                close()
                return@callbackFlow
            }

            lastRecognizerKind = recognizer.kind
            AniLog.i(
                TAG,
                "[COMMAND] recognizer started",
                "kind" to recognizer.kind.name,
                "locale" to localeTag,
                "onDeviceAvailable" to isOnDeviceAvailable()
            )
            trySend(SpeechEvent.Started(recognizer.kind, localeTag))

            withContext(Dispatchers.Main) {
                recognizer.instance.setRecognitionListener(listener)
                recognizer.instance.startListening(intentFor(language, partialResults))
            }

            awaitClose {
                // Must also happen on the main thread, and must not throw during teardown.
                runCatching {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching {
                            recognizer.instance.stopListening()
                            recognizer.instance.cancel()
                            recognizer.instance.destroy()
                        }
                    }
                }
            }
        }
            // Unbounded, because onRmsChanged fires far faster than the collector drains
            // and the event that must never be dropped — the transcript — arrives last.
            .buffer(Channel.UNLIMITED)
            .flowOn(Dispatchers.Main.immediate)

    override suspend fun supportedLanguages(): List<String> = withContext(Dispatchers.IO) {
        // There is no synchronous API for this; the broadcast-based one is unreliable
        // across OEMs, so report what we ask for rather than inventing a list.
        listOf("en-IN", "te-IN")
    }

    override fun release() = Unit

    // ---------------------------------------------------------------------------------

    private class Created(val instance: SpeechRecognizer, val kind: RecognizerKind)

    /**
     * Builds the recogniser, preferring on-device only when asked and available.
     *
     * A fallback is always logged. Silently swapping recognisers is how you end up with a
     * bug that reproduces on one phone and not another with no way to tell why.
     */
    private fun createRecognizer(): Created? {
        if (preferOnDevice() && isOnDeviceAvailable()) {
            val onDevice = runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            }.getOrNull()
            if (onDevice != null) return Created(onDevice, RecognizerKind.ON_DEVICE)
            AniLog.w(TAG, "[COMMAND] on-device recogniser refused; using the platform one")
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrNull()
            ?.let { Created(it, RecognizerKind.PLATFORM) }
    }

    private fun intentFor(language: Language, partialResults: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLocaleTag())
            // Asked for separately from EXTRA_LANGUAGE: some OEM recognisers read only
            // one of the two, and a mismatch is how a Telugu speaker ends up transcribed
            // as if they were speaking American English.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.toLocaleTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)

            // Telugu and Tanglish speakers pause mid-sentence far more than these
            // defaults assume — "Rey ... Annayya ki ... call chey" is one thought, not
            // three. The stock ~1 s windows cut people off inside it, and a quiet speaker
            // gets cut off soonest because their pauses read as silence earlier.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MILLIS
            )
            // Keeps the recogniser open for at least this long even if it thinks the
            // utterance ended immediately, which is the failure mode for a whisper.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MINIMUM_SPEECH_MILLIS
            )
        }

    private fun Bundle?.bestResult(isPartial: Boolean): SpeechResult? {
        val matches = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val confidence = this.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            ?.firstOrNull()
            ?: DEFAULT_CONFIDENCE
        return SpeechResult(text = text, confidence = confidence, isPartial = isPartial)
    }

    private fun Int.toSpeechError(): SpeechError = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NOT_UNDERSTOOD
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_SPEECH
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.NETWORK
        SpeechRecognizer.ERROR_AUDIO -> SpeechError.MICROPHONE_UNAVAILABLE
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_MISSING
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechError.NETWORK
        SpeechRecognizer.ERROR_CLIENT -> SpeechError.OTHER
        else -> {
            AniLog.w(TAG, "unmapped recogniser error", "code" to this)
            SpeechError.OTHER
        }
    }

    /** The platform constant by name, so a log line is readable without a lookup table. */
    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
        else -> "UNKNOWN_$code"
    }

    private companion object {
        const val TAG = "AniSpeech"
        const val MAX_ALTERNATIVES = 3
        const val DEFAULT_CONFIDENCE = 0.5f

        /** Was 1500. A Tanglish sentence routinely contains a pause longer than that. */
        const val SILENCE_TIMEOUT_MILLIS = 2_500L
        const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 2_000L

        /** Do not close the microphone before the speaker has had a chance to start. */
        const val MINIMUM_SPEECH_MILLIS = 2_000L
    }
}

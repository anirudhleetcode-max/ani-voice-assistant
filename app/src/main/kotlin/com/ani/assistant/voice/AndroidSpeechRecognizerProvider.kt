package com.ani.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ani.assistant.core.log.AniLog
import com.ani.nlu.text.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The platform recogniser.
 *
 * Two Android details shape this class:
 *
 * 1. [SpeechRecognizer] must be created and driven from the main thread. Every call is
 *    marshalled there; getting this wrong produces a silent no-op rather than an error.
 * 2. A recogniser instance cannot be reused across overlapping sessions, and destroying
 *    one mid-callback crashes on some OEM builds. So each [listen] owns its instance and
 *    tears it down in `awaitClose`.
 *
 * `EXTRA_PREFER_OFFLINE` is not set: Telugu recognition is substantially better with the
 * network available, and forcing offline would quietly degrade the main use case. The
 * offline path is handled a level up, by [SpeechError.NETWORK] reaching the orchestrator.
 */
class AndroidSpeechRecognizerProvider(private val context: Context) : SpeechRecognizerProvider {

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(language: Language, partialResults: Boolean): Flow<SpeechEvent> =
        callbackFlow {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                trySend(SpeechEvent.Failed(SpeechError.RECOGNIZER_UNAVAILABLE))
                close()
                return@callbackFlow
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    trySend(SpeechEvent.ReadyForSpeech)
                }

                override fun onBeginningOfSpeech() {
                    trySend(SpeechEvent.BeginningOfSpeech)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // The platform reports roughly -2..10 dB. Normalise for the orb.
                    trySend(SpeechEvent.AudioLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    trySend(SpeechEvent.Failed(error.toSpeechError()))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    val result = results.bestResult(isPartial = false)
                    if (result == null) {
                        trySend(SpeechEvent.Failed(SpeechError.NOT_UNDERSTOOD))
                    } else {
                        trySend(SpeechEvent.Final(result))
                    }
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults.bestResult(isPartial = true)?.let {
                        trySend(SpeechEvent.Partial(it))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }

            withContext(Dispatchers.Main) {
                recognizer.setRecognitionListener(listener)
                recognizer.startListening(intentFor(language, partialResults))
            }

            awaitClose {
                // Must also happen on the main thread, and must not throw during teardown.
                runCatching {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        runCatching {
                            recognizer.stopListening()
                            recognizer.cancel()
                            recognizer.destroy()
                        }
                    }
                }
            }
        }.flowOn(Dispatchers.Main.immediate)

    override suspend fun supportedLanguages(): List<String> = withContext(Dispatchers.IO) {
        // There is no synchronous API for this; the broadcast-based one is unreliable
        // across OEMs, so report what we ask for rather than inventing a list.
        listOf("en-IN", "te-IN")
    }

    override fun release() = Unit

    private fun intentFor(language: Language, partialResults: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLocaleTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_ALTERNATIVES)

            // Telugu speakers pause mid-sentence more than these defaults assume; the
            // stock values cut people off halfway through "repu morning seven ki...".
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_TIMEOUT_MILLIS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_COMPLETE_SILENCE_MILLIS
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
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.MICROPHONE_UNAVAILABLE
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
        SpeechRecognizer.ERROR_CLIENT -> SpeechError.OTHER
        else -> {
            AniLog.w(TAG, "unmapped recogniser error", "code" to this)
            SpeechError.OTHER
        }
    }

    private companion object {
        const val TAG = "AniSpeech"
        const val MAX_ALTERNATIVES = 3
        const val DEFAULT_CONFIDENCE = 0.5f
        const val SILENCE_TIMEOUT_MILLIS = 1500L
        const val POSSIBLY_COMPLETE_SILENCE_MILLIS = 1200L
    }
}

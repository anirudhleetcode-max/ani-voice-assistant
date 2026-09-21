package com.ani.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.ani.assistant.core.log.AniLog
import com.ani.nlu.text.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The platform TTS engine.
 *
 * Initialisation is asynchronous and callers need to await it, so the engine is wrapped in
 * a [CompletableDeferred] that every method funnels through — otherwise the first thing
 * Ani says after a cold start is silently dropped, which reads as the app being broken.
 */
class AndroidTtsProvider(context: Context) : TtsProvider {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Mirrors [ready] for the synchronous callers that cannot suspend. */
    @Volatile
    private var initialisedSuccessfully: Boolean? = null

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (!ok) AniLog.w(TAG, "TTS engine failed to initialise", "status" to status)
        initialisedSuccessfully = ok
        ready.complete(ok)
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(true) }
            }

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                AniLog.w(TAG, "utterance failed", "code" to errorCode)
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
        })
    }

    override suspend fun awaitReady(): Boolean = ready.await()

    override fun availabilityFor(language: Language): TtsAvailability {
        if (initialisedSuccessfully == false) return TtsAvailability.UNAVAILABLE
        val locale = language.toLocale()
        return when (engine.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> TtsAvailability.READY

            TextToSpeech.LANG_MISSING_DATA -> TtsAvailability.NEEDS_DOWNLOAD
            TextToSpeech.LANG_NOT_SUPPORTED -> TtsAvailability.UNSUPPORTED
            else -> TtsAvailability.UNAVAILABLE
        }
    }

    override suspend fun voicesFor(language: Language): List<TtsVoice> {
        if (!awaitReady()) return emptyList()
        val wanted = language.toLocale().language
        return runCatching {
            engine.voices.orEmpty()
                .filter { it.locale.language == wanted }
                .map { it.toTtsVoice() }
                .sortedBy { it.displayName }
        }.getOrElse {
            AniLog.w(TAG, "could not enumerate voices")
            emptyList()
        }
    }

    override suspend fun speak(text: String, language: Language): Boolean {
        if (text.isBlank()) return true
        if (!awaitReady()) return false

        val locale = language.toLocale()
        val availability = availabilityFor(language)
        if (availability == TtsAvailability.READY) {
            engine.language = locale
        } else {
            // Speaking Telugu through an English voice is worse than not speaking. Fall
            // back to the engine's default and let the caller surface the "install the
            // Telugu voice" message.
            AniLog.w(TAG, "voice unavailable for language", "availability" to availability)
        }

        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Boolean>()
        pending[utteranceId] = completion

        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(utteranceId)
            return false
        }

        return try {
            completion.await()
        } catch (cancellation: CancellationException) {
            // The user interrupted Ani; stop mid-sentence rather than finishing.
            pending.remove(utteranceId)
            runCatching { engine.stop() }
            throw cancellation
        } finally {
            pending.remove(utteranceId)
        }
    }

    override fun stop() {
        runCatching { engine.stop() }
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    override fun setRate(rate: Float) {
        engine.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
    }

    override fun setPitch(pitch: Float) {
        engine.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))
    }

    override fun setVoice(voiceName: String?) {
        if (voiceName == null) return
        runCatching {
            engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
        }
    }

    override fun shutdown() {
        stop()
        runCatching { engine.shutdown() }
    }

    private fun Voice.toTtsVoice() = TtsVoice(
        name = name,
        localeTag = locale.toLanguageTag(),
        displayName = buildString {
            append(locale.displayName)
            if (quality >= Voice.QUALITY_HIGH) append(" (high quality)")
        },
        isInstalled = !features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        isNetworkRequired = features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    )

    /**
     * Mixed speech is spoken with the Telugu voice.
     *
     * A Telugu voice reading "Spotify" is perfectly intelligible; an English voice reading
     * "chesthunna" is not. When the sentence contains both, the Telugu voice is the one
     * that makes the whole thing understandable.
     */
    private fun Language.toLocale(): Locale = when (this) {
        Language.TELUGU, Language.MIXED -> Locale.Builder().setLanguage("te").setRegion("IN").build()
        Language.ENGLISH -> Locale.Builder().setLanguage("en").setRegion("IN").build()
        Language.UNKNOWN -> Locale.Builder().setLanguage("en").setRegion("IN").build()
    }

    private companion object {
        const val TAG = "AniTts"
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 2.0f
        const val MIN_PITCH = 0.5f
        const val MAX_PITCH = 2.0f
    }
}

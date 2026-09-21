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

    /**
     * The locale the engine is currently set to.
     *
     * `isLanguageAvailable` and setting `engine.language` are both binder calls into the
     * TTS service, and the second can trigger a voice load. Doing them before every
     * single utterance, when the language almost never changes between turns, put that
     * cost on the critical path of every reply.
     */
    @Volatile
    private var currentLocale: Locale? = null

    /** Cached per locale, because the answer does not change while the app is running. */
    private val availabilityCache = ConcurrentHashMap<String, TtsAvailability>()

    /** Utterances waiting for their first audio callback. */
    private val firstAudioCallbacks = ConcurrentHashMap<String, () -> Unit>()

    private val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (!ok) AniLog.w(TAG, "TTS engine failed to initialise", "status" to status)
        initialisedSuccessfully = ok
        ready.complete(ok)
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // The moment audio actually begins, as distinct from the moment speak()
                // was called. Everything between the two is synthesis.
                utteranceId?.let { firstAudioCallbacks.remove(it)?.invoke() }
            }

            override fun onDone(utteranceId: String?) {
                utteranceId?.let {
                    firstAudioCallbacks.remove(it)
                    pending.remove(it)?.complete(true)
                }
            }

            // Abstract in the base class and deprecated since API 21; the typed
            // overload below is what actually fires on modern platforms.
            @Suppress("OVERRIDE_DEPRECATION")
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

    override suspend fun prepare(language: Language) {
        if (!awaitReady()) return
        val started = System.currentTimeMillis()
        applyLanguage(language)
        val elapsed = System.currentTimeMillis() - started
        if (elapsed > SLOW_PREPARE_MILLIS) {
            // Worth knowing: a voice that takes this long to load from warm is almost
            // certainly a network voice, and that cost lands on every reply.
            AniLog.i(TAG, "[TTS] prepare was slow", "millis" to elapsed)
        }
    }

    override fun availabilityFor(language: Language): TtsAvailability {
        if (initialisedSuccessfully == false) return TtsAvailability.UNAVAILABLE
        val locale = language.toLocale()
        availabilityCache[locale.toLanguageTag()]?.let { return it }
        val availability = queryAvailability(locale)
        availabilityCache[locale.toLanguageTag()] = availability
        return availability
    }

    /**
     * Whether the chosen voice synthesises over the network.
     *
     * A network voice is a latency source that no amount of local warming removes, and a
     * silent one — it simply takes longer, with no error to point at.
     */
    fun requiresNetwork(): Boolean = runCatching {
        engine.voice?.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
    }.getOrDefault(false)

    private fun queryAvailability(locale: Locale): TtsAvailability {
        return when (engine.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> TtsAvailability.READY

            TextToSpeech.LANG_MISSING_DATA -> TtsAvailability.NEEDS_DOWNLOAD
            TextToSpeech.LANG_NOT_SUPPORTED -> TtsAvailability.UNSUPPORTED
            else -> TtsAvailability.UNAVAILABLE
        }
    }

    /** Sets the engine language, but only when it is not already set to it. */
    private fun applyLanguage(language: Language) {
        val locale = language.toLocale()
        if (currentLocale == locale) return

        if (availabilityFor(language) == TtsAvailability.READY) {
            engine.language = locale
            currentLocale = locale
        } else {
            // Speaking Telugu through an English voice is worse than not speaking. Fall
            // back to the engine's default and let the caller surface the "install the
            // Telugu voice" message.
            AniLog.w(TAG, "voice unavailable for language", "availability" to availabilityFor(language))
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

    override suspend fun speak(
        text: String,
        language: Language,
        onFirstAudio: () -> Unit
    ): Boolean {
        if (text.isBlank()) return true

        val requestedAt = System.currentTimeMillis()
        if (!awaitReady()) return false
        val readyAt = System.currentTimeMillis()

        applyLanguage(language)

        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Boolean>()
        pending[utteranceId] = completion

        var firstAudioAt = 0L
        firstAudioCallbacks[utteranceId] = {
            firstAudioAt = System.currentTimeMillis()
            onFirstAudio()
        }

        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(utteranceId)
            firstAudioCallbacks.remove(utteranceId)
            AniLog.w(TAG, "[TTS] speak was refused by the engine")
            return false
        }

        return try {
            val completed = completion.await()
            val completedAt = System.currentTimeMillis()
            // Lengths and durations only — never the sentence.
            AniLog.i(
                TAG,
                "[TTS] spoken",
                "characters" to text.length,
                "waitedForInitMs" to (readyAt - requestedAt),
                "requestToFirstAudioMs" to
                    (if (firstAudioAt > 0) firstAudioAt - readyAt else -1L),
                "firstAudioToDoneMs" to
                    (if (firstAudioAt > 0) completedAt - firstAudioAt else -1L),
                "networkVoice" to requiresNetwork(),
                "completed" to completed
            )
            completed
        } catch (cancellation: CancellationException) {
            // The user interrupted Ani; stop mid-sentence rather than finishing.
            pending.remove(utteranceId)
            runCatching { engine.stop() }
            throw cancellation
        } finally {
            pending.remove(utteranceId)
            firstAudioCallbacks.remove(utteranceId)
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
            engine.voices?.firstOrNull { it.name == voiceName }?.let {
                engine.voice = it
                // The engine's locale follows the voice, so the cache would otherwise
                // believe a language is still applied when it is not.
                currentLocale = null
            }
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

        /** Above this, warming took long enough to be worth a log line. */
        const val SLOW_PREPARE_MILLIS = 250L
    }
}

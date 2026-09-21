package com.ani.assistant.voice

import android.media.AudioManager
import android.media.ToneGenerator
import com.ani.assistant.assistant.AniOrchestrator
import com.ani.assistant.assistant.AniTurn
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.data.settings.SettingsRepository
import com.ani.assistant.voice.audio.AudioVerdict
import com.ani.assistant.voice.audio.SpeechLevelSnapshot
import com.ani.assistant.voice.mic.MicAcquisition
import com.ani.assistant.voice.mic.MicArbiter
import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.response.Responses
import com.ani.nlu.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One end-to-end voice exchange: listen, understand, act, answer.
 *
 * Shared by the foreground wake-word service and by tapping the orb, so both paths go
 * through exactly the same pipeline — a command typed in the UI, spoken after "Rey", or
 * spoken after a tap all behave identically.
 *
 * Only one exchange runs at a time. A second request cancels the first rather than
 * queueing, because a user who taps the orb while Ani is talking wants to interrupt it.
 */
class VoiceSession(
    private val recognizer: SpeechRecognizerProvider,
    private val tts: TtsProvider,
    private val orchestrator: AniOrchestrator,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
    /**
     * The single gate to the microphone.
     *
     * Not optional and not a nicety. Without it the orb path opened `SpeechRecognizer`
     * while the wake engine still held an `AudioRecord`; Android reports no error for
     * that, the recogniser receives silence, and the user is told "sarigga vinapadatledu
     * ra" no matter how loudly they speak.
     */
    private val micArbiter: MicArbiter = MicArbiter()
) {

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /** Live partial transcription, for the "..." under the orb. */
    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _turns = MutableSharedFlow<AniTurn>(extraBufferCapacity = 8)

    /** Completed exchanges, for the UI to append to its transcript. */
    val turns: SharedFlow<AniTurn> = _turns.asSharedFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _errors.asStateFlow()

    private var exchangeJob: Job? = null

    /** Whether a wake-word loop should pause; true while an exchange is in progress. */
    val isBusy: Boolean get() = _state.value.isActive

    // ---------------------------------------------------------------------------------

    /** Starts listening for a command. Called after the wake word, or on a tap. */
    fun startListening(playChime: Boolean = true) {
        exchangeJob?.cancel()
        exchangeJob = scope.launch {
            try {
                runExchange(playChime)
            } catch (error: Exception) {
                AniLog.e(TAG, "voice exchange failed", error)
                _state.value = VoiceState.ERROR
            }
        }
    }

    /** Handles text the user typed, bypassing the microphone entirely. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        exchangeJob?.cancel()
        exchangeJob = scope.launch {
            _state.value = VoiceState.PROCESSING
            processAndSpeak(text, speakAloud = false)
            _state.value = VoiceState.IDLE
        }
    }

    /** Stops whatever is happening: recognition, thinking or speaking. */
    fun stop() {
        exchangeJob?.cancel()
        exchangeJob = null
        tts.stop()
        _audioLevel.value = 0f
        _partialTranscript.value = ""
        _state.value = VoiceState.IDLE
    }

    fun clearError() {
        _errors.value = null
    }

    // ---------------------------------------------------------------------------------

    private suspend fun runExchange(playChime: Boolean) {
        val settings = settingsRepository.settings.first()
        tts.setRate(settings.speechRate)
        tts.setPitch(settings.speechPitch)
        settings.ttsVoiceName?.let { tts.setVoice(it) }

        if (playChime && settings.playActivationSound) {
            _state.value = VoiceState.WAKE_DETECTED
            playActivationTone()
        }

        var keepListening = true

        while (keepListening && scope.isActive) {
            val heard = captureUtterance(settings.language ?: Language.MIXED)
            if (heard == null) {
                keepListening = false
                continue
            }

            _state.value = VoiceState.PROCESSING
            val turn = processAndSpeak(heard, speakAloud = true)

            // Stay in the conversation when Ani asked something, or when the user turned
            // on follow-ups. Otherwise go quiet — an assistant that keeps the microphone
            // open after answering is unsettling and expensive.
            keepListening = turn != null && (turn.expectsReply || settings.autoFollowUp)
        }

        _state.value = VoiceState.IDLE
        _audioLevel.value = 0f
        _partialTranscript.value = ""
    }

    /**
     * Listens, retrying once, and says something true when it cannot.
     *
     * @return the final transcription, or null when nothing usable was heard.
     */
    private suspend fun captureUtterance(language: Language): String? {
        var attempt = 0

        while (attempt < RecognitionRetryPolicy.MAX_ATTEMPTS) {
            attempt++

            // Take the microphone properly. The wake engine is asked to stop and is
            // *waited for*; if it will not confirm, recognition is not started at all,
            // because a second recorder over the first one produces silence rather than
            // an error.
            val acquired = micArbiter.acquireForCommand()
            if (acquired !is MicAcquisition.Granted) {
                AniLog.w(TAG, "[PIPELINE] COMMAND_LISTENING refused", "reason" to (acquired as MicAcquisition.Denied).reason.name)
                speak(FailureMessage.MICROPHONE_BLOCKED)
                return null
            }

            val outcome = try {
                listenOnce(language, attempt)
            } finally {
                micArbiter.releaseCommand()
            }

            outcome.transcript?.let { return it }

            val decision = RecognitionRetryPolicy.decide(
                error = outcome.error,
                verdict = outcome.audio.verdict,
                attempt = attempt
            )
            AniLog.i(
                TAG,
                "[COMMAND] attempt failed",
                "attempt" to attempt,
                "error" to (outcome.error?.name ?: "none"),
                "audio" to outcome.audio.describe(),
                "retry" to decision.retry,
                "say" to decision.message.name
            )

            speak(decision.message)
            if (!decision.retry) return null
        }
        return null
    }

    /** One pass of the recogniser. Owns nothing; the caller holds the microphone. */
    private suspend fun listenOnce(language: Language, attempt: Int): AttemptOutcome {
        _state.value = VoiceState.LISTENING
        _partialTranscript.value = ""
        AniLog.i(TAG, "[PIPELINE] COMMAND_LISTENING", "attempt" to attempt)

        var finalText: String? = null
        var failure: SpeechError? = null
        var audio = SpeechLevelSnapshot.EMPTY
        var heardAnything = false

        withTimeoutOrNull(LISTEN_TIMEOUT_MILLIS) {
            recognizer.listen(language, partialResults = true).collect { event ->
                when (event) {
                    is SpeechEvent.Started -> AniLog.i(
                        TAG,
                        "[PIPELINE] recognizer active",
                        "kind" to event.kind.name,
                        "locale" to event.localeTag
                    )
                    is SpeechEvent.BeginningOfSpeech -> {
                        heardAnything = true
                        AniLog.i(TAG, "[PIPELINE] COMMAND_AUDIO_RECEIVED")
                    }
                    is SpeechEvent.AudioLevel -> _audioLevel.value = event.level
                    is SpeechEvent.Partial -> _partialTranscript.value = event.result.text
                    is SpeechEvent.Final -> {
                        finalText = event.result.text
                        audio = event.audio
                    }
                    is SpeechEvent.Failed -> {
                        failure = event.error
                        audio = event.audio
                    }
                    else -> Unit
                }
            }
        }

        _audioLevel.value = 0f
        val transcript = finalText

        AniLog.i(
            TAG,
            "[PIPELINE] COMMAND_RESULT",
            "hasTranscript" to (transcript != null),
            "length" to (transcript?.length ?: 0),
            "beganSpeech" to heardAnything,
            "audio" to audio.describe()
        )

        return AttemptOutcome(transcript = transcript, error = failure, audio = audio)
    }

    private data class AttemptOutcome(
        val transcript: String?,
        val error: SpeechError?,
        val audio: SpeechLevelSnapshot
    )

    /**
     * Says the one thing that is actually true about this failure.
     *
     * Every branch here used to be "I didn't catch that", including the ones where no
     * audio existed, the permission was missing or another app held the recorder. Telling
     * someone to speak up when the microphone was never theirs is worse than saying
     * nothing.
     */
    private suspend fun speak(message: FailureMessage) {
        when (message) {
            FailureMessage.NONE -> Unit
            FailureMessage.DID_NOT_CATCH -> announce { Responses.didNotCatch(it) }
            FailureMessage.SAY_IT_AGAIN -> announce { Responses.sayItAgain(it) }
            FailureMessage.MICROPHONE_BLOCKED -> announce { Responses.microphoneBusy(it) }
            FailureMessage.NO_INTERNET -> announce { Responses.noInternet(it) }
            FailureMessage.PERMISSION_MISSING -> announce {
                Responses.permissionMissing("Microphone", it)
            }
            FailureMessage.NO_RECOGNIZER -> announce {
                if (it.speaksTelugu) {
                    "Ee phone lo speech recognition ledu${it.particle}."
                } else {
                    "This phone has no speech recognition installed."
                }
            }
            FailureMessage.GENERIC_ERROR -> announce { Responses.somethingWentWrong(it) }
        }
    }

    private suspend fun processAndSpeak(text: String, speakAloud: Boolean): AniTurn? {
        val turn = try {
            orchestrator.handle(text)
        } catch (error: Exception) {
            AniLog.e(TAG, "orchestration failed", error)
            _state.value = VoiceState.ERROR
            return null
        }

        _turns.emit(turn)
        _partialTranscript.value = ""

        if (speakAloud && turn.response.isNotBlank()) {
            _state.value = VoiceState.SPEAKING
            tts.speak(turn.response, turn.command.language)
        }
        return turn
    }

    private suspend fun announce(message: (ResponseStyle) -> String) {
        val settings = settingsRepository.settings.first()
        val style = settings.responseStyle(Language.MIXED)
        val text = message(style)
        _errors.value = text
        _state.value = VoiceState.SPEAKING
        tts.speak(text, style.language)
    }

    /**
     * A short beep on the notification stream so the user knows Ani woke up.
     *
     * [ToneGenerator] rather than a bundled sound file: it needs no asset, respects the
     * notification volume, and is inaudible when the phone is silenced — all of which are
     * the behaviours you want from an activation chime.
     */
    private fun playActivationTone() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MILLIS)
                scope.launch {
                    kotlinx.coroutines.delay(TONE_RELEASE_DELAY_MILLIS)
                    runCatching { release() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "AniVoiceSession"
        const val LISTEN_TIMEOUT_MILLIS = 15_000L
        const val TONE_VOLUME = 60
        const val TONE_DURATION_MILLIS = 120
        const val TONE_RELEASE_DELAY_MILLIS = 500L
    }
}

package com.ani.assistant.voice

import android.media.AudioManager
import android.media.ToneGenerator
import com.ani.assistant.assistant.AniOrchestrator
import com.ani.assistant.assistant.AniTurn
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.data.settings.SettingsRepository
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
    private val scope: CoroutineScope
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

    /** @return the final transcription, or null when nothing usable was heard. */
    private suspend fun captureUtterance(language: Language): String? {
        _state.value = VoiceState.LISTENING
        _partialTranscript.value = ""

        var finalText: String? = null
        var failure: SpeechError? = null

        withTimeoutOrNull(LISTEN_TIMEOUT_MILLIS) {
            recognizer.listen(language, partialResults = true).collect { event ->
                when (event) {
                    is SpeechEvent.AudioLevel -> _audioLevel.value = event.level
                    is SpeechEvent.Partial -> _partialTranscript.value = event.result.text
                    is SpeechEvent.Final -> finalText = event.result.text
                    is SpeechEvent.Failed -> failure = event.error
                    else -> Unit
                }
            }
        }

        _audioLevel.value = 0f

        // Copied out of the closure so the null check narrows the type.
        val transcription = finalText
        if (transcription != null) return transcription

        // Silence is not an error worth announcing; everything else is.
        when (val error = failure) {
            SpeechError.NO_SPEECH, null -> Unit
            SpeechError.NOT_UNDERSTOOD -> announce { Responses.didNotCatch(it) }
            SpeechError.NETWORK -> announce { Responses.noInternet(it) }
            SpeechError.MICROPHONE_UNAVAILABLE -> announce {
                Responses.permissionMissing("Microphone", it)
            }
            SpeechError.RECOGNIZER_UNAVAILABLE -> announce {
                if (it.speaksTelugu) {
                    "Ee phone lo speech recognition ledu${it.particle}."
                } else {
                    "This phone has no speech recognition installed."
                }
            }
            SpeechError.BUSY, SpeechError.OTHER -> AniLog.d(TAG, "recognition ended", "error" to error)
        }
        return null
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

package com.ani.assistant.voice.mic

import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.AndroidSpeechRecognizerProvider
import com.ani.assistant.voice.RecognizerKind
import com.ani.assistant.voice.SpeechEvent
import com.ani.assistant.voice.SpeechRecognizerProvider
import com.ani.assistant.voice.audio.AudioVerdict
import com.ani.assistant.voice.audio.WakeAudioPipeline
import com.ani.nlu.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** What the Mic Test screen shows. Levels and states only — never audio, never a recording. */
data class MicTestState(
    val permissionGranted: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,

    // ---- capture configuration, read back from the platform ----
    val audioSource: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val encoding: String? = null,
    val bufferBytes: Int? = null,
    val minimumBufferBytes: Int? = null,
    val recordInitialised: Boolean? = null,
    val recording: Boolean? = null,

    // ---- what the microphone actually delivered ----
    val rms: Float = 0f,
    val peak: Int = 0,
    val minRms: Float = 0f,
    val maxRms: Float = 0f,
    val noiseFloor: Float = 0f,
    val appliedGain: Float = 1f,
    /** Fraction of blocks above the silence gate, 0..1. */
    val aboveSilenceRatio: Float = 0f,
    val blocksMeasured: Int = 0,
    val verdict: AudioVerdict = AudioVerdict.UNKNOWN,

    // ---- the recogniser ----
    val recognizerKind: RecognizerKind? = null,
    val onDeviceAvailable: Boolean? = null,
    val recognizerState: String = "idle",
    val lastError: String? = null,
    /** Debug builds only; see [MicTestController.retainTranscripts]. */
    val lastPartial: String? = null,
    val lastFinal: String? = null
) {
    enum class Phase { IDLE, MEASURING_LEVELS, RECOGNISING, DONE }

    /** True when the measurement says the microphone delivered real audio. */
    val speechDetected: Boolean
        get() = verdict == AudioVerdict.LOW_AUDIO ||
            verdict == AudioVerdict.NORMAL_AUDIO ||
            verdict == AudioVerdict.CLIPPED
}

/**
 * The developer microphone test.
 *
 * It exists because of a question that could not be answered from inside a failing
 * command: *is the phone receiving audio at all?* During recognition, `SpeechRecognizer`
 * owns the microphone and the only telemetry is `onRmsChanged`, whose scale is not
 * standardised. This screen instead opens the recorder itself, while nothing else holds
 * it, and reports genuine PCM figures in LSB.
 *
 * So there are two measurements and they answer different questions:
 *
 *  - **Level test** — real `AudioRecord` numbers: RMS, peak, noise floor, the negotiated
 *    source, rate and buffer. If loud speech reads the same as silence here, the problem
 *    is capture or microphone ownership and nothing downstream can fix it.
 *  - **Recogniser test** — runs the same recogniser a command uses, and reports which one
 *    ran, the exact error constant, and whether a transcript came back. If levels are
 *    healthy here and this still says `ERROR_NO_MATCH`, the microphone is fine and the
 *    recogniser or its language is the problem.
 *
 * It goes through [MicArbiter] like everything else. A diagnostic that takes the
 * microphone from the wake engine without asking would be testing the bug it is meant to
 * find.
 */
class MicTestController(
    private val arbiter: MicArbiter,
    private val recognizer: SpeechRecognizerProvider,
    private val hasMicrophonePermission: () -> Boolean,
    private val scope: CoroutineScope,
    /**
     * Whether transcripts may be shown.
     *
     * A transcript is what the user said — exactly the thing the rest of the app takes
     * care never to retain. It is held in memory only, never written to logcat and never
     * persisted, and the caller passes `BuildConfig.DEBUG`.
     */
    private val retainTranscripts: Boolean = false
) {

    private val _state = MutableStateFlow(MicTestState())
    val state: StateFlow<MicTestState> = _state.asStateFlow()

    private var job: Job? = null

    fun refreshPermission() {
        _state.value = _state.value.copy(permissionGranted = hasMicrophonePermission())
    }

    /**
     * Opens the microphone directly and measures for [durationMillis].
     *
     * Three seconds by default: long enough to say a sentence, short enough that a user
     * running it four times (silence, quiet, normal, loud) is not bored.
     */
    fun startLevelTest(durationMillis: Long = DEFAULT_MEASURE_MILLIS) {
        if (!hasMicrophonePermission()) {
            _state.value = MicTestState(permissionGranted = false, lastError = "Microphone permission is not granted.")
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = MicTestState(
                permissionGranted = true,
                running = true,
                phase = MicTestState.Phase.MEASURING_LEVELS
            )

            val acquired = arbiter.acquireForCommand()
            if (acquired !is MicAcquisition.Granted) {
                _state.value = _state.value.copy(
                    running = false,
                    phase = MicTestState.Phase.DONE,
                    lastError = "Could not take the microphone: " +
                        (acquired as MicAcquisition.Denied).reason.name
                )
                return@launch
            }

            try {
                measure(durationMillis)
            } finally {
                arbiter.releaseCommand()
            }
        }
    }

    /** Runs one real recognition attempt and reports exactly what the platform said. */
    fun startRecognizerTest(language: Language = Language.MIXED) {
        if (!hasMicrophonePermission()) {
            _state.value = MicTestState(permissionGranted = false, lastError = "Microphone permission is not granted.")
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(
                permissionGranted = true,
                running = true,
                phase = MicTestState.Phase.RECOGNISING,
                recognizerState = "starting",
                lastError = null,
                lastPartial = null,
                lastFinal = null,
                onDeviceAvailable = (recognizer as? AndroidSpeechRecognizerProvider)?.isOnDeviceAvailable()
            )

            val acquired = arbiter.acquireForCommand()
            if (acquired !is MicAcquisition.Granted) {
                _state.value = _state.value.copy(
                    running = false,
                    phase = MicTestState.Phase.DONE,
                    recognizerState = "blocked",
                    lastError = "Could not take the microphone: " +
                        (acquired as MicAcquisition.Denied).reason.name
                )
                return@launch
            }

            try {
                recognizer.listen(language, partialResults = true).collect { event ->
                    _state.value = when (event) {
                        is SpeechEvent.Started -> _state.value.copy(
                            recognizerKind = event.kind,
                            recognizerState = "started (${event.localeTag})"
                        )
                        is SpeechEvent.ReadyForSpeech -> _state.value.copy(recognizerState = "ready")
                        is SpeechEvent.BeginningOfSpeech -> _state.value.copy(recognizerState = "hearing speech")
                        is SpeechEvent.EndOfSpeech -> _state.value.copy(recognizerState = "end of speech")
                        is SpeechEvent.Partial -> _state.value.copy(
                            lastPartial = if (retainTranscripts) event.result.text else HIDDEN
                        )
                        is SpeechEvent.Final -> _state.value.copy(
                            recognizerState = "final result",
                            verdict = event.audio.verdict,
                            lastFinal = if (retainTranscripts) event.result.text else HIDDEN
                        )
                        is SpeechEvent.Failed -> _state.value.copy(
                            recognizerState = "failed",
                            verdict = event.audio.verdict,
                            lastError = "${event.error.name}" +
                                (event.platformCode?.let { " (platform code $it)" } ?: "") +
                                " · ${event.audio.describe()}"
                        )
                    }
                }
            } finally {
                arbiter.releaseCommand()
                _state.value = _state.value.copy(running = false, phase = MicTestState.Phase.DONE)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        arbiter.releaseCommand()
        _state.value = _state.value.copy(running = false, phase = MicTestState.Phase.IDLE)
    }

    // ---------------------------------------------------------------------------------

    private suspend fun measure(durationMillis: Long) = withContext(Dispatchers.IO) {
        val pipeline = WakeAudioPipeline()
        val config = pipeline.open()
        if (config == null) {
            _state.value = _state.value.copy(
                running = false,
                phase = MicTestState.Phase.DONE,
                lastError = "The microphone would not open."
            )
            return@withContext
        }

        _state.value = _state.value.copy(
            audioSource = config.describe().substringBefore(" ·"),
            sampleRate = config.captureSampleRate,
            channels = 1,
            encoding = "PCM_16BIT",
            bufferBytes = config.bufferSizeBytes,
            recordInitialised = true,
            recording = true
        )

        val deadline = System.currentTimeMillis() + durationMillis
        var blocks = 0
        var aboveGate = 0
        var minRms = Float.MAX_VALUE
        var maxRms = 0f
        var peak = 0

        try {
            pipeline.captureInto(
                isActive = { System.currentTimeMillis() < deadline },
                isPaused = { false }
            ) { buffer, length ->
                // Statistics are computed from the buffer in place and the buffer is then
                // dropped. Nothing is copied out, nothing is written anywhere.
                var sumSquares = 0.0
                var blockPeak = 0
                for (index in 0 until length) {
                    val sample = buffer[index].toInt()
                    sumSquares += sample.toDouble() * sample.toDouble()
                    val magnitude = abs(sample).coerceAtMost(Short.MAX_VALUE.toInt())
                    if (magnitude > blockPeak) blockPeak = magnitude
                }
                val blockRms = if (length == 0) 0f else kotlin.math.sqrt(sumSquares / length).toFloat()

                blocks++
                if (blockRms < minRms) minRms = blockRms
                if (blockRms > maxRms) maxRms = blockRms
                if (blockPeak > peak) peak = blockPeak
                if (blockRms > SILENCE_RMS) aboveGate++
            }
        } catch (error: Exception) {
            AniLog.e(TAG, "mic test capture failed", error)
            _state.value = _state.value.copy(lastError = "Capture stopped unexpectedly.")
        } finally {
            pipeline.close()
            // Read back rather than assumed: "we closed it" and "Android released it"
            // are the two facts this whole screen exists to keep apart.
            _state.value = _state.value.copy(recording = pipeline.isRecording)
        }

        val ratio = if (blocks == 0) 0f else aboveGate.toFloat() / blocks
        val verdict = classify(blocks, if (blocks == 0) 0f else minRms, maxRms, peak, ratio)

        AniLog.i(
            TAG,
            "[AUDIO] mic test complete",
            "blocks" to blocks,
            "minRms" to "%.0f".format(if (blocks == 0) 0f else minRms),
            "maxRms" to "%.0f".format(maxRms),
            "peak" to peak,
            "aboveSilence" to "%.2f".format(ratio),
            "verdict" to verdict.name
        )

        _state.value = _state.value.copy(
            running = false,
            phase = MicTestState.Phase.DONE,
            blocksMeasured = blocks,
            rms = maxRms,
            minRms = if (blocks == 0) 0f else minRms,
            maxRms = maxRms,
            peak = peak,
            aboveSilenceRatio = ratio,
            verdict = verdict,
            recording = false
        )
        Unit
    }

    private companion object {
        const val TAG = "AniMicTest"
        const val DEFAULT_MEASURE_MILLIS = 3_000L
        const val HIDDEN = "(hidden in release builds)"

        /** PCM16 LSB. Below this a block is room tone rather than speech. */
        const val SILENCE_RMS = 120f

        /** Above this peak the converter is at its rail and consonants are lost. */
        const val CLIPPING_PEAK = 32_000

        /** Fewer blocks than this and the run was too short to judge. */
        const val MINIMUM_BLOCKS = 10

        fun classify(
            blocks: Int,
            minRms: Float,
            maxRms: Float,
            peak: Int,
            aboveSilenceRatio: Float
        ): AudioVerdict = when {
            blocks < MINIMUM_BLOCKS -> AudioVerdict.UNKNOWN

            // A dead stream: the loudest block is indistinguishable from the quietest and
            // both sit at the bottom. This is what a second recorder over a live one
            // looks like, and it is never what a quiet voice looks like.
            maxRms < SILENCE_RMS -> AudioVerdict.NO_AUDIO

            peak >= CLIPPING_PEAK -> AudioVerdict.CLIPPED
            aboveSilenceRatio < 0.05f -> AudioVerdict.NO_AUDIO
            maxRms < LOW_SPEECH_RMS -> AudioVerdict.LOW_AUDIO
            else -> AudioVerdict.NORMAL_AUDIO
        }

        /** Below this RMS the speaker is audible but quiet. */
        const val LOW_SPEECH_RMS = 700f
    }
}

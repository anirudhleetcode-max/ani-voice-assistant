package com.ani.assistant.voice

import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.audio.AudioVerdict
import com.ani.assistant.voice.mic.MicAcquisition
import com.ani.assistant.voice.mic.MicArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One configuration to try. */
data class BenchmarkTrial(
    val label: String,
    val kind: RecognizerKind,
    val localeTag: String,
    val endpointing: EndpointingConfig = EndpointingConfig()
)

/** What one trial produced. */
data class BenchmarkResult(
    val trial: BenchmarkTrial,
    /** What the engine actually was, which can differ from what was asked for. */
    val actualKind: RecognizerKind? = null,
    /**
     * The transcript.
     *
     * Kept because the whole question is *audio → actual transcript*, before the NLU
     * sees it: if Google Assistant hears "annayya" and this returns "anaya", that is a
     * recognition problem, and no amount of looking at the classifier will show it.
     * Held in memory for the length of the screen, never logged, never persisted.
     */
    val transcript: String? = null,
    val firstPartial: String? = null,
    val startedAtMillis: Long = 0,
    val firstPartialMillis: Long? = null,
    val finalResultMillis: Long? = null,
    val totalLatencyMillis: Long? = null,
    val audioVerdict: AudioVerdict = AudioVerdict.UNKNOWN,
    val error: String? = null,
    val running: Boolean = false
) {
    /** The `[RECOGNIZER]` block, with no transcript in it. */
    fun describe(): String = buildString {
        append("engine=").append(actualKind?.name ?: "none")
        append(" onDevice=").append(actualKind == RecognizerKind.ON_DEVICE)
        append(" language=").append(trial.localeTag)
        append(" firstPartialMs=").append(firstPartialMillis ?: -1)
        append(" finalResultMs=").append(finalResultMillis ?: -1)
        append(" totalLatencyMs=").append(totalLatencyMillis ?: -1)
        append(" transcriptLength=").append(transcript?.length ?: 0)
        append(" audio=").append(audioVerdict.name)
        append(" error=").append(error ?: "none")
    }
}

data class BenchmarkState(
    val running: Boolean = false,
    val currentTrial: String? = null,
    val support: RecognitionSupportReport = RecognitionSupportReport.UNAVAILABLE,
    val results: List<BenchmarkResult> = emptyList()
)

/**
 * The Speech Recognition Test: the same sentence, the same microphone, different engines.
 *
 * It exists because of one observation that rules out most of the obvious explanations —
 * **Google Assistant understands quiet speech on this exact phone and Ani does not.** The
 * microphone is therefore fine, the hardware is fine, and adding gain would be treating a
 * symptom that does not exist. What differs is everything between the microphone and the
 * transcript: which recogniser, which language, which endpointing.
 *
 * So this holds the sentence and the microphone constant and varies exactly one thing at
 * a time. Each trial reports the transcript it actually produced, because *audio →
 * transcript* is the measurement that separates a recognition problem from an NLU one:
 *
 *  - Google hears "annayya ki call chey", Ani's recogniser returns "anaya ki call che" →
 *    recognition or language model.
 *  - Ani's recogniser returns the right words and Ani still apologises → the NLU or the
 *    plumbing after it, and no audio change will help.
 *
 * Trials run one at a time, each taking the microphone through [MicArbiter] and giving it
 * back. Nothing overlaps, so nothing competes.
 */
class RecognizerBenchmark(
    private val provider: AndroidSpeechRecognizerProvider,
    private val arbiter: MicArbiter,
    private val scope: CoroutineScope,
    /** Debug builds only. A transcript is what the user said. */
    private val retainTranscripts: Boolean = false
) {

    private val _state = MutableStateFlow(BenchmarkState())
    val state: StateFlow<BenchmarkState> = _state.asStateFlow()

    private var job: Job? = null

    /** The default comparison: system vs on-device, en-IN vs te-IN. */
    fun defaultTrials(): List<BenchmarkTrial> = listOf(
        BenchmarkTrial("A · system, en-IN", RecognizerKind.PLATFORM, EN_IN),
        BenchmarkTrial("B · on-device, en-IN", RecognizerKind.ON_DEVICE, EN_IN),
        BenchmarkTrial("C · system, te-IN", RecognizerKind.PLATFORM, TE_IN),
        BenchmarkTrial("D · on-device, te-IN", RecognizerKind.ON_DEVICE, TE_IN),
        // The same engine and language Ani uses, but with the patient endpointing the
        // previous build shipped — so the latency the widened windows cost is a number
        // rather than an argument.
        BenchmarkTrial("E · system, en-IN, patient", RecognizerKind.PLATFORM, EN_IN, EndpointingConfig.PATIENT)
    )

    /**
     * Runs [trials] one after another, prompting the user to say the same sentence each
     * time.
     *
     * Sequential on purpose. Two recognisers at once is the bug this project already
     * spent a round fixing.
     */
    fun run(trials: List<BenchmarkTrial> = defaultTrials()) {
        job?.cancel()
        job = scope.launch {
            _state.value = BenchmarkState(
                running = true,
                support = provider.checkSupport(com.ani.nlu.text.Language.MIXED),
                results = trials.map { BenchmarkResult(trial = it) }
            )

            for ((index, trial) in trials.withIndex()) {
                _state.value = _state.value.copy(currentTrial = trial.label)
                update(index) { it.copy(running = true) }

                val result = runTrial(trial)
                update(index) { result }
                AniLog.i(TAG, "[RECOGNIZER] " + result.describe())
            }

            _state.value = _state.value.copy(running = false, currentTrial = null)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        arbiter.releaseCommand()
        _state.value = _state.value.copy(running = false, currentTrial = null)
    }

    // ---------------------------------------------------------------------------------

    private suspend fun runTrial(trial: BenchmarkTrial): BenchmarkResult {
        val acquired = arbiter.acquireForCommand()
        if (acquired !is MicAcquisition.Granted) {
            return BenchmarkResult(
                trial = trial,
                error = "microphone unavailable: " +
                    (acquired as MicAcquisition.Denied).reason.name
            )
        }

        val startedAt = System.currentTimeMillis()
        var actualKind: RecognizerKind? = null
        var firstPartialAt: Long? = null
        var firstPartial: String? = null
        var finalAt: Long? = null
        var transcript: String? = null
        var verdict = AudioVerdict.UNKNOWN
        var error: String? = null

        try {
            provider.listenWith(
                localeTag = trial.localeTag,
                preferredKind = trial.kind,
                endpointing = trial.endpointing,
                partialResults = true
            ).collect { event ->
                when (event) {
                    is SpeechEvent.Started -> actualKind = event.kind
                    is SpeechEvent.Partial -> if (firstPartialAt == null) {
                        firstPartialAt = System.currentTimeMillis()
                        firstPartial = event.result.text
                    }
                    is SpeechEvent.Final -> {
                        finalAt = System.currentTimeMillis()
                        transcript = event.result.text
                        verdict = event.audio.verdict
                    }
                    is SpeechEvent.Failed -> {
                        verdict = event.audio.verdict
                        error = event.error.name +
                            (event.platformCode?.let { " ($it)" } ?: "")
                    }
                    else -> Unit
                }
            }
        } catch (failure: Exception) {
            error = failure.javaClass.simpleName
        } finally {
            arbiter.releaseCommand()
        }

        val endedAt = finalAt ?: System.currentTimeMillis()
        return BenchmarkResult(
            trial = trial,
            actualKind = actualKind,
            transcript = transcript?.let { if (retainTranscripts) it else HIDDEN },
            firstPartial = firstPartial?.let { if (retainTranscripts) it else HIDDEN },
            startedAtMillis = startedAt,
            firstPartialMillis = firstPartialAt?.minus(startedAt),
            finalResultMillis = finalAt?.minus(startedAt),
            totalLatencyMillis = endedAt - startedAt,
            audioVerdict = verdict,
            error = error,
            running = false
        )
    }

    private fun update(index: Int, transform: (BenchmarkResult) -> BenchmarkResult) {
        _state.value = _state.value.copy(
            results = _state.value.results.mapIndexed { position, result ->
                if (position == index) transform(result) else result
            }
        )
    }

    private companion object {
        const val TAG = "AniRecogBench"
        const val EN_IN = "en-IN"
        const val TE_IN = "te-IN"
        const val HIDDEN = "(hidden in release builds)"
    }
}

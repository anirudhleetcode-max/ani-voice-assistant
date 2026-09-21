package com.ani.assistant.voice.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A live view of the wake-word audio path. Contains no audio and no recordings. */
data class WakeAudioSnapshot(
    /** The negotiated capture settings, once capture has opened. */
    val captureDescription: String? = null,

    /** RMS of the most recent block before gain, in PCM16 LSB. */
    val inputRms: Float = 0f,

    /** The adaptive background estimate, same units. */
    val noiseFloorRms: Float = 0f,

    /** Gain currently being applied. 1.0 means untouched. */
    val appliedGain: Float = 1f,

    /** Peak absolute sample after gain and limiting, 0..32767. */
    val outputPeak: Int = 0,

    /** True when the last block was loud enough to be treated as possible speech. */
    val gateOpen: Boolean = false,

    /** True when the limiter had to pull a sample back. */
    val limiterEngaged: Boolean = false,

    /**
     * Vosk's current partial hypothesis. **Debug builds only** — null in release.
     *
     * This is a transcript of what the user said, which is exactly the kind of thing the
     * rest of the app takes care never to retain. It is here because tuning quiet-speech
     * detection on a real phone is impossible without seeing what the decoder heard, and
     * it is confined to debug builds, kept in memory only, and never written to logcat.
     */
    val lastPartial: String? = null,

    /** Vosk's last completed hypothesis. **Debug builds only** — null in release. */
    val lastFinal: String? = null,

    /** When the wake phrase was last detected. */
    val lastDetectionAtMillis: Long? = null,

    /** Detections since capture opened. Useful for counting false wakes while testing. */
    val detectionCount: Int = 0,

    /** Safe, human sentence. Never an exception and never user content. */
    val lastError: String? = null
) {
    /** Rough dBFS of [inputRms], for a display that is easier to read than raw LSB. */
    val inputDbfs: Float
        get() = if (inputRms <= 0f) -120f else (20.0 * kotlin.math.log10(inputRms / 32767.0)).toFloat()

    val noiseFloorDbfs: Float
        get() = if (noiseFloorRms <= 0f) -120f else (20.0 * kotlin.math.log10(noiseFloorRms / 32767.0)).toFloat()
}

/**
 * Collects live signal statistics from the wake-word audio path.
 *
 * Built for one job: making it possible to tune quiet-speech detection on a real phone,
 * where the only alternative is guessing. The Diagnostics screen renders this directly, so
 * you can watch the RMS, the noise floor and the gain move as you speak, and see whether
 * the gate opened for a word that was not detected.
 *
 * **Transcripts are debug-only.** [WakeAudioSnapshot.lastPartial] and `lastFinal` hold
 * what the decoder heard, which is user speech. They are populated only when
 * [retainTranscripts] is true — wired to `BuildConfig.DEBUG` — kept in memory, and never
 * logged. Everything else here is a number.
 */
class WakeAudioDiagnostics(private val retainTranscripts: Boolean) {

    private val _snapshot = MutableStateFlow(WakeAudioSnapshot())
    val snapshot: StateFlow<WakeAudioSnapshot> = _snapshot.asStateFlow()

    fun onCaptureOpened(config: AudioCaptureConfig) {
        _snapshot.value = WakeAudioSnapshot(captureDescription = config.describe())
    }

    fun onAudioBlock(stats: GainFrameStats) {
        _snapshot.value = _snapshot.value.copy(
            inputRms = stats.inputRms,
            noiseFloorRms = stats.noiseFloorRms,
            appliedGain = stats.appliedGain,
            outputPeak = stats.outputPeak,
            gateOpen = stats.gateOpen,
            limiterEngaged = stats.limiterEngaged
        )
    }

    fun onPartial(text: String) {
        if (!retainTranscripts) return
        _snapshot.value = _snapshot.value.copy(lastPartial = text)
    }

    fun onFinal(text: String) {
        if (!retainTranscripts) return
        _snapshot.value = _snapshot.value.copy(lastFinal = text)
    }

    fun onDetection(atMillis: Long) {
        _snapshot.value = _snapshot.value.copy(
            lastDetectionAtMillis = atMillis,
            detectionCount = _snapshot.value.detectionCount + 1,
            lastError = null
        )
    }

    fun onCaptureError(message: String) {
        _snapshot.value = _snapshot.value.copy(lastError = message)
    }

    fun onCaptureClosed() {
        _snapshot.value = _snapshot.value.copy(
            captureDescription = null,
            gateOpen = false,
            appliedGain = 1f,
            inputRms = 0f,
            outputPeak = 0
        )
    }
}

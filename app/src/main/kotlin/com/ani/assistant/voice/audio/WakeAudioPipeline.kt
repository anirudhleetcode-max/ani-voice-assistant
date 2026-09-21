package com.ani.assistant.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.ani.assistant.core.log.AniLog

/**
 * Owns the microphone for wake-word detection, and shapes what it captures.
 *
 * ```
 * AudioRecord (VOICE_RECOGNITION, mono PCM16, negotiated rate)
 *   → hardware AGC, where the device offers it
 *   → DC removal
 *   → noise-floor estimation
 *   → adaptive gain for quiet speech (gate controls gain, never passage)
 *   → soft limiter
 *   → resample to 16 kHz if the device would not give us 16 kHz
 *   → Vosk
 * ```
 *
 * **Why this class exists at all.** Vosk's `SpeechService` constructs its own
 * `AudioRecord` — decompiling it shows `new AudioRecord(6, rate, 16, 2, round(rate*0.2)*2)`
 * — and hands the PCM straight to the recogniser. There was no seam to put a gain stage
 * in. Taking ownership of capture is the only way to process audio before the decoder
 * sees it.
 *
 * Two things that inspection also turned up, both fixed here:
 *
 *  - The buffer was a fixed 200 ms and was never compared against
 *    `AudioRecord.getMinBufferSize()`. On a device whose minimum exceeds that, capture
 *    fails to initialise. It is now derived from the minimum.
 *  - Reads were 200 ms long, which put a 200 ms floor under wake latency before the
 *    decoder had seen a sample. Reads are now 20 ms.
 *
 * What inspection did *not* find: a wrong audio source. `SpeechService` already used
 * `VOICE_RECOGNITION`, which is the correct choice, so that was never the cause of poor
 * quiet-speech detection and changing it would have been a fix for a problem that did not
 * exist.
 *
 * This class does not start threads. The caller drives [captureInto] from whatever
 * coroutine or thread it wants, which keeps microphone ownership exactly as visible as
 * it needs to be.
 */
class WakeAudioPipeline(
    private val gainConfig: GainConfig = GainConfig(),
    private val diagnostics: WakeAudioDiagnostics? = null,
    /**
     * Low-voice mode: ask for hardware AGC, and switch the platform noise suppressor off.
     *
     * The suppressor is the debatable half. It genuinely reduces false wakes, and it
     * genuinely also treats very quiet speech as the noise it was built to remove. With
     * the explicit goal being "detect a whispered Rey", it loses — and the energy gate
     * plus the two-word grammar are what hold false positives down instead.
     */
    private val lowVoiceMode: Boolean = true
) {

    private var record: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var resampler: LinearResampler? = null

    private val gain = AdaptiveGainProcessor(gainConfig)

    /** The settings actually negotiated. Null until [open] succeeds. */
    var config: AudioCaptureConfig? = null
        private set

    /**
     * Opens the microphone.
     *
     * @return the negotiated configuration, or null when capture could not be opened —
     *         permission revoked, another app holding the microphone, or no workable rate.
     */
    @SuppressLint("MissingPermission") // The caller gates on RECORD_AUDIO; see VoskWakeWordEngine.
    fun open(): AudioCaptureConfig? {
        close()

        val sampleRate = negotiateSampleRate()
        if (sampleRate == null) {
            AniLog.e(TAG, "no usable capture sample rate on this device")
            return null
        }

        val minimumBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0) {
            AniLog.e(TAG, "getMinBufferSize refused the negotiated rate", null, "rate" to sampleRate)
            return null
        }
        val bufferBytes = minimumBuffer * AudioCaptureConfig.BUFFER_SIZE_MULTIPLIER

        // VOICE_RECOGNITION first: it is the source tuned for speech recognition and, on
        // most devices, the one that leaves the signal least processed. MIC is the
        // fallback, and is always available.
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val candidate = createRecord(source, sampleRate, bufferBytes) ?: continue

            record = candidate
            val agcOn = configureAutomaticGainControl(candidate.audioSessionId)
            val nsOn = configureNoiseSuppressor(candidate.audioSessionId)

            resampler = if (sampleRate == AudioCaptureConfig.RECOGNIZER_SAMPLE_RATE) {
                null
            } else {
                LinearResampler(sampleRate, AudioCaptureConfig.RECOGNIZER_SAMPLE_RATE)
            }
            gain.reset()

            val negotiated = AudioCaptureConfig(
                audioSource = source,
                captureSampleRate = sampleRate,
                recognizerSampleRate = AudioCaptureConfig.RECOGNIZER_SAMPLE_RATE,
                bufferSizeBytes = bufferBytes,
                readChunkSamples = readChunkFor(sampleRate),
                hardwareAgcEnabled = agcOn,
                noiseSuppressorEnabled = nsOn
            )
            config = negotiated
            diagnostics?.onCaptureOpened(negotiated)
            AniLog.i(
                TAG,
                "capture open",
                "source" to AudioCaptureConfig.audioSourceName(source),
                "rate" to sampleRate,
                "bufferBytes" to bufferBytes,
                "agc" to agcOn,
                "ns" to nsOn
            )
            return negotiated
        }

        AniLog.e(TAG, "could not open any audio source")
        return null
    }

    /**
     * Reads until [isActive] returns false, handing processed 16 kHz PCM to [onAudio].
     *
     * Blocks the calling thread. While [isPaused] returns true the microphone is still
     * open but nothing is forwarded, which is what lets the engine be suspended without
     * the cost of tearing capture down and back up.
     */
    fun captureInto(
        isActive: () -> Boolean,
        isPaused: () -> Boolean,
        onAudio: (ShortArray, Int) -> Unit
    ) {
        val active = record ?: run {
            AniLog.w(TAG, "captureInto called before open")
            return
        }
        val negotiated = config ?: return

        val chunk = negotiated.readChunkSamples
        val input = ShortArray(chunk)
        val currentResampler = resampler
        val output = currentResampler
            ?.let { ShortArray(it.maximumOutputLength(chunk)) }
            ?: input

        try {
            active.startRecording()
            if (active.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                AniLog.e(TAG, "AudioRecord refused to start")
                diagnostics?.onCaptureError("The microphone would not start.")
                return
            }
        } catch (error: IllegalStateException) {
            AniLog.e(TAG, "AudioRecord.startRecording threw", error)
            diagnostics?.onCaptureError("The microphone would not start.")
            return
        }

        var blocksSinceReport = 0

        while (isActive()) {
            val read = active.read(input, 0, chunk)
            if (read <= 0) {
                // ERROR_INVALID_OPERATION means the record was stopped underneath us.
                if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_DEAD_OBJECT) {
                    AniLog.w(TAG, "capture ended", "code" to read)
                    diagnostics?.onCaptureError("The microphone stopped unexpectedly.")
                    return
                }
                continue
            }

            if (isPaused()) {
                // Keep the stream draining so the buffer does not overflow while paused,
                // and keep the noise estimate current — but forward nothing.
                continue
            }

            val stats = gain.process(input, read)
            blocksSinceReport++
            if (blocksSinceReport >= DIAGNOSTIC_BLOCK_INTERVAL) {
                blocksSinceReport = 0
                diagnostics?.onAudioBlock(stats)
            }

            val forwarded = if (currentResampler == null) {
                onAudio(input, read)
                read
            } else {
                val produced = currentResampler.resample(input, read, output)
                if (produced > 0) onAudio(output, produced)
                produced
            }
            if (forwarded < 0) return
        }
    }

    /** Stops and releases everything. Safe to call more than once. */
    fun close() {
        runCatching { automaticGainControl?.release() }
        automaticGainControl = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        record?.let { active ->
            runCatching {
                if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
            }
            runCatching { active.release() }
        }
        record = null
        resampler = null
        config = null
        gain.reset()
    }

    // ---------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, sampleRate: Int, bufferBytes: Int): AudioRecord? = try {
        val candidate = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
            candidate
        } else {
            AniLog.w(TAG, "AudioRecord did not initialise", "source" to source)
            runCatching { candidate.release() }
            null
        }
    } catch (error: Exception) {
        // A SecurityException here means RECORD_AUDIO was revoked between the check and
        // the call; UnsupportedOperationException means the source is not available.
        AniLog.w(TAG, "could not open audio source", "source" to source, "type" to error.javaClass.simpleName)
        null
    }

    /**
     * Finds a rate the device will actually open, rather than assuming 16 kHz.
     *
     * `getMinBufferSize` returning an error for a rate is the documented way to discover
     * that the rate is unsupported, and it does not need the microphone permission.
     */
    private fun negotiateSampleRate(): Int? {
        val supported = AudioCaptureConfig.CANDIDATE_SAMPLE_RATES.filter { rate ->
            val size = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            size > 0
        }
        return AudioCaptureConfig.chooseSampleRate(supported)
    }

    /** Scale the 20 ms read chunk to whatever rate we ended up with. */
    private fun readChunkFor(sampleRate: Int): Int =
        (AudioCaptureConfig.READ_CHUNK_SAMPLES.toLong() * sampleRate /
            AudioCaptureConfig.RECOGNIZER_SAMPLE_RATE).toInt().coerceAtLeast(160)

    /**
     * Turns on the platform's own gain control where the device has one.
     *
     * This matters more than the software stage does: hardware AGC acts before the
     * converter quantises, so it recovers dynamic range that no amount of arithmetic
     * afterwards can put back.
     */
    private fun configureAutomaticGainControl(sessionId: Int): Boolean {
        if (!lowVoiceMode) return false
        if (!AutomaticGainControl.isAvailable()) {
            AniLog.i(TAG, "no hardware AGC on this device")
            return false
        }
        return try {
            val control = AutomaticGainControl.create(sessionId) ?: return false
            control.enabled = true
            automaticGainControl = control
            control.enabled
        } catch (error: Exception) {
            AniLog.w(TAG, "could not enable hardware AGC", "type" to error.javaClass.simpleName)
            false
        }
    }

    /** @return whether suppression is left on. */
    private fun configureNoiseSuppressor(sessionId: Int): Boolean {
        if (!NoiseSuppressor.isAvailable()) return false
        return try {
            val suppressor = NoiseSuppressor.create(sessionId) ?: return false
            // In low-voice mode the suppressor is switched off: it cannot tell a whispered
            // word from the room tone it exists to remove.
            suppressor.enabled = !lowVoiceMode
            noiseSuppressor = suppressor
            suppressor.enabled
        } catch (error: Exception) {
            AniLog.w(TAG, "could not configure noise suppressor", "type" to error.javaClass.simpleName)
            false
        }
    }

    private companion object {
        const val TAG = "AniWakeAudio"

        /** Report roughly five times a second rather than every 20 ms block. */
        const val DIAGNOSTIC_BLOCK_INTERVAL = 10
    }
}

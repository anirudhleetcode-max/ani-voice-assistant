package com.ani.assistant.voice.wake

import kotlinx.coroutines.flow.Flow

/** A wake phrase was heard. */
data class WakeDetection(
    val phrase: String,
    val confidence: Double,
    /** Anything the user said after the phrase in the same breath, if the recogniser caught it. */
    val trailingText: String?
)

/**
 * Listens for the activation phrase.
 *
 * Behind an interface because the implementation that ships here is not the
 * implementation this deserves. See [SpeechWakeWordDetector] for exactly what the
 * trade-off is; a dedicated small-footprint hotword model is the right answer and can be
 * dropped in here without touching anything else.
 */
interface WakeWordDetector {

    /** Whether detection can run at all right now. */
    fun isSupported(): Boolean

    /**
     * Emits once per detection, running until the collector is cancelled.
     *
     * Implementations must stop using the microphone the moment collection stops.
     */
    fun detections(): Flow<WakeDetection>

    /** Human-readable note about this detector's cost, shown in Settings and Diagnostics. */
    val costDescription: String
}

package com.ani.assistant.voice.wake

import com.ani.assistant.core.log.AniLog

/**
 * Picks the wake-word engine.
 *
 * The user's choice is honoured when that engine can actually run. When it cannot — no
 * Vosk model downloaded, no Porcupine licence — the factory falls back rather than
 * leaving the assistant deaf, and records what it did so Diagnostics can say
 * "you asked for Porcupine, you are getting Vosk, here is why".
 *
 * Falling back silently would be the wrong call: the whole point of the engine choice is
 * that the trade-offs differ, so the user has to be able to see which one is live.
 */
class WakeWordEngineFactory(
    private val vosk: VoskWakeWordEngine,
    private val porcupine: PorcupineWakeWordEngine,
    private val platformRecognizer: WakeWordEngine
) {

    /** What the factory chose, and why, for Diagnostics. */
    data class Selection(
        val engine: WakeWordEngine,
        val requested: WakeWordEngineId,
        val availability: WakeEngineAvailability,
        /** Non-null when the requested engine could not run. */
        val fellBackBecause: String?
    )

    fun engineFor(id: WakeWordEngineId): WakeWordEngine = when (id) {
        WakeWordEngineId.VOSK -> vosk
        WakeWordEngineId.PORCUPINE -> porcupine
        WakeWordEngineId.PLATFORM_RECOGNIZER -> platformRecognizer
    }

    fun all(): List<WakeWordEngine> = listOf(vosk, porcupine, platformRecognizer)

    suspend fun select(requested: WakeWordEngineId): Selection {
        val preferred = engineFor(requested)
        val availability = preferred.availability()
        if (availability.isReady) {
            return Selection(preferred, requested, availability, fellBackBecause = null)
        }

        val reason = describe(availability)
        AniLog.i(TAG, "requested wake engine unavailable", "requested" to requested.name)

        // Order matters: try the free on-device engine before the battery-hungry one.
        val fallbackOrder = listOf(vosk, porcupine, platformRecognizer)
            .filter { it.id != requested }

        for (candidate in fallbackOrder) {
            if (candidate.availability().isReady) {
                return Selection(candidate, requested, availability, fellBackBecause = reason)
            }
        }

        // Nothing can run. Report the requested engine so the UI explains the real problem
        // rather than the last one tried.
        return Selection(preferred, requested, availability, fellBackBecause = reason)
    }

    private fun describe(availability: WakeEngineAvailability): String = when (availability) {
        is WakeEngineAvailability.Ready -> "ready"
        is WakeEngineAvailability.NeedsModel ->
            "the ${availability.sizeMegabytes} MB wake model is not installed yet"
        is WakeEngineAvailability.NeedsCredentials -> "it needs ${availability.what}"
        is WakeEngineAvailability.Blocked -> availability.reason
        is WakeEngineAvailability.Error -> availability.reason
    }

    private companion object {
        const val TAG = "AniWakeFactory"
    }
}

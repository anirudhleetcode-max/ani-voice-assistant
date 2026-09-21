package com.ani.assistant.voice

import com.ani.assistant.voice.wake.WakeWordEngineId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A snapshot of what the listening service is doing, for Diagnostics. */
data class VoiceServiceStatus(
    val isRunning: Boolean = false,
    val wakeEngineReady: Boolean = false,
    val activeEngine: WakeWordEngineId? = null,
    /** Set when the requested engine could not run and another was used instead. */
    val fellBackBecause: String? = null,
    val lastWakeAtMillis: Long? = null,
    val lastCommandAtMillis: Long? = null,
    /** Always a safe, human sentence — never an exception or any user content. */
    val lastError: String? = null
)

/**
 * Process-wide status of the listening service.
 *
 * A singleton because the service, the Activity and the Diagnostics screen live in
 * different lifecycles and all three need the same answer to "is it actually running?".
 * Asking the service directly would mean binding to it, and a Diagnostics screen that
 * starts the thing it is reporting on would be lying about the state it found.
 *
 * Every field here is a state, a count or a timestamp. Nothing derived from what the user
 * said, what a notification contained, or who they called ever goes in.
 */
object ServiceState {

    private val _status = MutableStateFlow(VoiceServiceStatus())
    val status: StateFlow<VoiceServiceStatus> = _status.asStateFlow()

    fun setRunning(running: Boolean) {
        _status.value = _status.value.copy(
            isRunning = running,
            // Stale engine state after a stop would read as "ready" on the next glance.
            wakeEngineReady = if (running) _status.value.wakeEngineReady else false,
            activeEngine = if (running) _status.value.activeEngine else null
        )
    }

    fun setWakeEngineReady(ready: Boolean) {
        _status.value = _status.value.copy(wakeEngineReady = ready)
    }

    fun setEngine(engine: WakeWordEngineId, fellBackBecause: String?) {
        _status.value = _status.value.copy(activeEngine = engine, fellBackBecause = fellBackBecause)
    }

    fun setLastWake(atMillis: Long) {
        _status.value = _status.value.copy(lastWakeAtMillis = atMillis, lastError = null)
    }

    fun setLastCommand(atMillis: Long) {
        _status.value = _status.value.copy(lastCommandAtMillis = atMillis)
    }

    fun setLastError(message: String) {
        _status.value = _status.value.copy(lastError = message)
    }

    fun clearError() {
        _status.value = _status.value.copy(lastError = null)
    }
}

package com.ani.assistant.voice.mic

import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whoever currently owns the wake-word microphone, and can be told to let go of it. */
fun interface WakeMicrophoneOwner {
    /**
     * Tears the wake engine down and does not return until the recorder is actually
     * stopped.
     *
     * @return true when the platform confirmed the microphone is free. False means the
     *         caller must **not** start a second recorder.
     */
    suspend fun releaseMicrophoneForCommand(timeoutMillis: Long): Boolean
}

/** What happened when something asked for the microphone. */
sealed interface MicAcquisition {

    data object Granted : MicAcquisition

    data class Denied(val reason: Reason) : MicAcquisition {
        enum class Reason {
            /** The wake engine did not confirm it had stopped inside the timeout. */
            WAKE_ENGINE_WOULD_NOT_RELEASE,

            /** Another command session already holds it. */
            ALREADY_HELD
        }
    }

    val isGranted: Boolean get() = this is Granted
}

/**
 * The single gate to the microphone, for the whole process.
 *
 * **This is the fix for the bug where Ani said "sarigga vinapadatledu ra" no matter how
 * loudly you spoke.** Two things wanted the microphone and neither asked the other:
 *
 *  - the wake engine, which holds an `AudioRecord` continuously while the listening
 *    service runs;
 *  - `SpeechRecognizer`, which opens its own recorder when a command is captured.
 *
 * The wake path released the engine first, so it mostly worked. The *tap-the-orb* path
 * did not release anything at all — it called `startListening` straight from the UI while
 * the service was still recording. Android does not report that as an error. The second
 * recorder opens, returns silence, and the recogniser ends with `ERROR_NO_MATCH`, which
 * this app translated into "I didn't catch that". The user is told they were not heard
 * clearly; in fact they were not heard at all.
 *
 * So no component opens a recorder without coming through here first. [acquireForCommand]
 * asks the registered wake owner to stop, **waits for confirmation that the recorder is
 * genuinely stopped**, and only then grants. When confirmation does not arrive it denies,
 * and the caller says something true instead of blaming the user's diction.
 */
class MicArbiter(
    /**
     * Where the handover trace goes.
     *
     * Injected rather than called directly so this class stays free of Android types —
     * it is the piece whose correctness matters most, so it is the piece that has to be
     * testable on a plain JVM.
     */
    private val log: (String) -> Unit = { AniLog.i(TAG, it) }
) {

    private val mutex = Mutex()

    @Volatile
    private var wakeOwner: WakeMicrophoneOwner? = null

    private val _commandHoldsMicrophone = MutableStateFlow(false)

    /**
     * True while a command recogniser owns the microphone.
     *
     * The wake loop watches this and refuses to re-arm while it is true, which is the
     * other half of "never two owners".
     */
    val commandHoldsMicrophone: StateFlow<Boolean> = _commandHoldsMicrophone.asStateFlow()

    /** Installed by the listening service for as long as its wake loop is running. */
    fun registerWakeOwner(owner: WakeMicrophoneOwner) {
        wakeOwner = owner
    }

    fun unregisterWakeOwner() {
        wakeOwner = null
    }

    /**
     * Takes the microphone for a command recognition attempt.
     *
     * Safe to call when no wake engine is running — that is the normal case for a phone
     * where always-on listening is switched off, and it grants immediately.
     */
    suspend fun acquireForCommand(
        timeoutMillis: Long = DEFAULT_RELEASE_TIMEOUT_MILLIS
    ): MicAcquisition = mutex.withLock {
        if (_commandHoldsMicrophone.value) {
            log("[MIC] acquire denied: already held by a command session")
            return@withLock MicAcquisition.Denied(MicAcquisition.Denied.Reason.ALREADY_HELD)
        }

        val owner = wakeOwner
        if (owner == null) {
            // Nothing is listening for a wake word, so nothing has to let go.
            _commandHoldsMicrophone.value = true
            log("[MIC] acquired for command wakeOwner=none")
            return@withLock MicAcquisition.Granted
        }

        val released = owner.releaseMicrophoneForCommand(timeoutMillis)
        if (!released) {
            // Starting SpeechRecognizer now is what produced silence and a misleading
            // "I didn't catch that". Refusing is the honest outcome.
            log("[MIC] acquire denied: wake engine did not confirm release")
            return@withLock MicAcquisition.Denied(
                MicAcquisition.Denied.Reason.WAKE_ENGINE_WOULD_NOT_RELEASE
            )
        }

        _commandHoldsMicrophone.value = true
        log("[MIC] acquired for command wakeOwner=released")
        MicAcquisition.Granted
    }

    /** Hands the microphone back. Safe to call when it was never held. */
    fun releaseCommand() {
        if (!_commandHoldsMicrophone.value) return
        _commandHoldsMicrophone.value = false
        log("[MIC] released by command session")
    }

    companion object {
        private const val TAG = "AniMicArbiter"

        /**
         * How long to wait for the wake recorder to stop.
         *
         * Reads are 20 ms, so an orderly unwind takes tens of milliseconds. A second is
         * long enough that a loaded phone still makes it, and short enough that a user
         * who tapped the orb does not think the tap was ignored.
         */
        const val DEFAULT_RELEASE_TIMEOUT_MILLIS = 1_000L
    }
}

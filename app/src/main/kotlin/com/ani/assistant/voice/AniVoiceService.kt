package com.ani.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ani.assistant.AniApplication
import com.ani.assistant.MainActivity
import com.ani.assistant.R
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.wake.WakeWordEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The listening service.
 *
 * It exists because Android requires it — an app that records audio while not in the
 * foreground must run a foreground service typed `microphone`, with a notification the
 * user can see and dismiss. Ani leans into that rather than minimising it: the
 * notification names the wake phrase and offers a one-tap stop.
 *
 * **Why the wake engine is stopped for the whole exchange, not merely paused.**
 * Every wake engine owns an `AudioRecord`. So does `SpeechRecognizer`. On most Android
 * devices the second one to ask loses, which would mean Ani hears "Rey", wakes, and then
 * cannot hear the command that follows. So a detection tears the wake loop down
 * completely — releasing the microphone — runs the exchange, and starts it again
 * afterwards. That single decision is what makes screen-off operation work at all.
 *
 * It also solves self-triggering for free: while Ani is speaking its reply, the wake
 * engine is not running, so "Rey" inside its own sentence cannot wake it again.
 */
class AniVoiceService : LifecycleService() {

    private var wakeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            AniLog.i(TAG, "stopping on user request")
            stopListening()
            return START_NOT_STICKY
        }

        val graph = AniApplication.graphOrNull(applicationContext)
        if (graph == null || !graph.canListen()) {
            // Microphone permission was revoked while we were away. Never start a
            // microphone foreground service we are not allowed to use.
            AniLog.w(TAG, "cannot listen; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()
        startWakeLoop()

        // START_STICKY so listening returns after a low-memory kill, which is what a user
        // who deliberately enabled always-on listening expects.
        return START_STICKY
    }

    // ---------------------------------------------------------------------------------
    // Foreground
    // ---------------------------------------------------------------------------------

    private fun startForegroundSafely() {
        lifecycleScope.launch {
            val graph = AniApplication.graphOrNull(applicationContext) ?: return@launch
            val settings = graph.settingsRepository.settings.first()
            val phrase = settings.effectiveWakePhrases().firstOrNull() ?: DEFAULT_PHRASE

            try {
                ServiceCompat.startForeground(
                    this@AniVoiceService,
                    NOTIFICATION_ID,
                    buildNotification(phrase, listening = true),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    }
                )
                ServiceState.setRunning(true)
            } catch (error: Exception) {
                // Android 14 throws when a foreground service is started from the
                // background without an allowed reason; Android 12+ throws for a missing
                // typed permission. Neither is recoverable here.
                AniLog.e(TAG, "could not enter foreground", error)
                ServiceState.setLastError("Android refused to start the listening service.")
                stopSelf()
            }
        }
    }

    private fun updateNotification(phrase: String, listening: Boolean) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(NOTIFICATION_ID, buildNotification(phrase, listening))
        }
    }

    // ---------------------------------------------------------------------------------
    // Wake loop
    // ---------------------------------------------------------------------------------

    private fun startWakeLoop() {
        if (wakeJob?.isActive == true) return
        val graph = AniApplication.graphOrNull(applicationContext) ?: return

        wakeJob = lifecycleScope.launch {
            var consecutiveFailures = 0

            while (isActive) {
                val selection = graph.selectWakeEngine()
                ServiceState.setEngine(selection.engine.id, selection.fellBackBecause)

                if (!selection.availability.isReady && selection.fellBackBecause == null) {
                    // Nothing can run — no model, no key, no microphone. Say so and stop
                    // rather than spinning on a loop that cannot succeed.
                    AniLog.w(TAG, "no wake engine available")
                    ServiceState.setLastError("No wake engine is ready. Check Diagnostics.")
                    ServiceState.setWakeEngineReady(false)
                    stopListening()
                    return@launch
                }

                ServiceState.setWakeEngineReady(true)
                AniLog.i(TAG, "wake loop started", "engine" to selection.engine.id.name)

                val handledAWake = runCatching { collectDetections(graph, selection.engine) }
                    .getOrDefault(false)

                if (!isActive) return@launch

                // A handled wake is the engine working. The flow ending without one means
                // it failed to start or died, and repeated failures earn a longer wait so
                // a broken engine cannot spin the CPU.
                consecutiveFailures = if (handledAWake) 0 else consecutiveFailures + 1
                val backoff = if (consecutiveFailures >= FAILURE_BACKOFF_THRESHOLD) {
                    LONG_BACKOFF_MILLIS
                } else {
                    SHORT_BACKOFF_MILLIS
                }
                if (!handledAWake) {
                    AniLog.w(TAG, "wake engine ended without a detection", "failures" to consecutiveFailures)
                }
                delay(backoff)
            }
        }
    }

    /**
     * Listens until one wake phrase arrives, then runs the exchange.
     *
     * @return true when a wake was handled; false when the engine ended without one,
     *         which is how the caller tells "working" from "broken".
     */
    private suspend fun collectDetections(
        graph: com.ani.assistant.AppGraph,
        engine: WakeWordEngine
    ): Boolean {
        var detection: com.ani.assistant.voice.wake.WakeDetection? = null

        try {
            engine.detections().collect { candidate ->
                // Ignore anything heard while a conversation is already running.
                if (graph.voiceSession.isBusy) return@collect
                detection = candidate
                // Throwing unwinds out of collect, which cancels the flow and releases
                // the microphone before the command recogniser asks for it.
                throw WakeDetected
            }
        } catch (signal: WakeDetectedSignal) {
            // Expected: this is how the collector stops.
        } finally {
            engine.release()
        }

        val wake = detection ?: return false

        ServiceState.setLastWake(wake.atEpochMillis)
        AniLog.i(
            TAG,
            "wake phrase detected",
            "engine" to engine.id.name,
            "confidence" to "%.2f".format(wake.confidence)
        )
        runExchange(graph, engine)
        return true
    }

    /**
     * Runs one conversation with the microphone all to itself.
     *
     * A partial wake lock is held for the duration. Without it, a phone with the screen
     * off can suspend the CPU between the recogniser's callbacks and drop the command
     * half-way through. It is released in `finally` and has a hard timeout, because a
     * leaked wake lock is a flat battery.
     */
    private suspend fun runExchange(graph: com.ani.assistant.AppGraph, engine: WakeWordEngine) {
        acquireWakeLock()
        val phrase = graph.settingsState.value.effectiveWakePhrases().firstOrNull() ?: DEFAULT_PHRASE
        updateNotification(phrase, listening = false)
        try {
            engine.setPaused(true)
            graph.voiceSession.startListening(playChime = true)

            // startListening dispatches to another coroutine, so the state is still IDLE
            // when we get here. Waiting for "not active" without waiting for it to become
            // active first would return immediately and restart the wake engine on top of
            // the command recogniser — two things fighting over one microphone, failing
            // intermittently and only with the screen off.
            val started = withTimeoutOrNull(ACTIVATION_TIMEOUT_MILLIS) {
                graph.voiceSession.state.first { it.isActive }
            }
            if (started == null) {
                AniLog.w(TAG, "voice session never started; abandoning exchange")
                ServiceState.setLastError("Ani woke up but could not start listening.")
                return
            }

            graph.voiceSession.state.first { !it.isActive }
            ServiceState.setLastCommand(System.currentTimeMillis())
        } catch (error: Exception) {
            AniLog.e(TAG, "exchange failed", error)
            ServiceState.setLastError("The conversation ended unexpectedly.")
        } finally {
            engine.setPaused(false)
            releaseWakeLock()
            updateNotification(phrase, listening = true)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = getSystemService(PowerManager::class.java) ?: return
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // ---------------------------------------------------------------------------------

    private fun stopListening() {
        wakeJob?.cancel()
        wakeJob = null
        releaseWakeLock()
        AniApplication.graphOrNull(applicationContext)?.let { graph ->
            graph.voiceSession.stop()
            graph.wakeEngineFactory.all().forEach { runCatching { it.release() } }
        }
        ServiceState.setRunning(false)
        ServiceState.setWakeEngineReady(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeJob?.cancel()
        wakeJob = null
        releaseWakeLock()
        ServiceState.setRunning(false)
        AniLog.i(TAG, "voice service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------

    private fun buildNotification(wakePhrase: String, listening: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AniVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (listening) {
                    getString(R.string.listening_notification_title, wakePhrase)
                } else {
                    getString(R.string.listening_notification_active)
                }
            )
            .setContentText(getString(R.string.listening_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // The notification names the wake phrase, so keep it off a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.listening_notification_stop), stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.listening_channel_name),
                // Low, not minimum: the user must be able to see it, but it should never
                // make a sound or appear as a heads-up.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.listening_channel_description)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "AniVoiceService"
        private const val CHANNEL_ID = "ani_listening"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN = 1
        private const val REQUEST_STOP = 2
        private const val DEFAULT_PHRASE = "Rey"

        private const val WAKE_LOCK_TAG = "Ani:exchange"

        /** Generous enough for a long command, short enough that a leak cannot flatten the battery. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 90_000L

        /** How long to wait for the voice session to actually come up after a wake. */
        private const val ACTIVATION_TIMEOUT_MILLIS = 5_000L

        private const val SHORT_BACKOFF_MILLIS = 500L
        private const val LONG_BACKOFF_MILLIS = 10_000L
        private const val FAILURE_BACKOFF_THRESHOLD = 3

        const val ACTION_STOP = "com.ani.assistant.action.STOP_LISTENING"

        fun start(context: Context) {
            val intent = Intent(context, AniVoiceService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (error: Exception) {
                // Android 12+ forbids starting a foreground service from the background in
                // most cases; the UI path always has a visible activity, so this only
                // fires for edge cases such as a boot race.
                AniLog.w(TAG, "could not start listening service", "type" to error.javaClass.simpleName)
                ServiceState.setLastError("Android would not let the listening service start.")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, AniVoiceService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}

/** Thrown to unwind out of `collect` so the flow is cancelled and the microphone freed. */
private object WakeDetected : WakeDetectedSignal()

private open class WakeDetectedSignal : RuntimeException(null, null, false, false)

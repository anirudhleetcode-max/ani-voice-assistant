package com.ani.assistant.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ani.assistant.AniApplication
import com.ani.assistant.MainActivity
import com.ani.assistant.R
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The listening service.
 *
 * It exists because Android requires it: an app that records audio while not in the
 * foreground must run a foreground service typed `microphone`, and that service must post
 * a notification the user can see and dismiss. That is not a hoop to jump through — it is
 * the mechanism that makes "is this thing listening to me?" answerable, and Ani leans into
 * it rather than minimising it. The notification says what the wake phrase is and offers a
 * one-tap stop.
 *
 * The service does as little as possible: it owns the wake-word loop and hands every
 * detection to the shared [VoiceSession]. It is stopped the moment the user turns wake
 * detection off.
 */
class AniVoiceService : LifecycleService() {

    private var wakeJob: Job? = null

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
            // Microphone permission was revoked while we were away. Do not start a
            // microphone foreground service we are not allowed to use.
            AniLog.w(TAG, "cannot listen; stopping service")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()
        startWakeLoop()

        // START_STICKY so the system brings listening back after a low-memory kill, which
        // is what a user who deliberately enabled always-on listening expects.
        return START_STICKY
    }

    private fun startForegroundSafely() {
        lifecycleScope.launch {
            val graph = AniApplication.graphOrNull(applicationContext) ?: return@launch
            val settings = graph.settingsRepository.settings.first()
            val phrase = settings.effectiveWakePhrases().firstOrNull() ?: "Rey"

            try {
                ServiceCompat.startForeground(
                    this@AniVoiceService,
                    NOTIFICATION_ID,
                    buildNotification(phrase),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    }
                )
            } catch (error: Exception) {
                // Android 14 throws if the app is not allowed to start a foreground
                // service from the background, and Android 12+ throws for a missing
                // typed permission. Neither is recoverable here.
                AniLog.e(TAG, "could not enter foreground", error)
                stopSelf()
            }
        }
    }

    private fun startWakeLoop() {
        if (wakeJob?.isActive == true) return
        val graph = AniApplication.graphOrNull(applicationContext) ?: return

        wakeJob = lifecycleScope.launch {
            val detector = graph.wakeWordDetector
            if (!detector.isSupported()) {
                AniLog.w(TAG, "no recogniser available for wake detection")
                stopSelf()
                return@launch
            }

            AniLog.i(TAG, "wake loop started")
            detector.detections().collect { detection ->
                // Ignore a detection that arrives while Ani is already mid-exchange;
                // otherwise its own spoken reply can re-trigger the wake phrase.
                if (graph.voiceSession.isBusy) return@collect

                AniLog.i(TAG, "wake phrase detected", "confidence" to "%.2f".format(detection.confidence))
                graph.voiceSession.startListening(playChime = true)
            }
        }
    }

    private fun stopListening() {
        wakeJob?.cancel()
        wakeJob = null
        AniApplication.graphOrNull(applicationContext)?.voiceSession?.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeJob?.cancel()
        wakeJob = null
        AniLog.i(TAG, "voice service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------

    private fun buildNotification(wakePhrase: String): Notification {
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
            .setContentTitle(getString(R.string.listening_notification_title, wakePhrase))
            .setContentText(getString(R.string.listening_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

        const val ACTION_STOP = "com.ani.assistant.action.STOP_LISTENING"

        fun start(context: Context) {
            val intent = Intent(context, AniVoiceService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (error: Exception) {
                // Android 12+ forbids starting a foreground service from the background
                // in most cases; the UI path always has a visible activity, so this only
                // fires for edge cases like a boot race.
                AniLog.w(TAG, "could not start listening service", "error" to error.javaClass.simpleName)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AniVoiceService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

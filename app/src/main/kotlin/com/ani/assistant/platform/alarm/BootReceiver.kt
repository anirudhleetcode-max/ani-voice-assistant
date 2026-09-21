package com.ani.assistant.platform.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ani.assistant.AniApplication
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.AniVoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Puts Ani back the way the user left it after a reboot or an app update.
 *
 * Two things have to be restored, and both are invisible failures if they are not:
 * AlarmManager drops every scheduled alarm across a restart, and the listening foreground
 * service does not come back on its own.
 *
 * Listening is only restarted when the user had it switched on *and* microphone
 * permission is still granted. Starting a microphone foreground service after boot for a
 * user who turned it off would be a serious breach of their expectations.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val graph = AniApplication.graphOrNull(context) ?: return@launch

                val rescheduled = graph.reminderScheduler.rescheduleAll()
                AniLog.i(TAG, "restored after restart", "reminders" to rescheduled)

                val settings = graph.settingsRepository.settings.first()
                if (settings.wakeWordEnabled && graph.canListen()) {
                    AniVoiceService.start(context)
                } else {
                    AniLog.i(
                        TAG,
                        "not restarting listener",
                        "enabled" to settings.wakeWordEnabled,
                        "canListen" to graph.canListen()
                    )
                }
            } catch (error: Exception) {
                AniLog.e(TAG, "restore after restart failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "AniBoot"
    }
}

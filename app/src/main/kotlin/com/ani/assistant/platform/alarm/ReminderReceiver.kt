package com.ani.assistant.platform.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ani.assistant.AniApplication
import com.ani.assistant.MainActivity
import com.ani.assistant.R
import com.ani.assistant.core.log.AniLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Delivers a reminder when its moment arrives.
 *
 * A broadcast receiver has roughly ten seconds before the system may kill the process, so
 * the notification is posted synchronously and only the bookkeeping is handed to a
 * coroutine with `goAsync` holding the process alive.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        postNotification(context, id, text)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AniApplication.graphOrNull(context)?.reminderScheduler?.markFired(id)
            } catch (error: Exception) {
                AniLog.e(TAG, "could not mark reminder fired", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postNotification(context: Context, id: String, text: String) {
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS was revoked between scheduling and firing.
            AniLog.w(TAG, "cannot post reminder, notification permission missing")
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.reminder_channel_description)
            }
        )
    }

    companion object {
        const val ACTION_FIRE = "com.ani.assistant.action.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TEXT = "reminder_text"
        const val CHANNEL_ID = "ani_reminders"
        private const val TAG = "AniReminderReceiver"
    }
}

package com.ani.assistant.platform.launch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ani.assistant.R
import com.ani.assistant.core.log.AniLog

/**
 * What actually happened when Ani tried to launch something.
 *
 * The distinction between [Launched] and [Deferred] is the entire point of this file.
 * Reporting the second as the first is how an assistant ends up saying "calling Annayya"
 * while nothing opens.
 */
sealed interface LaunchOutcome {

    /** The activity is on screen now. */
    data object Launched : LaunchOutcome

    /**
     * Android refused to start an activity from the background, so the action is waiting
     * behind a notification the user can act on.
     *
     * [asHeadsUp] is true when the system downgraded a full-screen intent to an ordinary
     * heads-up notification, which Android 14 does for apps it does not consider calling
     * or alarm apps.
     */
    data class Deferred(val asHeadsUp: Boolean) : LaunchOutcome

    /** Nothing on this device handles the intent. */
    data object NoHandler : LaunchOutcome

    /** It could not be launched and could not be deferred. */
    data class Failed(val reason: String) : LaunchOutcome

    val didReachTheScreen: Boolean get() = this is Launched
}

/**
 * The one place Ani starts an activity.
 *
 * **The bug this exists to fix.** Every external action — calling, Spotify, opening an
 * app, maps, settings — used to call `context.startActivity` on the application context
 * and treat the absence of an exception as success. That works while the user is looking
 * at Ani. It does not work when the command arrived through the wake word with the app in
 * the background or the screen off, because Android 10 and later block background activity
 * starts *silently*: no exception, no callback, nothing on screen. The calling code had no
 * way to tell, so it said the action had happened.
 *
 * So this class does three things the old code did not:
 *
 *  1. Checks there is a handler at all, before claiming anything.
 *  2. Knows whether the app is in the foreground, and therefore whether a direct start is
 *     permitted rather than assuming it is.
 *  3. When it is not permitted, falls back to a full-screen-intent notification — the
 *     documented route for an action that needs to surface over a locked screen — and
 *     returns [LaunchOutcome.Deferred] so the caller says something true.
 *
 * A full-screen intent is not a loophole: it is what the platform provides for exactly
 * this, it is visible to the user, and Android 14 downgrades it to a heads-up notification
 * for apps it does not class as calling apps. Both outcomes are reported distinctly.
 */
class ActivityLauncher(
    private val context: Context,
    private val foregroundState: ForegroundState
) {

    /**
     * Launches [intent], or defers it behind a notification when Android will not allow a
     * background start.
     *
     * @param label short human name for the notification, e.g. "Call Annayya".
     * @param allowDeferral false for actions that make no sense as a notification, such as
     *        opening a settings screen the user did not ask to be interrupted by.
     */
    fun launch(
        intent: Intent,
        label: String,
        allowDeferral: Boolean = true
    ): LaunchOutcome {
        val launchable = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launchable.resolveActivity(context.packageManager) == null) {
            AniLog.i(TAG, "INTENT no handler", "action" to (launchable.action ?: "none"))
            return LaunchOutcome.NoHandler
        }

        val route = LaunchPolicy.route(
            hasHandler = true,
            isInForeground = foregroundState.isInForeground,
            sdkInt = Build.VERSION.SDK_INT,
            allowDeferral = allowDeferral
        )

        if (route == LaunchRoute.DIRECT) {
            return try {
                context.startActivity(launchable)
                AniLog.i(TAG, "activity launch SUCCESS", "action" to (launchable.action ?: "none"))
                LaunchOutcome.Launched
            } catch (error: ActivityNotFoundException) {
                AniLog.w(TAG, "activity launch FAILURE: no handler")
                LaunchOutcome.NoHandler
            } catch (error: SecurityException) {
                // A handler exists but will not accept a caller with our permissions.
                AniLog.w(TAG, "activity launch FAILURE: refused")
                LaunchOutcome.Failed("Android refused to open it.")
            } catch (error: Exception) {
                AniLog.e(TAG, "activity launch FAILURE", error)
                LaunchOutcome.Failed("It could not be opened.")
            }
        }

        if (route == LaunchRoute.REFUSE) {
            return LaunchOutcome.Failed("Android will not open that while Ani is in the background.")
        }

        AniLog.i(TAG, "background start blocked; deferring", "action" to (launchable.action ?: "none"))
        return deferBehindNotification(launchable, label)
    }

    /**
     * Posts a high-priority notification whose full-screen intent is the action.
     *
     * With the screen off or locked this surfaces immediately, which is the behaviour a
     * hands-free command needs. Where the system declines to honour the full-screen intent
     * it still appears as a heads-up notification, so the action is one tap away rather
     * than lost.
     */
    private fun deferBehindNotification(intent: Intent, label: String): LaunchOutcome {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return LaunchOutcome.Failed("Notifications are unavailable.")

        ensureChannel(manager)

        val pending = PendingIntent.getActivity(
            context,
            intent.filterHashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenHonoured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 reserves full-screen intents for calling and alarm apps.
            runCatching { manager.canUseFullScreenIntent() }.getOrDefault(false)
        } else {
            true
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setContentText(context.getString(R.string.launch_deferred_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .apply { if (fullScreenHonoured) setFullScreenIntent(pending, true) }
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            AniLog.i(TAG, "activity deferred to notification", "fullScreen" to fullScreenHonoured)
            LaunchOutcome.Deferred(asHeadsUp = !fullScreenHonoured)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS revoked; there is no route left, and saying so is the
            // only honest option.
            AniLog.w(TAG, "cannot defer: notification permission missing")
            LaunchOutcome.Failed("Ani needs notification permission to act while in the background.")
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.launch_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.launch_channel_description)
                setShowBadge(false)
            }
        )
    }

    private companion object {
        const val TAG = "AniLaunch"
        const val CHANNEL_ID = "ani_actions"
        const val NOTIFICATION_ID = 2001
    }
}

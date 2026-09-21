package com.ani.assistant.notifications

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ani.assistant.AniApplication
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.data.notifications.NotificationRecord
import com.ani.nlu.response.NotificationPrivacy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reads notifications, so "em messages vachayi?" has something to answer with.
 *
 * This service is the single most privacy-sensitive component in Ani, and it is written
 * accordingly:
 *
 * - **Nothing is captured from an app the user has not ticked.** The allow-list is
 *   checked on every notification, not at bind time, so removing an app takes effect on
 *   the next notification rather than the next restart.
 * - **Message text is dropped before storage** unless the privacy setting is
 *   [NotificationPrivacy.SENDER_AND_PREVIEW]. Turning previews off does not merely stop
 *   Ani reading them aloud; the text never reaches disk.
 * - **Nothing is ever logged.** Not the sender, not the text, not the app. Counts only.
 * - **Nothing leaves the device.** No notification content is sent to the AI backend.
 *
 * The user must enable this in Settings > Notification access; it cannot be granted with
 * a runtime dialog, and Ani says so rather than pretending otherwise.
 */
class AniNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        AniLog.i(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        AniLog.i(TAG, "notification listener disconnected")
        // Android occasionally unbinds the listener without the user revoking access.
        // Asking for a rebind is the documented recovery.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, AniNotificationListenerService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val application = applicationContext
        scope.launch {
            try {
                val graph = AniApplication.graphOrNull(application) ?: return@launch
                val settings = graph.settingsRepository.settings.first()

                if (!settings.storeNotificationHistory) return@launch
                if (sbn.packageName !in settings.allowedNotificationPackages) return@launch
                if (sbn.packageName == application.packageName) return@launch
                if (!isWorthKeeping(sbn)) return@launch

                val record = sbn.toRecord(settings.notificationPrivacy) ?: return@launch
                graph.notificationRepository.record(record, historyEnabled = true)
            } catch (error: Exception) {
                // Never let a malformed notification from another app take the listener down.
                AniLog.e(TAG, "could not process a notification", error)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val key = sbn.key
        scope.launch {
            runCatching {
                AniApplication.graphOrNull(applicationContext)?.notificationRepository?.remove(key)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        isConnected = false
        super.onDestroy()
    }

    /**
     * Filters out the notifications that would make a summary useless: ongoing media
     * controls, progress bars, group summaries (whose children we already have) and
     * anything with no text at all.
     */
    private fun isWorthKeeping(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification ?: return false
        val flags = notification.flags

        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)) return false
        if (notification.extras.containsKey(Notification.EXTRA_PROGRESS_MAX)) return false

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return !title.isNullOrBlank() || !text.isNullOrBlank()
    }

    private fun StatusBarNotification.toRecord(privacy: NotificationPrivacy): NotificationRecord? {
        val extras = notification?.extras ?: return null
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

        return NotificationRecord(
            key = key,
            packageName = packageName,
            appLabel = appLabel,
            sender = sender?.takeIf { it.isNotBlank() },
            // The preview is discarded here, at the boundary, rather than filtered later.
            preview = if (privacy == NotificationPrivacy.SENDER_AND_PREVIEW) {
                text?.take(MAX_PREVIEW_LENGTH)
            } else {
                null
            },
            postedAtMillis = postTime,
            isMessaging = isMessagingNotification()
        )
    }

    /**
     * Whether this counts as a message for "em messages vachayi?".
     *
     * `CATEGORY_MESSAGE` is the reliable signal when an app sets it; the MessagingStyle
     * template is the other. Falling back to a package list would go stale, so apps that
     * set neither are simply treated as general notifications.
     */
    private fun StatusBarNotification.isMessagingNotification(): Boolean {
        val notification = notification ?: return false
        if (notification.category == Notification.CATEGORY_MESSAGE) return true
        if (notification.category == Notification.CATEGORY_EMAIL) return true
        val template = notification.extras.getString(Notification.EXTRA_TEMPLATE)
        return template?.contains("MessagingStyle") == true
    }

    companion object {
        private const val TAG = "AniNotifications"
        private const val MAX_PREVIEW_LENGTH = 200

        /**
         * Whether the service is currently bound.
         *
         * Diagnostics shows this next to the Settings toggle state, because the two can
         * legitimately differ for a few seconds after the user grants access.
         */
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}

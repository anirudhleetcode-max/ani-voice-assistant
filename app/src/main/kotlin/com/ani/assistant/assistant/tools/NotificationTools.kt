package com.ani.assistant.assistant.tools

import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.notifications.NotificationRepository
import com.ani.assistant.platform.device.DeviceController
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.KnownApps
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.NotificationPrivacy
import com.ani.nlu.response.NotificationSummarizer
import com.ani.nlu.response.Responses

/**
 * Answers "em messages vachayi?".
 *
 * Three privacy gates apply before a single word is read out, and all three are the
 * user's choices rather than ours:
 *
 * 1. Only apps on the allow-list were ever captured (enforced in the listener service).
 * 2. Previews are only spoken at [NotificationPrivacy.SENDER_AND_PREVIEW].
 * 3. If the screen is locked, or headphones are required and absent, the content is
 *    downgraded to counts — because a phone on a table reading out "Rahul: are you
 *    coming tonight" to the room is a failure no matter how good the recognition was.
 */
class NotificationReaderTool(
    private val notifications: NotificationRepository,
    private val device: DeviceController
) : AniTool {

    override val id: String = "notification_reader"
    override val handles: Set<IntentType> =
        setOf(IntentType.READ_NOTIFICATIONS, IntentType.READ_MISSED_CALLS)
    override val requiredPermissions: List<AniPermission> =
        listOf(AniPermission.NOTIFICATION_ACCESS)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val filter = when (command.type) {
            IntentType.READ_MISSED_CALLS -> MISSED_CALL_FILTER
            else -> command[SlotKey.NOTIFICATION_FILTER] ?: "all"
        }

        val all = notifications.snapshot().map { record ->
            record.toSummaryItem(KnownApps.canonicalOrNull(record.appLabel) ?: record.packageName)
        }

        val matching = if (filter == MISSED_CALL_FILTER) {
            all.filter { it.appLabel.contains("call", ignoreCase = true) }
        } else {
            NotificationSummarizer.applyFilter(all, filter)
        }

        if (matching.isEmpty()) {
            return AniResult.Success(Responses.noNotifications(context.style))
        }

        val privacy = effectivePrivacy(context)
        val summary = NotificationSummarizer.summarize(matching, privacy, context.style)

        // Reading them out counts as delivery; the next "em messages vachayi" starts fresh.
        notifications.markAllRead()

        return if (privacy != context.settings.notificationPrivacy) {
            AniResult.Limitation(
                spokenResponse = summary + " " + privacyHint(context),
                fallbackTaken = "read counts only"
            )
        } else {
            AniResult.Success(summary)
        }
    }

    /**
     * Downgrades privacy when the situation calls for it.
     *
     * Never upgrades. A user who chose "sender only" gets sender only even with
     * headphones on and the screen unlocked.
     */
    private fun effectivePrivacy(context: ToolContext): NotificationPrivacy {
        val chosen = context.settings.notificationPrivacy
        val lockedAndHidden = context.isDeviceLocked && context.settings.hideNotificationsOnLockScreen
        val needsHeadphones = context.settings.notificationsRequireHeadphones &&
            !device.isHeadsetConnected()

        return if (lockedAndHidden || needsHeadphones) NotificationPrivacy.COUNT_ONLY else chosen
    }

    private fun privacyHint(context: ToolContext) = if (context.style.speaksTelugu) {
        "Vivaralu kavalante phone unlock chesi malli adugu."
    } else {
        "Unlock the phone and ask again for the details."
    }

    private companion object {
        const val MISSED_CALL_FILTER = "missed_calls"
    }
}

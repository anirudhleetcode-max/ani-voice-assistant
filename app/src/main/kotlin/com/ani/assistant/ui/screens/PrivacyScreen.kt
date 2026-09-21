package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.permission.PermissionStatus
import com.ani.assistant.ui.components.NavigationRow
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup
import com.ani.assistant.ui.components.SwitchRow
import com.ani.assistant.data.settings.AniSettings
import com.ani.nlu.response.NotificationPrivacy

/**
 * The Privacy Center.
 *
 * Three jobs: show exactly what Ani is allowed to do, show exactly what it has kept, and
 * make both reversible in one tap. Every claim on this screen is read from the live
 * permission state rather than from a stored flag, so it cannot drift out of date when
 * the user changes something in system Settings.
 */
@Composable
fun PrivacyScreen(
    settings: AniSettings,
    permissions: Map<AniPermission, PermissionStatus>,
    historyCount: Int,
    memoryCount: Int,
    notificationCount: Int,
    onUpdate: ((AniSettings) -> AniSettings) -> Unit,
    onRequestPermission: (AniPermission) -> Unit,
    onClearHistory: () -> Unit,
    onClearMemory: () -> Unit,
    onDeleteEverything: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmWipe by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Ani keeps everything on this phone. Nothing here is uploaded, and " +
                "notification contents never leave the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        SectionHeader("Permissions")
        SettingsGroup {
            for (permission in AniPermission.entries) {
                val status = permissions[permission] ?: PermissionStatus.NOT_REQUESTED
                NavigationRow(
                    title = permission.displayName,
                    subtitle = permission.whyNeeded,
                    trailingText = status.label(),
                    trailingColor = status.color(),
                    onClick = { onRequestPermission(permission) }
                )
            }
        }

        SectionHeader("What Ani may read out")
        SettingsGroup {
            for (option in NotificationPrivacy.entries) {
                SwitchRow(
                    title = option.label(),
                    subtitle = option.description(),
                    checked = settings.notificationPrivacy == option,
                    onCheckedChange = { checked ->
                        if (checked) onUpdate { current -> current.copy(notificationPrivacy = option) }
                    }
                )
            }
            SwitchRow(
                title = "Keep quiet on the lock screen",
                subtitle = "Read counts only until the phone is unlocked.",
                checked = settings.hideNotificationsOnLockScreen,
                onCheckedChange = {
                    onUpdate { current -> current.copy(hideNotificationsOnLockScreen = it) }
                }
            )
            SwitchRow(
                title = "Only with headphones",
                subtitle = "Never read message content out loud through the speaker.",
                checked = settings.notificationsRequireHeadphones,
                onCheckedChange = {
                    onUpdate { current -> current.copy(notificationsRequireHeadphones = it) }
                }
            )
        }

        SectionHeader("Stored on this phone")
        SettingsGroup {
            SwitchRow(
                title = "Keep conversation history",
                subtitle = "$historyCount lines stored · text only, never audio",
                checked = settings.storeConversationHistory,
                onCheckedChange = {
                    onUpdate { current -> current.copy(storeConversationHistory = it) }
                }
            )
            SwitchRow(
                title = "Keep recent notifications",
                subtitle = "$notificationCount kept · cleared after " +
                    "${settings.notificationRetentionHours} hours",
                checked = settings.storeNotificationHistory,
                onCheckedChange = {
                    onUpdate { current -> current.copy(storeNotificationHistory = it) }
                }
            )
            NavigationRow(
                title = "Delete conversation history",
                subtitle = "$historyCount lines",
                onClick = onClearHistory
            )
            NavigationRow(
                title = "Delete what Ani remembers",
                subtitle = "$memoryCount entries",
                onClick = onClearMemory
            )
        }

        SectionHeader("Everything")
        SettingsGroup {
            NavigationRow(
                title = "Delete all Ani data",
                subtitle = "History, memory, notifications, commands and stored tokens",
                onClick = { confirmWipe = true }
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Ani never records audio to disk, never sends notification contents " +
                "anywhere, and never logs contact names or message text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(32.dp))
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete everything?") },
            text = {
                Text(
                    "This removes every transcript, remembered fact, saved command and " +
                        "stored token from this phone. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteEverything()
                    confirmWipe = false
                }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PermissionStatus.color(): Color = when (this) {
    PermissionStatus.GRANTED, PermissionStatus.NOT_APPLICABLE -> MaterialTheme.colorScheme.primary
    PermissionStatus.PERMANENTLY_DENIED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun PermissionStatus.label(): String = when (this) {
    PermissionStatus.GRANTED -> "Allowed"
    PermissionStatus.NOT_APPLICABLE -> "Not needed"
    PermissionStatus.DENIED -> "Not allowed"
    PermissionStatus.PERMANENTLY_DENIED -> "Blocked"
    PermissionStatus.NOT_REQUESTED -> "Not set up"
}

private fun NotificationPrivacy.label(): String = when (this) {
    NotificationPrivacy.COUNT_ONLY -> "Counts only"
    NotificationPrivacy.SENDER_ONLY -> "Who messaged"
    NotificationPrivacy.SENDER_AND_PREVIEW -> "Who, and what they said"
}

private fun NotificationPrivacy.description(): String = when (this) {
    NotificationPrivacy.COUNT_ONLY -> "\"WhatsApp lo moodu messages vachayi.\""
    NotificationPrivacy.SENDER_ONLY -> "Adds names. The default."
    NotificationPrivacy.SENDER_AND_PREVIEW -> "Message text is stored and read aloud."
}

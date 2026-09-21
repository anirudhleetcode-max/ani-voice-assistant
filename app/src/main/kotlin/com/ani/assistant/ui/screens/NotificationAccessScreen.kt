package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ani.assistant.platform.apps.InstalledApp
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup
import com.ani.assistant.ui.components.SwitchRow

/**
 * Which apps Ani may read notifications from.
 *
 * Two separate things, and the screen keeps them separate because users conflate them and
 * then wonder why nothing works: the *system* toggle that lets Ani see notifications at
 * all, and the per-app allow-list that decides which of them it keeps. Both off by
 * default.
 */
@Composable
fun NotificationAccessScreen(
    accessGranted: Boolean,
    listenerConnected: Boolean,
    candidateApps: List<InstalledApp>,
    allowedPackages: Set<String>,
    onOpenSystemSettings: () -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (accessGranted) {
                "Ani can see notifications. Pick which apps it should keep."
            } else {
                "Android does not let an app ask for notification access with a pop-up — " +
                    "it has to be switched on in system Settings. Until then Ani will say " +
                    "so rather than guess at your messages."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        Button(
            onClick = onOpenSystemSettings,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(if (accessGranted) "Open notification access settings" else "Turn on notification access")
        }

        if (accessGranted && !listenerConnected) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Access is granted but the listener hasn't reconnected yet. " +
                    "This usually resolves within a few seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        SectionHeader("Apps Ani may read")
        SettingsGroup {
            if (candidateApps.isEmpty()) {
                Text(
                    text = "No apps found.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                for (app in candidateApps) {
                    SwitchRow(
                        title = app.label,
                        checked = app.packageName in allowedPackages,
                        enabled = accessGranted,
                        onCheckedChange = { onToggleApp(app.packageName, it) }
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

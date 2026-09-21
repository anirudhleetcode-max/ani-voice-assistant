package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ani.assistant.platform.device.RestrictionCheck
import com.ani.assistant.platform.device.RestrictionState
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup

/**
 * "Keep Ani Ready" — the background-restriction setup.
 *
 * This screen exists because of a specific, common failure: everything works while the
 * app is open, then the phone sits on a table for an hour and the wake word stops being
 * heard. On stock Android a foreground service survives that; on realme, Xiaomi, vivo and
 * several others, a separate OEM battery manager kills it anyway.
 *
 * The screen is careful about what it claims. A check the platform can answer shows a real
 * state. The OEM auto-start list, which has no API on any skin, shows "Can't check" and
 * asks the user to confirm by eye — because a green tick next to something we never read
 * would be the most damaging thing on this screen.
 */
@Composable
fun KeepReadyScreen(
    deviceDescription: String,
    checks: List<RestrictionCheck>,
    hasAggressiveBatteryManager: Boolean,
    onOpen: (RestrictionCheck) -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "For Ani to hear \"Rey\" with the screen off, Android has to let it keep " +
                "running in the background.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        Text(
            text = deviceDescription,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (hasAggressiveBatteryManager) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This phone ships a battery manager of its own, on top of Android's. " +
                    "It will stop Ani after a while unless Ani is on its allow-list — and " +
                    "there is no way for an app to check or change that list, so the last " +
                    "step below has to be done by hand.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        SectionHeader("Checks")
        SettingsGroup {
            for (check in checks) {
                RestrictionRow(check = check, onOpen = { onOpen(check) })
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRecheck,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) { Text("Re-check now") }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "After changing a setting, come back and re-check. Ani only reports what " +
                "Android actually tells it — anything it cannot read is shown as unknown " +
                "rather than guessed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun RestrictionRow(check: RestrictionCheck, onOpen: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = check.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = check.state.label(),
                style = MaterialTheme.typography.labelLarge,
                color = check.state.color()
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = check.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (check.settingsIntent != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpen) {
                Text(if (check.state == RestrictionState.UNKNOWN) "Open and check" else "Open settings")
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No screen on this phone handles this. Look under Battery or App " +
                    "management in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun RestrictionState.label(): String = when (this) {
    RestrictionState.ALLOWED -> "Allowed"
    RestrictionState.RESTRICTED -> "Restricted"
    RestrictionState.UNKNOWN -> "Can't check"
}

@Composable
private fun RestrictionState.color(): Color = when (this) {
    RestrictionState.ALLOWED -> MaterialTheme.colorScheme.primary
    RestrictionState.RESTRICTED -> MaterialTheme.colorScheme.error
    RestrictionState.UNKNOWN -> MaterialTheme.colorScheme.tertiary
}

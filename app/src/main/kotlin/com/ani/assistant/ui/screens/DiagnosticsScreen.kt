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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup

/** One line of the diagnostics report. */
data class DiagnosticEntry(
    val label: String,
    val value: String,
    val state: DiagnosticState,
    val detail: String? = null
)

enum class DiagnosticState { OK, WARNING, PROBLEM, NEUTRAL }

/**
 * What is working and what is not.
 *
 * Exists because "Ani didn't answer" has a dozen possible causes — no recogniser, no
 * Telugu voice installed, notification access revoked, the backend unreachable — and
 * guessing between them from the outside is miserable. Every row is read live.
 *
 * Nothing here contains user content: states, counts and availability only.
 */
@Composable
fun DiagnosticsScreen(
    entries: List<DiagnosticEntry>,
    unhandledIntents: List<String>,
    onRefresh: () -> Unit,
    onOpenMicTest: () -> Unit,
    onOpenRecognizerTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Live status. Nothing on this screen includes your messages, contacts " +
                "or recordings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Button(onClick = onOpenMicTest) { Text("Mic Test") }
            OutlinedButton(onClick = onOpenRecognizerTest) { Text("Recogniser A/B") }
        }

        Text(
            text = "Measures the microphone directly. Run it when Ani says it could not " +
                "hear you — it shows whether audio reached the phone at all, which is a " +
                "different question from whether the words were understood.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SectionHeader("Status")
        SettingsGroup {
            for (entry in entries) {
                DiagnosticRow(entry)
            }
        }

        if (unhandledIntents.isNotEmpty()) {
            SectionHeader("Not implemented yet")
            SettingsGroup {
                Text(
                    text = unhandledIntents.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRefresh,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) { Text("Re-check") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun DiagnosticRow(entry: DiagnosticEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.label, style = MaterialTheme.typography.bodyLarge)
            if (entry.detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = entry.value,
            style = MaterialTheme.typography.labelLarge,
            color = entry.state.color()
        )
    }
}

@Composable
private fun DiagnosticState.color(): Color = when (this) {
    DiagnosticState.OK -> MaterialTheme.colorScheme.primary
    DiagnosticState.WARNING -> MaterialTheme.colorScheme.tertiary
    DiagnosticState.PROBLEM -> MaterialTheme.colorScheme.error
    DiagnosticState.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

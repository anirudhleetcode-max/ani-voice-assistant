package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup
import com.ani.assistant.voice.BenchmarkResult
import com.ani.assistant.voice.BenchmarkState

/**
 * Speech Recognition Test — the same sentence through different recognisers.
 *
 * The benchmark that matters is not Ani against itself, it is Ani against the phone's own
 * assistant. Google Assistant understands quiet speech on this device; if Ani's recogniser
 * does not, the difference is in here — which engine, which language — and not in the
 * microphone.
 *
 * Say the **same sentence at the same volume** for every trial, or the comparison means
 * nothing:
 *
 * ```
 * rey annayya ki call chey
 * rey em messages vacchayi
 * rey spotify lo arijit singh play chey
 * ```
 *
 * Then repeat the whole set quietly.
 */
@Composable
fun RecognizerTestScreen(
    state: BenchmarkState,
    showTranscripts: Boolean,
    preferOnDevice: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onPreferOnDeviceChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Runs the same sentence through each recogniser in turn, one at a time. " +
                "Say the identical sentence at the identical volume each time — the point " +
                "is the difference between engines, so everything else has to be held still.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Button(onClick = onRun, enabled = !state.running) { Text("Run all trials") }
            if (state.running) OutlinedButton(onClick = onStop) { Text("Stop") }
        }

        if (state.currentTrial != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Speak now — ${state.currentTrial}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        SectionHeader("Which engine Ani uses")
        SettingsGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use on-device recognition", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Turn this on only if a trial above shows the on-device " +
                            "engine producing transcripts as good as the system one. It " +
                            "removes the network round trip; it is often worse at mixed " +
                            "Telugu and English.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = preferOnDevice, onCheckedChange = onPreferOnDeviceChanged)
            }
        }

        SectionHeader("On-device support")
        SettingsGroup {
            Text(
                text = state.support.describe(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }

        SectionHeader("Trials")
        for (result in state.results) {
            TrialCard(result = result, showTranscripts = showTranscripts)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "If a trial returns the right words and Ani still says it could not " +
                "hear you, the problem is after recognition and no audio change will fix " +
                "it. If every trial garbles the same quiet sentence that Google Assistant " +
                "gets right, the difference is the engine or the language — compare the " +
                "transcripts, not just whether it worked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TrialCard(result: BenchmarkResult, showTranscripts: Boolean) {
    SettingsGroup {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = result.trial.label,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        result.running -> "listening…"
                        result.error != null -> "failed"
                        result.transcript != null -> "ok"
                        else -> "—"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (result.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            Spacer(Modifier.height(6.dp))
            Field("engine", result.actualKind?.name ?: "—")
            Field("onDevice", (result.actualKind?.name == "ON_DEVICE").toString())
            Field("language", result.trial.localeTag)
            Field("firstPartialMs", result.firstPartialMillis?.toString() ?: "—")
            Field("finalResultMs", result.finalResultMillis?.toString() ?: "—")
            Field("totalLatencyMs", result.totalLatencyMillis?.toString() ?: "—")
            Field("completeSilenceMs", result.trial.endpointing.completeSilenceMillis.toString())
            Field("audio", result.audioVerdict.name)
            Field("error", result.error ?: "none")

            if (showTranscripts) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Field("first partial", result.firstPartial ?: "—")
                Field("transcript", result.transcript ?: "—")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

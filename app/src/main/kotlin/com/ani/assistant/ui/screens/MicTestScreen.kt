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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup
import com.ani.assistant.voice.audio.AudioVerdict
import com.ani.assistant.voice.mic.MicTestState

/**
 * Mic Test — the answer to "is the phone actually hearing anything?"
 *
 * Every number here was read back from the platform rather than assumed. That is the
 * point of the screen: when Ani says it could not hear you, this is how you find out
 * whether that was true, and if so at which stage it stopped being true.
 *
 * Read it in this order:
 *
 *  1. **Level test.** Run it four times — silence, a whisper, normal speech, loud speech.
 *     If all four read the same, the microphone is not reaching this app and nothing
 *     downstream matters. That is almost always another recorder still holding it.
 *  2. **Recogniser test.** If levels look right and this still fails, the microphone is
 *     fine and the recogniser, its language or the network is the problem — and the exact
 *     platform error constant is printed rather than paraphrased.
 */
@Composable
fun MicTestScreen(
    state: MicTestState,
    showTranscripts: Boolean,
    onRunLevelTest: () -> Unit,
    onRunRecognizerTest: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Measures the microphone directly, before any recognition happens. " +
                "Nothing is recorded and no audio leaves this screen — only levels.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Button(onClick = onRunLevelTest, enabled = !state.running) {
                Text("Level test")
            }
            OutlinedButton(onClick = onRunRecognizerTest, enabled = !state.running) {
                Text("Recogniser test")
            }
            if (state.running) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
        }

        SectionHeader("Verdict")
        SettingsGroup {
            MicRow("Mic permission", if (state.permissionGranted) "GRANTED" else "DENIED")
            MicRow(
                label = "Audio",
                value = state.verdict.label(),
                valueColor = state.verdict.color()
            )
            MicRow("Speech detected", if (state.speechDetected) "YES" else "NO")
            MicRow("Phase", state.phase.name)
        }

        SectionHeader("Capture")
        SettingsGroup {
            MicRow("Audio source", state.audioSource ?: "—")
            MicRow("Sample rate", state.sampleRate?.let { "$it Hz" } ?: "—")
            MicRow("Channels", state.channels?.toString() ?: "—")
            MicRow("Encoding", state.encoding ?: "—")
            MicRow("Buffer", state.bufferBytes?.let { "$it bytes" } ?: "—")
            MicRow("AudioRecord initialised", state.recordInitialised.yesNo())
            MicRow("Still recording after close", state.recording.yesNo())
        }

        SectionHeader("Levels (PCM16 LSB)")
        SettingsGroup {
            MicRow("Blocks measured", state.blocksMeasured.toString())
            MicRow("RMS (max)", "%.0f".format(state.maxRms))
            MicRow("RMS (min)", "%.0f".format(state.minRms))
            MicRow("Peak", state.peak.toString())
            MicRow("Noise floor", "%.0f".format(state.noiseFloor))
            MicRow("Above silence", "%.0f%%".format(state.aboveSilenceRatio * 100))
        }

        SectionHeader("Recogniser")
        SettingsGroup {
            MicRow("Engine", state.recognizerKind?.name ?: "—")
            MicRow("On-device available", state.onDeviceAvailable.yesNo())
            MicRow("State", state.recognizerState)
            MicRow("Last error", state.lastError ?: "none")
            if (showTranscripts) {
                MicRow("Last partial", state.lastPartial ?: "—")
                MicRow("Last final", state.lastFinal ?: "—")
            }
        }

        if (!showTranscripts) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Transcripts are hidden in release builds. What you said is the one " +
                    "thing this screen will not show you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = "If loud speech and silence give the same numbers, the problem is before " +
                "recognition: something else holds the microphone. If the numbers move but " +
                "the recogniser still reports ERROR_NO_MATCH, capture is fine and the " +
                "recogniser or its language is the problem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MicRow(label: String, value: String, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun Boolean?.yesNo(): String = when (this) {
    true -> "YES"
    false -> "NO"
    null -> "—"
}

private fun AudioVerdict.label(): String = when (this) {
    AudioVerdict.UNKNOWN -> "NOT MEASURED"
    AudioVerdict.NO_AUDIO -> "NO AUDIO"
    AudioVerdict.LOW_AUDIO -> "LOW AUDIO"
    AudioVerdict.NORMAL_AUDIO -> "NORMAL AUDIO"
    AudioVerdict.CLIPPED -> "CLIPPED"
}

@Composable
private fun AudioVerdict.color(): Color = when (this) {
    AudioVerdict.NORMAL_AUDIO -> MaterialTheme.colorScheme.primary
    AudioVerdict.LOW_AUDIO, AudioVerdict.CLIPPED -> MaterialTheme.colorScheme.tertiary
    AudioVerdict.NO_AUDIO -> MaterialTheme.colorScheme.error
    AudioVerdict.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

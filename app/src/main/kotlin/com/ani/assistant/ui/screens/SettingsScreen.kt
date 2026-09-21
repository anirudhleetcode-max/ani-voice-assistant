package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ani.assistant.data.settings.AiProviderChoice
import com.ani.assistant.data.settings.AniSettings
import com.ani.assistant.data.settings.ThemePreference
import com.ani.assistant.ui.components.NavigationRow
import com.ani.assistant.ui.components.SectionHeader
import com.ani.assistant.ui.components.SettingsGroup
import com.ani.assistant.ui.components.SwitchRow
import com.ani.nlu.dialog.ConfirmationLevel
import com.ani.nlu.response.Persona
import com.ani.nlu.text.Language

/**
 * Settings.
 *
 * Grouped the way the user thinks about Ani rather than the way the code is organised:
 * how it wakes, how it sounds, what it may read, what it keeps.
 */
@Composable
fun SettingsScreen(
    settings: AniSettings,
    appVersion: String,
    wakeWordCostNote: String,
    onUpdate: ((AniSettings) -> AniSettings) -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---- Assistant ----------------------------------------------------------------
        SectionHeader("Assistant")
        SettingsGroup {
            LabelledField(
                label = "Assistant name",
                value = settings.assistantName,
                onValueChange = { onUpdate { current -> current.copy(assistantName = it) } }
            )
            LabelledField(
                label = "Wake phrases",
                value = settings.wakePhrases.joinToString(", "),
                supporting = "Comma separated. Ani listens for any of them.",
                onValueChange = { text ->
                    onUpdate { current ->
                        current.copy(
                            wakePhrases = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        )
                    }
                }
            )
            SwitchRow(
                title = "Listen for the wake phrase",
                subtitle = wakeWordCostNote,
                checked = settings.wakeWordEnabled,
                onCheckedChange = { onUpdate { current -> current.copy(wakeWordEnabled = it) } }
            )
            SliderRow(
                label = "Wake sensitivity",
                value = settings.wakeSensitivity,
                valueLabel = when {
                    settings.wakeSensitivity < 0.4f -> "Strict — exact match only"
                    settings.wakeSensitivity < 0.75f -> "Balanced"
                    else -> "Forgiving — may wake by mistake"
                },
                onValueChange = { onUpdate { current -> current.copy(wakeSensitivity = it) } }
            )
            SwitchRow(
                title = "Play a sound when Ani wakes",
                checked = settings.playActivationSound,
                onCheckedChange = { onUpdate { current -> current.copy(playActivationSound = it) } }
            )
            SwitchRow(
                title = "Keep listening for follow-ups",
                subtitle = "Stay in the conversation for a few seconds after answering.",
                checked = settings.autoFollowUp,
                onCheckedChange = { onUpdate { current -> current.copy(autoFollowUp = it) } }
            )
        }

        // ---- Voice --------------------------------------------------------------------
        SectionHeader("Voice and language")
        SettingsGroup {
            ChipRow(
                label = "Language",
                options = listOf(
                    null to "Auto",
                    Language.TELUGU to "Telugu",
                    Language.ENGLISH to "English",
                    Language.MIXED to "Mixed"
                ),
                selected = settings.language,
                onSelect = { onUpdate { current -> current.copy(language = it) } }
            )
            ChipRow(
                label = "Personality",
                options = Persona.entries.map { it to it.name.lowercase().replaceFirstChar(Char::titlecase) },
                selected = settings.persona,
                onSelect = { onUpdate { current -> current.copy(persona = it) } }
            )
            SliderRow(
                label = "Speaking speed",
                value = (settings.speechRate - 0.5f) / 1.5f,
                valueLabel = "%.1f×".format(settings.speechRate),
                onValueChange = {
                    onUpdate { current -> current.copy(speechRate = 0.5f + it * 1.5f) }
                }
            )
            SliderRow(
                label = "Pitch",
                value = (settings.speechPitch - 0.5f) / 1.5f,
                valueLabel = "%.1f×".format(settings.speechPitch),
                onValueChange = {
                    onUpdate { current -> current.copy(speechPitch = 0.5f + it * 1.5f) }
                }
            )
        }

        // ---- Confirmations --------------------------------------------------------------
        SectionHeader("Before acting")
        SettingsGroup {
            ChipRow(
                label = "Ask me first",
                options = listOf(
                    ConfirmationLevel.RELAXED to "Rarely",
                    ConfirmationLevel.BALANCED to "Balanced",
                    ConfirmationLevel.CAREFUL to "Often",
                    ConfirmationLevel.ALWAYS to "Always"
                ),
                selected = settings.confirmationLevel,
                onSelect = { onUpdate { current -> current.copy(confirmationLevel = it) } }
            )
            Text(
                text = when (settings.confirmationLevel) {
                    ConfirmationLevel.RELAXED -> "Only before silencing the phone or losing data."
                    ConfirmationLevel.BALANCED -> "Before anything that reaches another person."
                    ConfirmationLevel.CAREFUL -> "Before any visible change."
                    ConfirmationLevel.ALWAYS -> "Before everything."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        // ---- Integrations ----------------------------------------------------------------
        SectionHeader("Integrations")
        SettingsGroup {
            NavigationRow(
                title = "Notification access",
                subtitle = "Which apps Ani may read messages from",
                icon = Icons.Rounded.Notifications,
                onClick = onOpenNotificationAccess
            )
            LabelledField(
                label = "Preferred music app",
                value = settings.preferredMusicApp,
                supporting = "spotify, youtube, gaana, wynk, jiosaavn",
                onValueChange = { onUpdate { current -> current.copy(preferredMusicApp = it.trim()) } }
            )
            ChipRow(
                label = "Answering open questions",
                options = listOf(
                    AiProviderChoice.BACKEND to "Use the AI backend",
                    AiProviderChoice.OFFLINE_ONLY to "Stay offline"
                ),
                selected = settings.aiProvider,
                onSelect = { onUpdate { current -> current.copy(aiProvider = it) } }
            )
            Text(
                text = "Phone commands — calls, messages, alarms, notifications, music — " +
                    "never use the network either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        // ---- Appearance ---------------------------------------------------------------------
        SectionHeader("Appearance")
        SettingsGroup {
            ChipRow(
                label = "Theme",
                options = listOf(
                    ThemePreference.SYSTEM to "System",
                    ThemePreference.LIGHT to "Light",
                    ThemePreference.DARK to "Dark"
                ),
                selected = settings.theme,
                onSelect = { onUpdate { current -> current.copy(theme = it) } }
            )
            SwitchRow(
                title = "Use wallpaper colours",
                checked = settings.useDynamicColor,
                onCheckedChange = { onUpdate { current -> current.copy(useDynamicColor = it) } }
            )
        }

        // ---- Privacy and about ---------------------------------------------------------------
        SectionHeader("Privacy")
        SettingsGroup {
            NavigationRow(
                title = "Privacy Center",
                subtitle = "Permissions, stored data, delete everything",
                icon = Icons.Rounded.Lock,
                onClick = onOpenPrivacy
            )
            NavigationRow(
                title = "Diagnostics",
                subtitle = "What's working and what isn't",
                icon = Icons.Rounded.BugReport,
                onClick = onOpenDiagnostics
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Ani $appVersion",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    supporting: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { text -> { Text(text) } },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        // Scrolls rather than wrapping: FlowRow is still experimental, and a row of four
        // or five short chips reads fine as a scroller.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            for ((option, optionLabel) in options) {
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(optionLabel, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ani.assistant.data.commands.SaveCommandResult
import com.ani.assistant.data.commands.StoredCommand
import com.ani.assistant.ui.components.EmptyState
import com.ani.nlu.command.CustomAction
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey

/**
 * A step the user can add to a custom command.
 *
 * The list is closed and each entry maps to exactly one typed intent with at most one
 * argument. That is what makes a saved routine safe to run without re-confirming every
 * step: it can only contain things the user could have asked for one at a time.
 */
data class CommandActionTemplate(
    val intent: IntentType,
    val label: String,
    val slot: SlotKey? = null,
    val hint: String = "",
    /** A fixed value for the slot, for steps like "turn DND on". */
    val fixedValue: String? = null
) {
    fun toAction(argument: String): CustomAction {
        val slots = buildMap {
            val key = slot ?: return@buildMap
            put(key, fixedValue ?: argument)
        }
        return CustomAction(intent, slots)
    }

    val needsArgument: Boolean get() = slot != null && fixedValue == null
}

/** Everything a routine can be built from. */
val commandActionTemplates: List<CommandActionTemplate> = listOf(
    CommandActionTemplate(IntentType.OPEN_APP, "Open an app", SlotKey.APP_NAME, "spotify"),
    CommandActionTemplate(IntentType.PLAY_MUSIC, "Play music", SlotKey.MUSIC_QUERY, "lofi telugu"),
    CommandActionTemplate(IntentType.CALL_CONTACT, "Call someone", SlotKey.CONTACT_NAME, "Amma"),
    CommandActionTemplate(IntentType.CONTROL_DND, "Do Not Disturb on", SlotKey.TOGGLE_STATE, fixedValue = "on"),
    CommandActionTemplate(IntentType.CONTROL_DND, "Do Not Disturb off", SlotKey.TOGGLE_STATE, fixedValue = "off"),
    CommandActionTemplate(IntentType.CONTROL_VOLUME, "Set volume", SlotKey.LEVEL, "30"),
    CommandActionTemplate(IntentType.CONTROL_FLASHLIGHT, "Torch on", SlotKey.TOGGLE_STATE, fixedValue = "on"),
    CommandActionTemplate(IntentType.CONTROL_FLASHLIGHT, "Torch off", SlotKey.TOGGLE_STATE, fixedValue = "off"),
    CommandActionTemplate(IntentType.OPEN_SETTINGS, "Open a settings screen", SlotKey.SETTINGS_TARGET, "wifi"),
    CommandActionTemplate(IntentType.READ_NOTIFICATIONS, "Read my notifications"),
    CommandActionTemplate(IntentType.GET_BATTERY, "Tell me the battery level"),
    CommandActionTemplate(IntentType.NAVIGATE, "Navigate somewhere", SlotKey.DESTINATION, "college")
)

/** A step under construction, before it becomes a [CustomAction]. */
private data class DraftStep(val template: CommandActionTemplate, val argument: String)

/**
 * "Teach Ani" — the custom command list and builder.
 *
 * A saved phrase is matched *before* the built-in rules, so a user who defines
 * "good night" gets their own routine rather than Ani's idea of one.
 */
@Composable
fun CommandsScreen(
    commands: List<StoredCommand>,
    saveResult: SaveCommandResult?,
    onSave: (String, List<CustomAction>) -> Unit,
    onRun: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onConsumeSaveResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showBuilder by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showBuilder = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Create a command")
            }
        }
    ) { padding ->
        if (commands.isEmpty()) {
            EmptyState(
                title = "No commands yet",
                body = "Build one phrase that does several things — \"college mode\", " +
                    "\"good night\", whatever you actually say.",
                icon = Icons.Rounded.Bolt,
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(commands, key = { it.id }) { command ->
                    CommandCard(
                        command = command,
                        onRun = { onRun(command.phrase) },
                        onSetEnabled = { onSetEnabled(command.id, it) },
                        onDelete = { onDelete(command.id) }
                    )
                }
            }
        }
    }

    if (showBuilder) {
        CommandBuilderDialog(
            onDismiss = { showBuilder = false },
            onSave = { phrase, actions ->
                onSave(phrase, actions)
                showBuilder = false
            }
        )
    }

    if (saveResult != null) {
        val message = when (saveResult) {
            is SaveCommandResult.Saved -> "Saved \"${saveResult.command.phrase}\"."
            SaveCommandResult.PhraseEmpty -> "Give the command something to be called."
            SaveCommandResult.NoActions -> "Add at least one step."
            is SaveCommandResult.DuplicatePhrase ->
                "\"${saveResult.existing.phrase}\" already sounds the same. Pick a different phrase."
        }
        AlertDialog(
            onDismissRequest = onConsumeSaveResult,
            title = { Text(if (saveResult is SaveCommandResult.Saved) "Done" else "Can't save that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = onConsumeSaveResult) { Text("OK") } }
        )
    }
}

@Composable
private fun CommandCard(
    command: StoredCommand,
    onRun: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "\"${command.phrase}\"",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = command.enabled, onCheckedChange = onSetEnabled)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = command.actions.joinToString(" → ") { it.intent.readable() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onRun,
                    label = { Text("Run") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) }
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete command")
                }
            }
        }
    }
}

@Composable
private fun CommandBuilderDialog(
    onDismiss: () -> Unit,
    onSave: (String, List<CustomAction>) -> Unit
) {
    var phrase by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<DraftStep>() }
    var selected by remember { mutableStateOf<CommandActionTemplate?>(null) }
    var argument by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New command") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("What you'll say") },
                    placeholder = { Text("college mode") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (steps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Steps", style = MaterialTheme.typography.labelMedium)
                    for ((index, step) in steps.withIndex()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}. ${step.template.label}" +
                                    step.argument.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { steps.removeAt(index) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Remove step")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Add a step", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))

                for (template in commandActionTemplates) {
                    FilterChip(
                        selected = selected == template,
                        onClick = {
                            selected = template
                            argument = ""
                        },
                        label = { Text(template.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }

                val current = selected
                if (current != null) {
                    Spacer(Modifier.height(8.dp))
                    if (current.needsArgument) {
                        OutlinedTextField(
                            value = argument,
                            onValueChange = { argument = it },
                            label = { Text("Value") },
                            placeholder = { Text(current.hint) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        onClick = {
                            steps.add(DraftStep(current, argument.trim()))
                            selected = null
                            argument = ""
                        },
                        enabled = !current.needsArgument || argument.isNotBlank()
                    ) { Text("Add step") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(phrase.trim(), steps.map { it.template.toAction(it.argument) }) },
                enabled = phrase.isNotBlank() && steps.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Turns a stored step's intent name into something readable.
 *
 * Operates on the stored [String] rather than a parsed [IntentType] so that a step saved
 * by a newer version still shows up in the list. Dropping it from the summary would make
 * the card silently disagree with what the command actually contains.
 */
private fun String.readable(): String = lowercase().replace('_', ' ')

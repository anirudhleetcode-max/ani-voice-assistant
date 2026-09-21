package com.ani.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ani.assistant.data.memory.MemoryCategory
import com.ani.assistant.data.memory.MemoryEntry
import com.ani.assistant.ui.components.EmptyState

/**
 * Everything Ani remembers.
 *
 * The list is complete. Ani does not learn from behaviour, does not infer preferences and
 * does not write anything here that the user did not state — so this screen is not a
 * summary of a model, it is the model.
 */
@Composable
fun MemoryScreen(
    entries: List<MemoryEntry>,
    onAdd: (MemoryCategory, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdd by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Teach Ani something")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (entries.isEmpty()) {
                EmptyState(
                    title = "Nothing remembered yet",
                    body = "Say \"Rey, Amma ante Lakshmi\" and Ani will know who you mean next time.",
                    icon = Icons.Rounded.AutoAwesome
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        MemoryRow(entry = entry, onDelete = { onDelete(entry.id) })
                    }
                }
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Forget everything")
                }
            }
        }
    }

    if (showAdd) {
        AddMemoryDialog(
            onDismiss = { showAdd = false },
            onSave = { category, key, value ->
                onAdd(category, key, value)
                showAdd = false
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget everything?") },
            text = { Text("All aliases and remembered facts are deleted from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    confirmClear = false
                }) { Text("Forget all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MemoryRow(entry: MemoryEntry, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.category.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${entry.key} → ${entry.value}",
                style = MaterialTheme.typography.bodyLarge
            )
            if (entry.learnedFrom != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "from: \"${entry.learnedFrom}\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Forget this")
        }
    }
}

@Composable
private fun AddMemoryDialog(
    onDismiss: () -> Unit,
    onSave: (MemoryCategory, String, String) -> Unit
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.CONTACT_ALIAS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Teach Ani") },
        text = {
            Column {
                // Four options fit as chips; a dropdown would be two taps for no gain.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in MemoryCategory.entries) {
                        FilterChip(
                            selected = option == category,
                            onClick = { category = option },
                            label = {
                                Text(option.shortLabel(), style = MaterialTheme.typography.labelSmall)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("When I say") },
                    placeholder = { Text("amma") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("You mean") },
                    placeholder = { Text("Lakshmi") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(category, key.trim(), value.trim()) },
                enabled = key.isNotBlank() && value.isNotBlank()
            ) { Text("Remember") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun MemoryCategory.shortLabel(): String = when (this) {
    MemoryCategory.CONTACT_ALIAS -> "Contact"
    MemoryCategory.APP_ALIAS -> "App"
    MemoryCategory.PREFERENCE -> "Preference"
    MemoryCategory.FACT -> "Fact"
}

private fun MemoryCategory.label(): String = when (this) {
    MemoryCategory.CONTACT_ALIAS -> "Contact name"
    MemoryCategory.APP_ALIAS -> "App nickname"
    MemoryCategory.PREFERENCE -> "Preference"
    MemoryCategory.FACT -> "Fact"
}

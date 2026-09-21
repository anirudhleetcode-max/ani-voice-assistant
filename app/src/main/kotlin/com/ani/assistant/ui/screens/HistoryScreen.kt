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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.ani.assistant.data.conversation.ConversationEntry
import com.ani.assistant.data.conversation.Speaker
import com.ani.assistant.ui.components.EmptyState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stored transcripts.
 *
 * Text only — there are no recordings to show because none are kept. Everything here is
 * deletable individually or all at once, and it stops being written the moment the user
 * turns history off in Privacy.
 */
@Composable
fun HistoryScreen(
    entries: List<ConversationEntry>,
    historyEnabled: Boolean,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val filtered = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { it.text.contains(query, ignoreCase = true) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
            IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Icon(Icons.Rounded.Delete, contentDescription = "Clear all history")
            }
        }

        when {
            !historyEnabled -> EmptyState(
                title = "History is off",
                body = "Ani isn't keeping transcripts. Turn it back on in Privacy if you want them.",
                icon = Icons.Rounded.History
            )

            filtered.isEmpty() -> EmptyState(
                title = if (query.isBlank()) "Nothing yet" else "No matches",
                body = if (query.isBlank()) {
                    "Your conversations with Ani will appear here."
                } else {
                    "Nothing in your history matches \"$query\"."
                },
                icon = Icons.Rounded.History
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    HistoryRow(entry = entry, onDelete = { onDelete(entry.id) })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all history?") },
            text = { Text("Every stored transcript is removed from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    confirmClear = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HistoryRow(entry: ConversationEntry, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (entry.speaker == Speaker.USER) "You" else "Ani",
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.speaker == Speaker.USER) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(Modifier.height(2.dp))
            Text(text = entry.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = timestampFormat.format(Date(entry.timestampMillis)) +
                    (entry.intent?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Rounded.Delete,
                contentDescription = "Delete this line",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val timestampFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

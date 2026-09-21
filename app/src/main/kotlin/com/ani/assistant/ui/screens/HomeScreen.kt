package com.ani.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ani.assistant.ui.TranscriptLine
import com.ani.assistant.ui.components.VoiceOrb
import com.ani.assistant.voice.VoiceState

/** A one-tap shortcut under the orb. */
data class QuickAction(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val utterance: String,
    val badgeCount: Int = 0
)

/**
 * The main screen: the orb, what Ani is doing, and the conversation so far.
 *
 * Deliberately sparse. The orb is the interface; the transcript is there so the user can
 * check what was heard, and the quick actions exist for the times when speaking out loud
 * is not an option. Everything else lives a tab away.
 */
@Composable
fun HomeScreen(
    assistantName: String,
    voiceState: VoiceState,
    audioLevel: Float,
    partialTranscript: String,
    transcript: List<TranscriptLine>,
    unreadNotifications: Int,
    wakePhrase: String,
    onOrbTap: () -> Unit,
    onQuickAction: (String) -> Unit,
    onSubmitText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var typed by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = assistantName.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        VoiceOrb(
            state = voiceState,
            audioLevel = audioLevel,
            onTap = onOrbTap,
            contentDescription = orbDescription(voiceState, wakePhrase)
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = statusText(voiceState, wakePhrase),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        AnimatedVisibility(
            visible = partialTranscript.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = partialTranscript,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        QuickActionRow(
            unreadNotifications = unreadNotifications,
            onAction = onQuickAction
        )

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty()) {
                Text(
                    text = "Say \"$wakePhrase\" or tap the circle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter).padding(24.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 20.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(transcript) { line -> TranscriptBubble(line) }
                }
            }
        }

        TextCommandField(
            value = typed,
            onValueChange = { typed = it },
            onSubmit = {
                if (typed.isNotBlank()) {
                    onSubmitText(typed.trim())
                    typed = ""
                }
            }
        )
    }
}

@Composable
private fun TranscriptBubble(line: TranscriptLine) {
    val alignment = if (line.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val background = if (line.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (line.fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            color = background,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (line.fromUser) 18.dp else 4.dp,
                bottomEnd = if (line.fromUser) 4.dp else 18.dp
            )
        ) {
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun QuickActionRow(unreadNotifications: Int, onAction: (String) -> Unit) {
    val actions = listOf(
        QuickAction("Messages", Icons.Rounded.Notifications, "em messages vachayi", unreadNotifications),
        QuickAction("Call", Icons.Rounded.Call, "call chey"),
        QuickAction("Music", Icons.Rounded.LibraryMusic, "music pettu"),
        QuickAction("Apps", Icons.Rounded.Apps, "app open chey")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        for (action in actions) {
            AssistChip(
                onClick = { onAction(action.utterance) },
                label = {
                    val label = if (action.badgeCount > 0) {
                        "${action.label} ${action.badgeCount}"
                    } else {
                        action.label
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium)
                },
                leadingIcon = {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.width(18.dp))
                }
            )
        }
    }
}

@Composable
private fun TextCommandField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Type instead of speaking") },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        trailingIcon = {
            IconButton(onClick = onSubmit, enabled = value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
            }
        }
    )
}

private fun statusText(state: VoiceState, wakePhrase: String): String = when (state) {
    VoiceState.IDLE -> "Tap to talk"
    VoiceState.WAITING_FOR_WAKE -> "Listening for \"$wakePhrase\""
    VoiceState.WAKE_DETECTED -> "Cheppu ra."
    VoiceState.LISTENING -> "Listening..."
    VoiceState.PROCESSING -> "Thinking..."
    VoiceState.SPEAKING -> "Speaking..."
    VoiceState.ERROR -> "Something went wrong"
}

private fun orbDescription(state: VoiceState, wakePhrase: String): String = when (state) {
    VoiceState.IDLE, VoiceState.WAITING_FOR_WAKE -> "Tap to talk to Ani, or say $wakePhrase"
    else -> "Ani is busy. Tap to stop."
}

package com.ani.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.permission.PermissionStatus
import com.ani.assistant.voice.wake.VoskModelState

/** One step of onboarding. */
data class OnboardingStep(
    val title: String,
    val body: String,
    /** The capability this step asks for, if any. */
    val permission: AniPermission? = null,
    /** True for the step that lets the user pick their wake phrase. */
    val isWakePhraseStep: Boolean = false,
    /** True for the step that offers the one-time wake-model download. */
    val isWakeModelStep: Boolean = false
)

/**
 * First-run setup.
 *
 * Structured as one ask per screen, each explaining what it is for *before* the system
 * dialog appears — rather than the usual wall of permission prompts on launch, which
 * trains people to tap Deny. Every step is skippable; the feature that needed it degrades
 * and says so later, which is the whole design of the tool layer.
 */
@Composable
fun OnboardingScreen(
    steps: List<OnboardingStep>,
    permissions: Map<AniPermission, PermissionStatus>,
    wakePhrase: String,
    wakeModelState: VoskModelState,
    onDownloadWakeModel: () -> Unit,
    onWakePhraseChange: (String) -> Unit,
    onRequestPermission: (AniPermission) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    var index by remember { mutableIntStateOf(0) }
    val step = steps.getOrNull(index) ?: return
    val isLast = index == steps.lastIndex

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        StepIndicator(current = index, total = steps.size)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (step.isWakePhraseStep) {
                Spacer(Modifier.height(28.dp))
                WakePhraseField(value = wakePhrase, onValueChange = onWakePhraseChange)
            }

            if (step.isWakeModelStep) {
                Spacer(Modifier.height(28.dp))
                WakeModelBlock(
                    state = wakeModelState,
                    onDownload = onDownloadWakeModel
                )
            }

            val permission = step.permission
            if (permission != null) {
                Spacer(Modifier.height(28.dp))
                PermissionStatusBlock(
                    permission = permission,
                    status = permissions[permission] ?: PermissionStatus.NOT_REQUESTED,
                    onRequest = { onRequestPermission(permission) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { if (index > 0) index-- else onFinish() },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (index > 0) "Back" else "Skip setup")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { if (isLast) onFinish() else index++ },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isLast) "Cheppu ra" else "Next")
            }
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { position ->
            Box(
                modifier = Modifier
                    .size(if (position == current) 9.dp else 6.dp)
                    .background(
                        color = if (position <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun WakePhraseField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Wake phrase") },
        supportingText = { Text("Rey, Ani, Hey Ani, Orey — or anything you like.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * The one-time wake-model download, offered during setup.
 *
 * Skippable. Without the model Ani still works on a tap; it just cannot hear "Rey" until
 * this is done, and the step says exactly that rather than blocking the flow.
 */
@Composable
private fun WakeModelBlock(
    state: VoskModelState,
    onDownload: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = when (state) {
                is VoskModelState.Ready -> "Installed. Ani can hear you with the screen off."
                VoskModelState.NotInstalled ->
                    "About 40 MB, once. After that the wake word works with no internet, " +
                        "and nothing you say is ever uploaded."
                is VoskModelState.Installing -> "Installing… ${state.percent}%"
                is VoskModelState.Failed -> state.reason
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state is VoskModelState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        when (state) {
            is VoskModelState.Ready -> Text(
                text = "Ready",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            is VoskModelState.Installing -> Unit
            else -> Button(onClick = onDownload) { Text("Download now") }
        }
    }
}

@Composable
private fun PermissionStatusBlock(
    permission: AniPermission,
    status: PermissionStatus,
    onRequest: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Without it: ${permission.withoutIt}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        when (status) {
            PermissionStatus.GRANTED, PermissionStatus.NOT_APPLICABLE -> Text(
                text = "Allowed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            PermissionStatus.PERMANENTLY_DENIED -> Button(onClick = onRequest) {
                Text("Open Settings")
            }

            else -> Button(onClick = onRequest) {
                Text("Allow ${permission.displayName.lowercase()}")
            }
        }
    }
}

/** The shipped flow. Order matters: the essential asks come first, the optional ones later. */
val defaultOnboardingSteps: List<OnboardingStep> = listOf(
    OnboardingStep(
        title = "Meet Ani",
        body = "Your personal voice assistant — Telugu, English, or however you actually talk."
    ),
    OnboardingStep(
        title = "Wake phrase",
        body = "Pick what wakes Ani. You can change it any time in Settings.",
        isWakePhraseStep = true
    ),
    OnboardingStep(
        title = "Microphone",
        body = AniPermission.MICROPHONE.whyNeeded,
        permission = AniPermission.MICROPHONE
    ),
    OnboardingStep(
        title = "Notifications",
        body = AniPermission.NOTIFICATIONS_POST.whyNeeded,
        permission = AniPermission.NOTIFICATIONS_POST
    ),
    OnboardingStep(
        title = "Contacts",
        body = AniPermission.CONTACTS.whyNeeded,
        permission = AniPermission.CONTACTS
    ),
    OnboardingStep(
        title = "Phone",
        body = AniPermission.PHONE.whyNeeded,
        permission = AniPermission.PHONE
    ),
    OnboardingStep(
        title = "Hearing \"Rey\"",
        body = "Ani listens for your wake phrase on the phone itself — no audio leaves the " +
            "device and nothing is recorded. That needs a small speech model downloaded once.",
        isWakeModelStep = true
    ),
    OnboardingStep(
        title = "Notification access",
        body = "This one is a Settings toggle, not a pop-up — Android does not let apps " +
            "ask for it directly. Ani will take you to the right screen.",
        permission = AniPermission.NOTIFICATION_ACCESS
    ),
    OnboardingStep(
        title = "Reminders",
        body = AniPermission.EXACT_ALARMS.whyNeeded,
        permission = AniPermission.EXACT_ALARMS
    ),
    OnboardingStep(
        title = "Music and WhatsApp",
        body = "Ani can search Spotify and compose WhatsApp messages for you. Neither app " +
            "lets an assistant press play or press send on your behalf, so Ani gets " +
            "everything ready and you tap once."
    ),
    OnboardingStep(
        title = "Ready",
        body = "Tap the circle, or say your wake phrase. Ani will answer."
    )
)

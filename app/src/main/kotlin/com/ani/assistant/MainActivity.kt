package com.ani.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.permission.GrantMechanism
import com.ani.assistant.core.permission.PermissionStatus
import com.ani.assistant.notifications.AniNotificationListenerService
import com.ani.assistant.platform.apps.InstalledApp
import com.ani.assistant.ui.AniApp
import com.ani.assistant.ui.AniViewModel
import com.ani.assistant.ui.screens.DiagnosticEntry
import com.ani.assistant.ui.screens.DiagnosticState
import com.ani.assistant.ui.theme.AniTheme
import com.ani.assistant.platform.device.RestrictionCheck
import com.ani.assistant.platform.device.RestrictionState
import com.ani.assistant.voice.AniVoiceService
import com.ani.assistant.voice.ServiceState
import com.ani.assistant.voice.assistant.AssistantRolePolicy
import com.ani.assistant.voice.assistant.AssistantRoleState
import com.ani.assistant.voice.TtsAvailability
import com.ani.nlu.text.Language
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The only activity.
 *
 * It owns the things that genuinely need an `Activity`: the permission launcher, the
 * keyguard-aware window flags, and starting or stopping the listening service in response
 * to the wake-word setting. Everything else is Compose.
 */
class MainActivity : ComponentActivity() {

    private val graph: AppGraph
        get() = (application as AniApplication).graph

    private val viewModel: AniViewModel by viewModels { AniViewModel.Factory(graph) }

    /** Set just before launching a request, so the result can be attributed. */
    private var pendingPermission: AniPermission? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val permission = pendingPermission ?: return@registerForActivityResult
        pendingPermission = null

        val manifestPermission = graph.permissionManager.manifestPermissionOf(permission)
        // If Android stopped showing the rationale after a denial, further requests are
        // no-ops and the user has to go to Settings. Record that so the UI can say so.
        val rationaleShown = manifestPermission != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, manifestPermission)

        graph.permissionManager.recordRequested(permission, granted, rationaleShown)
        viewModel.refreshPermissions()
        syncListeningService()
    }

    private var notificationApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            notificationApps = graph.appResolver.installedApps()
        }

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val serviceStatus by ServiceState.status.collectAsStateWithLifecycle()

            val wakeEngineNote = buildString {
                append(graph.wakeEngineFactory.engineFor(settings.wakeEngine).costDescription)
                serviceStatus.fellBackBecause?.let {
                    append("\n\nRunning ")
                    append(serviceStatus.activeEngine?.name?.lowercase() ?: "another engine")
                    append(" instead, because ")
                    append(it)
                    append('.')
                }
            }

            AniTheme(
                preference = settings.theme,
                useDynamicColor = settings.useDynamicColor
            ) {
                AniApp(
                    viewModel = viewModel,
                    appVersion = BuildConfig.VERSION_NAME,
                    wakeWordCostNote = graph.wakeEngineFactory
                        .engineFor(settings.wakeEngine)
                        .costDescription,
                    diagnostics = ::buildDiagnostics,
                    unhandledIntents = graph.toolRegistry.unhandledIntents().map { it.name },
                    notificationApps = notificationApps,
                    isNotificationAccessGranted = {
                        graph.permissionManager.isNotificationListenerEnabled()
                    },
                    isListenerConnected = { AniNotificationListenerService.isConnected },
                    wakeEngineOptions = graph.wakeEngineFactory.all().map { it.id to it.displayName },
                    wakeEngineNote = wakeEngineNote,
                    deviceDescription = graph.backgroundRestrictions.deviceDescription(),
                    hasAggressiveBatteryManager =
                        graph.backgroundRestrictions.hasAggressiveBatteryManager(),
                    restrictionChecks = ::buildReadinessChecks,
                    onOpenRestriction = { check ->
                        if (check.id == ASSISTANT_ROLE_CHECK_ID) {
                            graph.assistantRoleManager.openSettings()
                        } else {
                            graph.backgroundRestrictions.open(check)
                        }
                    },
                    onRequestPermission = ::requestPermission,
                    onOpenNotificationSettings = {
                        openSettingsFor(AniPermission.NOTIFICATION_ACCESS)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions and special access can change while the app is in the background,
        // and Android gives no callback for either.
        viewModel.refreshPermissions()
        syncListeningService()
    }

    /**
     * Routes a permission request to the mechanism that can actually grant it.
     *
     * A runtime permission gets the dialog, unless Android has stopped showing it — in
     * which case, and for every special access, the user goes to Settings.
     */
    private fun requestPermission(permission: AniPermission) {
        when (val mechanism = permission.mechanism) {
            is GrantMechanism.Automatic -> Unit

            is GrantMechanism.SpecialAccess -> openSettingsFor(permission)

            is GrantMechanism.Runtime -> {
                if (graph.permissionManager.status(permission) == PermissionStatus.PERMANENTLY_DENIED) {
                    startSettings(graph.permissionManager.appSettingsIntent())
                } else {
                    pendingPermission = permission
                    permissionLauncher.launch(mechanism.manifestPermission)
                }
            }
        }
    }

    private fun openSettingsFor(permission: AniPermission) {
        val intent = graph.permissionManager.settingsIntentFor(permission)
            ?: graph.permissionManager.appSettingsIntent()
        startSettings(intent)
    }

    private fun startSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: Exception) {
            AniLog.w(TAG, "could not open settings screen")
        }
    }

    /**
     * Keeps the listening service in step with the setting.
     *
     * Starting it requires both the user's choice *and* microphone permission — an
     * always-on microphone service for a user who revoked the permission would be both
     * broken and alarming.
     */
    private fun syncListeningService() {
        lifecycleScope.launch {
            val settings = graph.settingsRepository.settings.first()
            if (settings.wakeWordEnabled && graph.canListen()) {
                AniVoiceService.start(this@MainActivity)
            } else {
                AniVoiceService.stop(this@MainActivity)
            }
        }
    }

    /**
     * "Keep Ani Ready", with the assistant role at the top.
     *
     * The role belongs here rather than only in Diagnostics because it is the first thing
     * that decides whether the assist gesture and a lock-screen session reach Ani at all —
     * and because the state that looks most like success, "Ani opens on the gesture", is
     * the one that silently cannot do either.
     */
    private fun buildReadinessChecks(): List<RestrictionCheck> {
        val role = graph.assistantRoleManager.report()
        val assistant = RestrictionCheck(
            id = ASSISTANT_ROLE_CHECK_ID,
            title = "Assistant role",
            explanation = AssistantRolePolicy.explain(role.state) +
                if (!role.aniDeclaresVoiceInteractionService) {
                    " This build of Ani ships no voice interaction service yet, so the " +
                        "system-assistant route is not available to it whatever this is set to."
                } else {
                    ""
                },
            state = when (role.state) {
                AssistantRoleState.HELD -> RestrictionState.ALLOWED
                // Deliberately not RESTRICTED: nothing is blocking Ani, the capability
                // simply is not there. Reporting a block would send the user hunting for
                // a setting that would not help.
                AssistantRoleState.NOT_SUPPORTED, AssistantRoleState.UNKNOWN -> RestrictionState.UNKNOWN
                else -> RestrictionState.RESTRICTED
            },
            settingsIntent = graph.assistantRoleManager.settingsIntent(),
            // Ani's own listening service is the working wake-word route today, so not
            // holding the role is not "Ani is not ready".
            isRequired = false
        )
        return listOf(assistant) + graph.backgroundRestrictions.checks()
    }

    /**
     * The Diagnostics report.
     *
     * Every value is read at call time rather than cached, because the entire purpose of
     * the screen is to answer "what is true right now".
     */
    private fun buildDiagnostics(): List<DiagnosticEntry> {
        val permissions = graph.permissionManager
        val recogniserAvailable = graph.speechRecognizer.isAvailable()
        val teluguVoice = graph.ttsProvider.availabilityFor(Language.TELUGU)
        val notificationAccess = permissions.isNotificationListenerEnabled()
        val status = ServiceState.status.value
        val restrictions = graph.backgroundRestrictions

        val assistantRole = graph.assistantRoleManager.report()

        return buildList {
            add(
                DiagnosticEntry(
                    label = "Assistant role",
                    value = when (assistantRole.state) {
                        AssistantRoleState.HELD -> "Ani (system assistant)"
                        AssistantRoleState.LEGACY_ASSIST_ONLY -> "Ani (gesture only)"
                        AssistantRoleState.AVAILABLE_NOT_HELD -> "Another app"
                        AssistantRoleState.NOT_SUPPORTED -> "Not supported"
                        AssistantRoleState.UNKNOWN -> "Unknown"
                    },
                    // "Gesture only" is a warning, not a success. It is the state that
                    // looks like the feature working and is not.
                    state = when (assistantRole.state) {
                        AssistantRoleState.HELD -> DiagnosticState.OK
                        AssistantRoleState.LEGACY_ASSIST_ONLY -> DiagnosticState.WARNING
                        AssistantRoleState.AVAILABLE_NOT_HELD -> DiagnosticState.NEUTRAL
                        AssistantRoleState.NOT_SUPPORTED -> DiagnosticState.NEUTRAL
                        AssistantRoleState.UNKNOWN -> DiagnosticState.WARNING
                    },
                    detail = AssistantRolePolicy.explain(assistantRole.state)
                )
            )
            add(
                DiagnosticEntry(
                    label = "Voice service",
                    value = if (status.isRunning) "Running" else "Stopped",
                    state = if (status.isRunning) DiagnosticState.OK else DiagnosticState.NEUTRAL
                )
            )
            add(
                DiagnosticEntry(
                    label = "Wake engine",
                    value = when {
                        !status.isRunning -> "Stopped"
                        status.wakeEngineReady -> status.activeEngine?.name?.readableEngine() ?: "Ready"
                        else -> "Error"
                    },
                    state = when {
                        !status.isRunning -> DiagnosticState.NEUTRAL
                        status.wakeEngineReady -> DiagnosticState.OK
                        else -> DiagnosticState.PROBLEM
                    },
                    detail = status.fellBackBecause?.let { "Fell back because $it." }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Wake model",
                    value = if (graph.voskModelStore.isInstalled()) "Installed" else "Not installed",
                    state = if (graph.voskModelStore.isInstalled()) {
                        DiagnosticState.OK
                    } else {
                        DiagnosticState.WARNING
                    },
                    detail = if (graph.voskModelStore.isInstalled()) {
                        null
                    } else {
                        "The on-device wake engine needs a one-time ~40 MB download."
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Microphone",
                    value = if (permissions.isGranted(AniPermission.MICROPHONE)) "Available" else "Blocked",
                    state = if (permissions.isGranted(AniPermission.MICROPHONE)) {
                        DiagnosticState.OK
                    } else {
                        DiagnosticState.PROBLEM
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Speech recognition",
                    value = if (recogniserAvailable) "Ready" else "Error",
                    state = if (recogniserAvailable) DiagnosticState.OK else DiagnosticState.PROBLEM,
                    detail = if (recogniserAvailable) null else "No recogniser installed on this device."
                )
            )
            add(
                DiagnosticEntry(
                    label = "Text to speech",
                    value = when (teluguVoice) {
                        TtsAvailability.READY -> "Ready"
                        TtsAvailability.NEEDS_DOWNLOAD -> "Telugu voice missing"
                        TtsAvailability.UNSUPPORTED -> "No Telugu voice"
                        TtsAvailability.UNAVAILABLE -> "Error"
                    },
                    state = when (teluguVoice) {
                        TtsAvailability.READY -> DiagnosticState.OK
                        TtsAvailability.NEEDS_DOWNLOAD -> DiagnosticState.WARNING
                        else -> DiagnosticState.PROBLEM
                    },
                    detail = if (teluguVoice != TtsAvailability.READY) {
                        "Settings > System > Languages > Text-to-speech > Install voice data > Telugu."
                    } else {
                        null
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Battery restriction",
                    value = when {
                        restrictions.isIgnoringBatteryOptimisations() -> "Unrestricted"
                        else -> "Optimised"
                    },
                    state = if (restrictions.isIgnoringBatteryOptimisations()) {
                        DiagnosticState.OK
                    } else {
                        DiagnosticState.WARNING
                    },
                    detail = "OEM auto-start lists cannot be read by any app. See Keep Ani Ready."
                )
            )
            add(
                DiagnosticEntry(
                    label = "Notification access",
                    value = when {
                        notificationAccess && AniNotificationListenerService.isConnected -> "Enabled"
                        notificationAccess -> "Granted, reconnecting"
                        else -> "Disabled"
                    },
                    state = when {
                        notificationAccess && AniNotificationListenerService.isConnected -> DiagnosticState.OK
                        notificationAccess -> DiagnosticState.WARNING
                        else -> DiagnosticState.NEUTRAL
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Contacts",
                    value = if (permissions.isGranted(AniPermission.CONTACTS)) "Granted" else "Denied",
                    state = if (permissions.isGranted(AniPermission.CONTACTS)) {
                        DiagnosticState.OK
                    } else {
                        DiagnosticState.NEUTRAL
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Phone",
                    value = if (permissions.isGranted(AniPermission.PHONE)) "Granted" else "Denied",
                    state = if (permissions.isGranted(AniPermission.PHONE)) {
                        DiagnosticState.OK
                    } else {
                        DiagnosticState.WARNING
                    },
                    detail = if (permissions.isGranted(AniPermission.PHONE)) {
                        null
                    } else {
                        "Ani opens the dialler with the number filled in; you press call."
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Exact alarms",
                    value = if (permissions.canScheduleExactAlarms()) "Granted" else "Inexact",
                    state = if (permissions.canScheduleExactAlarms()) {
                        DiagnosticState.OK
                    } else {
                        DiagnosticState.WARNING
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Encrypted storage",
                    value = if (graph.secureStore.isAvailable) "Ready" else "Error",
                    state = if (graph.secureStore.isAvailable) DiagnosticState.OK else DiagnosticState.PROBLEM
                )
            )
            add(
                DiagnosticEntry(
                    label = "AI backend",
                    value = if (BuildConfig.AI_BACKEND_URL.isBlank()) "Not configured" else "Configured",
                    state = if (BuildConfig.AI_BACKEND_URL.isBlank()) {
                        DiagnosticState.NEUTRAL
                    } else {
                        DiagnosticState.OK
                    },
                    detail = if (BuildConfig.AI_BACKEND_URL.isBlank()) {
                        "Open questions are answered offline. See AI_INTEGRATION.md."
                    } else {
                        null
                    }
                )
            )
            add(
                DiagnosticEntry(
                    label = "Last wake",
                    value = status.lastWakeAtMillis.asTimestamp(),
                    state = DiagnosticState.NEUTRAL
                )
            )
            add(
                DiagnosticEntry(
                    label = "Last command",
                    value = status.lastCommandAtMillis.asTimestamp(),
                    state = DiagnosticState.NEUTRAL
                )
            )
            add(
                DiagnosticEntry(
                    label = "Last error",
                    value = if (status.lastError == null) "None" else "See detail",
                    state = if (status.lastError == null) DiagnosticState.OK else DiagnosticState.WARNING,
                    // ServiceState only ever holds safe sentences: no user content reaches it.
                    detail = status.lastError
                )
            )
            add(
                DiagnosticEntry(
                    label = "Tools registered",
                    value = "${graph.toolRegistry.registeredTools.size}",
                    state = DiagnosticState.NEUTRAL
                )
            )
        }
    }

    private fun Long?.asTimestamp(): String =
        this?.let { DIAGNOSTIC_TIME_FORMAT.format(java.util.Date(it)) } ?: "Never"

    private fun String.readableEngine(): String = when (this) {
        "VOSK" -> "Ready (on-device)"
        "PORCUPINE" -> "Ready (Porcupine)"
        "PLATFORM_RECOGNIZER" -> "Ready (phone recogniser)"
        else -> "Ready"
    }

    private companion object {
        /** Identifies the assistant-role row so Keep Ani Ready routes it to the right screen. */
        const val ASSISTANT_ROLE_CHECK_ID = "assistant_role"

        const val TAG = "AniMainActivity"

        val DIAGNOSTIC_TIME_FORMAT =
            java.text.SimpleDateFormat("d MMM, HH:mm:ss", java.util.Locale.getDefault())
    }
}

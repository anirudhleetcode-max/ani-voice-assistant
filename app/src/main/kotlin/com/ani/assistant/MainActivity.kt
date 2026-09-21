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
import com.ani.assistant.voice.AniVoiceService
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

            AniTheme(
                preference = settings.theme,
                useDynamicColor = settings.useDynamicColor
            ) {
                AniApp(
                    viewModel = viewModel,
                    appVersion = BuildConfig.VERSION_NAME,
                    wakeWordCostNote = graph.wakeWordDetector.costDescription,
                    diagnostics = ::buildDiagnostics,
                    unhandledIntents = graph.toolRegistry.unhandledIntents().map { it.name },
                    notificationApps = notificationApps,
                    isNotificationAccessGranted = {
                        graph.permissionManager.isNotificationListenerEnabled()
                    },
                    isListenerConnected = { AniNotificationListenerService.isConnected },
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

        return listOf(
            DiagnosticEntry(
                label = "Microphone",
                value = if (permissions.isGranted(AniPermission.MICROPHONE)) "Ready" else "Blocked",
                state = if (permissions.isGranted(AniPermission.MICROPHONE)) {
                    DiagnosticState.OK
                } else {
                    DiagnosticState.PROBLEM
                }
            ),
            DiagnosticEntry(
                label = "Speech recognition",
                value = if (recogniserAvailable) "Ready" else "Unavailable",
                state = if (recogniserAvailable) DiagnosticState.OK else DiagnosticState.PROBLEM,
                detail = if (recogniserAvailable) null else "No recogniser installed on this device."
            ),
            DiagnosticEntry(
                label = "Telugu voice",
                value = when (teluguVoice) {
                    TtsAvailability.READY -> "Installed"
                    TtsAvailability.NEEDS_DOWNLOAD -> "Needs download"
                    TtsAvailability.UNSUPPORTED -> "Not supported"
                    TtsAvailability.UNAVAILABLE -> "Engine error"
                },
                state = when (teluguVoice) {
                    TtsAvailability.READY -> DiagnosticState.OK
                    TtsAvailability.NEEDS_DOWNLOAD -> DiagnosticState.WARNING
                    else -> DiagnosticState.PROBLEM
                },
                detail = if (teluguVoice != TtsAvailability.READY) {
                    "Ani will speak with the default voice until a Telugu voice is installed. " +
                        "Settings > Languages > Text-to-speech."
                } else {
                    null
                }
            ),
            DiagnosticEntry(
                label = "Notification access",
                value = when {
                    notificationAccess && AniNotificationListenerService.isConnected -> "Connected"
                    notificationAccess -> "Granted, reconnecting"
                    else -> "Off"
                },
                state = when {
                    notificationAccess && AniNotificationListenerService.isConnected -> DiagnosticState.OK
                    notificationAccess -> DiagnosticState.WARNING
                    else -> DiagnosticState.NEUTRAL
                }
            ),
            DiagnosticEntry(
                label = "Contacts",
                value = if (permissions.isGranted(AniPermission.CONTACTS)) "Allowed" else "Not allowed",
                state = if (permissions.isGranted(AniPermission.CONTACTS)) {
                    DiagnosticState.OK
                } else {
                    DiagnosticState.NEUTRAL
                }
            ),
            DiagnosticEntry(
                label = "Place calls directly",
                value = if (permissions.isGranted(AniPermission.PHONE)) "Allowed" else "Dialler only",
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
            ),
            DiagnosticEntry(
                label = "Exact alarms",
                value = if (permissions.canScheduleExactAlarms()) "Allowed" else "Inexact",
                state = if (permissions.canScheduleExactAlarms()) {
                    DiagnosticState.OK
                } else {
                    DiagnosticState.WARNING
                },
                detail = if (permissions.canScheduleExactAlarms()) {
                    null
                } else {
                    "Reminders may be delivered a few minutes late."
                }
            ),
            DiagnosticEntry(
                label = "Do Not Disturb access",
                value = if (permissions.isDndAccessGranted()) "Allowed" else "Not allowed",
                state = if (permissions.isDndAccessGranted()) {
                    DiagnosticState.OK
                } else {
                    DiagnosticState.NEUTRAL
                }
            ),
            DiagnosticEntry(
                label = "Encrypted storage",
                value = if (graph.secureStore.isAvailable) "Ready" else "Unavailable",
                state = if (graph.secureStore.isAvailable) DiagnosticState.OK else DiagnosticState.PROBLEM,
                detail = if (graph.secureStore.isAvailable) {
                    null
                } else {
                    "Integration tokens will not be saved on this device."
                }
            ),
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
            ),
            DiagnosticEntry(
                label = "Wake word service",
                value = if (graph.canListen()) "Available" else "Unavailable",
                state = if (graph.canListen()) DiagnosticState.OK else DiagnosticState.NEUTRAL,
                detail = graph.wakeWordDetector.costDescription
            ),
            DiagnosticEntry(
                label = "Tools registered",
                value = "${graph.toolRegistry.registeredTools.size}",
                state = DiagnosticState.NEUTRAL
            )
        )
    }

    private companion object {
        const val TAG = "AniMainActivity"
    }
}

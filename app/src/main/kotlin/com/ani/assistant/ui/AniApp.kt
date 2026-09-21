package com.ani.assistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.platform.apps.InstalledApp
import com.ani.assistant.platform.device.RestrictionCheck
import com.ani.assistant.voice.wake.WakeWordEngineId
import com.ani.assistant.ui.screens.CommandsScreen
import com.ani.assistant.ui.screens.DiagnosticEntry
import com.ani.assistant.ui.screens.DiagnosticsScreen
import com.ani.assistant.ui.screens.HistoryScreen
import com.ani.assistant.ui.screens.HomeScreen
import com.ani.assistant.ui.screens.KeepReadyScreen
import com.ani.assistant.ui.screens.MemoryScreen
import com.ani.assistant.ui.screens.NotificationAccessScreen
import com.ani.assistant.ui.screens.OnboardingScreen
import com.ani.assistant.ui.screens.PrivacyScreen
import com.ani.assistant.ui.screens.SettingsScreen
import com.ani.assistant.ui.screens.defaultOnboardingSteps

/**
 * The whole interface.
 *
 * Onboarding is a separate graph rather than a dialog: until it is finished there is no
 * bottom bar and no way to wander off into Settings, which keeps the first run a single
 * linear thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AniApp(
    viewModel: AniViewModel,
    appVersion: String,
    wakeWordCostNote: String,
    diagnostics: () -> List<DiagnosticEntry>,
    unhandledIntents: List<String>,
    notificationApps: List<InstalledApp>,
    isNotificationAccessGranted: () -> Boolean,
    isListenerConnected: () -> Boolean,
    wakeEngineOptions: List<Pair<WakeWordEngineId, String>>,
    wakeEngineNote: String,
    deviceDescription: String,
    hasAggressiveBatteryManager: Boolean,
    restrictionChecks: () -> List<RestrictionCheck>,
    onOpenRestriction: (RestrictionCheck) -> Unit,
    onRequestPermission: (AniPermission) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val voiceError by viewModel.voiceError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(voiceError) {
        val message = voiceError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearVoiceError()
    }

    if (!settings.onboardingCompleted) {
        val permissions by viewModel.permissions.collectAsStateWithLifecycle()
        val wakeModel by viewModel.wakeModelState.collectAsStateWithLifecycle()
        OnboardingScreen(
            steps = defaultOnboardingSteps,
            permissions = permissions,
            wakePhrase = settings.wakePhrases.firstOrNull().orEmpty(),
            wakeModelState = wakeModel,
            onDownloadWakeModel = viewModel::downloadWakeModel,
            onWakePhraseChange = { phrase ->
                viewModel.updateSettings { current ->
                    current.copy(
                        wakePhrases = listOf(phrase.trim()).filter { it.isNotEmpty() }
                            .ifEmpty { current.wakePhrases }
                    )
                }
            },
            onRequestPermission = onRequestPermission,
            onFinish = viewModel::completeOnboarding
        )
        return
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentDestination = AniDestination.fromRoute(currentRoute)
    val showBottomBar = currentDestination?.inBottomBar == true

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (currentDestination != null && !currentDestination.inBottomBar) {
                TopAppBar(
                    title = { Text(currentDestination.label) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    for (destination in AniDestination.bottomBar) {
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(AniDestination.HOME.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                destination.icon?.let {
                                    Icon(it, contentDescription = destination.label)
                                }
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AniNavHost(
                navController = navController,
                viewModel = viewModel,
                appVersion = appVersion,
                wakeWordCostNote = wakeWordCostNote,
                diagnostics = diagnostics,
                unhandledIntents = unhandledIntents,
                notificationApps = notificationApps,
                isNotificationAccessGranted = isNotificationAccessGranted,
                isListenerConnected = isListenerConnected,
                wakeEngineOptions = wakeEngineOptions,
                wakeEngineNote = wakeEngineNote,
                deviceDescription = deviceDescription,
                hasAggressiveBatteryManager = hasAggressiveBatteryManager,
                restrictionChecks = restrictionChecks,
                onOpenRestriction = onOpenRestriction,
                onRequestPermission = onRequestPermission,
                onOpenNotificationSettings = onOpenNotificationSettings
            )
        }
    }
}

@Composable
private fun AniNavHost(
    navController: NavHostController,
    viewModel: AniViewModel,
    appVersion: String,
    wakeWordCostNote: String,
    diagnostics: () -> List<DiagnosticEntry>,
    unhandledIntents: List<String>,
    notificationApps: List<InstalledApp>,
    isNotificationAccessGranted: () -> Boolean,
    isListenerConnected: () -> Boolean,
    wakeEngineOptions: List<Pair<WakeWordEngineId, String>>,
    wakeEngineNote: String,
    deviceDescription: String,
    hasAggressiveBatteryManager: Boolean,
    restrictionChecks: () -> List<RestrictionCheck>,
    onOpenRestriction: (RestrictionCheck) -> Unit,
    onRequestPermission: (AniPermission) -> Unit,
    onOpenNotificationSettings: () -> Unit
) {
    NavHost(navController = navController, startDestination = AniDestination.HOME.route) {

        composable(AniDestination.HOME.route) {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
            val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
            val partial by viewModel.partialTranscript.collectAsStateWithLifecycle()
            val transcript by viewModel.transcript.collectAsStateWithLifecycle()
            val unread by viewModel.notificationCount.collectAsStateWithLifecycle()

            HomeScreen(
                assistantName = settings.assistantName,
                voiceState = voiceState,
                audioLevel = audioLevel,
                partialTranscript = partial,
                transcript = transcript,
                unreadNotifications = unread,
                wakePhrase = settings.wakePhrases.firstOrNull().orEmpty(),
                onOrbTap = viewModel::onOrbTapped,
                onQuickAction = viewModel::submitTypedCommand,
                onSubmitText = viewModel::submitTypedCommand
            )
        }

        composable(AniDestination.HISTORY.route) {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val history by viewModel.history.collectAsStateWithLifecycle()
            HistoryScreen(
                entries = history,
                historyEnabled = settings.storeConversationHistory,
                onDelete = viewModel::deleteHistoryEntry,
                onClearAll = viewModel::clearHistory
            )
        }

        composable(AniDestination.COMMANDS.route) {
            val commands by viewModel.commands.collectAsStateWithLifecycle()
            val saveResult by viewModel.saveCommandResult.collectAsStateWithLifecycle()
            CommandsScreen(
                commands = commands,
                saveResult = saveResult,
                onSave = { phrase, actions -> viewModel.saveCommand(phrase, actions) },
                onRun = viewModel::runCommand,
                onSetEnabled = viewModel::setCommandEnabled,
                onDelete = viewModel::deleteCommand,
                onConsumeSaveResult = viewModel::consumeSaveResult
            )
        }

        composable(AniDestination.MEMORY.route) {
            val memories by viewModel.memories.collectAsStateWithLifecycle()
            MemoryScreen(
                entries = memories,
                onAdd = viewModel::rememberFact,
                onDelete = viewModel::forgetMemory,
                onClearAll = viewModel::clearMemory
            )
        }

        composable(AniDestination.SETTINGS.route) {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val wakeModel by viewModel.wakeModelState.collectAsStateWithLifecycle()
            SettingsScreen(
                settings = settings,
                appVersion = appVersion,
                wakeWordCostNote = wakeWordCostNote,
                wakeEngineOptions = wakeEngineOptions,
                wakeEngineNote = wakeEngineNote,
                wakeModelState = wakeModel,
                onDownloadWakeModel = viewModel::downloadWakeModel,
                onRemoveWakeModel = viewModel::removeWakeModel,
                onUpdate = viewModel::updateSettings,
                onOpenPrivacy = { navController.navigate(AniDestination.PRIVACY.route) },
                onOpenDiagnostics = { navController.navigate(AniDestination.DIAGNOSTICS.route) },
                onOpenNotificationAccess = {
                    navController.navigate(AniDestination.NOTIFICATION_SETTINGS.route)
                },
                onOpenKeepReady = { navController.navigate(AniDestination.KEEP_READY.route) }
            )
        }

        composable(AniDestination.PRIVACY.route) {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val permissions by viewModel.permissions.collectAsStateWithLifecycle()
            val history by viewModel.history.collectAsStateWithLifecycle()
            val memories by viewModel.memories.collectAsStateWithLifecycle()
            val unread by viewModel.notificationCount.collectAsStateWithLifecycle()

            PrivacyScreen(
                settings = settings,
                permissions = permissions,
                historyCount = history.size,
                memoryCount = memories.size,
                notificationCount = unread,
                onUpdate = viewModel::updateSettings,
                onRequestPermission = onRequestPermission,
                onClearHistory = viewModel::clearHistory,
                onClearMemory = viewModel::clearMemory,
                onDeleteEverything = viewModel::deleteAllUserData
            )
        }

        composable(AniDestination.DIAGNOSTICS.route) {
            DiagnosticsScreen(
                entries = diagnostics(),
                unhandledIntents = unhandledIntents,
                onRefresh = viewModel::refreshPermissions
            )
        }

        composable(AniDestination.KEEP_READY.route) {
            KeepReadyScreen(
                deviceDescription = deviceDescription,
                checks = restrictionChecks(),
                hasAggressiveBatteryManager = hasAggressiveBatteryManager,
                onOpen = onOpenRestriction,
                onRecheck = viewModel::refreshPermissions
            )
        }

        composable(AniDestination.NOTIFICATION_SETTINGS.route) {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            NotificationAccessScreen(
                accessGranted = isNotificationAccessGranted(),
                listenerConnected = isListenerConnected(),
                candidateApps = notificationApps,
                allowedPackages = settings.allowedNotificationPackages,
                onOpenSystemSettings = onOpenNotificationSettings,
                onToggleApp = { packageName, allowed ->
                    viewModel.updateSettings { current ->
                        current.copy(
                            allowedNotificationPackages = if (allowed) {
                                current.allowedNotificationPackages + packageName
                            } else {
                                current.allowedNotificationPackages - packageName
                            }
                        )
                    }
                }
            )
        }
    }
}

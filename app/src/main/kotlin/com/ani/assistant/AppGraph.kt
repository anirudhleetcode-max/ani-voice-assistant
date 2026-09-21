package com.ani.assistant

import android.app.KeyguardManager
import android.content.Context
import com.ani.assistant.ai.AiProvider
import com.ani.assistant.ai.BackendAiProvider
import com.ani.assistant.ai.OfflineAiProvider
import com.ani.assistant.assistant.AniOrchestrator
import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolRegistry
import com.ani.assistant.assistant.tools.AlarmTool
import com.ani.assistant.assistant.tools.AppLauncherTool
import com.ani.assistant.assistant.tools.CallContactTool
import com.ani.assistant.assistant.tools.ConversationTool
import com.ani.assistant.assistant.tools.CustomCommandTool
import com.ani.assistant.assistant.tools.DeviceStatusTool
import com.ani.assistant.assistant.tools.DndTool
import com.ani.assistant.assistant.tools.FlashlightTool
import com.ani.assistant.assistant.tools.InformationTool
import com.ani.assistant.assistant.tools.MemoryTool
import com.ani.assistant.assistant.tools.MusicTool
import com.ani.assistant.assistant.tools.NavigationTool
import com.ani.assistant.assistant.tools.NotificationReaderTool
import com.ani.assistant.assistant.tools.ReminderTool
import com.ani.assistant.assistant.tools.SendMessageTool
import com.ani.assistant.assistant.tools.SystemSettingsTool
import com.ani.assistant.assistant.tools.VolumeTool
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.permission.PermissionManager
import com.ani.assistant.core.permission.PermissionRequestHistory
import com.ani.assistant.core.security.SecureStore
import com.ani.assistant.data.commands.CustomCommandRepository
import com.ani.assistant.data.conversation.ConversationRepository
import com.ani.assistant.data.memory.MemoryRepository
import com.ani.assistant.data.notifications.NotificationRepository
import com.ani.assistant.data.settings.AiProviderChoice
import com.ani.assistant.data.settings.AniSettings
import com.ani.assistant.data.settings.SettingsRepository
import com.ani.assistant.platform.alarm.AlarmLauncher
import com.ani.assistant.platform.alarm.ReminderScheduler
import com.ani.assistant.platform.apps.AppResolver
import com.ani.assistant.platform.contacts.ContactResolver
import com.ani.assistant.platform.device.BackgroundRestrictions
import com.ani.assistant.platform.device.DeviceController
import com.ani.assistant.platform.device.SystemSettingsLauncher
import com.ani.assistant.platform.launch.ActivityLauncher
import com.ani.assistant.platform.launch.ForegroundState
import com.ani.assistant.platform.music.MusicController
import com.ani.assistant.platform.share.CommunicationLauncher
import com.ani.assistant.voice.AndroidSpeechRecognizerProvider
import com.ani.assistant.voice.EndpointingConfig
import com.ani.assistant.voice.RecognizerBenchmark
import com.ani.assistant.voice.audio.WakeAudioDiagnostics
import com.ani.assistant.voice.AndroidTtsProvider
import com.ani.assistant.voice.SpeechRecognizerProvider
import com.ani.assistant.voice.TtsProvider
import com.ani.assistant.voice.VoiceSession
import com.ani.assistant.voice.mic.MicArbiter
import com.ani.assistant.voice.mic.MicTestController
import com.ani.assistant.voice.wake.PlatformRecognizerWakeEngine
import com.ani.assistant.voice.wake.PorcupineWakeWordEngine
import com.ani.assistant.voice.wake.VoskModelStore
import com.ani.assistant.voice.wake.VoskWakeWordEngine
import com.ani.assistant.voice.wake.WakeSensitivity
import com.ani.assistant.voice.wake.WakeWordEngineFactory
import com.ani.nlu.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

/**
 * The composition root: one place where every object is built and wired.
 *
 * **On not using a DI framework.** Hilt would generate most of this file, and on a larger
 * team that is worth the annotation processor. Here it is not: the graph is a few dozen
 * singletons with no scoping beyond "one per process", every dependency is visible in one
 * screen of code, and there is no code generation step to go wrong. Constructor injection
 * is still constructor injection — every class above this one takes what it needs as a
 * parameter and none of them reach for a global.
 *
 * Everything is `by lazy`, so nothing is constructed until something asks for it. That
 * matters for the TTS engine in particular, which takes hundreds of milliseconds to
 * initialise and should not be on the cold-start path.
 */
class AppGraph(private val context: Context) {

    /** Lives as long as the process. Used for fire-and-forget work with no UI owner. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- Core ------------------------------------------------------------------------

    val secureStore: SecureStore by lazy { SecureStore(context) }

    val permissionManager: PermissionManager by lazy {
        PermissionManager(context, PermissionRequestHistory(context))
    }

    // ---- Data ------------------------------------------------------------------------

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }

    /**
     * The current settings, readable without suspending.
     *
     * The wake-word loop needs the phrases on every iteration and runs on the main
     * dispatcher; blocking there to read DataStore would be an ANR. A hot [StateFlow]
     * keeps a snapshot in memory that any thread can read for free.
     */
    val settingsState: StateFlow<AniSettings> by lazy {
        settingsRepository.settings.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = AniSettings.DEFAULT
        )
    }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(context) }
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(context) }
    val commandRepository: CustomCommandRepository by lazy { CustomCommandRepository(context) }
    val notificationRepository: NotificationRepository by lazy { NotificationRepository(context) }

    // ---- Platform --------------------------------------------------------------------

    /**
     * Tracks whether an Ani activity is resumed.
     *
     * Registered by [com.ani.assistant.AniApplication]. Everything that starts an activity
     * consults it, because Android silently drops background activity starts and the only
     * way to tell is to know which side of that line you are on.
     */
    val foregroundState: ForegroundState by lazy { ForegroundState() }

    val activityLauncher: ActivityLauncher by lazy { ActivityLauncher(context, foregroundState) }

    val contactResolver: ContactResolver by lazy { ContactResolver(context) }
    val appResolver: AppResolver by lazy { AppResolver(context, activityLauncher) }
    val deviceController: DeviceController by lazy { DeviceController(context) }
    val settingsLauncher: SystemSettingsLauncher by lazy {
        SystemSettingsLauncher(context, activityLauncher)
    }
    val backgroundRestrictions: BackgroundRestrictions by lazy { BackgroundRestrictions(context) }
    val communicationLauncher: CommunicationLauncher by lazy {
        CommunicationLauncher(context, activityLauncher)
    }
    val musicController: MusicController by lazy { MusicController(context, activityLauncher) }
    val alarmLauncher: AlarmLauncher by lazy { AlarmLauncher(context, activityLauncher) }
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(context) }

    // ---- Voice -----------------------------------------------------------------------

    /**
     * The concrete recogniser.
     *
     * Held as the implementation type as well as the interface, because the benchmark and
     * the Mic Test need the device-specific questions — is on-device recognition
     * available, which languages does it actually have — that the interface has no
     * business carrying.
     */
    val androidSpeechRecognizer: AndroidSpeechRecognizerProvider by lazy {
        AndroidSpeechRecognizerProvider(
            context = context,
            preferOnDevice = { settingsState.value.preferOnDeviceRecognition },
            endpointingProvider = {
                EndpointingConfig(
                    completeSilenceMillis = settingsState.value.completeSilenceMillis,
                    possiblyCompleteSilenceMillis = settingsState.value.possiblyCompleteSilenceMillis,
                    minimumSpeechMillis = settingsState.value.minimumSpeechMillis
                )
            }
        )
    }

    val speechRecognizer: SpeechRecognizerProvider by lazy { androidSpeechRecognizer }

    /** The A/B recogniser comparison behind Diagnostics. */
    val recognizerBenchmark: RecognizerBenchmark by lazy {
        RecognizerBenchmark(
            provider = androidSpeechRecognizer,
            arbiter = micArbiter,
            scope = applicationScope,
            retainTranscripts = BuildConfig.DEBUG
        )
    }

    val ttsProvider: TtsProvider by lazy { AndroidTtsProvider(context) }

    val voskModelStore: VoskModelStore by lazy { VoskModelStore(context) }

    /**
     * Live wake-audio statistics.
     *
     * Transcripts are retained only in debug builds; see [WakeAudioDiagnostics].
     */
    val wakeAudioDiagnostics: WakeAudioDiagnostics by lazy {
        WakeAudioDiagnostics(retainTranscripts = BuildConfig.DEBUG)
    }

    private val wakePhrases: () -> List<String> = { settingsState.value.effectiveWakePhrases() }
    private val wakeSensitivity: () -> WakeSensitivity = { settingsState.value.wakeSensitivity }
    private val micGranted: () -> Boolean = { permissionManager.isGranted(AniPermission.MICROPHONE) }

    val voskWakeEngine: VoskWakeWordEngine by lazy {
        VoskWakeWordEngine(
            modelStore = voskModelStore,
            phrasesProvider = wakePhrases,
            sensitivityProvider = wakeSensitivity,
            hasMicrophonePermission = micGranted,
            diagnostics = wakeAudioDiagnostics
        )
    }

    val porcupineWakeEngine: PorcupineWakeWordEngine by lazy {
        PorcupineWakeWordEngine(
            context = context,
            // Read from encrypted storage on every start, so revoking the key in Settings
            // takes effect without a restart. It is never logged and never built in.
            accessKeyProvider = { secureStore.get(SecureStore.KEY_PICOVOICE_ACCESS_KEY) },
            sensitivityProvider = wakeSensitivity,
            hasMicrophonePermission = micGranted
        )
    }

    val platformWakeEngine: PlatformRecognizerWakeEngine by lazy {
        PlatformRecognizerWakeEngine(
            recognizer = speechRecognizer,
            phrasesProvider = wakePhrases,
            sensitivityProvider = wakeSensitivity,
            languageProvider = { settingsState.value.language ?: Language.MIXED },
            hasMicrophonePermission = micGranted
        )
    }

    val wakeEngineFactory: WakeWordEngineFactory by lazy {
        WakeWordEngineFactory(voskWakeEngine, porcupineWakeEngine, platformWakeEngine)
    }

    /**
     * The engine that will actually run, given what is installed and licensed.
     *
     * Recomputed rather than cached: the user can download the Vosk model or paste a
     * Picovoice key at any moment, and the service should pick that up on its next start.
     */
    suspend fun selectWakeEngine(): WakeWordEngineFactory.Selection =
        wakeEngineFactory.select(settingsState.value.wakeEngine)

    /**
     * The one gate to the microphone, shared by the wake loop and every command session.
     *
     * A single instance for the whole process is the entire point: two arbiters would
     * arbitrate nothing.
     */
    val micArbiter: MicArbiter by lazy { MicArbiter() }

    /**
     * The developer microphone test.
     *
     * Goes through [micArbiter] like everything else — a diagnostic that grabbed the
     * microphone out from under the wake engine would be creating the fault it exists to
     * find.
     */
    val micTestController: MicTestController by lazy {
        MicTestController(
            arbiter = micArbiter,
            recognizer = speechRecognizer,
            hasMicrophonePermission = micGranted,
            scope = applicationScope,
            retainTranscripts = BuildConfig.DEBUG
        )
    }

    val voiceSession: VoiceSession by lazy {
        VoiceSession(
            recognizer = speechRecognizer,
            tts = ttsProvider,
            orchestrator = orchestrator,
            settingsRepository = settingsRepository,
            scope = applicationScope,
            micArbiter = micArbiter
        )
    }

    // ---- AI --------------------------------------------------------------------------

    /** Opaque, random, regenerable. Used by the backend for rate limiting only. */
    val installId: String by lazy {
        secureStore.get(SecureStore.KEY_INSTALL_ID) ?: UUID.randomUUID().toString().also {
            secureStore.put(SecureStore.KEY_INSTALL_ID, it)
        }
    }

    private val offlineAiProvider: AiProvider by lazy {
        OfflineAiProvider { settingsState.value.responseStyle(Language.MIXED) }
    }

    /** Picks the provider the user configured, falling back to offline when unusable. */
    suspend fun aiProvider(): AiProvider {
        val settings = settingsState.value
        if (settings.aiProvider == AiProviderChoice.OFFLINE_ONLY) return offlineAiProvider

        val url = settings.backendUrlOverride?.takeIf { it.isNotBlank() }
            ?: BuildConfig.AI_BACKEND_URL
        if (url.isBlank()) return offlineAiProvider

        return backendProviderFor(url)
    }

    private var cachedBackendUrl: String? = null
    private var cachedBackendProvider: AiProvider? = null

    private fun backendProviderFor(url: String): AiProvider {
        cachedBackendProvider?.let { if (cachedBackendUrl == url) return it }
        val provider = BackendAiProvider(baseUrl = url, installId = installId)
        cachedBackendUrl = url
        cachedBackendProvider = provider
        return provider
    }

    // ---- Assistant -------------------------------------------------------------------

    val tools: List<AniTool> by lazy {
        listOf(
            CallContactTool(contactResolver, memoryRepository, communicationLauncher, permissionManager),
            SendMessageTool(contactResolver, memoryRepository, communicationLauncher, permissionManager),
            NotificationReaderTool(notificationRepository, deviceController),
            MusicTool(musicController, appResolver),
            AppLauncherTool(appResolver, memoryRepository),
            SystemSettingsTool(settingsLauncher),
            AlarmTool(alarmLauncher),
            ReminderTool(reminderScheduler),
            DeviceStatusTool(deviceController),
            FlashlightTool(deviceController),
            VolumeTool(deviceController),
            DndTool(deviceController),
            InformationTool(),
            NavigationTool(context, communicationLauncher),
            MemoryTool(memoryRepository),
            // Steps run back through the registry, so a custom command behaves exactly
            // as if the user had asked for each step out loud.
            CustomCommandTool(commandRepository) { command, toolContext ->
                toolRegistry.execute(command, toolContext)
            },
            ConversationTool({ aiProvider() }, conversationRepository)
        )
    }

    val toolRegistry: ToolRegistry by lazy { ToolRegistry(tools, permissionManager) }

    val orchestrator: AniOrchestrator by lazy {
        AniOrchestrator(
            settingsRepository = settingsRepository,
            commandRepository = commandRepository,
            conversationRepository = conversationRepository,
            toolRegistry = toolRegistry,
            isDeviceLocked = { isDeviceLocked() }
        )
    }

    // ---- Helpers ---------------------------------------------------------------------

    /** True when Ani has everything it needs to open the microphone. */
    fun canListen(): Boolean =
        permissionManager.isGranted(AniPermission.MICROPHONE) && speechRecognizer.isAvailable()

    fun isDeviceLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Called from the Privacy Center's "delete everything". */
    suspend fun wipeAllUserData() {
        conversationRepository.clear()
        memoryRepository.clear()
        notificationRepository.clear()
        commandRepository.clear()
        secureStore.clear()
        orchestrator.reset()
    }
}

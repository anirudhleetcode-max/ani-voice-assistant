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
import com.ani.assistant.data.settings.SettingsRepository
import com.ani.assistant.platform.alarm.AlarmLauncher
import com.ani.assistant.platform.alarm.ReminderScheduler
import com.ani.assistant.platform.apps.AppResolver
import com.ani.assistant.platform.contacts.ContactResolver
import com.ani.assistant.platform.device.DeviceController
import com.ani.assistant.platform.device.SystemSettingsLauncher
import com.ani.assistant.platform.music.MusicController
import com.ani.assistant.platform.share.CommunicationLauncher
import com.ani.assistant.voice.AndroidSpeechRecognizerProvider
import com.ani.assistant.voice.AndroidTtsProvider
import com.ani.assistant.voice.SpeechRecognizerProvider
import com.ani.assistant.voice.TtsProvider
import com.ani.assistant.voice.VoiceSession
import com.ani.assistant.voice.wake.SpeechWakeWordDetector
import com.ani.assistant.voice.wake.WakeWordDetector
import com.ani.nlu.dialog.WakeWordMatcher
import com.ani.nlu.text.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(context) }
    val memoryRepository: MemoryRepository by lazy { MemoryRepository(context) }
    val commandRepository: CustomCommandRepository by lazy { CustomCommandRepository(context) }
    val notificationRepository: NotificationRepository by lazy { NotificationRepository(context) }

    // ---- Platform --------------------------------------------------------------------

    val contactResolver: ContactResolver by lazy { ContactResolver(context) }
    val appResolver: AppResolver by lazy { AppResolver(context) }
    val deviceController: DeviceController by lazy { DeviceController(context) }
    val settingsLauncher: SystemSettingsLauncher by lazy { SystemSettingsLauncher(context) }
    val communicationLauncher: CommunicationLauncher by lazy { CommunicationLauncher(context) }
    val musicController: MusicController by lazy { MusicController(context) }
    val alarmLauncher: AlarmLauncher by lazy { AlarmLauncher(context) }
    val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(context) }

    // ---- Voice -----------------------------------------------------------------------

    val speechRecognizer: SpeechRecognizerProvider by lazy {
        AndroidSpeechRecognizerProvider(context)
    }

    val ttsProvider: TtsProvider by lazy { AndroidTtsProvider(context) }

    val wakeWordDetector: WakeWordDetector by lazy {
        SpeechWakeWordDetector(
            recognizer = speechRecognizer,
            matcherProvider = {
                // Read synchronously: the wake loop needs the current phrases on every
                // iteration, and DataStore caches the value in memory after first read.
                val settings = runBlocking { settingsRepository.settings.first() }
                WakeWordMatcher(
                    phrases = settings.effectiveWakePhrases(),
                    sensitivity = settings.wakeSensitivity.toDouble()
                )
            },
            languageProvider = {
                runBlocking { settingsRepository.settings.first() }.language ?: Language.MIXED
            }
        )
    }

    val voiceSession: VoiceSession by lazy {
        VoiceSession(
            recognizer = speechRecognizer,
            tts = ttsProvider,
            orchestrator = orchestrator,
            settingsRepository = settingsRepository,
            scope = applicationScope
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
        OfflineAiProvider {
            runBlocking { settingsRepository.settings.first() }.responseStyle(Language.MIXED)
        }
    }

    /** Picks the provider the user configured, falling back to offline when unusable. */
    suspend fun aiProvider(): AiProvider {
        val settings = settingsRepository.settings.first()
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

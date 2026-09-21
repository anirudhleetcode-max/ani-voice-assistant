package com.ani.assistant.data.settings

import com.ani.nlu.dialog.ConfirmationLevel
import com.ani.nlu.dialog.WakeWordMatcher
import com.ani.nlu.response.NotificationPrivacy
import com.ani.nlu.response.Persona
import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.response.Verbosity
import com.ani.assistant.voice.wake.WakeSensitivity
import com.ani.assistant.voice.wake.WakeWordEngineId
import com.ani.nlu.text.Language

/** Which colour scheme the user picked. */
enum class ThemePreference { SYSTEM, LIGHT, DARK }

/** Where natural-language replies come from when Ani needs to actually think. */
enum class AiProviderChoice {
    /** Ani's own backend, which holds the API key. The only cloud option. */
    BACKEND,

    /** No cloud at all. Device actions still work; open questions get a polite refusal. */
    OFFLINE_ONLY
}

/**
 * Everything the user can change, in one immutable snapshot.
 *
 * Kept as a single value rather than a bag of individual flows so that any code reading
 * settings sees a consistent picture — it is genuinely confusing to read a wake phrase
 * from one emission and its sensitivity from the next.
 */
data class AniSettings(
    // ---- Assistant ---------------------------------------------------------------
    val assistantName: String = "Ani",
    val wakePhrases: List<String> = WakeWordMatcher.DEFAULT_PHRASES,
    val wakeWordEnabled: Boolean = true,
    /** Which detector runs. Vosk is the default: free, on-device, any phrase. */
    val wakeEngine: WakeWordEngineId = WakeWordEngineId.VOSK,
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.MEDIUM,
    val allowLockScreenActivation: Boolean = false,
    val playActivationSound: Boolean = true,

    // ---- Voice -------------------------------------------------------------------
    val language: Language? = null, // null = follow whatever the user just spoke
    val persona: Persona = Persona.FRIENDLY,
    val verbosity: Verbosity = Verbosity.SHORT,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    /** Locale tag of the chosen TTS voice, or null for the engine default. */
    val ttsVoiceName: String? = null,

    // ---- Conversation ------------------------------------------------------------
    /** Keep listening for follow-ups after answering. */
    val autoFollowUp: Boolean = true,
    val followUpWindowSeconds: Int = 8,
    val confirmationLevel: ConfirmationLevel = ConfirmationLevel.BALANCED,

    // ---- Notifications -----------------------------------------------------------
    val notificationPrivacy: NotificationPrivacy = NotificationPrivacy.SENDER_ONLY,
    /** Package names the user allowed Ani to read. Empty means none. */
    val allowedNotificationPackages: Set<String> = emptySet(),
    /** Don't speak notification content out loud unless headphones are connected. */
    val notificationsRequireHeadphones: Boolean = false,
    /** Don't speak notification content while the screen is locked. */
    val hideNotificationsOnLockScreen: Boolean = true,

    // ---- Privacy -----------------------------------------------------------------
    val storeConversationHistory: Boolean = true,
    val historyRetentionDays: Int = 30,
    val storeNotificationHistory: Boolean = true,
    val notificationRetentionHours: Int = 24,

    // ---- Integrations ------------------------------------------------------------
    val aiProvider: AiProviderChoice = AiProviderChoice.BACKEND,
    /** Overrides the compiled-in backend URL. Never a secret; the key lives server side. */
    val backendUrlOverride: String? = null,
    /** Canonical name of the music app to prefer, e.g. "spotify". */
    val preferredMusicApp: String = "spotify",

    // ---- Appearance --------------------------------------------------------------
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColor: Boolean = true,

    // ---- Lifecycle ---------------------------------------------------------------
    val onboardingCompleted: Boolean = false
) {

    /** The style object the response library needs. */
    fun responseStyle(detectedLanguage: Language): ResponseStyle = ResponseStyle(
        language = language ?: detectedLanguage.takeIf { it != Language.UNKNOWN } ?: Language.MIXED,
        persona = persona,
        verbosity = verbosity,
        assistantName = assistantName
    )

    /** Wake phrases, falling back to the defaults if the user cleared the list. */
    fun effectiveWakePhrases(): List<String> =
        wakePhrases.filter { it.isNotBlank() }.ifEmpty { WakeWordMatcher.DEFAULT_PHRASES }

    companion object {
        val DEFAULT = AniSettings()

        /** Apps Ani offers to read notifications from during onboarding. */
        val SUGGESTED_NOTIFICATION_PACKAGES = listOf(
            "com.whatsapp",
            "com.google.android.apps.messaging",
            "com.instagram.android",
            "com.google.android.gm",
            "org.telegram.messenger"
        )
    }
}

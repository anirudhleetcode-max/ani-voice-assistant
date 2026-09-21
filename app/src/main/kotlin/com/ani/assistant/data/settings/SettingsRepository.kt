package com.ani.assistant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.voice.wake.WakeSensitivity
import com.ani.assistant.voice.wake.WakeWordEngineId
import com.ani.nlu.dialog.ConfirmationLevel
import com.ani.nlu.response.NotificationPrivacy
import com.ani.nlu.response.Persona
import com.ani.nlu.response.Verbosity
import com.ani.nlu.text.Language
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ani_settings")

/**
 * Reads and writes [AniSettings].
 *
 * Stored as individual preference keys rather than one serialised blob so that adding a
 * setting never invalidates the others, and so a corrupted value costs one default rather
 * than the user's entire configuration.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AniSettings> = context.settingsDataStore.data
        .catch { error ->
            // A corrupt or unreadable store must not take the assistant down with it.
            if (error is IOException) {
                AniLog.w(TAG, "settings unreadable, falling back to defaults")
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { it.toSettings() }

    suspend fun update(transform: (AniSettings) -> AniSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences.writeSettings(transform(preferences.toSettings()))
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) =
        update { it.copy(onboardingCompleted = completed) }

    /** Used by the Privacy Center's "reset everything" action. */
    suspend fun resetToDefaults() {
        context.settingsDataStore.edit { it.clear() }
    }

    // ---------------------------------------------------------------------------------

    private fun Preferences.toSettings(): AniSettings {
        val defaults = AniSettings.DEFAULT
        return AniSettings(
            assistantName = this[Keys.assistantName] ?: defaults.assistantName,
            // Stored as one newline-joined string so the user's ordering survives; a
            // Set would silently reshuffle the list they curated.
            wakePhrases = this[Keys.wakePhrases]
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: defaults.wakePhrases,
            wakeWordEnabled = this[Keys.wakeWordEnabled] ?: defaults.wakeWordEnabled,
            wakeEngine = this[Keys.wakeEngine]?.let { enumOrNull<WakeWordEngineId>(it) }
                ?: defaults.wakeEngine,
            wakeSensitivity = this[Keys.wakeSensitivity]?.let { enumOrNull<WakeSensitivity>(it) }
                ?: defaults.wakeSensitivity,
            allowLockScreenActivation = this[Keys.lockScreenActivation]
                ?: defaults.allowLockScreenActivation,
            playActivationSound = this[Keys.activationSound] ?: defaults.playActivationSound,

            language = this[Keys.language]?.let { enumOrNull<Language>(it) },
            persona = this[Keys.persona]?.let { enumOrNull<Persona>(it) } ?: defaults.persona,
            verbosity = this[Keys.verbosity]?.let { enumOrNull<Verbosity>(it) } ?: defaults.verbosity,
            speechRate = this[Keys.speechRate] ?: defaults.speechRate,
            speechPitch = this[Keys.speechPitch] ?: defaults.speechPitch,
            ttsVoiceName = this[Keys.ttsVoice],

            autoFollowUp = this[Keys.autoFollowUp] ?: defaults.autoFollowUp,
            followUpWindowSeconds = this[Keys.followUpWindow] ?: defaults.followUpWindowSeconds,
            confirmationLevel = this[Keys.confirmationLevel]?.let { enumOrNull<ConfirmationLevel>(it) }
                ?: defaults.confirmationLevel,

            notificationPrivacy = this[Keys.notificationPrivacy]
                ?.let { enumOrNull<NotificationPrivacy>(it) } ?: defaults.notificationPrivacy,
            allowedNotificationPackages = this[Keys.allowedNotificationPackages]
                ?: defaults.allowedNotificationPackages,
            notificationsRequireHeadphones = this[Keys.requireHeadphones]
                ?: defaults.notificationsRequireHeadphones,
            hideNotificationsOnLockScreen = this[Keys.hideOnLockScreen]
                ?: defaults.hideNotificationsOnLockScreen,

            storeConversationHistory = this[Keys.storeHistory] ?: defaults.storeConversationHistory,
            historyRetentionDays = this[Keys.historyRetentionDays] ?: defaults.historyRetentionDays,
            storeNotificationHistory = this[Keys.storeNotificationHistory]
                ?: defaults.storeNotificationHistory,
            notificationRetentionHours = this[Keys.notificationRetentionHours]
                ?: defaults.notificationRetentionHours,

            aiProvider = this[Keys.aiProvider]?.let { enumOrNull<AiProviderChoice>(it) }
                ?: defaults.aiProvider,
            backendUrlOverride = this[Keys.backendUrl],
            preferredMusicApp = this[Keys.preferredMusicApp] ?: defaults.preferredMusicApp,

            theme = this[Keys.theme]?.let { enumOrNull<ThemePreference>(it) } ?: defaults.theme,
            useDynamicColor = this[Keys.dynamicColor] ?: defaults.useDynamicColor,

            onboardingCompleted = this[Keys.onboardingCompleted] ?: defaults.onboardingCompleted
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSettings(value: AniSettings) {
        this[Keys.assistantName] = value.assistantName
        this[Keys.wakePhrases] = value.wakePhrases.joinToString("\n")
        this[Keys.wakeWordEnabled] = value.wakeWordEnabled
        this[Keys.wakeEngine] = value.wakeEngine.name
        this[Keys.wakeSensitivity] = value.wakeSensitivity.name
        this[Keys.lockScreenActivation] = value.allowLockScreenActivation
        this[Keys.activationSound] = value.playActivationSound

        value.language?.let { this[Keys.language] = it.name } ?: remove(Keys.language)
        this[Keys.persona] = value.persona.name
        this[Keys.verbosity] = value.verbosity.name
        this[Keys.speechRate] = value.speechRate
        this[Keys.speechPitch] = value.speechPitch
        value.ttsVoiceName?.let { this[Keys.ttsVoice] = it } ?: remove(Keys.ttsVoice)

        this[Keys.autoFollowUp] = value.autoFollowUp
        this[Keys.followUpWindow] = value.followUpWindowSeconds
        this[Keys.confirmationLevel] = value.confirmationLevel.name

        this[Keys.notificationPrivacy] = value.notificationPrivacy.name
        this[Keys.allowedNotificationPackages] = value.allowedNotificationPackages
        this[Keys.requireHeadphones] = value.notificationsRequireHeadphones
        this[Keys.hideOnLockScreen] = value.hideNotificationsOnLockScreen

        this[Keys.storeHistory] = value.storeConversationHistory
        this[Keys.historyRetentionDays] = value.historyRetentionDays
        this[Keys.storeNotificationHistory] = value.storeNotificationHistory
        this[Keys.notificationRetentionHours] = value.notificationRetentionHours

        this[Keys.aiProvider] = value.aiProvider.name
        value.backendUrlOverride?.let { this[Keys.backendUrl] = it } ?: remove(Keys.backendUrl)
        this[Keys.preferredMusicApp] = value.preferredMusicApp

        this[Keys.theme] = value.theme.name
        this[Keys.dynamicColor] = value.useDynamicColor

        this[Keys.onboardingCompleted] = value.onboardingCompleted
    }

    /** A stored enum name that no longer exists falls back to the default rather than crashing. */
    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()

    private object Keys {
        val assistantName = stringPreferencesKey("assistant_name")
        val wakePhrases = stringPreferencesKey("wake_phrases")
        val wakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val wakeEngine = stringPreferencesKey("wake_engine")
        // Stored as a name, not the old float: the three levels mean different things to
        // different engines, and a raw number could not carry that.
        val wakeSensitivity = stringPreferencesKey("wake_sensitivity_level")
        val lockScreenActivation = booleanPreferencesKey("lock_screen_activation")
        val activationSound = booleanPreferencesKey("activation_sound")

        val language = stringPreferencesKey("language")
        val persona = stringPreferencesKey("persona")
        val verbosity = stringPreferencesKey("verbosity")
        val speechRate = floatPreferencesKey("speech_rate")
        val speechPitch = floatPreferencesKey("speech_pitch")
        val ttsVoice = stringPreferencesKey("tts_voice")

        val autoFollowUp = booleanPreferencesKey("auto_follow_up")
        val followUpWindow = intPreferencesKey("follow_up_window_seconds")
        val confirmationLevel = stringPreferencesKey("confirmation_level")

        val notificationPrivacy = stringPreferencesKey("notification_privacy")
        val allowedNotificationPackages = stringSetPreferencesKey("allowed_notification_packages")
        val requireHeadphones = booleanPreferencesKey("notifications_require_headphones")
        val hideOnLockScreen = booleanPreferencesKey("hide_notifications_lock_screen")

        val storeHistory = booleanPreferencesKey("store_conversation_history")
        val historyRetentionDays = intPreferencesKey("history_retention_days")
        val storeNotificationHistory = booleanPreferencesKey("store_notification_history")
        val notificationRetentionHours = intPreferencesKey("notification_retention_hours")

        val aiProvider = stringPreferencesKey("ai_provider")
        val backendUrl = stringPreferencesKey("backend_url")
        val preferredMusicApp = stringPreferencesKey("preferred_music_app")

        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")

        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
    }

    private companion object {
        const val TAG = "AniSettings"
    }
}

package com.ani.assistant.voice.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.ani.assistant.core.log.AniLog

/**
 * Reads — never assumes — whether Ani is the device's assistant.
 *
 * Three independent sources have to agree before anything can be claimed, and they
 * routinely disagree:
 *
 *  - `RoleManager.isRoleHeld(ROLE_ASSISTANT)` exists from API 29 and is the documented
 *    question, but on several OEM builds it answers for the legacy assist app rather than
 *    for a voice interaction service.
 *  - `Settings.Secure` "voice_interaction_service" names the component that actually gets
 *    a session, over the keyguard, with hotword access. This is the one that decides what
 *    the platform will permit.
 *  - `Settings.Secure` "assistant" names the assist app, which may be an ordinary activity.
 *
 * Reading all three and reporting the difference is the point. An app that asks only the
 * first will tell the user it is the assistant and then fail to appear on the lock
 * screen, with nothing in the UI to explain why.
 */
class AssistantRoleManager(private val context: Context) {

    /**
     * Whether this build ships a `VoiceInteractionService` at all.
     *
     * Read from the package manager rather than hard-coded, so the answer stays correct
     * the moment one is added and cannot become a stale `true` in the meantime.
     */
    fun declaresVoiceInteractionService(): Boolean = runCatching {
        val intent = Intent(VOICE_INTERACTION_SERVICE_ACTION).setPackage(context.packageName)
        context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    /**
     * What `RoleManager` says, or nulls before API 29.
     *
     * Split out and annotated rather than guarded inline with a constant of our own,
     * because lint can only verify a version check written against
     * [Build.VERSION_CODES]. A guard it cannot read is a guard it will flag, and
     * suppressing that would have thrown away a genuine check for the sake of quiet.
     */
    private data class RoleReading(val available: Boolean, val held: Boolean)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun readRole(): RoleReading {
        val roleManager = runCatching { context.getSystemService(RoleManager::class.java) }
            .getOrNull() ?: return RoleReading(available = false, held = false)
        return RoleReading(
            available = runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }
                .getOrDefault(false),
            held = runCatching { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) }
                .getOrDefault(false)
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestRoleIntent(): Intent? {
        val roleManager = runCatching { context.getSystemService(RoleManager::class.java) }
            .getOrNull() ?: return null
        val available = runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }
            .getOrDefault(false)
        if (!available) return null
        return runCatching { roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }
            .getOrNull()
    }

    /** Everything the platform will tell us, in one read. */
    fun report(): AssistantRoleReport {
        // Compared against Build.VERSION.SDK_INT directly rather than through a local.
        // Lint's version-check analysis only follows the former, and a check it cannot
        // follow is a check it reports as missing.
        val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            readRole()
        } else {
            RoleReading(available = false, held = false)
        }
        val sdkInt = Build.VERSION.SDK_INT

        val roleApiAvailable = role.available
        val roleHeld = role.held

        val voiceInteraction = secureSetting(SETTING_VOICE_INTERACTION_SERVICE)
        val assist = secureSetting(SETTING_ASSISTANT)
        val hasVis = declaresVoiceInteractionService()

        val state = AssistantRolePolicy.evaluate(
            sdkInt = sdkInt,
            roleApiAvailable = roleApiAvailable,
            roleHeld = roleHeld,
            voiceInteractionComponent = voiceInteraction,
            assistComponent = assist,
            aniPackage = context.packageName,
            aniDeclaresVoiceInteractionService = hasVis
        )

        val report = AssistantRoleReport(
            state = state,
            sdkInt = sdkInt,
            roleApiAvailable = roleApiAvailable,
            roleHeld = roleHeld,
            voiceInteractionComponent = voiceInteraction,
            assistComponent = assist,
            aniDeclaresVoiceInteractionService = hasVis
        )
        AniLog.i(TAG, "[ASSISTANT] " + report.describe())
        return report
    }

    /**
     * Where to send the user to change the assistant.
     *
     * `createRequestRoleIntent(ROLE_ASSISTANT)` is the documented route and is tried
     * first, but the platform is entitled to refuse it for this role and several OEM
     * builds do — so every candidate is probed with `resolveActivity` before being
     * offered, and null means "there is no screen for this on this device", which the UI
     * says rather than opening something that will not help.
     */
    fun settingsIntent(): Intent? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestRoleIntent()?.let { add(it) }
            }
            add(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            // Where voice input settings are missing, the assist picker usually lives
            // under the app's own details page on OEM skins.
            add(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
            )
        }

        return candidates.firstOrNull { candidate ->
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { candidate.resolveActivity(context.packageManager) != null }
                .getOrDefault(false)
        }
    }

    /** @return true when a screen was opened. Never claims success it did not observe. */
    fun openSettings(): Boolean {
        val intent = settingsIntent() ?: return false
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (error: Exception) {
            AniLog.w(TAG, "[ASSISTANT] settings screen refused", "type" to error.javaClass.simpleName)
            false
        }
    }

    private fun secureSetting(key: String): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, key)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val TAG = "AniAssistantRole"

        /**
         * Hidden `Settings.Secure` constants, referenced by their stable string keys.
         *
         * `Settings.Secure.VOICE_INTERACTION_SERVICE` and `ASSISTANT` are `@hide`, so the
         * literals are used instead of reflection. They are read-only reads of a public
         * content provider — no restricted API, nothing the platform forbids — and a
         * rename would surface as a null, which reports UNKNOWN rather than a wrong answer.
         */
        const val SETTING_VOICE_INTERACTION_SERVICE = "voice_interaction_service"
        const val SETTING_ASSISTANT = "assistant"

        const val VOICE_INTERACTION_SERVICE_ACTION = "android.service.voice.VoiceInteractionService"
    }
}

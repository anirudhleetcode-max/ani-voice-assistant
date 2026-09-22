package com.ani.assistant.voice.assistant

/**
 * How far Ani has got towards being the device's actual assistant.
 *
 * The distinction between [LEGACY_ASSIST_ONLY] and [HELD] is the one that matters and the
 * one that is easiest to get wrong. Android has two unrelated mechanisms that both look
 * like "being the assistant":
 *
 *  - An activity with an `android.intent.action.ASSIST` intent filter. This lets the user
 *    pick the app in the assist picker, and the assist gesture launches it. That is all
 *    it does. It is an ordinary activity launch.
 *  - A [android.service.voice.VoiceInteractionService] selected as the device's voice
 *    interaction service. *This* is what grants a session that can be shown over the
 *    keyguard, and what makes `AlwaysOnHotwordDetector` reachable at all.
 *
 * Ani has the first and not the second. Reporting that as "Ani is the assistant" would be
 * the exact kind of claim this project refuses to make — the gesture would work, and the
 * lock-screen behaviour the user actually asked for would not, with nothing to explain
 * the difference.
 */
enum class AssistantRoleState {

    /**
     * Ani is the selected voice interaction service.
     *
     * This is the only state in which the system-level assistant behaviours are on the
     * table. It still does not *guarantee* them — hotword needs hardware support, and OEM
     * builds vary — but nothing else can be attempted without it.
     */
    HELD,

    /**
     * Ani is the chosen assist app, but only through the legacy activity route.
     *
     * The assist gesture reaches Ani. A lock-screen voice session does not exist, and
     * `AlwaysOnHotwordDetector` is unavailable, because there is no voice interaction
     * service selected for this package.
     */
    LEGACY_ASSIST_ONLY,

    /** The role exists on this device and something else holds it. */
    AVAILABLE_NOT_HELD,

    /**
     * No assistant role on this build.
     *
     * Some Android-derived and stripped builds genuinely have no assist framework. Saying
     * so is better than offering a settings screen that does not exist.
     */
    NOT_SUPPORTED,

    /** The state could not be read. Never reported as either held or not held. */
    UNKNOWN;

    /** True only when the system-level assistant surface is actually available to Ani. */
    val grantsSystemAssistant: Boolean get() = this == HELD
}

/**
 * Everything read from the platform about the assistant role, and nothing inferred.
 *
 * Every field is a fact that was read. [AssistantRolePolicy] turns them into a state;
 * keeping the two apart is what makes the decision testable without a device.
 */
data class AssistantRoleReport(
    val state: AssistantRoleState,
    val sdkInt: Int,
    /** Whether `RoleManager` exists and reports ROLE_ASSISTANT as available (API 29+). */
    val roleApiAvailable: Boolean,
    /** Whether `RoleManager.isRoleHeld(ROLE_ASSISTANT)` returned true. */
    val roleHeld: Boolean,
    /** `Settings.Secure` "voice_interaction_service", flattened component or null. */
    val voiceInteractionComponent: String?,
    /** `Settings.Secure` "assistant", flattened component or null. */
    val assistComponent: String?,
    /** Whether this build of Ani actually ships a VoiceInteractionService. */
    val aniDeclaresVoiceInteractionService: Boolean
) {
    /** One line for the log. Component names only — no user content. */
    fun describe(): String =
        "state=${state.name} sdk=$sdkInt roleApi=$roleApiAvailable roleHeld=$roleHeld " +
            "vis=${voiceInteractionComponent ?: "none"} assist=${assistComponent ?: "none"} " +
            "aniHasVis=$aniDeclaresVoiceInteractionService"

    companion object {
        fun unknown(sdkInt: Int) = AssistantRoleReport(
            state = AssistantRoleState.UNKNOWN,
            sdkInt = sdkInt,
            roleApiAvailable = false,
            roleHeld = false,
            voiceInteractionComponent = null,
            assistComponent = null,
            aniDeclaresVoiceInteractionService = false
        )
    }
}

/**
 * Turns platform readings into a state, with no Android types involved.
 *
 * Pure because the interesting cases — Ani chosen as the assist app while some other
 * package owns the voice interaction service, a device with no role API at all — are
 * awkward to reproduce on hardware and trivial to write down.
 */
object AssistantRolePolicy {

    /** The API level at which `RoleManager` and ROLE_ASSISTANT appear. */
    const val ROLE_MANAGER_FROM_SDK = 29

    /**
     * @param voiceInteractionComponent flattened `package/class`, from Settings.Secure.
     * @param assistComponent flattened `package/class`, from Settings.Secure.
     * @param aniPackage this app's package name.
     */
    fun evaluate(
        sdkInt: Int,
        roleApiAvailable: Boolean,
        roleHeld: Boolean,
        voiceInteractionComponent: String?,
        assistComponent: String?,
        aniPackage: String,
        aniDeclaresVoiceInteractionService: Boolean
    ): AssistantRoleState {
        val visIsAni = belongsTo(voiceInteractionComponent, aniPackage)
        val assistIsAni = belongsTo(assistComponent, aniPackage)

        return when {
            // The system has selected our voice interaction service. This is the real
            // thing, and it is checked before RoleManager because it is the fact that
            // actually determines what the platform will let Ani do.
            visIsAni && aniDeclaresVoiceInteractionService -> AssistantRoleState.HELD

            // RoleManager says we hold ROLE_ASSISTANT, but no voice interaction service of
            // ours is selected. That is the legacy activity route: the gesture works,
            // nothing else does.
            roleHeld || assistIsAni -> AssistantRoleState.LEGACY_ASSIST_ONLY

            roleApiAvailable -> AssistantRoleState.AVAILABLE_NOT_HELD

            // Pre-29 there is no role API, but the secure settings still exist. Something
            // else holding them is a perfectly readable "available, not held".
            sdkInt < ROLE_MANAGER_FROM_SDK &&
                (voiceInteractionComponent != null || assistComponent != null) ->
                AssistantRoleState.AVAILABLE_NOT_HELD

            else -> AssistantRoleState.NOT_SUPPORTED
        }
    }

    /**
     * Whether a flattened component belongs to [packageName].
     *
     * Compares the package segment rather than using `startsWith`, so a hypothetical
     * `com.ani.assistant.clone` cannot be mistaken for `com.ani.assistant`.
     */
    fun belongsTo(flattenedComponent: String?, packageName: String): Boolean {
        if (flattenedComponent.isNullOrBlank()) return false
        val owner = flattenedComponent.substringBefore('/')
        return owner == packageName
    }

    /**
     * What actually changes for the user in each state.
     *
     * Written here rather than in the UI so it cannot drift away from the state machine
     * that produces it, and so the claims are testable.
     */
    fun explain(state: AssistantRoleState): String = when (state) {
        AssistantRoleState.HELD ->
            "Ani is the device's voice assistant. The assist gesture reaches it, and the " +
                "system can show Ani over the lock screen."

        AssistantRoleState.LEGACY_ASSIST_ONLY ->
            "Ani opens on the assist gesture, but only as an ordinary app. It cannot show " +
                "a voice session over the lock screen and cannot use the system hotword, " +
                "because no Ani voice interaction service is selected."

        AssistantRoleState.AVAILABLE_NOT_HELD ->
            "Another app is the device's assistant. Ani's wake word still works while its " +
                "own listening service is running."

        AssistantRoleState.NOT_SUPPORTED ->
            "This device has no assistant role to hold. Ani's own listening service is the " +
                "only route to the wake word."

        AssistantRoleState.UNKNOWN ->
            "Ani could not read the assistant setting on this device."
    }
}

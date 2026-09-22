package com.ani.assistant.assistant

import com.ani.assistant.voice.assistant.AssistantRolePolicy
import com.ani.assistant.voice.assistant.AssistantRoleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Being the assistant" is two unrelated things, and conflating them is the failure this
 * pins down.
 *
 * An activity with an ACTION_ASSIST filter can be chosen in the assist picker and gets
 * launched by the assist gesture. That is all it does. A VoiceInteractionService selected
 * as the device's voice interaction service is what can show a session over the keyguard
 * and reach AlwaysOnHotwordDetector.
 *
 * Ani currently has the first and not the second. Reporting that as "Ani is the
 * assistant" would promise lock-screen behaviour that cannot happen, with nothing in the
 * UI to explain why it does not.
 */
class AssistantRolePolicyTest {

    private val ani = "com.ani.assistant"
    private val modern = 35
    private val legacy = 28

    private fun evaluate(
        sdkInt: Int = modern,
        roleApiAvailable: Boolean = true,
        roleHeld: Boolean = false,
        voiceInteractionComponent: String? = null,
        assistComponent: String? = null,
        aniDeclaresVoiceInteractionService: Boolean = false
    ) = AssistantRolePolicy.evaluate(
        sdkInt = sdkInt,
        roleApiAvailable = roleApiAvailable,
        roleHeld = roleHeld,
        voiceInteractionComponent = voiceInteractionComponent,
        assistComponent = assistComponent,
        aniPackage = ani,
        aniDeclaresVoiceInteractionService = aniDeclaresVoiceInteractionService
    )

    @Test
    fun `our voice interaction service being selected is the only thing that counts as held`() {
        val state = evaluate(
            voiceInteractionComponent = "$ani/.voice.assistant.AniVoiceInteractionService",
            aniDeclaresVoiceInteractionService = true
        )

        assertEquals(AssistantRoleState.HELD, state)
        assertTrue(state.grantsSystemAssistant)
    }

    @Test
    fun `holding the role without a voice interaction service is the legacy route`() {
        // The state Ani is actually in today: an ACTION_ASSIST activity and nothing else.
        // RoleManager cheerfully says the role is held. The lock screen will not work.
        val state = evaluate(roleHeld = true, aniDeclaresVoiceInteractionService = false)

        assertEquals(AssistantRoleState.LEGACY_ASSIST_ONLY, state)
        assertFalse(
            "legacy assist must never be reported as the system assistant",
            state.grantsSystemAssistant
        )
    }

    @Test
    fun `being the assist app without holding the role is also the legacy route`() {
        val state = evaluate(assistComponent = "$ani/.MainActivity")

        assertEquals(AssistantRoleState.LEGACY_ASSIST_ONLY, state)
        assertFalse(state.grantsSystemAssistant)
    }

    @Test
    fun `declaring a voice interaction service that is not selected is not held`() {
        // Shipping the service changes nothing until the user selects it. This is the
        // case that would otherwise tempt the UI into claiming the feature early.
        val state = evaluate(
            voiceInteractionComponent = "com.google.android.googlequicksearchbox/.VoiceInteractionService",
            aniDeclaresVoiceInteractionService = true
        )

        assertEquals(AssistantRoleState.AVAILABLE_NOT_HELD, state)
        assertFalse(state.grantsSystemAssistant)
    }

    @Test
    fun `another app holding the role leaves the role available`() {
        val state = evaluate(
            voiceInteractionComponent = "com.google.android.googlequicksearchbox/.VoiceInteractionService"
        )

        assertEquals(AssistantRoleState.AVAILABLE_NOT_HELD, state)
    }

    @Test
    fun `a build with no assist framework says so rather than guessing`() {
        val state = evaluate(sdkInt = legacy, roleApiAvailable = false)

        assertEquals(AssistantRoleState.NOT_SUPPORTED, state)
    }

    @Test
    fun `before the role API the secure settings still answer the question`() {
        val state = evaluate(
            sdkInt = legacy,
            roleApiAvailable = false,
            assistComponent = "com.google.android.googlequicksearchbox/.SearchActivity"
        )

        assertEquals(AssistantRoleState.AVAILABLE_NOT_HELD, state)
    }

    @Test
    fun `a package that merely starts with ours is not ours`() {
        // com.ani.assistant.clone must not be read as com.ani.assistant.
        assertFalse(AssistantRolePolicy.belongsTo("$ani.clone/.Service", ani))
        assertTrue(AssistantRolePolicy.belongsTo("$ani/.Service", ani))
    }

    @Test
    fun `a blank or missing component belongs to nobody`() {
        assertFalse(AssistantRolePolicy.belongsTo(null, ani))
        assertFalse(AssistantRolePolicy.belongsTo("", ani))
        assertFalse(AssistantRolePolicy.belongsTo("   ", ani))
    }

    @Test
    fun `only the held state promises the system assistant surface`() {
        for (state in AssistantRoleState.entries) {
            assertEquals(
                "grantsSystemAssistant is wrong for $state",
                state == AssistantRoleState.HELD,
                state.grantsSystemAssistant
            )
        }
    }

    @Test
    fun `every state explains itself and none of them overclaim`() {
        for (state in AssistantRoleState.entries) {
            val explanation = AssistantRolePolicy.explain(state)
            assertTrue("$state has no explanation", explanation.isNotBlank())

            if (!state.grantsSystemAssistant) {
                assertFalse(
                    "$state must not promise the lock screen",
                    explanation.contains("show Ani over the lock screen")
                )
            }
        }
    }
    @Test
    fun `the policy's SDK constant matches the platform constant it mirrors`() {
        // The Android side guards against Build.VERSION_CODES.Q directly, because lint
        // only verifies version checks written that way. This keeps the pure copy from
        // drifting away from it unnoticed.
        assertEquals(29, AssistantRolePolicy.ROLE_MANAGER_FROM_SDK)
    }

}

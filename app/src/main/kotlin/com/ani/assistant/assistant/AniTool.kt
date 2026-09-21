package com.ani.assistant.assistant

import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.settings.AniSettings
import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.response.ResponseStyle

/** Everything a tool needs that is not in the command itself. */
data class ToolContext(
    val style: ResponseStyle,
    val settings: AniSettings,
    val conversation: ConversationContext,
    /** True when the screen is locked, which gates reading private content aloud. */
    val isDeviceLocked: Boolean = false
)

/**
 * One capability of the assistant.
 *
 * Tools are the *only* way Ani affects the device. Each one declares which intents it
 * handles and which permissions it needs, and the registry enforces both — so a tool
 * cannot be reached by an intent it did not claim, and cannot run without the permissions
 * it declared. That is what keeps the blast radius of a misclassification to "did the
 * wrong harmless thing" rather than "did something it was never allowed to do".
 *
 * A tool never speaks. It returns an [AniResult] carrying the words, and the orchestrator
 * decides whether they are spoken, shown, or both.
 */
interface AniTool {

    /** Stable identifier, used in diagnostics and logs. */
    val id: String

    /** Intents this tool claims. Overlaps are a programming error and fail fast. */
    val handles: Set<IntentType>

    /**
     * Permissions without which this tool cannot run at all.
     *
     * A tool that merely *prefers* a permission (calling works better with CALL_PHONE but
     * falls back to the dialler) does not list it here — it checks at execution time and
     * returns a [AniResult.Limitation] instead.
     */
    val requiredPermissions: List<AniPermission>
        get() = emptyList()

    suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult
}

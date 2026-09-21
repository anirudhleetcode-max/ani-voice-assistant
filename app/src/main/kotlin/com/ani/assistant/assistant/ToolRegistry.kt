package com.ani.assistant.assistant

import com.ani.assistant.core.log.AniLog
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.permission.PermissionManager
import com.ani.assistant.core.result.AniResult
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.response.Responses

/**
 * Routes an intent to the one tool that handles it, and refuses to route it anywhere else.
 *
 * The map is built once and validated at construction: two tools claiming the same intent
 * is a bug that shows up immediately at startup rather than as a coin flip in production.
 */
class ToolRegistry(
    tools: List<AniTool>,
    private val permissionManager: PermissionManager
) {

    private val byIntent: Map<IntentType, AniTool> = buildMap {
        for (tool in tools) {
            for (intent in tool.handles) {
                val existing = put(intent, tool)
                require(existing == null) {
                    "Both ${existing?.id} and ${tool.id} claim $intent. Exactly one tool must handle each intent."
                }
            }
        }
    }

    val registeredTools: List<AniTool> = tools

    fun toolFor(intent: IntentType): AniTool? = byIntent[intent]

    /** Intents with no tool. Surfaced in Diagnostics so gaps are visible, not silent. */
    fun unhandledIntents(): List<IntentType> =
        IntentType.entries.filter { it.isDeviceAction && it !in byIntent }

    /**
     * Runs the tool for [command] after checking its declared permissions.
     *
     * A missing permission returns [AniResult.NeedsPermission] rather than throwing, so
     * the assistant can explain what it needs and where to grant it — which is the whole
     * difference between "Ani is broken" and "Ani needs notification access".
     */
    suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val tool = byIntent[command.type]
            ?: return AniResult.Failure(Responses.dontKnowHow(context.style))

        val missing = tool.requiredPermissions.firstOrNull { !permissionManager.isGranted(it) }
        if (missing != null) {
            AniLog.i(TAG, "tool blocked by permission", "tool" to tool.id, "permission" to missing.name)
            return AniResult.NeedsPermission(
                spokenResponse = permissionMessage(missing, context.style),
                permissionKey = missing.storageKey
            )
        }

        return try {
            tool.execute(command, context)
        } catch (error: Exception) {
            // A tool throwing must never take the assistant down mid-conversation.
            AniLog.e(TAG, "tool threw", error, "tool" to tool.id, "intent" to command.type.name)
            AniResult.Failure(Responses.somethingWentWrong(context.style), error)
        }
    }

    private fun permissionMessage(permission: AniPermission, style: ResponseStyle): String =
        when (permission) {
            AniPermission.NOTIFICATION_ACCESS -> Responses.notificationAccessMissing(style)
            AniPermission.DO_NOT_DISTURB -> Responses.dndNeedsPermission(style)
            else -> Responses.permissionMissing(permission.displayName, style)
        }

    private companion object {
        const val TAG = "AniTools"
    }
}

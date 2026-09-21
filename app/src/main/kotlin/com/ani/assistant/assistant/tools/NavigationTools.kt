package com.ani.assistant.assistant.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.log.AniLog
import com.ani.assistant.core.permission.AniPermission
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.platform.share.CommunicationLauncher
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses

/** Directions and location sharing. */
class NavigationTool(
    private val context: Context,
    private val launcher: CommunicationLauncher
) : AniTool {

    override val id: String = "navigation"
    override val handles: Set<IntentType> = setOf(IntentType.NAVIGATE, IntentType.SHARE_LOCATION)

    override val requiredPermissions: List<AniPermission> = emptyList()

    override suspend fun execute(command: ParsedCommand, toolContext: ToolContext): AniResult =
        when (command.type) {
            IntentType.SHARE_LOCATION -> shareLocation(toolContext)
            else -> navigate(command, toolContext)
        }

    private fun navigate(command: ParsedCommand, toolContext: ToolContext): AniResult {
        val destination = command[SlotKey.DESTINATION]
            ?: return AniResult.NeedsInput(
                if (toolContext.style.speaksTelugu) "Ekkadiki?" else "Where to?",
                SlotKey.DESTINATION.name
            )

        return if (launcher.navigateTo(destination)) {
            AniResult.Success(
                if (toolContext.style.speaksTelugu) {
                    "$destination ki route chupistunna${toolContext.style.particle}."
                } else {
                    "Showing directions to $destination."
                }
            )
        } else {
            AniResult.Failure(Responses.appNotInstalled("Maps", toolContext.style))
        }
    }

    /**
     * Shares a maps link through the system share sheet.
     *
     * Uses the last known fix rather than requesting a live one: asking for a fresh GPS
     * lock can take thirty seconds outdoors and never resolve indoors, which is not a
     * behaviour a voice command should have. If there is no recent fix, Ani says so.
     */
    @SuppressLint("MissingPermission") // Checked immediately below via the permission gate.
    private fun shareLocation(toolContext: ToolContext): AniResult {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return AniResult.Failure(Responses.somethingWentWrong(toolContext.style))

        val location: Location? = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .asSequence()
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        } catch (error: SecurityException) {
            AniLog.d(TAG, "location permission not granted")
            return AniResult.NeedsPermission(
                Responses.permissionMissing(AniPermission.LOCATION.displayName, toolContext.style),
                AniPermission.LOCATION.storageKey
            )
        }

        if (location == null) {
            return AniResult.Limitation(
                spokenResponse = if (toolContext.style.speaksTelugu) {
                    "Ippudu location teliyatledu${toolContext.style.particle}. Maps open chesi try chey."
                } else {
                    "I don't have a recent location fix. Open Maps once and try again."
                },
                fallbackTaken = null
            )
        }

        return if (launcher.shareLocation(location.latitude, location.longitude)) {
            AniResult.Success(
                if (toolContext.style.speaksTelugu) {
                    "Location share cheyyadaniki app select chey${toolContext.style.particle}."
                } else {
                    "Pick where to share your location."
                }
            )
        } else {
            AniResult.Failure(Responses.somethingWentWrong(toolContext.style))
        }
    }

    private companion object {
        const val TAG = "AniNavigation"
    }
}

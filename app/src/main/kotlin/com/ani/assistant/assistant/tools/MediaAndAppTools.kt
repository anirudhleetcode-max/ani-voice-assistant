package com.ani.assistant.assistant.tools

import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.memory.MemoryCategory
import com.ani.assistant.data.memory.MemoryRepository
import com.ani.assistant.platform.apps.AppResolver
import com.ani.assistant.platform.music.MusicController
import com.ani.assistant.platform.music.MusicOutcome
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses

/**
 * Music.
 *
 * The honesty rule bites hardest here. "Arijit Singh play chey" cannot start playback in
 * Spotify from a third-party app, so Ani opens the search and says *that* — never "play
 * chesthunna". Transport controls, on the other hand, are real, and get a real
 * confirmation.
 */
class MusicTool(
    private val music: MusicController,
    private val apps: AppResolver
) : AniTool {

    override val id: String = "music"
    override val handles: Set<IntentType> = setOf(IntentType.PLAY_MUSIC, IntentType.MUSIC_CONTROL)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult =
        when (command.type) {
            IntentType.MUSIC_CONTROL -> control(command, context)
            else -> play(command, context)
        }

    private fun control(command: ParsedCommand, context: ToolContext): AniResult {
        val action = command[SlotKey.MEDIA_ACTION] ?: return AniResult.Failure(
            Responses.didNotUnderstand(context.style)
        )
        return when (val outcome = music.control(action)) {
            is MusicOutcome.Controlled ->
                AniResult.Success(Responses.musicActionDone(outcome.action, context.style))

            MusicOutcome.NothingPlaying ->
                AniResult.Failure(Responses.nothingPlaying(context.style))

            else -> AniResult.Failure(Responses.somethingWentWrong(context.style))
        }
    }

    private suspend fun play(command: ParsedCommand, context: ToolContext): AniResult {
        val query = command[SlotKey.MUSIC_QUERY]
            ?: context.conversation.lastMusicQuery
            ?: return AniResult.NeedsInput(
                Responses.askWhatToPlay(context.style),
                SlotKey.MUSIC_QUERY.name
            )

        val provider = command[SlotKey.MUSIC_PROVIDER] ?: context.settings.preferredMusicApp

        return when (val outcome = music.openSearch(query, provider)) {
            is MusicOutcome.SearchOpened -> AniResult.Limitation(
                spokenResponse = Responses.openedMusicSearch(query, outcome.appLabel, context.style),
                fallbackTaken = "opened a search"
            )

            is MusicOutcome.AppNotInstalled -> {
                // Offer whatever music app they do have rather than a flat refusal.
                val alternative = apps.resolve("spotify") ?: apps.resolve("youtube")
                if (alternative != null) {
                    AniResult.Limitation(
                        Responses.appNotInstalled(outcome.appLabel, context.style),
                        fallbackTaken = "suggested ${alternative.label}"
                    )
                } else {
                    AniResult.Failure(Responses.appNotInstalled(outcome.appLabel, context.style))
                }
            }

            else -> AniResult.Failure(Responses.somethingWentWrong(context.style))
        }
    }
}

/**
 * Opens apps by whatever the user calls them.
 *
 * Resolution order is the user's own nicknames first ("na college app ante Moodle"), then
 * the built-in alias table, then the installed app labels — so a personal name always
 * beats a generic one.
 */
class AppLauncherTool(
    private val apps: AppResolver,
    private val memory: MemoryRepository
) : AniTool {

    override val id: String = "app_launcher"
    override val handles: Set<IntentType> = setOf(IntentType.OPEN_APP)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val spoken = command[SlotKey.APP_NAME]
            ?: return AniResult.NeedsInput(
                if (context.style.speaksTelugu) "Ye app open cheyyali?" else "Which app?",
                SlotKey.APP_NAME.name
            )

        val target = memory.resolveAlias(MemoryCategory.APP_ALIAS, spoken) ?: spoken
        val app = apps.resolve(target)
            ?: return AniResult.Failure(Responses.appNotFound(spoken, context.style))

        val intent = apps.launchIntentFor(app)
            ?: return AniResult.Failure(Responses.appNotInstalled(app.label, context.style))

        return try {
            apps.startApp(intent)
            AniResult.Success(Responses.openedApp(app.label, context.style))
        } catch (error: Exception) {
            AniResult.Failure(Responses.somethingWentWrong(context.style), error)
        }
    }
}

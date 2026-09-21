package com.ani.assistant.assistant.tools

import com.ani.assistant.ai.AiOutcome
import com.ani.assistant.ai.AiProvider
import com.ani.assistant.ai.AiRequest
import com.ani.assistant.ai.AiTurn
import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.conversation.ConversationRepository
import com.ani.assistant.data.conversation.Speaker
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses
import kotlinx.coroutines.flow.first

/**
 * Small talk and open questions — the only place an AI provider is consulted.
 *
 * It is worth being precise about what this tool can and cannot do. It sends a sentence
 * and gets a sentence back. It has no access to tools, no ability to name an action, and
 * nothing it returns is parsed for commands. If the model replied "I have called your
 * mother", nothing would happen, because nothing downstream of here can call anyone.
 *
 * What it *does* forward is the recent transcript, so the reply follows the conversation.
 * Notification contents and contact details are never included.
 */
class ConversationTool(
    private val providerFor: suspend (ToolContext) -> AiProvider,
    private val conversation: ConversationRepository
) : AniTool {

    override val id: String = "conversation"
    override val handles: Set<IntentType> = setOf(
        IntentType.GENERAL_QUESTION,
        IntentType.CONVERSATION,
        IntentType.WAKE_ONLY,
        IntentType.REPEAT_LAST,
        IntentType.TEACH_COMMAND,
        IntentType.CONFIRM,
        IntentType.DENY,
        IntentType.CANCEL,
        IntentType.UNKNOWN
    )

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        when (command.type) {
            IntentType.WAKE_ONLY ->
                return AniResult.Success(Responses.wakeAcknowledgement(context.style))

            IntentType.CANCEL, IntentType.DENY ->
                return AniResult.Success(Responses.cancelled(context.style))

            IntentType.CONFIRM ->
                // A bare "avunu" with nothing pending has nothing to confirm.
                return AniResult.Success(Responses.acknowledged(context.style))

            IntentType.REPEAT_LAST -> {
                val last = context.conversation.lastResponse
                    ?: return AniResult.Success(Responses.didNotUnderstand(context.style))
                return AniResult.Success(last)
            }

            IntentType.TEACH_COMMAND -> return AniResult.Limitation(
                spokenResponse = if (context.style.speaksTelugu) {
                    "Commands screen lo kotha command add cheyyachu${context.style.particle}. Open chey."
                } else {
                    "You can build that on the Commands screen — steps and all."
                },
                fallbackTaken = "pointed at the Commands screen"
            )

            IntentType.UNKNOWN ->
                return AniResult.Failure(Responses.dontKnowHow(context.style))

            else -> Unit
        }

        val question = command[SlotKey.QUESTION] ?: command.normalizedText
        if (question.isBlank()) {
            return AniResult.Success(Responses.didNotUnderstand(context.style))
        }

        val provider = providerFor(context)
        val request = AiRequest(
            userText = command.originalText.ifBlank { question },
            language = context.style.language,
            persona = context.style.persona,
            recentTurns = recentTurns(context)
        )

        return when (val outcome = provider.reply(request)) {
            is AiOutcome.Reply -> AniResult.Success(outcome.text)

            is AiOutcome.Unavailable -> AniResult.Limitation(
                spokenResponse = outcome.reason.ifBlank { Responses.noInternet(context.style) },
                fallbackTaken = null
            )

            is AiOutcome.Failed -> AniResult.Failure(Responses.networkTrouble(context.style))
        }
    }

    /**
     * The last few turns, oldest first.
     *
     * Only sent when the user has history enabled — with history off, nothing about
     * previous turns leaves the device, and the reply is answered cold.
     */
    private suspend fun recentTurns(context: ToolContext): List<AiTurn> {
        if (!context.settings.storeConversationHistory) return emptyList()
        return conversation.recent(MAX_TURNS).first()
            .reversed()
            .map { AiTurn(fromUser = it.speaker == Speaker.USER, text = it.text) }
    }

    private companion object {
        const val MAX_TURNS = 6
    }
}

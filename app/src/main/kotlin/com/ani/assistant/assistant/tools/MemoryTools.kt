package com.ani.assistant.assistant.tools

import com.ani.assistant.assistant.AniTool
import com.ani.assistant.assistant.ToolContext
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.commands.CustomCommandRepository
import com.ani.assistant.data.memory.MemoryCategory
import com.ani.assistant.data.memory.MemoryRepository
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.response.Responses

/**
 * "Rey, Amma ante Lakshmi."
 *
 * Only stores what the user explicitly stated. There is no inference here, no learning
 * from behaviour, and nothing written without a sentence that asked for it — which is
 * what lets the Memory screen honestly claim to show everything Ani knows.
 */
class MemoryTool(private val memory: MemoryRepository) : AniTool {

    override val id: String = "memory"
    override val handles: Set<IntentType> =
        setOf(IntentType.REMEMBER_FACT, IntentType.FORGET_FACT)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        if (command.type == IntentType.FORGET_FACT) {
            return AniResult.Limitation(
                spokenResponse = if (context.style.speaksTelugu) {
                    "Memory screen lo chusi delete cheyyi${context.style.particle}."
                } else {
                    "You can review and delete memories on the Memory screen."
                },
                fallbackTaken = null
            )
        }

        val key = command[SlotKey.MEMORY_KEY]
        val value = command[SlotKey.MEMORY_VALUE]

        if (key.isNullOrBlank() || value.isNullOrBlank()) {
            return AniResult.NeedsInput(
                if (context.style.speaksTelugu) "Enti gurthu pettukovali?" else "What should I remember?",
                SlotKey.MEMORY_VALUE.name
            )
        }

        // A kinship word or a name maps to a contact; anything else is a plain fact.
        val category = if (looksLikeContactAlias(key)) {
            MemoryCategory.CONTACT_ALIAS
        } else {
            MemoryCategory.FACT
        }

        memory.remember(
            category = category,
            key = key,
            value = value,
            learnedFrom = command.originalText
        )
        return AniResult.Success(Responses.rememberedFact(key, value, context.style))
    }

    private fun looksLikeContactAlias(key: String): Boolean =
        key.trim().split(' ').size <= 2
}

/**
 * Runs a phrase the user taught Ani.
 *
 * The steps were approved once, when the command was saved, so they run without
 * re-confirming each one — which is the whole point of "college mode". Execution is
 * delegated back through the registry so a custom step behaves exactly as if the user had
 * asked for it directly, permissions and all.
 */
class CustomCommandTool(
    private val commands: CustomCommandRepository,
    /** Supplied by the orchestrator to avoid a construction cycle. */
    private val runStep: suspend (ParsedCommand, ToolContext) -> AniResult
) : AniTool {

    override val id: String = "custom_command"
    override val handles: Set<IntentType> = setOf(IntentType.CUSTOM_COMMAND)

    override suspend fun execute(command: ParsedCommand, context: ToolContext): AniResult {
        val id = command[SlotKey.CUSTOM_COMMAND_ID]
            ?: return AniResult.Failure(Responses.dontKnowHow(context.style))

        val stored = commands.findById(id)
            ?: return AniResult.Failure(Responses.dontKnowHow(context.style))

        val steps = stored.toDomain().actions
        if (steps.isEmpty()) {
            return AniResult.Failure(Responses.dontKnowHow(context.style))
        }

        val failures = mutableListOf<String>()
        for (step in steps) {
            val stepCommand = ParsedCommand(
                type = step.intent,
                confidence = 1.0,
                slots = step.slots,
                language = command.language,
                normalizedText = command.normalizedText,
                originalText = command.originalText
            )
            when (val result = runStep(stepCommand, context)) {
                is AniResult.Success, is AniResult.Limitation -> Unit
                else -> failures += result.spoken
            }
        }

        // Report partial success honestly rather than announcing the whole routine ran.
        return when {
            failures.isEmpty() -> AniResult.Success(
                Responses.runningCommand(stored.phrase, context.style)
            )

            failures.size == steps.size -> AniResult.Failure(failures.first())

            else -> AniResult.Limitation(
                spokenResponse = Responses.runningCommand(stored.phrase, context.style) + " " +
                    partialHint(failures.size, context),
                fallbackTaken = "ran ${steps.size - failures.size} of ${steps.size} steps"
            )
        }
    }

    private fun partialHint(failed: Int, context: ToolContext) = if (context.style.speaksTelugu) {
        "$failed step kaaledu${context.style.particle}."
    } else {
        "$failed step(s) didn't work."
    }
}

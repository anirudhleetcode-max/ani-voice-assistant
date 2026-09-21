package com.ani.assistant.assistant

import com.ani.assistant.core.log.AniLog
import com.ani.assistant.core.result.AniResult
import com.ani.assistant.data.commands.CustomCommandRepository
import com.ani.assistant.data.conversation.ConversationRepository
import com.ani.assistant.data.settings.AniSettings
import com.ani.assistant.data.settings.SettingsRepository
import com.ani.nlu.command.CustomCommandMatcher
import com.ani.nlu.dialog.ConfirmationPolicy
import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.dialog.WakeWordMatcher
import com.ani.nlu.intent.IntentClassifier
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey
import com.ani.nlu.lexicon.LanguageDetector
import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.response.Responses
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything that came out of handling one utterance. */
data class AniTurn(
    /** What Ani should say and show. */
    val response: String,
    val command: ParsedCommand,
    val result: AniResult,
    /** True when Ani is waiting for the user to answer something. */
    val expectsReply: Boolean
)

/**
 * The pipeline, in one place.
 *
 * ```
 * text -> normalise -> detect language -> classify -> check slots
 *      -> check confirmation -> run tool -> phrase the result -> update context
 * ```
 *
 * The two checks in the middle are what make this an assistant rather than a command
 * parser. A command missing a slot becomes a question; a consequential command becomes a
 * confirmation. Both park the parsed command in the conversation context so the *next*
 * utterance can complete it, which is how "Rahul ki message pampu" / "Em message?" /
 * "repu late avutha ani cheppu" works as one thought spread over three turns.
 *
 * The classifier is rebuilt whenever the wake phrases or custom commands change, so a
 * command saved in the UI is live on the next sentence without a restart.
 */
class AniOrchestrator(
    private val settingsRepository: SettingsRepository,
    private val commandRepository: CustomCommandRepository,
    private val conversationRepository: ConversationRepository,
    private val toolRegistry: ToolRegistry,
    /** Supplied by the caller because only the UI layer knows the keyguard state. */
    private val isDeviceLocked: () -> Boolean = { false }
) {

    /** Guards [context]; utterances can arrive from the service and the UI at once. */
    private val mutex = Mutex()

    @Volatile
    private var context: ConversationContext = ConversationContext.EMPTY

    private var cachedClassifier: IntentClassifier? = null
    private var cachedSignature: String? = null

    val conversationContext: ConversationContext get() = context

    /** Handles one thing the user said. Never throws. */
    suspend fun handle(utterance: String): AniTurn = mutex.withLock {
        val settings = settingsRepository.settings.first()
        val classifier = classifierFor(settings)

        val command = classifier.classify(utterance, context)
        val style = settings.responseStyle(command.language)

        conversationRepository.recordUser(utterance, settings.storeConversationHistory)
        AniLog.i(
            TAG,
            "classified utterance",
            "intent" to command.type.name,
            "confidence" to "%.2f".format(command.confidence),
            "complete" to command.isComplete
        )

        val turn = route(command, settings, style)

        conversationRepository.recordAni(
            text = turn.response,
            intent = command.type.name,
            succeeded = turn.result is AniResult.Success,
            historyEnabled = settings.storeConversationHistory
        )
        context = context.afterResponding(turn.response)
        turn
    }

    /** Clears the pending question and short-term memory, e.g. when the user taps "stop". */
    suspend fun reset() = mutex.withLock {
        context = ConversationContext.EMPTY
    }

    // ---------------------------------------------------------------------------------

    private suspend fun route(
        command: ParsedCommand,
        settings: AniSettings,
        style: ResponseStyle
    ): AniTurn {
        val toolContext = ToolContext(
            style = style,
            settings = settings,
            conversation = context,
            isDeviceLocked = isDeviceLocked()
        )

        // 1. Missing arguments become a question, not a failure.
        val missingSlot = command.needsSlots.firstOrNull()
        if (missingSlot != null) {
            val question = questionFor(missingSlot, command, style)
            context = context.awaitingSlot(command, missingSlot, question)
            return AniTurn(
                response = question,
                command = command,
                result = AniResult.NeedsInput(question, missingSlot.name),
                expectsReply = true
            )
        }

        // 2. Consequential actions are confirmed first.
        if (ConfirmationPolicy.requiresConfirmation(command, settings.confirmationLevel)) {
            val prompt = confirmationFor(command, style)
            context = context.awaitingConfirmation(command, prompt)
            return AniTurn(
                response = prompt,
                command = command,
                result = AniResult.NeedsConfirmation(prompt),
                expectsReply = true
            )
        }

        // 3. Do it.
        val result = toolRegistry.execute(command, toolContext)
        return finish(command, result, style)
    }

    /**
     * Turns a tool result into a turn, parking the command again when the tool itself
     * needs something more.
     */
    private fun finish(command: ParsedCommand, result: AniResult, style: ResponseStyle): AniTurn {
        when (result) {
            is AniResult.NeedsInput -> {
                val slot = runCatching { SlotKey.valueOf(result.slotKey) }.getOrNull()
                context = if (slot != null) {
                    context.awaitingSlot(command, slot, result.spokenResponse)
                } else {
                    context.cleared()
                }
                return AniTurn(result.spokenResponse, command, result, expectsReply = true)
            }

            is AniResult.NeedsConfirmation -> {
                context = context.awaitingConfirmation(command, result.spokenResponse)
                return AniTurn(result.spokenResponse, command, result, expectsReply = true)
            }

            is AniResult.NeedsPermission -> {
                context = context.cleared()
                return AniTurn(result.spokenResponse, command, result, expectsReply = false)
            }

            else -> {
                context = context.afterExecuting(command)
                return AniTurn(result.spoken, command, result, expectsReply = false)
            }
        }
    }

    private fun questionFor(slot: SlotKey, command: ParsedCommand, style: ResponseStyle): String =
        when (slot) {
            SlotKey.CONTACT_NAME -> when (command.type) {
                IntentType.SEND_MESSAGE ->
                    if (style.speaksTelugu) "Evariki pampali?" else "Who should I send it to?"
                else -> Responses.whichContact(style)
            }

            SlotKey.MESSAGE_BODY -> Responses.askMessageBody(command[SlotKey.CONTACT_NAME], style)
            SlotKey.MUSIC_QUERY -> Responses.askWhatToPlay(style)
            SlotKey.REMINDER_TEXT -> Responses.askWhatToRemind(style)
            SlotKey.APP_NAME -> if (style.speaksTelugu) "Ye app?" else "Which app?"
            SlotKey.DESTINATION -> if (style.speaksTelugu) "Ekkadiki?" else "Where to?"
            else -> Responses.didNotUnderstand(style)
        }

    private fun confirmationFor(command: ParsedCommand, style: ResponseStyle): String =
        when (command.type) {
            IntentType.CALL_CONTACT ->
                Responses.confirmCall(command[SlotKey.CONTACT_NAME].orEmpty(), style)

            IntentType.SEND_MESSAGE -> Responses.confirmMessage(
                name = command[SlotKey.CONTACT_NAME].orEmpty(),
                body = command[SlotKey.MESSAGE_BODY].orEmpty(),
                channel = command[SlotKey.MESSAGE_CHANNEL],
                style = style
            )

            IntentType.CONTROL_DND ->
                Responses.confirmDnd(command[SlotKey.TOGGLE_STATE] != "off", style)

            else -> if (style.speaksTelugu) "Cheyyala?" else "Go ahead?"
        }

    /**
     * Builds the classifier, reusing it until the wake phrases or the saved commands
     * change. Rebuilding on every utterance would re-index the whole command list for
     * nothing; never rebuilding would mean a newly-saved command did not work until
     * restart.
     */
    private suspend fun classifierFor(settings: AniSettings): IntentClassifier {
        val matcher = commandRepository.matcher.first()
        val signature = buildString {
            append(settings.effectiveWakePhrases().joinToString("|"))
            append('#')
            append(settings.wakeSensitivity)
            append('#')
            append(commandRepository.commands.first().joinToString("|") { it.id + it.phrase + it.enabled })
        }

        cachedClassifier?.let { existing ->
            if (cachedSignature == signature) return existing
        }

        val classifier = IntentClassifier(
            wakeMatcher = WakeWordMatcher(
                phrases = settings.effectiveWakePhrases(),
                sensitivity = settings.wakeSensitivity.toDouble()
            ),
            customCommands = matcher
        )
        cachedClassifier = classifier
        cachedSignature = signature
        return classifier
    }

    private companion object {
        const val TAG = "AniOrchestrator"
    }
}

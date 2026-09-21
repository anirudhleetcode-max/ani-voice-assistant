package com.ani.nlu.dialog

import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.ParsedCommand
import com.ani.nlu.intent.SlotKey

/** An action Ani has described and is waiting for the user to approve. */
data class PendingConfirmation(
    val command: ParsedCommand,
    /** What Ani asked, so it can be repeated if the user says "enti?". */
    val prompt: String
)

/** A slot Ani asked a direct question about ("Em message?"). */
data class PendingSlotRequest(
    val command: ParsedCommand,
    val slot: SlotKey,
    val prompt: String
)

/**
 * Short-term memory for the current exchange.
 *
 * This is what makes the second turn work:
 * ```
 * "Rey Spotify open chey."    -> activeApp = spotify
 * "Arijit Singh play chey."   -> play *in Spotify*, because activeApp says so
 * ```
 * It is deliberately small and deliberately short-lived. Anything worth keeping past the
 * conversation belongs in the user's memory store, where they can see and delete it;
 * nothing here is persisted to disk.
 */
data class ConversationContext(
    val lastIntent: IntentType? = null,
    val lastSlots: Map<SlotKey, String> = emptyMap(),
    val pendingConfirmation: PendingConfirmation? = null,
    val pendingSlot: PendingSlotRequest? = null,
    /** Canonical name of the app the user most recently opened or used. */
    val activeApp: String? = null,
    /** Last contact Ani acted on — resolves "malli call chey". */
    val lastContact: String? = null,
    /** Last music search — resolves "ee song malli pettu". */
    val lastMusicQuery: String? = null,
    /** True while media is believed to be playing, so "aapu" means pause, not cancel. */
    val mediaActive: Boolean = false,
    /** Turns since the wake phrase; the follow-up window closes after a few. */
    val turnsSinceWake: Int = 0,
    /** The exact words Ani last spoke, for "malli cheppu". */
    val lastResponse: String? = null
) {

    val isAwaitingAnswer: Boolean get() = pendingConfirmation != null || pendingSlot != null

    fun awaitingConfirmation(command: ParsedCommand, prompt: String): ConversationContext =
        copy(pendingConfirmation = PendingConfirmation(command, prompt), pendingSlot = null)

    fun awaitingSlot(command: ParsedCommand, slot: SlotKey, prompt: String): ConversationContext =
        copy(pendingSlot = PendingSlotRequest(command, slot, prompt), pendingConfirmation = null)

    fun cleared(): ConversationContext = copy(pendingConfirmation = null, pendingSlot = null)

    /** Records that [command] was executed, so the next turn can refer back to it. */
    fun afterExecuting(command: ParsedCommand): ConversationContext = copy(
        lastIntent = command.type,
        lastSlots = command.slots,
        pendingConfirmation = null,
        pendingSlot = null,
        activeApp = command[SlotKey.APP_NAME]
            ?: command[SlotKey.MUSIC_PROVIDER]
            ?: activeApp,
        lastContact = command[SlotKey.CONTACT_NAME] ?: lastContact,
        lastMusicQuery = command[SlotKey.MUSIC_QUERY] ?: lastMusicQuery,
        mediaActive = when (command.type) {
            IntentType.PLAY_MUSIC -> true
            IntentType.MUSIC_CONTROL -> command[SlotKey.MEDIA_ACTION] != "stop"
            else -> mediaActive
        },
        turnsSinceWake = turnsSinceWake + 1
    )

    fun afterResponding(response: String): ConversationContext = copy(lastResponse = response)

    fun awake(): ConversationContext = copy(turnsSinceWake = 0)

    companion object {
        val EMPTY = ConversationContext()

        /** How many follow-up turns stay in the same conversation before context expires. */
        const val FOLLOW_UP_WINDOW_TURNS = 6
    }
}

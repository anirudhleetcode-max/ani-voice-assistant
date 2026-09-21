package com.ani.assistant.data.commands

import android.content.Context
import com.ani.assistant.data.store.JsonListStore
import com.ani.nlu.command.CustomAction
import com.ani.nlu.command.CustomCommand
import com.ani.nlu.command.CustomCommandMatcher
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The on-disk form of a custom command step.
 *
 * Kept separate from [CustomAction] so that the language engine stays free of
 * serialization annotations, and so that a step referring to an intent that no longer
 * exists in a future version is dropped on read instead of crashing the app.
 */
@Serializable
data class StoredAction(
    val intent: String,
    val slots: Map<String, String> = emptyMap()
) {
    fun toDomain(): CustomAction? {
        val type = runCatching { IntentType.valueOf(intent) }.getOrNull() ?: return null
        val mapped = slots.mapNotNull { (key, value) ->
            runCatching { SlotKey.valueOf(key) }.getOrNull()?.let { it to value }
        }.toMap()
        return CustomAction(type, mapped)
    }

    companion object {
        fun from(action: CustomAction) = StoredAction(
            intent = action.intent.name,
            slots = action.slots.mapKeys { it.key.name }
        )
    }
}

@Serializable
data class StoredCommand(
    val id: String = UUID.randomUUID().toString(),
    val phrase: String,
    val actions: List<StoredAction>,
    val enabled: Boolean = true,
    val description: String = "",
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    fun toDomain() = CustomCommand(
        id = id,
        phrase = phrase,
        actions = actions.mapNotNull { it.toDomain() },
        enabled = enabled,
        description = description,
        createdAtEpochMillis = createdAtMillis
    )

    companion object {
        fun from(command: CustomCommand) = StoredCommand(
            id = command.id,
            phrase = command.phrase,
            actions = command.actions.map { StoredAction.from(it) },
            enabled = command.enabled,
            description = command.description,
            createdAtMillis = command.createdAtEpochMillis
        )
    }
}

/** The result of trying to save a command, so the UI can explain a rejection. */
sealed interface SaveCommandResult {
    data class Saved(val command: StoredCommand) : SaveCommandResult
    data object PhraseEmpty : SaveCommandResult
    data object NoActions : SaveCommandResult
    data class DuplicatePhrase(val existing: StoredCommand) : SaveCommandResult
}

/**
 * The commands the user taught Ani.
 *
 * Duplicate detection uses the *phonetic* form of the phrase, because two commands whose
 * triggers sound identical would be a coin flip at recognition time — better to reject
 * the second one at save time, where there is a person to explain it to.
 */
class CustomCommandRepository(context: Context) {

    private val store = JsonListStore(
        context = context,
        fileName = "custom_commands.json",
        itemSerializer = StoredCommand.serializer(),
        maxEntries = MAX_COMMANDS
    )

    val commands: Flow<List<StoredCommand>> = store.items

    /** The matcher the classifier uses, rebuilt whenever the list changes. */
    val matcher: Flow<CustomCommandMatcher> = commands.map { stored ->
        CustomCommandMatcher(stored.map { it.toDomain() })
    }

    suspend fun save(phrase: String, actions: List<CustomAction>, description: String = ""): SaveCommandResult {
        val trimmed = phrase.trim()
        if (trimmed.isEmpty()) return SaveCommandResult.PhraseEmpty
        if (actions.isEmpty()) return SaveCommandResult.NoActions

        val phraseKey = CustomCommandMatcher.phraseKey(trimmed)
        val existing = store.items.first()
            .firstOrNull { CustomCommandMatcher.phraseKey(it.phrase) == phraseKey }
        if (existing != null) return SaveCommandResult.DuplicatePhrase(existing)

        val command = StoredCommand(
            phrase = trimmed,
            actions = actions.map { StoredAction.from(it) },
            description = description
        )
        store.add(command)
        return SaveCommandResult.Saved(command)
    }

    suspend fun update(command: StoredCommand) {
        store.update { current -> current.map { if (it.id == command.id) command else it } }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        store.update { current -> current.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    }

    suspend fun delete(id: String) = store.removeWhere { it.id == id }

    suspend fun findById(id: String): StoredCommand? = store.items.first().firstOrNull { it.id == id }

    suspend fun clear() = store.clear()

    private companion object {
        const val MAX_COMMANDS = 100
    }
}

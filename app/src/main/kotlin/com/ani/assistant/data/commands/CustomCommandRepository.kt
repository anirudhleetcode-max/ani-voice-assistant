package com.ani.assistant.data.commands

import android.content.Context
import com.ani.assistant.data.store.JsonListStore
import com.ani.nlu.command.CustomAction
import com.ani.nlu.command.CustomCommandMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

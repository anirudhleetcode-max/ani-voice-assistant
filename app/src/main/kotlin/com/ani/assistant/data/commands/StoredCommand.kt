package com.ani.assistant.data.commands

import com.ani.nlu.command.CustomAction
import com.ani.nlu.command.CustomCommand
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey
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

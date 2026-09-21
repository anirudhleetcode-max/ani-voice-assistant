package com.ani.assistant.data

import com.ani.assistant.data.commands.StoredAction
import com.ani.assistant.data.commands.StoredCommand
import com.ani.nlu.command.CustomAction
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stored commands have to survive the app changing underneath them.
 *
 * A routine saved in one version and read back in another must not crash the app because
 * an intent was renamed — it should lose the step it no longer understands and keep the
 * rest. These tests pin that behaviour.
 */
class CustomCommandSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a command round-trips through storage`() {
        val original = CustomAction(
            IntentType.OPEN_APP,
            mapOf(SlotKey.APP_NAME to "spotify")
        )
        val restored = StoredAction.from(original).toDomain()
        assertEquals(original, restored)
    }

    @Test
    fun `a step naming an intent that no longer exists is dropped, not fatal`() {
        val stored = StoredCommand(
            phrase = "college mode",
            actions = listOf(
                StoredAction("OPEN_APP", mapOf("APP_NAME" to "calendar")),
                StoredAction("INTENT_FROM_A_FUTURE_VERSION", mapOf("X" to "y"))
            )
        )
        val domain = stored.toDomain()
        assertEquals(1, domain.actions.size)
        assertEquals(IntentType.OPEN_APP, domain.actions.first().intent)
    }

    @Test
    fun `a slot that no longer exists is dropped from its step`() {
        val action = StoredAction(
            "OPEN_APP",
            mapOf("APP_NAME" to "chrome", "SLOT_FROM_A_FUTURE_VERSION" to "x")
        ).toDomain()
        assertEquals(mapOf(SlotKey.APP_NAME to "chrome"), action?.slots)
    }

    @Test
    fun `an unknown intent yields no action at all`() {
        assertNull(StoredAction("NOPE", emptyMap()).toDomain())
    }

    @Test
    fun `stored JSON tolerates fields added by a later version`() {
        val payload = """
            {"id":"a","phrase":"good night","actions":[{"intent":"CONTROL_DND",
             "slots":{"TOGGLE_STATE":"on"}}],"enabled":true,"description":"",
             "createdAtMillis":0,"somethingNew":42}
        """.trimIndent()
        val parsed = json.decodeFromString(StoredCommand.serializer(), payload)
        assertEquals("good night", parsed.phrase)
        assertEquals(1, parsed.toDomain().actions.size)
    }

    @Test
    fun `a command with no runnable steps is not runnable`() {
        val stored = StoredCommand(phrase = "x", actions = listOf(StoredAction("NOPE")))
        assertTrue(!stored.toDomain().isRunnable)
    }
}

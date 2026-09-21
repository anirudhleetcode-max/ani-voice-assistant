package com.ani.nlu

import com.ani.nlu.command.CustomAction
import com.ani.nlu.command.CustomCommand
import com.ani.nlu.command.CustomCommandMatcher
import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.intent.IntentClassifier
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomCommandTest {

    private val collegeMode = CustomCommand(
        id = "cmd-college",
        phrase = "college mode",
        actions = listOf(
            CustomAction(IntentType.CONTROL_DND, mapOf(SlotKey.TOGGLE_STATE to "on")),
            CustomAction(IntentType.OPEN_APP, mapOf(SlotKey.APP_NAME to "calendar")),
            CustomAction(IntentType.CONTROL_VOLUME, mapOf(SlotKey.LEVEL to "30"))
        )
    )

    private val goodNight = CustomCommand(
        id = "cmd-night",
        phrase = "good night",
        actions = listOf(CustomAction(IntentType.CONTROL_DND, mapOf(SlotKey.TOGGLE_STATE to "on")))
    )

    private val matcher = CustomCommandMatcher(listOf(collegeMode, goodNight))

    @Test
    fun `a saved phrase is matched`() {
        val match = assertNotNull(matcher.match("college mode"))
        assertEquals("cmd-college", match.command.id)
    }

    @Test
    fun `the wake word and fillers do not block a match`() {
        assertNotNull(matcher.match("rey college mode"))
        assertNotNull(matcher.match("rey college mode ra"))
    }

    @Test
    fun `spelling drift still matches`() {
        assertNotNull(matcher.match("rey collage mode"))
    }

    @Test
    fun `a phrase that is merely similar does not match`() {
        assertNull(matcher.match("rey college ki route chupinchu"))
    }

    @Test
    fun `an unrelated command does not match`() {
        assertNull(matcher.match("rey amma ki call chey"))
    }

    @Test
    fun `disabled commands are ignored`() {
        val disabled = CustomCommandMatcher(listOf(collegeMode.copy(enabled = false)))
        assertNull(disabled.match("college mode"))
    }

    @Test
    fun `a command with no actions is ignored`() {
        val empty = CustomCommandMatcher(listOf(collegeMode.copy(actions = emptyList())))
        assertNull(empty.match("college mode"))
    }

    @Test
    fun `custom commands take priority over the built-in rules`() {
        val classifier = IntentClassifier(customCommands = matcher)
        val parsed = classifier.classify("rey good night", ConversationContext.EMPTY)
        assertEquals(IntentType.CUSTOM_COMMAND, parsed.type)
        assertEquals("cmd-night", parsed[SlotKey.CUSTOM_COMMAND_ID])
    }

    @Test
    fun `without the saved command the same phrase is ordinary conversation`() {
        val parsed = IntentClassifier().classify("rey good night")
        assertTrue(parsed.type != IntentType.CUSTOM_COMMAND)
    }

    @Test
    fun `phrase keys ignore case and punctuation when catching duplicates`() {
        assertEquals(
            CustomCommandMatcher.phraseKey("college mode"),
            CustomCommandMatcher.phraseKey("  College,  Mode! ")
        )
        assertTrue(
            CustomCommandMatcher.phraseKey("college mode") !=
                CustomCommandMatcher.phraseKey("good night")
        )
    }
}

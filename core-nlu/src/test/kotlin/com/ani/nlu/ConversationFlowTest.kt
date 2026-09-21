package com.ani.nlu

import com.ani.nlu.dialog.ConfirmationLevel
import com.ani.nlu.dialog.ConfirmationPolicy
import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.intent.IntentClassifier
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-turn behaviour — the part that separates an assistant from a command line.
 */
class ConversationFlowTest {

    private val classifier = IntentClassifier()

    @Test
    fun `asking for the message body and then receiving it`() {
        // Turn 1: who, but not what.
        val first = classifier.classify("rey Rahul ki message pampu")
        assertEquals(IntentType.SEND_MESSAGE, first.type)
        assertEquals(listOf(SlotKey.MESSAGE_BODY), first.needsSlots)

        // Ani asks "Em message pampali?" and waits.
        val waiting = ConversationContext.EMPTY
            .awaitingSlot(first, SlotKey.MESSAGE_BODY, "Em message pampali?")

        // Turn 2: the user answers with the message and nothing else.
        val second = classifier.classify("repu college ki late avutha ani cheppu", waiting)
        assertEquals(IntentType.SEND_MESSAGE, second.type)
        assertEquals("Rahul", second[SlotKey.CONTACT_NAME])
        assertEquals("repu college ki late avutha", second[SlotKey.MESSAGE_BODY])
        assertTrue(second.isComplete)
    }

    @Test
    fun `confirming a pending action executes it`() {
        val pending = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(pending, "Amma ki call cheyyala?")

        for (answer in listOf("avunu", "sare", "ok", "ha", "yes")) {
            val reply = classifier.classify(answer, waiting)
            assertEquals(IntentType.CALL_CONTACT, reply.type, "\"$answer\" should confirm")
            assertEquals("Amma", reply[SlotKey.CONTACT_NAME])
        }
    }

    @Test
    fun `denying a pending action cancels it`() {
        val pending = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(pending, "Amma ki call cheyyala?")

        for (answer in listOf("vaddu", "no", "kaadu", "cancel")) {
            assertEquals(IntentType.DENY, classifier.classify(answer, waiting).type, "\"$answer\"")
        }
    }

    @Test
    fun `changing the subject while a confirmation is pending is honoured`() {
        val pending = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(pending, "Amma ki call cheyyala?")
        val reply = classifier.classify("rey battery entha undi", waiting)
        assertEquals(IntentType.GET_BATTERY, reply.type)
    }

    @Test
    fun `saying no to a slot question cancels the whole action`() {
        val first = classifier.classify("rey Rahul ki message pampu")
        val waiting = ConversationContext.EMPTY.awaitingSlot(first, SlotKey.MESSAGE_BODY, "Em message?")
        assertEquals(IntentType.CANCEL, classifier.classify("vaddu", waiting).type)
    }

    @Test
    fun `the app opened on the previous turn becomes the music provider`() {
        val opened = classifier.classify("rey spotify open chey")
        assertEquals(IntentType.OPEN_APP, opened.type)

        val context = ConversationContext.EMPTY.afterExecuting(opened)
        assertEquals("spotify", context.activeApp)

        val followUp = classifier.classify("Arijit Singh play chey", context)
        assertEquals(IntentType.PLAY_MUSIC, followUp.type)
        assertEquals("spotify", followUp[SlotKey.MUSIC_PROVIDER])
        assertEquals("arijit singh", followUp[SlotKey.MUSIC_QUERY])
    }

    @Test
    fun `stop means pause the music while something is playing`() {
        val playing = ConversationContext.EMPTY
            .afterExecuting(classifier.classify("rey Kesariya play chey"))
        assertTrue(playing.mediaActive)

        val stop = classifier.classify("rey idi aapu", playing)
        assertEquals(IntentType.MUSIC_CONTROL, stop.type)
        assertEquals("stop", stop[SlotKey.MEDIA_ACTION])
    }

    @Test
    fun `context remembers the last contact and search`() {
        var context = ConversationContext.EMPTY
        context = context.afterExecuting(classifier.classify("rey amma ki call chey"))
        assertEquals("Amma", context.lastContact)
        context = context.afterExecuting(classifier.classify("rey Kesariya play chey"))
        assertEquals("kesariya", context.lastMusicQuery)
        // The earlier contact survives a turn that does not mention anyone.
        assertEquals("Amma", context.lastContact)
    }

    @Test
    fun `calls are confirmed by default but battery checks are not`() {
        val call = classifier.classify("rey amma ki call chey")
        val battery = classifier.classify("rey battery entha undi")
        assertTrue(ConfirmationPolicy.requiresConfirmation(call))
        assertTrue(!ConfirmationPolicy.requiresConfirmation(battery))
    }

    @Test
    fun `the confirmation threshold is configurable`() {
        val openApp = classifier.classify("rey whatsapp open chey")
        assertTrue(!ConfirmationPolicy.requiresConfirmation(openApp, ConfirmationLevel.BALANCED))
        assertTrue(ConfirmationPolicy.requiresConfirmation(openApp, ConfirmationLevel.CAREFUL))

        val call = classifier.classify("rey amma ki call chey")
        assertTrue(!ConfirmationPolicy.requiresConfirmation(call, ConfirmationLevel.RELAXED))
        assertTrue(ConfirmationPolicy.requiresConfirmation(call, ConfirmationLevel.BALANCED))
    }

    @Test
    fun `an incomplete action is not confirmed until its slots are filled`() {
        val incomplete = classifier.classify("rey message pampu")
        assertTrue(!incomplete.isComplete)
        assertTrue(!ConfirmationPolicy.requiresConfirmation(incomplete))
    }

    @Test
    fun `do not disturb always needs confirmation even when relaxed`() {
        val dnd = classifier.classify("rey do not disturb on chey")
        assertEquals(IntentType.CONTROL_DND, dnd.type)
        assertTrue(ConfirmationPolicy.requiresConfirmation(dnd, ConfirmationLevel.RELAXED))
    }
}

package com.ani.nlu

import com.ani.nlu.dialog.ConfirmationLevel
import com.ani.nlu.dialog.ConfirmationPolicy
import com.ani.nlu.dialog.ConversationContext
import com.ani.nlu.intent.IntentClassifier
import com.ani.nlu.intent.IntentType
import com.ani.nlu.intent.SlotKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The confirmation handshake, end to end.
 *
 * These exist because of a real bug that reached a physical phone: saying "avunu" to
 * "Amma ki call cheyyala?" produced the *same question again*, forever, and the call was
 * never placed. The classifier handed back the pending command unchanged, the orchestrator
 * re-ran the same policy against it, got the same answer, and asked again.
 *
 * The rule these lock down: a confirmed command must never be confirmable again.
 */
class ConfirmationExecutionTest {

    private val classifier = IntentClassifier()

    @Test
    fun `confirming a call marks the command approved so it can execute`() {
        val request = classifier.classify("rey amma ki call chey")
        assertEquals(IntentType.CALL_CONTACT, request.type)
        assertFalse(request.confirmed, "a fresh command has not been approved")
        assertTrue(
            ConfirmationPolicy.requiresConfirmation(request),
            "calling someone should be confirmed by default"
        )

        val waiting = ConversationContext.EMPTY
            .awaitingConfirmation(request, "Amma ki call cheyyala?")

        val approved = classifier.classify("avunu", waiting)

        assertEquals(IntentType.CALL_CONTACT, approved.type)
        assertEquals("Amma", approved[SlotKey.CONTACT_NAME])
        assertTrue(approved.confirmed, "the approval has to be carried on the command")
    }

    @Test
    fun `an approved command is never asked about a second time`() {
        val request = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(request, "prompt")
        val approved = classifier.classify("avunu", waiting)

        // This is the assertion that would have caught the shipped bug. Before the fix it
        // returned true, the orchestrator asked again, and the call never happened.
        assertFalse(
            ConfirmationPolicy.requiresConfirmation(approved),
            "re-confirming an approved command is the infinite loop that blocked execution"
        )
    }

    @Test
    fun `approval survives at every confirmation level`() {
        val request = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(request, "prompt")
        val approved = classifier.classify("sare", waiting)

        for (level in ConfirmationLevel.entries) {
            assertFalse(
                ConfirmationPolicy.requiresConfirmation(approved, level),
                "an approved command must execute at level $level"
            )
        }
    }

    @Test
    fun `every way of saying yes resolves the pending action`() {
        val request = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(request, "prompt")

        for (yes in listOf("avunu", "sare", "ok", "yes", "ha", "correct", "sari")) {
            val approved = classifier.classify(yes, waiting)
            assertEquals(IntentType.CALL_CONTACT, approved.type, "\"$yes\" should approve")
            assertTrue(approved.confirmed, "\"$yes\" should mark the command approved")
        }
    }

    @Test
    fun `saying no cancels instead of approving`() {
        val request = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(request, "prompt")

        for (no in listOf("vaddu", "no", "kaadu", "cancel")) {
            val answer = classifier.classify(no, waiting)
            assertEquals(IntentType.DENY, answer.type, "\"$no\" should cancel")
            assertFalse(answer.confirmed)
        }
    }

    @Test
    fun `an unrelated sentence does not execute the pending action`() {
        val request = classifier.classify("rey amma ki call chey")
        val waiting = ConversationContext.EMPTY.awaitingConfirmation(request, "prompt")

        val elsewhere = classifier.classify("rey battery entha undi", waiting)
        assertEquals(IntentType.GET_BATTERY, elsewhere.type)
        assertFalse(elsewhere.confirmed, "changing the subject must not approve the call")
    }

    @Test
    fun `a stale approval cannot place a call`() {
        val request = classifier.classify("rey amma ki call chey")

        // Asked for two minutes and one second ago.
        var now = 1_000_000L
        val expiring = IntentClassifier(nowMillis = { now })
        val waiting = ConversationContext.EMPTY
            .awaitingConfirmation(request, "prompt", nowMillis = now)

        now += ConversationContext.PENDING_TIMEOUT_MILLIS + 1_000L

        val late = expiring.classify("avunu", waiting)
        assertTrue(
            late.type != IntentType.CALL_CONTACT || !late.confirmed,
            "an approval overheard minutes later must not place the call"
        )
    }

    @Test
    fun `an approval inside the window still works`() {
        val request = classifier.classify("rey amma ki call chey")

        var now = 1_000_000L
        val timed = IntentClassifier(nowMillis = { now })
        val waiting = ConversationContext.EMPTY
            .awaitingConfirmation(request, "prompt", nowMillis = now)

        now += ConversationContext.PENDING_TIMEOUT_MILLIS / 2

        val approved = timed.classify("avunu", waiting)
        assertEquals(IntentType.CALL_CONTACT, approved.type)
        assertTrue(approved.confirmed)
    }

    @Test
    fun `a stale slot answer is not folded into a forgotten command`() {
        val request = classifier.classify("rey Rahul ki message pampu")
        assertTrue(SlotKey.MESSAGE_BODY in request.needsSlots)

        var now = 1_000_000L
        val timed = IntentClassifier(nowMillis = { now })
        val waiting = ConversationContext.EMPTY
            .awaitingSlot(request, SlotKey.MESSAGE_BODY, "Em message?", nowMillis = now)

        now += ConversationContext.PENDING_TIMEOUT_MILLIS + 1_000L

        val late = timed.classify("repu kaluddam", waiting)
        assertTrue(
            late.type != IntentType.SEND_MESSAGE || late[SlotKey.MESSAGE_BODY] == null,
            "a sentence minutes later must not become the body of a forgotten message"
        )
    }

    @Test
    fun `filling a slot then confirming reaches an executable command`() {
        // The full three-turn shape: ask, answer, approve.
        val first = classifier.classify("rey Rahul ki message pampu")
        val askingBody = ConversationContext.EMPTY
            .awaitingSlot(first, SlotKey.MESSAGE_BODY, "Em message?")

        val complete = classifier.classify("repu college ki late avutha ani cheppu", askingBody)
        assertEquals(IntentType.SEND_MESSAGE, complete.type)
        assertTrue(complete.isComplete)
        assertTrue(ConfirmationPolicy.requiresConfirmation(complete), "sending needs approval")

        val askingApproval = ConversationContext.EMPTY.awaitingConfirmation(complete, "pampana?")
        val approved = classifier.classify("avunu", askingApproval)

        assertTrue(approved.confirmed)
        assertFalse(ConfirmationPolicy.requiresConfirmation(approved))
        assertEquals("repu college ki late avutha", approved[SlotKey.MESSAGE_BODY])
    }

    @Test
    fun `music plays without asking, so it must not be gated behind a confirmation`() {
        val play = classifier.classify("rey Kesariya play chey")
        assertEquals(IntentType.PLAY_MUSIC, play.type)
        assertFalse(
            ConfirmationPolicy.requiresConfirmation(play),
            "playing a song is not consequential enough to interrupt for"
        )
    }
}

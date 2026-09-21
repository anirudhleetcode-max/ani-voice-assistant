package com.ani.assistant.mic

import com.ani.assistant.voice.mic.MicAcquisition
import com.ani.assistant.voice.mic.MicArbiter
import com.ani.assistant.voice.mic.WakeMicrophoneOwner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The gate that stops two recorders existing at once.
 *
 * The bug: tapping the orb called `startListening` while the wake engine still held an
 * `AudioRecord`. Android raises nothing for that — the second recorder opens and returns
 * silence — so recognition ended in `ERROR_NO_MATCH` and Ani said it could not hear the
 * user clearly. It could not hear them at all.
 */
class MicArbiterTest {

    @Test
    fun `with no wake engine running it grants immediately`() = runTest {
        val arbiter = MicArbiter(log = {})

        assertTrue(arbiter.acquireForCommand().isGranted)
        assertTrue(arbiter.commandHoldsMicrophone.value)
    }

    @Test
    fun `it asks the wake engine to let go before granting`() = runTest {
        val asked = AtomicInteger(0)
        val arbiter = MicArbiter(log = {})
        arbiter.registerWakeOwner { _ ->
            asked.incrementAndGet()
            true
        }

        assertTrue(arbiter.acquireForCommand().isGranted)
        assertEquals(1, asked.get())
    }

    @Test
    fun `a wake engine that will not confirm release is refused, not overridden`() = runTest {
        // This is the whole point. Granting here is what produced a silent recorder and
        // a misleading apology; refusing lets the caller say something true instead.
        val arbiter = MicArbiter(log = {})
        arbiter.registerWakeOwner { _ -> false }

        val outcome = arbiter.acquireForCommand()

        assertFalse(outcome.isGranted)
        assertEquals(
            MicAcquisition.Denied.Reason.WAKE_ENGINE_WOULD_NOT_RELEASE,
            (outcome as MicAcquisition.Denied).reason
        )
        assertFalse(arbiter.commandHoldsMicrophone.value)
    }

    @Test
    fun `a second command session cannot take a microphone already held`() = runTest {
        val arbiter = MicArbiter(log = {})
        assertTrue(arbiter.acquireForCommand().isGranted)

        val second = arbiter.acquireForCommand()

        assertFalse(second.isGranted)
        assertEquals(
            MicAcquisition.Denied.Reason.ALREADY_HELD,
            (second as MicAcquisition.Denied).reason
        )
    }

    @Test
    fun `releasing lets the next session in`() = runTest {
        val arbiter = MicArbiter(log = {})
        arbiter.acquireForCommand()
        arbiter.releaseCommand()

        assertFalse(arbiter.commandHoldsMicrophone.value)
        assertTrue(arbiter.acquireForCommand().isGranted)
    }

    @Test
    fun `releasing when nothing was held is harmless`() {
        val arbiter = MicArbiter(log = {})

        arbiter.releaseCommand()
        arbiter.releaseCommand()

        assertFalse(arbiter.commandHoldsMicrophone.value)
    }

    @Test
    fun `unregistering the wake owner means nothing is asked to release`() = runTest {
        val asked = AtomicInteger(0)
        val arbiter = MicArbiter(log = {})
        val owner = WakeMicrophoneOwner { _ ->
            asked.incrementAndGet()
            true
        }
        arbiter.registerWakeOwner(owner)
        arbiter.unregisterWakeOwner()

        assertTrue(arbiter.acquireForCommand().isGranted)
        assertEquals(0, asked.get())
    }

    @Test
    fun `the wake loop can see that a command holds the microphone`() = runTest {
        // The service watches this flow and refuses to re-arm while it is true, which is
        // the other half of "never two owners".
        val arbiter = MicArbiter(log = {})
        assertFalse(arbiter.commandHoldsMicrophone.value)

        arbiter.acquireForCommand()
        assertTrue(arbiter.commandHoldsMicrophone.value)

        arbiter.releaseCommand()
        assertFalse(arbiter.commandHoldsMicrophone.value)
    }

    @Test
    fun `the release timeout is passed through to the owner`() = runTest {
        var seen = 0L
        val arbiter = MicArbiter(log = {})
        arbiter.registerWakeOwner { timeout ->
            seen = timeout
            true
        }

        arbiter.acquireForCommand(timeoutMillis = 250L)

        assertEquals(250L, seen)
    }
}

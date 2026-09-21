package com.ani.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one knob that trades accuracy against latency, pinned so a future change to it is
 * deliberate rather than incidental.
 */
class EndpointingConfigTest {

    @Test
    fun `the default sits between cutting people off and making them wait`() {
        val config = EndpointingConfig()

        // The stock ~1 s cut Tanglish speakers off inside "Annayya ki … call chey".
        assertTrue(config.completeSilenceMillis > EndpointingConfig.STOCK.completeSilenceMillis)
        // 2.5 s fixed that and added a visible delay to every single command.
        assertTrue(config.completeSilenceMillis < EndpointingConfig.PATIENT.completeSilenceMillis)
    }

    @Test
    fun `possibly-complete never waits longer than complete`() {
        // Inverting these would mean the recogniser holds on longer when it already
        // thinks the sentence is finished than when it does not.
        for (config in listOf(EndpointingConfig(), EndpointingConfig.PATIENT, EndpointingConfig.STOCK)) {
            assertTrue(
                "possiblyComplete must not exceed complete in $config",
                config.possiblyCompleteSilenceMillis <= config.completeSilenceMillis
            )
        }
    }

    @Test
    fun `the default sets no minimum speech length`() {
        // A minimum is a hard floor under every command's latency, including the ones
        // where the user spoke immediately. It is opt-in, for a measured reason.
        assertEquals(0L, EndpointingConfig().minimumSpeechMillis)
    }

    @Test
    fun `the patient profile is kept so the previous build can be compared on device`() {
        assertEquals(2_500L, EndpointingConfig.PATIENT.completeSilenceMillis)
        assertTrue(EndpointingConfig.PATIENT.minimumSpeechMillis > 0)
    }
}

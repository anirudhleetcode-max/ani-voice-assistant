package com.ani.assistant.voice.mic

/**
 * How stages that happen outside the voice service reach the microphone machine.
 *
 * Launching a call or Spotify happens deep inside the tool layer, which has no idea a
 * wake word started it. Without this, [MicStage.ACTION_EXECUTING] would be a state
 * nothing ever enters — a diagram rather than a fact — and a launch that blocks would
 * show up in a log as an unexplained gap between "[NLU] classified" and the reply.
 *
 * Deliberately tiny and deliberately optional: when no service is listening, reporting a
 * stage does nothing. A null sink is the normal state for the tap-to-talk path.
 */
object MicStageBus {

    @Volatile
    private var sink: ((MicEvent) -> Unit)? = null

    /** Installed by [com.ani.assistant.voice.AniVoiceService] while it is listening. */
    fun install(sink: (MicEvent) -> Unit) {
        this.sink = sink
    }

    fun clear() {
        sink = null
    }

    fun report(event: MicEvent) {
        sink?.invoke(event)
    }

    /** Reports [MicEvent.ACTION_STARTED] and [MicEvent.ACTION_FINISHED] around [block]. */
    inline fun <T> aroundAction(block: () -> T): T {
        report(MicEvent.ACTION_STARTED)
        return try {
            block()
        } finally {
            report(MicEvent.ACTION_FINISHED)
        }
    }
}

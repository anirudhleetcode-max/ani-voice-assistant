package com.ani.assistant.ai

import com.ani.nlu.response.Persona
import com.ani.nlu.text.Language

/** One earlier turn, for continuity. */
data class AiTurn(val fromUser: Boolean, val text: String)

/**
 * A request for *words*.
 *
 * Note what is not in here: no tool list, no function definitions, no device state beyond
 * a short opt-in summary, and no way to express an action. The AI provider in Ani is a
 * language model in the literal sense — it produces sentences, and the assistant has
 * already decided what it is going to do before this is ever called.
 */
data class AiRequest(
    val userText: String,
    val language: Language,
    val persona: Persona,
    /** The last few turns, oldest first. Capped by the caller. */
    val recentTurns: List<AiTurn> = emptyList(),
    /**
     * A one-line factual summary the user's question needs, e.g. "battery 62%".
     * Populated only when the intent required it; never notification content.
     */
    val factualContext: String? = null
)

sealed interface AiOutcome {
    data class Reply(val text: String) : AiOutcome

    /** No network, or the user chose offline-only. Not an error. */
    data class Unavailable(val reason: String) : AiOutcome

    data class Failed(val message: String) : AiOutcome
}

/**
 * Where conversational replies come from.
 *
 * Swappable by configuration, as the brief requires — but the swap happens *server side*.
 * The app talks to one endpoint; which model answers is the backend's business. That is
 * not a limitation, it is the point: shipping an OpenAI or Anthropic key inside an APK
 * means shipping it to everyone who downloads the APK, and no amount of obfuscation
 * changes that. See AI_INTEGRATION.md.
 */
interface AiProvider {
    val id: String

    suspend fun reply(request: AiRequest): AiOutcome

    /** Cheap reachability check for the Diagnostics screen. */
    suspend fun isReachable(): Boolean
}

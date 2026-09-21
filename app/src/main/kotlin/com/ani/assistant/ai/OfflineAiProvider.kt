package com.ani.assistant.ai

import com.ani.nlu.response.ResponseStyle
import com.ani.nlu.response.Responses
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.TextNormalizer

/**
 * Conversational replies with no network at all.
 *
 * This is not a fallback that pretends to be the real thing. It handles the handful of
 * exchanges that are pure ritual — greetings, thanks, "em chesthunnav" — and for anything
 * that needs actual knowledge it says so plainly. An assistant that invents an answer
 * offline is worse than one that admits it is offline.
 *
 * Every device command still works without this: calls, messages, alarms, notifications,
 * music control and app launching never touch the network.
 */
class OfflineAiProvider(private val styleProvider: () -> ResponseStyle) : AiProvider {

    override val id: String = "offline"

    override suspend fun reply(request: AiRequest): AiOutcome {
        val style = styleProvider()
        val keys = TextNormalizer.normalize(request.userText).tokens.map { it.key }.toSet()

        canned(keys, style)?.let { return AiOutcome.Reply(it) }

        return AiOutcome.Unavailable(Responses.noInternet(style))
    }

    override suspend fun isReachable(): Boolean = true

    private fun canned(keys: Set<String>, style: ResponseStyle): String? {
        val telugu = style.speaksTelugu
        return when {
            keys.containsAnyOf(GREETINGS) ->
                if (telugu) "Chepu${style.particle}, ela unnav?" else "Hey — how's it going?"

            keys.containsAnyOf(HOW_ARE_YOU) ->
                if (telugu) "Nenu baane unna${style.particle}. Nuvvu cheppu." else "I'm good. What do you need?"

            keys.containsAnyOf(THANKS) ->
                if (telugu) "Deniki${style.particle}, cheppu inkemaina kavalante."
                else "Anytime. Just say the word."

            keys.containsAnyOf(GOODBYES) ->
                if (telugu) "Sare${style.particle}, malli cheppu." else "Alright, talk soon."

            keys.containsAnyOf(WHO_ARE_YOU) ->
                if (telugu) "Nenu ${style.assistantName}${style.particle}, nee phone assistant."
                else "I'm ${style.assistantName}, your phone assistant."

            else -> null
        }
    }

    private fun Set<String>.containsAnyOf(words: Set<String>) = any { it in words }

    private companion object {
        private fun keysOf(vararg words: String) = words.map { PhoneticKey.of(it) }.toSet()

        val GREETINGS = keysOf("hi", "hello", "hey", "namaskaram", "namaste")
        val HOW_ARE_YOU = keysOf("chesthunnav", "chestunnav", "unnav", "unnaru")
        val THANKS = keysOf("thanks", "thank", "dhanyavadalu", "thanku")
        val GOODBYES = keysOf("bye", "goodbye", "veltunna", "veldam")
        val WHO_ARE_YOU = keysOf("evaru", "evvaru", "nuvvevaru")
    }
}

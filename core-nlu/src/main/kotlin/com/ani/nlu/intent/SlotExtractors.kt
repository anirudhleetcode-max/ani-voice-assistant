package com.ani.nlu.intent

import com.ani.nlu.lexicon.Lexicon
import com.ani.nlu.lexicon.Morphology
import com.ani.nlu.lexicon.Numbers
import com.ani.nlu.lexicon.SemanticTag
import com.ani.nlu.lexicon.SemanticTag.*
import com.ani.nlu.text.NormalizedText
import com.ani.nlu.text.PhoneticKey
import com.ani.nlu.text.Token

/**
 * Pulls arguments out of an utterance once the intent is known.
 *
 * All of these work on the same principle: Ani knows a lot of *words* but nothing about
 * the user's contacts, songs or installed apps. So the extractors identify and remove
 * everything they recognise, and whatever survives is the argument. That is why a contact
 * called "Battery" would be the one name Ani struggles with, and why nothing else needs a
 * per-user model.
 */
object SlotExtractors {

    /** Words that never form part of an argument. */
    private val structuralTags = setOf(
        F_WAKE, F_FILLER, LIGHT_DO, LIGHT_PUT, V_CALL, V_SEND, V_PLAY, V_OPEN, V_CLOSE,
        V_STOP, V_PAUSE, V_RESUME, V_NEXT, V_PREVIOUS, V_READ, V_TELL, V_WAKE, V_REMIND,
        V_SEARCH, V_SHOW, V_TURN_ON, V_TURN_OFF, V_INCREASE, V_DECREASE, V_NAVIGATE,
        V_TEACH, V_REMEMBER, V_DELETE, V_SHARE, V_REPEAT, F_QUESTION, F_AGAIN, F_QUOTATIVE,
        F_AFFIRM, F_DENY, F_ALL, F_NEGATION
    )

    /** English words that introduce quoted message content ("tell him **that** ..."). */
    private val englishQuoteStarters =
        setOf("saying", "say", "that", "telling", "about").map { PhoneticKey.of(it) }.toSet()

    private val caseMarkers = setOf("ki", "ku", "kki", "lo", "loki", "loni", "tho", "to", "nu", "ni")
        .map { PhoneticKey.of(it) }.toSet()

    // ---------------------------------------------------------------------------------
    // Contacts
    // ---------------------------------------------------------------------------------

    /**
     * Finds who the user is talking about.
     *
     * Telugu marks the recipient with a dative ending, which may be a separate word
     * ("amma ki") or glued on ("ammaki"). Both are handled; when neither is present we
     * fall back to the unrecognised words around the verb, which is how "call mom" and
     * "Anirudh call chey" work.
     */
    fun contactName(text: NormalizedText): String? {
        val tokens = text.tokens
        if (tokens.isEmpty()) return null

        // 1. Standalone dative marker: the name is the run of words just before it.
        val dativeIndex = tokens.indexOfFirst { Morphology.isDativeMarker(it.text) }
        if (dativeIndex > 0) {
            val run = nameRunEndingAt(tokens, dativeIndex - 1)
            if (run.isNotEmpty()) return run.joinToString(" ") { displayName(it.text) }
        }

        // 2. Glued dative: "ammaki", "rahulki".
        for (token in tokens) {
            if (Morphology.isDativeMarker(token.text)) continue
            val stem = Morphology.stripCaseSuffix(token.text)
            if (stem == token.text) continue
            if (!isNameCandidate(Token(stem, PhoneticKey.of(stem), token.index))) continue
            return displayName(stem)
        }

        // 3. No case marking at all — take the longest run of name-like words.
        val run = longestNameRun(tokens)
        return if (run.isEmpty()) null else run.joinToString(" ") { displayName(it.text) }
    }

    private fun nameRunEndingAt(tokens: List<Token>, endIndex: Int): List<Token> {
        var start = endIndex
        while (start >= 0 && isNameCandidate(tokens[start])) start--
        val run = tokens.subList(start + 1, endIndex + 1)
        return run.filterNot { Lexicon.has(it, F_SELF) }
    }

    private fun longestNameRun(tokens: List<Token>): List<Token> {
        var best: List<Token> = emptyList()
        var current = mutableListOf<Token>()
        for (token in tokens) {
            if (isNameCandidate(token) && !Lexicon.has(token, F_SELF)) {
                current.add(token)
            } else {
                if (current.size > best.size) best = current.toList()
                current = mutableListOf()
            }
        }
        if (current.size > best.size) best = current.toList()
        return best
    }

    /**
     * A word that could be part of a person's name: something Ani does not recognise, or
     * a kinship term, which people use as a name constantly ("Amma ki call chey").
     */
    private fun isNameCandidate(token: Token): Boolean {
        if (token.isNumeric) return false
        if (token.key in caseMarkers) return false
        val tags = Lexicon.tagsFor(token)
        if (N_KINSHIP in tags) return true
        if (tags.isEmpty()) return !KnownApps.isKnownApp(token)
        return false
    }

    /** "rahul" -> "Rahul". The contact resolver is case-insensitive; users are not. */
    private fun displayName(word: String): String =
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    // ---------------------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------------------

    /**
     * The words to actually send.
     *
     * Telugu closes quoted speech with "ani" ("... late avutha **ani** cheppu"), English
     * opens it ("tell him **that** ..."). Both are handled, and in a follow-up turn where
     * the user simply says the message, the whole utterance is the body.
     */
    fun messageBody(text: NormalizedText, isFollowUpAnswer: Boolean = false): String? {
        val tokens = text.tokens
        if (tokens.isEmpty()) return null

        val quotativeIndex = tokens.indexOfLast { Lexicon.has(it, F_QUOTATIVE) }
        if (quotativeIndex > 0) {
            // Content runs up to "ani", starting after whichever comes later: the
            // "message"/"send" word, or the recipient at the head of the sentence.
            val afterVerb = tokens.take(quotativeIndex)
                .indexOfLast { Lexicon.has(it, N_MESSAGE) || Lexicon.has(it, V_SEND) }
                .let { if (it < 0) 0 else it + 1 }
            // In a follow-up answer the user is speaking only the message, so a dative in
            // it ("college ki late avutha") belongs to the body, not to a recipient.
            val afterRecipient = if (isFollowUpAnswer) 0 else leadingRecipientEnd(tokens)
            val start = maxOf(afterVerb, afterRecipient)
            val body = tokens.subList(start.coerceAtMost(quotativeIndex), quotativeIndex)
                .dropWhile { Lexicon.has(it, F_WAKE) || Lexicon.has(it, F_FILLER) }
            if (body.isNotEmpty()) return body.joinToString(" ") { it.text }
        }

        val starterIndex = tokens.indexOfFirst { it.key in englishQuoteStarters }
        if (starterIndex >= 0 && starterIndex < tokens.lastIndex) {
            return tokens.drop(starterIndex + 1).joinToString(" ") { it.text }
        }

        if (isFollowUpAnswer) {
            val body = tokens
                .dropWhile { Lexicon.has(it, F_WAKE) || Lexicon.has(it, F_FILLER) }
                .dropLastWhile { Lexicon.has(it, V_TELL) || Lexicon.has(it, V_SEND) || Lexicon.has(it, F_QUOTATIVE) }
            if (body.isNotEmpty()) return body.joinToString(" ") { it.text }
        }
        return null
    }

    /**
     * Index just past the recipient at the start of the sentence, or 0.
     *
     * "Rahul ki repu 10 ki kaluddam ani message pampu" has two datives; only the first
     * marks who the message is for.
     */
    private fun leadingRecipientEnd(tokens: List<Token>): Int {
        val separate = tokens.take(LEADING_RECIPIENT_WINDOW)
            .indexOfFirst { Morphology.isDativeMarker(it.text) }
        if (separate >= 0) return separate + 1
        val glued = tokens.take(LEADING_RECIPIENT_WINDOW).indexOfFirst {
            Morphology.hasCaseSuffix(it.text) && isNameCandidate(
                Token(
                    Morphology.stripCaseSuffix(it.text),
                    PhoneticKey.of(Morphology.stripCaseSuffix(it.text)),
                    it.index
                )
            )
        }
        return if (glued >= 0) glued + 1 else 0
    }

    /** "WhatsApp lo message pampu" -> "whatsapp". */
    fun messageChannel(text: NormalizedText): String? {
        for (token in text.tokens) {
            val canonical = KnownApps.canonicalOrNull(token) ?: continue
            if (canonical in MESSAGING_APPS) return canonical
        }
        if (text.tokens.any { Lexicon.has(it, N_MESSAGE) && it.key == PhoneticKey.of("sms") }) return "sms"
        return null
    }

    // ---------------------------------------------------------------------------------
    // Music
    // ---------------------------------------------------------------------------------

    /** "Spotify lo Arijit Singh play chey" -> "arijit singh". */
    fun musicQuery(text: NormalizedText): String? {
        val kept = text.tokens.filter { token ->
            val tags = Lexicon.tagsFor(token)
            when {
                tags.any { it in structuralTags } -> false
                N_MUSIC in tags || N_ARTIST in tags -> false
                token.key in caseMarkers -> false
                KnownApps.isMusicProvider(token) -> false
                else -> true
            }
        }
        if (kept.isEmpty()) return null
        val query = kept.joinToString(" ") { it.text }.trim()
        return query.ifEmpty { null }
    }

    fun musicProvider(text: NormalizedText): String? =
        text.tokens.firstNotNullOfOrNull { token ->
            KnownApps.canonicalOrNull(token)?.takeIf { it in KnownApps.musicProviders }
        }

    // ---------------------------------------------------------------------------------
    // Apps
    // ---------------------------------------------------------------------------------

    /**
     * The app the user named. Known nicknames win; otherwise the unrecognised word next
     * to the open/launch verb is taken as an app name and resolved on-device.
     */
    fun appName(text: NormalizedText): String? {
        text.tokens.firstNotNullOfOrNull { KnownApps.canonicalOrNull(it) }?.let { return it }

        val verbIndex = text.tokens.indexOfFirst { Lexicon.has(it, V_OPEN) || Lexicon.has(it, V_CLOSE) }
        val candidates = if (verbIndex > 0) text.tokens.take(verbIndex) else text.tokens
        val unknown = candidates.filter { token ->
            !token.isNumeric && Lexicon.tagsFor(token).isEmpty() && token.key !in caseMarkers
        }
        if (unknown.isEmpty()) return null
        return unknown.joinToString(" ") { it.text }
    }

    // ---------------------------------------------------------------------------------
    // Device settings
    // ---------------------------------------------------------------------------------

    /** Which system toggle the user meant, as a stable identifier. */
    fun settingsTarget(text: NormalizedText): String? {
        for (token in text.tokens) {
            val tags = Lexicon.tagsFor(token)
            when {
                N_WIFI in tags -> return "wifi"
                N_BLUETOOTH in tags -> return "bluetooth"
                N_DND in tags -> return "dnd"
                N_AIRPLANE in tags -> return "airplane"
                N_BRIGHTNESS in tags -> return "brightness"
                N_VOLUME in tags -> return "volume"
                N_LOCATION in tags -> return "location"
                N_STORAGE in tags -> return "storage"
                N_NETWORK in tags -> return "network"
                N_BATTERY in tags -> return "battery"
                N_FLASHLIGHT in tags -> return "flashlight"
            }
        }
        if (text.tokens.any { N_SETTINGS in Lexicon.tagsFor(it) }) return "settings"
        return null
    }

    /** on / off / toggle. */
    fun toggleState(text: NormalizedText): String? {
        val on = text.tokens.any { Lexicon.has(it, V_TURN_ON) }
        val off = text.tokens.any { Lexicon.has(it, V_TURN_OFF) || Lexicon.has(it, V_STOP) }
        return when {
            on && !off -> "on"
            off && !on -> "off"
            on && off -> "toggle"
            else -> null
        }
    }

    /** "penchu" -> "up", "taggu" -> "down", "volume 30 pettu" -> "30". */
    fun level(text: NormalizedText): String? {
        text.tokens.firstNotNullOfOrNull { Numbers.valueOf(it) }
            ?.takeIf { it in 0..100 }
            ?.let { return it.toString() }
        return when {
            text.tokens.any { Lexicon.has(it, V_INCREASE) } -> "up"
            text.tokens.any { Lexicon.has(it, V_DECREASE) } -> "down"
            else -> null
        }
    }

    // ---------------------------------------------------------------------------------
    // Notifications
    // ---------------------------------------------------------------------------------

    /**
     * Which notifications to read out. "em messages vachayi" asks for messaging apps
     * specifically; "em notifications vachayi" asks for everything the user allowed.
     */
    fun notificationFilter(text: NormalizedText): String {
        text.tokens.firstNotNullOfOrNull { KnownApps.canonicalOrNull(it) }?.let { return it }
        val asksForMessages = text.tokens.any { Lexicon.has(it, N_MESSAGE) }
        val asksForMissed = text.tokens.any { Lexicon.has(it, N_MISSED) }
        return when {
            asksForMissed -> "missed_calls"
            asksForMessages -> "messages"
            else -> "all"
        }
    }

    // ---------------------------------------------------------------------------------
    // Reminders, navigation, memory
    // ---------------------------------------------------------------------------------

    /**
     * What the reminder is about.
     *
     * Telugu closes the quoted part with "ani" ("Rahul ki call cheyyali **ani** gurthu
     * chey"), and everything before it is the user's own words — kept verbatim, light
     * verbs included, because this text is read back to them later. Without a quotative
     * there is no such boundary, so the trailing "pettu"/"chey" is dropped instead.
     */
    fun reminderText(text: NormalizedText): String? {
        val quotativeIndex = text.tokens.indexOfLast { Lexicon.has(it, F_QUOTATIVE) }
        val hasQuotative = quotativeIndex > 0
        val body = if (hasQuotative) text.tokens.take(quotativeIndex) else text.tokens

        val kept = body.filter { token ->
            val tags = Lexicon.tagsFor(token)
            when {
                F_WAKE in tags || F_FILLER in tags -> false
                V_REMIND in tags || N_REMINDER in tags -> false
                F_SELF in tags -> false
                tags.any { it.name.startsWith("T_") } -> false
                Numbers.isNumber(token) -> false
                token.key in caseMarkers -> false
                else -> true
            }
        }
        val trimmed = if (hasQuotative) {
            kept
        } else {
            kept.dropLastWhile {
                Lexicon.has(it, LIGHT_DO) || Lexicon.has(it, LIGHT_PUT) || Lexicon.has(it, V_TELL)
            }
        }
        val result = trimmed.joinToString(" ") { it.text }.trim()
        return result.ifEmpty { null }
    }

    /**
     * The place named with a dative ending: "college ki route chupinchu" -> "college".
     *
     * Kept separate from [destination] because it is the only *reliable* signal. Without
     * it, "Google Maps open chey" would hand back "google" as somewhere to drive to.
     */
    fun dativeDestination(text: NormalizedText): String? {
        val dativeIndex = text.tokens.indexOfFirst { Morphology.isDativeMarker(it.text) }
        if (dativeIndex <= 0) return null
        val run = nameRunEndingAt(text.tokens, dativeIndex - 1)
        return if (run.isEmpty()) null else run.joinToString(" ") { it.text }
    }

    /** Where to navigate to. Only meaningful once an explicit navigation verb is present. */
    fun destination(text: NormalizedText): String? {
        dativeDestination(text)?.let { return it }
        val kept = text.tokens.filter { token ->
            val tags = Lexicon.tagsFor(token)
            tags.none { it in structuralTags } && N_MAPS !in tags && token.key !in caseMarkers &&
                !KnownApps.isKnownApp(token)
        }
        val result = kept.joinToString(" ") { it.text }.trim()
        return result.ifEmpty { null }
    }

    /**
     * "Amma ante Lakshmi" — the Telugu copula "ante" ("means") is how people state a
     * fact they want remembered.
     */
    fun memoryPair(text: NormalizedText): Pair<String, String>? {
        val anteKeys = setOf("ante", "antey", "anna", "means").map { PhoneticKey.of(it) }.toSet()
        val index = text.tokens.indexOfFirst { it.key in anteKeys }
        if (index <= 0 || index >= text.tokens.lastIndex) return null
        val key = text.tokens.take(index)
            .dropWhile { Lexicon.has(it, F_WAKE) || Lexicon.has(it, F_FILLER) }
            .joinToString(" ") { it.text }
        val value = text.tokens.drop(index + 1).joinToString(" ") { it.text }
        if (key.isBlank() || value.isBlank()) return null
        return key to value
    }

    /** Only a dative this early in the sentence can be the recipient. */
    private const val LEADING_RECIPIENT_WINDOW = 4

    private val MESSAGING_APPS =
        setOf("whatsapp", "telegram", "instagram", "snapchat", "gmail", "sms")
}
